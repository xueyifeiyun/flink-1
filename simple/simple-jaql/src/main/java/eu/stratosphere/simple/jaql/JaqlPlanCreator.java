package eu.stratosphere.simple.jaql;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import com.ibm.jaql.lang.Jaql;
import com.ibm.jaql.lang.expr.core.ArrayExpr;
import com.ibm.jaql.lang.expr.core.BindingExpr;
import com.ibm.jaql.lang.expr.core.CompareExpr;
import com.ibm.jaql.lang.expr.core.ConstExpr;
import com.ibm.jaql.lang.expr.core.CopyField;
import com.ibm.jaql.lang.expr.core.CopyRecord;
import com.ibm.jaql.lang.expr.core.Expr;
import com.ibm.jaql.lang.expr.core.FilterExpr;
import com.ibm.jaql.lang.expr.core.FixedRecordExpr;
import com.ibm.jaql.lang.expr.core.ForExpr;
import com.ibm.jaql.lang.expr.core.GroupByExpr;
import com.ibm.jaql.lang.expr.core.JoinExpr;
import com.ibm.jaql.lang.expr.core.MathExpr;
import com.ibm.jaql.lang.expr.core.NameValueBinding;
import com.ibm.jaql.lang.expr.core.RecordExpr;
import com.ibm.jaql.lang.expr.core.TransformExpr;
import com.ibm.jaql.lang.expr.core.VarExpr;
import com.ibm.jaql.lang.expr.function.BuiltInFunction;
import com.ibm.jaql.lang.expr.function.BuiltInFunctionDescriptor;
import com.ibm.jaql.lang.expr.io.AbstractHandleFn;
import com.ibm.jaql.lang.expr.io.ReadFn;
import com.ibm.jaql.lang.expr.io.WriteFn;
import com.ibm.jaql.lang.expr.path.PathArrayAll;
import com.ibm.jaql.lang.expr.path.PathExpr;
import com.ibm.jaql.lang.expr.path.PathFieldValue;
import com.ibm.jaql.lang.expr.path.PathIndex;
import com.ibm.jaql.lang.expr.path.PathReturn;

import eu.stratosphere.dag.DirectedAcyclicGraphPrinter;
import eu.stratosphere.dag.Navigator;
import eu.stratosphere.reflect.TypeHandler;
import eu.stratosphere.reflect.TypeHandlerListener;
import eu.stratosphere.reflect.TypeSpecificHandler;
import eu.stratosphere.simple.jaql.rewrite.RewriteEngine;
import eu.stratosphere.sopremo.Comparison;
import eu.stratosphere.sopremo.Comparison.BinaryOperator;
import eu.stratosphere.sopremo.Condition;
import eu.stratosphere.sopremo.Condition.Combination;
import eu.stratosphere.sopremo.JsonPath;
import eu.stratosphere.sopremo.JsonPath.Arithmetic.ArithmeticOperator;
import eu.stratosphere.sopremo.JsonPath.Constant;
import eu.stratosphere.sopremo.JsonPath.Input;
import eu.stratosphere.sopremo.Mapping;
import eu.stratosphere.sopremo.Operator;
import eu.stratosphere.sopremo.Plan;
import eu.stratosphere.sopremo.PlanCreator;
import eu.stratosphere.sopremo.Transformation;
import eu.stratosphere.sopremo.ValueAssignment;
import eu.stratosphere.sopremo.operator.Aggregation;
import eu.stratosphere.sopremo.operator.Join;
import eu.stratosphere.sopremo.operator.Projection;
import eu.stratosphere.sopremo.operator.Selection;
import eu.stratosphere.sopremo.operator.Sink;
import eu.stratosphere.sopremo.operator.Source;

public class JaqlPlanCreator extends PlanCreator {
	private static final ExprNavigator EXPR_NAVIGATOR = new ExprNavigator();

	@Override
	public Plan getPlan(InputStream stream) {
		Jaql jaql = new Jaql() {
			{
				this.rewriter = new RewriteEngine();
			}
		};
		// jaql.enableRewrite(false);
		jaql.setInput("test", stream);
		// jaql.setInput("test", new FileInputStream("scrub.jaql"));
		Expr expr;
		try {
			expr = jaql.expr();
			return this.convert(expr);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	static class Binding {
		private Expr expr;

		private Object transformed;

		public Binding(Expr expr, Object transformed) {
			this.expr = expr;
			this.transformed = transformed;
		}

		public Expr getExpr() {
			return expr;
		}

		public Object getTransformed() {
			return transformed;
		}

		public void setTransformed(Object transformed) {
			if (transformed == null)
				throw new NullPointerException("transformed must not be null");

			this.transformed = transformed;
		}

		public void setExpr(Expr expr) {
			if (expr == null)
				throw new NullPointerException("expr must not be null");

			this.expr = expr;
		}

		@Override
		public String toString() {
			if (transformed != null)
				return transformed.toString();
			return expr.toString();
		}
	}

	final class BindingExtractor<Output> implements TypeHandler<BindingExpr, Output> {
		private final Mapping BINDING = new ValueAssignment(null);

		private BindingExtractor() {
		}

		public Output convert(BindingExpr expr, List<Object> childOperators) {
			// System.out.println(expr);
			if (childOperators.isEmpty() && expr.numChildren() == 0)
				return null;

			Expr valueExpr;
			switch (expr.type) {
			case IN:
				valueExpr = expr.inExpr();
				break;
			case EQ:
				valueExpr = expr.eqExpr();
				break;
			default:
				System.out.println("unhandled binding");
				return null;
			}

			Object transformedExpr = null;
			if (!childOperators.isEmpty())
				transformedExpr = childOperators.get(0);
			else if (valueExpr instanceof VarExpr)
				transformedExpr = JaqlPlanCreator.this.bindings.get(valueExpr.toString());
			else {
				transformedExpr = JaqlPlanCreator.this.parseTransformation(valueExpr);
				if (transformedExpr == null)
					transformedExpr = JaqlPlanCreator.this.parsePath(valueExpr);
			}
			JaqlPlanCreator.this.bindings.put(expr.var.taggedName(), new Binding(valueExpr, transformedExpr));

			return null;
		}
	}

	private static final class ExprNavigator implements Navigator<Expr> {
		@Override
		public Iterable<Expr> getConnectedNodes(Expr node) {
			return this.getChildren(node);
		}

		private List<Expr> getChildren(Expr node) {
			List<Expr> children = new ArrayList<Expr>();
			for (int index = 0; index < node.numChildren(); index++)
				children.add(node.child(index));
			return children;
		}
	}

	public static class ExprPrinter extends DirectedAcyclicGraphPrinter<Expr> {

		private static final class PartialExprNavigator implements Navigator<Expr> {
			@Override
			public Iterable<Expr> getConnectedNodes(Expr node) {
				return this.getChildren(node);
			}

			private List<Expr> getChildren(Expr node) {
				List<Expr> children = new ArrayList<Expr>();
				for (int index = 0; index < node.numChildren(); index++) {
					Expr child = node.child(index);
					if (!(child instanceof ConstExpr) && !(child instanceof VarExpr)
							&& !(child instanceof PathReturn) && !(child instanceof NameValueBinding)
						&& !(child instanceof PathExpr)
							&& !(child instanceof RecordExpr) && !(child instanceof CompareExpr)
						&& !(child instanceof CopyField)
							&& !(child instanceof FixedRecordExpr) && !(child instanceof CopyRecord)// && !(child
																									// instanceof
																									// BindingExpr)
						&& !(child instanceof PathFieldValue))
						children.add(node.child(index));
				}
				return children;
			}
		}

		public ExprPrinter(Expr expr) {
			super(new PartialExprNavigator(), expr);
		}
	}

	private Plan convert(Expr expr) {
		// System.out.println(new ExprPrinter(expr).toString(new DirectedAcyclicGraphPrinter.NodePrinter<Expr>() {
		// @Override
		// public String toString(Expr node) {
		// return node.getClass().getSimpleName() + " "
		// + node.toString().replaceAll("system::", "").replaceAll("\n",
		// " ");
		// }
		// }, 30));
		Operator operator = this.convertSubtree(expr);

		return new Plan(Arrays.asList(operator));
	}

	private static interface ExprConverter<I extends Expr> extends TypeHandler<I, Operator> {
		public abstract Operator convert(I expr, List<Operator> childOperators);
	}

	private static interface CondConverter<I extends Expr> extends TypeHandler<I, Condition> {
		public Condition convert(I expr, List<Condition> childConditions);
	}

	private static interface PathConverter<I extends Expr> extends TypeHandler<I, JsonPath> {
		public JsonPath convert(I expr, List<JsonPath> childPaths);
	}

	private static interface TransformationConverter<I extends Expr> extends TypeHandler<I, Mapping> {
		public Mapping convert(I expr, List<Mapping> childTransformations);
	}

	private TypeSpecificHandler<Expr, Operator, TypeHandler<Expr, Operator>> operatorConverter = new TypeSpecificHandler<Expr, Operator, TypeHandler<Expr, Operator>>();

	private TypeSpecificHandler<Expr, Condition, TypeHandler<Expr, Condition>> condConverter = new TypeSpecificHandler<Expr, Condition, TypeHandler<Expr, Condition>>();

	private TypeSpecificHandler<Expr, JsonPath, TypeHandler<Expr, JsonPath>> pathConverter = new TypeSpecificHandler<Expr, JsonPath, TypeHandler<Expr, JsonPath>>();

	private TypeSpecificHandler<Expr, Mapping, TypeHandler<Expr, Mapping>> transformationConverter = new TypeSpecificHandler<Expr, Mapping, TypeHandler<Expr, Mapping>>();

	public JaqlPlanCreator() {
		this.registerConverters();
	}

	private void registerConverters() {
		this.initOperatorConverter();
		this.initCondConverter();
		this.initPathConverter();
		this.initTransformationConverter();
	}

	private void initTransformationConverter() {
		this.transformationConverter.register(RecordExpr.class, new TransformationConverter<RecordExpr>() {
			@Override
			public Transformation convert(RecordExpr expr, List<Mapping> childTransformations) {
				Transformation transformation = new Transformation();
				for (Mapping mapping : childTransformations)
					transformation.addMapping(mapping);
				return transformation;
			}
		});
		this.transformationConverter.register(NameValueBinding.class, new TransformationConverter<NameValueBinding>() {
			@Override
			public Mapping convert(NameValueBinding expr, List<Mapping> childTransformations) {
				if (childTransformations.isEmpty())
					return new ValueAssignment(((ConstExpr) expr.nameExpr()).value.toString(),
						JaqlPlanCreator.this.parsePath(expr.valueExpr()));
				Mapping transformation = childTransformations.get(0);
				transformation.setTarget(((ConstExpr) expr.nameExpr()).value.toString());
				return transformation;
			}
		});
		this.transformationConverter.register(CopyField.class, new TransformationConverter<CopyField>() {
			@Override
			public ValueAssignment convert(CopyField expr, List<Mapping> childTransformations) {
				String fieldName = ((ConstExpr) expr.nameExpr()).value.toString();
				JsonPath path = JaqlPlanCreator.this.parsePath(expr.recExpr());
				path.setSelector(new JsonPath.FieldAccess(fieldName));
				return new ValueAssignment(fieldName, path);
			}
		});
		// this.transformationConverter.register(ArrayExpr.class, new TransformationConverter<ArrayExpr>() {
		// @Override
		// public Transformation convert(ArrayExpr expr, List<Mapping> childTransformations) {
		// Transformation transformation = new Transformation();
		// if (childTransformations.size() > 0)
		// for (Mapping mapping : childTransformations)
		// transformation.addMapping(mapping);
		// else {
		// List<JsonPath> elements = new ArrayList<JsonPath>();
		// for (int index = 0; index < expr.numChildren(); index++) {
		// elements.add( parsePath(expr.child(index)));
		// }
		// transformation.addMapping(new ValueAssignment(new JsonPath.ArrayCreation(elements)));
		// }
		// return transformation;
		// }
		// });
		this.transformationConverter.register(CopyRecord.class, new TransformationConverter<CopyRecord>() {
			@Override
			public ValueAssignment convert(CopyRecord expr, List<Mapping> childTransformations) {
				JsonPath path = JaqlPlanCreator.this.parsePath(expr.recExpr());
				return new ValueAssignment("", path);
			}
		});
		this.transformationConverter.register(TransformExpr.class, new TransformationConverter<TransformExpr>() {
			@Override
			public Transformation convert(TransformExpr expr, List<Mapping> childOperators) {
				return (Transformation) childOperators.get(childOperators.size() - 1);
			}
		});
		this.transformationConverter.register(BindingExpr.class, new BindingExtractor<Transformation>());
	}

	private void initPathConverter() {
		this.pathConverter.register(PathExpr.class, new PathConverter<PathExpr>() {
			@Override
			public JsonPath convert(PathExpr expr, List<JsonPath> childPaths) {
				for (int index = childPaths.size() - 2; index >= 0; index--) {
					JsonPath childPath = childPaths.get(index);
					JsonPath lastSelector;
					if (childPath.getSelector() == null)
						childPaths.set(index, lastSelector = childPath.clone());
					else {
						JsonPath lastButOneSelector = childPath.getSelector(-2);
						lastButOneSelector.setSelector(lastSelector = lastButOneSelector.getSelector().clone());
					}
					lastSelector.setSelector(childPaths.get(index + 1));
				}
				return childPaths.get(0);
			}
		});
		this.pathConverter.register(VarExpr.class, new PathConverter<VarExpr>() {
			@Override
			public JsonPath convert(VarExpr expr, List<JsonPath> childPath) {
				// if (!expr.var().taggedName().equals("$"))
				Binding binding = JaqlPlanCreator.this.bindings.get(expr.var().taggedName());
				Object var = binding.getTransformed();
				if (expressionToOperators.containsKey(binding.getExpr())) {
					var = expressionToOperators.get(binding.getExpr());
				}
				if (var instanceof Operator) {
					int index = JaqlPlanCreator.this.findInputIndex((Operator) var);
					if (index != -1)
						return new JsonPath.Input(index);
				}

				if (var instanceof JsonPath)
					return (JsonPath) var;
				// if(var instanceof Operator)
				System.out.println(expr.var().taggedName() + " -> " + var);

				return new JsonPath.IdentifierAccess(expr.var().taggedName());
			}
		});
		this.pathConverter.register(PathFieldValue.class, new PathConverter<PathFieldValue>() {
			@Override
			public JsonPath convert(PathFieldValue expr, List<JsonPath> childPath) {
				JsonPath.FieldAccess fieldAccess = new JsonPath.FieldAccess(((ConstExpr) expr.nameExpr()).value
					.toString());
				if (childPath.size() > 1)
					fieldAccess.setSelector(childPath.get(1));
				return fieldAccess;
			}
		});
		this.pathConverter.register(ConstExpr.class, new PathConverter<ConstExpr>() {
			@Override
			public JsonPath convert(ConstExpr expr, List<JsonPath> childPath) {
				return new JsonPath.Constant(expr.value);
			}
		});
		this.pathConverter.register(PathIndex.class, new PathConverter<PathIndex>() {
			@Override
			public JsonPath convert(PathIndex expr, List<JsonPath> childPath) {
				return new JsonPath.ArrayAccess(((Constant) childPath.get(0)).asInt());
			}
		});
		this.pathConverter.register(PathArrayAll.class, new PathConverter<PathArrayAll>() {
			@Override
			public JsonPath convert(PathArrayAll expr, List<JsonPath> childPath) {
				JsonPath.ArrayAccess path = new JsonPath.ArrayAccess();
				path.setSelector(childPath.get(0));
				return path;
			}
		});
		// function fall-back
		this.pathConverter.register(Expr.class, new PathConverter<Expr>() {
			@Override
			public JsonPath convert(Expr expr, List<JsonPath> childPaths) {
				if (!expr.getClass().getSimpleName().endsWith("Fn"))
					return null;
				BuiltInFunctionDescriptor d = BuiltInFunction.getDescriptor(expr.getClass());
				return new JsonPath.Function(d.getName(), childPaths.toArray(new JsonPath[childPaths.size()]));
			}
		});
		this.pathConverter.register(ArrayExpr.class, new PathConverter<ArrayExpr>() {
			@Override
			public JsonPath convert(ArrayExpr expr, List<JsonPath> childPaths) {
				return new JsonPath.ArrayCreation(childPaths);
			}
		});
		this.pathConverter.register(MathExpr.class, new PathConverter<MathExpr>() {
			private Field OpField;

			private ArithmeticOperator[] OperatorMapping = { ArithmeticOperator.PLUS, ArithmeticOperator.MINUS,
				ArithmeticOperator.MULTIPLY, ArithmeticOperator.DIVIDE };

			{
				try {
					this.OpField = MathExpr.class.getDeclaredField("op");
					this.OpField.setAccessible(true);
				} catch (Exception e) {
					throw new IllegalStateException("Cannot find op field", e);
				}
			}

			@Override
			public JsonPath convert(MathExpr expr, List<JsonPath> childConditions) {
				try {
					int op = (Integer) this.OpField.get(expr);
					return new JsonPath.Arithmetic(childConditions.get(0), this.OperatorMapping[op], childConditions
						.get(1));
				} catch (Exception e) {
					throw new IllegalArgumentException("Cannot parse " + expr, e);
				}
			}
		});
	}

	protected int findInputIndex(Operator input) {
		if (operatorInputs.isEmpty())
			return -1;
		Iterator<List<Operator>> iterator = operatorInputs.descendingIterator();
		while (iterator.hasNext()) {
			List<Operator> inputs = iterator.next();

			for (int index = 0; index < inputs.size(); index++) {
				if (inputs.get(index) == input)
					return index;
			}
		}
		return -1;
	}

	private void initCondConverter() {
		this.condConverter.register(CompareExpr.class, new CondConverter<CompareExpr>() {
			private Field OpField;

			private BinaryOperator[] OperatorMapping = { BinaryOperator.EQUAL, BinaryOperator.NOT_EQUAL,
				BinaryOperator.LESS, BinaryOperator.LESS_EQUAL, BinaryOperator.GREATER, BinaryOperator.GREATER_EQUAL };

			{
				try {
					this.OpField = CompareExpr.class.getDeclaredField("op");
					this.OpField.setAccessible(true);
				} catch (Exception e) {
					throw new IllegalStateException("Cannot find op field", e);
				}
			}

			@Override
			public Condition convert(CompareExpr expr, List<Condition> childConditions) {
				try {
					int op = (Integer) this.OpField.get(expr);
					return new Condition(new Comparison(JaqlPlanCreator.this.parsePath(expr.child(0)),
						this.OperatorMapping[op], JaqlPlanCreator.this.parsePath(expr
							.child(1))));
				} catch (Exception e) {
					throw new IllegalArgumentException("Cannot parse " + expr, e);
				}
			}
		});
	}

	private Map<Expr, Operator> expressionToOperators = new IdentityHashMap<Expr, Operator>();

	private Deque<List<Operator>> operatorInputs = new LinkedList<List<Operator>>();

	private void initOperatorConverter() {
		operatorConverter.addListener(new TypeHandlerListener<Expr, Operator>() {
			@Override
			public void beforeConversion(Expr in, Object[] params) {
				operatorInputs.addLast((List<Operator>) params[0]);
			}

			@Override
			public void afterConversion(Expr in, Object[] params, Operator out) {
				// if (!(in instanceof BindingExpr))
				// operatorParameters.removeLast();
				expressionToOperators.put(in, out);
			}
		});
		this.operatorConverter.register(WriteFn.class, new ExprConverter<WriteFn>() {
			@Override
			public Operator convert(WriteFn expr, List<Operator> childOperators) {
				return new Sink("hdfs", ((AbstractHandleFn) expr.descriptor()).location().toString(), childOperators
					.get(0));
			}
		});
		this.operatorConverter.register(FilterExpr.class, new ExprConverter<FilterExpr>() {
			@Override
			public Operator convert(FilterExpr expr, List<Operator> childOperators) {
				return new Selection(JaqlPlanCreator.this.parseCondition(expr), childOperators.get(0));
			}
		});
		this.operatorConverter.register(TransformExpr.class, new ExprConverter<TransformExpr>() {
			@Override
			public Operator convert(TransformExpr expr, List<Operator> childOperators) {
				return new Projection(JaqlPlanCreator.this.parseTransformation(expr), childOperators.get(0));
			}
		});
		this.operatorConverter.register(JoinExpr.class, new ExprConverter<JoinExpr>() {
			private List<String> inputAliases = new ArrayList<String>();

			@Override
			public Operator convert(JoinExpr expr, List<Operator> childOperators) {
				// Expr optionsExpr = expr.optionsExpr();
				List<List<JsonPath>> onPaths = new ArrayList<List<JsonPath>>();
				for (int index = 0; index < expr.numBindings(); index++) {
					ArrayExpr onExpr = (ArrayExpr) expr.onExpr(index);
					ArrayList<JsonPath> onPath = new ArrayList<JsonPath>();
					for (int i = 0; i < onExpr.numChildren(); i++)
						onPath.add(JaqlPlanCreator.this.parsePath(onExpr.child(i)));
					onPaths.add(onPath);
				}

				Condition condition = null;
				for (int index = 0; index < onPaths.get(0).size(); index++)
					condition = new Condition(new Comparison(onPaths.get(0).get(index), BinaryOperator.EQUAL, onPaths
						.get(1).get(index)), Combination.AND, condition);

				if (inputAliases.size() < childOperators.size())
					inputAliases.addAll(Arrays.asList(new String[childOperators.size() - inputAliases.size()]));
				for (int index = 0; index < childOperators.size(); index++)
					childOperators.set(index, withoutNameBinding(childOperators.get(index), index));
				return new Join(parseTransformation(expr, childOperators.size()), condition, childOperators);
			}

			private Transformation parseTransformation(JoinExpr expr, int numInputs) {
				bindings.put("$", new Binding(null, new JsonPath.Input(0)));
				Transformation transformation = JaqlPlanCreator.this.parseTransformation(((ForExpr) expr.parent()
					.parent()).collectExpr());
				for (int inputIndex = 0; inputIndex < numInputs; inputIndex++) {
					JsonPath alias = new JsonPath.Input(inputIndex);
					alias.setSelector(new JsonPath.FieldAccess(inputAliases.get(inputIndex)));
					transformation.replace(alias, new JsonPath.Input(inputIndex));
				}
				return transformation;
			}

			private Operator withoutNameBinding(Operator operator, int inputIndex) {
				if (operator instanceof Projection && operator.getTransformation().getMappingSize() == 1) {
					Mapping mapping = operator.getTransformation().getMapping(0);
					if (mapping instanceof ValueAssignment
						&& ((ValueAssignment) mapping).getTransformation() instanceof JsonPath.Input) {
						Operator coreInput = operator.getInputs().get(
							((Input) ((ValueAssignment) mapping).getTransformation()).getIndex());
						Iterator<Entry<String, Binding>> iterator = bindings.entrySet().iterator();

						// replace bindings to core input
						while (iterator.hasNext()) {
							Binding binding = iterator.next().getValue();
							if (binding.getTransformed() == operator)
								binding.setTransformed(coreInput);
						}
						inputAliases.set(inputIndex, mapping.getTarget());
						return coreInput;
					}
				}
				return operator;
			}
		});
		this.operatorConverter.register(GroupByExpr.class, new ExprConverter<GroupByExpr>() {
			@Override
			public Operator convert(GroupByExpr expr, List<Operator> childOperators) {
				// expr.optionsExpr(), expr.usingExpr()
				if (childOperators.size() > 0)
					return new Aggregation(JaqlPlanCreator.this.parseTransformation(((ArrayExpr) expr.collectExpr())
						.child(0)),
						JaqlPlanCreator.this.parsePath(expr.byBinding()), childOperators.get(0));
				return null;
			}
		});
		this.operatorConverter.register(ReadFn.class, new ExprConverter<ReadFn>() {
			@Override
			public Operator convert(ReadFn expr, List<Operator> childOperators) {
				return new Source("hdfs", ((AbstractHandleFn) expr.child(0)).location().toString());
			}
		});
		// this.operatorConverter.register(ForExpr.class, new ExprConverter<ForExpr>() {
		// @Override
		// public Operator convert(ForExpr expr, List<Operator> childOperators) {
		// Operator operator = childOperators.get(0);
		// operator.setTransformation(parseTransformation(expr.collectExpr()));
		// return operator;
		// }
		// });

		this.operatorConverter.register(BindingExpr.class, new BindingExtractor<Operator>());
	}

	protected Transformation parseTransformation(Expr expr) {
		return (Transformation) this.transformationConverter.handleRecursively(EXPR_NAVIGATOR, expr);
	}

	protected Condition parseCondition(FilterExpr expr) {
		return this.condConverter.handleRecursively(EXPR_NAVIGATOR, expr);
	}

	private Map<String, Binding> bindings = new HashMap<String, Binding>();

	private Operator convertSubtree(Expr expr) {
		return this.operatorConverter.handleRecursively(EXPR_NAVIGATOR, expr);
	}

	private JsonPath parsePath(Expr expr) {
		return this.pathConverter.handleRecursively(EXPR_NAVIGATOR, expr);
	}

}
