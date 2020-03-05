// Copyright 2011 The Whiley Project Developers
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package wyil.check;

import wybs.lang.*;
import wybs.util.AbstractCompilationUnit.Identifier;
import wybs.util.AbstractCompilationUnit.Name;
import wybs.util.AbstractCompilationUnit.Pair;

import static wyc.util.ErrorMessages.syntaxError;
import static wyil.lang.WyilFile.*;

import java.sql.Types;
import java.util.*;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

import org.apache.tools.ant.types.Environment;

import wybs.util.AbstractCompilationUnit.Tuple;
import wycc.util.ArrayUtils;
import wyil.lang.WyilFile;
import wyil.lang.WyilFile.Decl;
import wyil.lang.WyilFile.Expr;
import wyil.lang.WyilFile.LVal;
import wyil.lang.WyilFile.Stmt;
import wyil.lang.WyilFile.Template;
import wyil.lang.WyilFile.Type;
import wyil.lang.WyilFile.Type.Array;
import wyil.lang.WyilFile.Type.Existential;
import wyil.lang.WyilFile.Type.Field;
import wyil.lang.WyilFile.Type.Record;
import wyil.lang.WyilFile.Type.Union;
import wyil.util.*;
import wyil.util.Subtyping.Constraints;

import static wyil.util.IncrementalSubtypeConstraints.BOTTOM;

/**
 * This is an overflow class for <code>FlowTypeCheck</code>. It provides various
 * clearly defined helper functions which can be off-loaded to make
 * <code>FlowTypeCheck</code> more focused.
 *
 * @author David J. Pearce
 *
 */
public class FlowTypeUtils {

	/**
	 * Update the environment to reflect the fact that the special "this" lifetime
	 * is contained within all declared lifetime parameters. Observe that this only
	 * makes sense if the enclosing declaration is for a method.
	 *
	 * @param decl
	 * @param environment
	 * @return
	 */
	public static Environment declareThisWithin(Decl.FunctionOrMethod decl, Environment environment) {
		if (decl instanceof Decl.Method) {
			Decl.Method method = (Decl.Method) decl;
			environment = environment.declareWithin("this", method.getLifetimes());
		}
		return environment;
	}

	public static Environment union(Environment... environments) {
		Environment result = environments[0];
		for (int i = 1; i != environments.length; ++i) {
			result = union(result, environments[i]);
		}
		//
		return result;
	}

	public static Environment union(Environment left, Environment right) {
		if (left == right || right == BOTTOM) {
			return left;
		} else if (left == BOTTOM) {
			return right;
		} else {
			Environment result = new Environment();
			Set<Decl.Variable> leftRefinements = left.getRefinedVariables();
			Set<Decl.Variable> rightRefinements = right.getRefinedVariables();
			for (Decl.Variable var : leftRefinements) {
				if (rightRefinements.contains(var)) {
					// We have a refinement on both branches
					Type leftT = left.getType(var);
					Type rightT = right.getType(var);
					Type mergeT = new Type.Union(leftT, rightT);
					result = result.refineType(var, mergeT);
				}
			}
			return result;
		}
	}

	/**
	 * <p>
	 * Extract the "true" test from a given type test in order that we might try to
	 * retype it. This does not always succeed if, for example, the expression being
	 * tested cannot be retyped. An example would be a test like
	 * <code>arr[i] is int</code> as, in this case, we cannot retype
	 * <code>arr[i]</code>.
	 * </p>
	 *
	 * <p>
	 * In the simple case of e.g. <code>x is int</code> we just extract
	 * <code>x</code> and type <code>int</code>. The more interesting case arises
	 * when there is at least one field access involved. For example,
	 * <code>x.f is int</code> extracts variable <code>x</code> with type
	 * <code>{int f, ...}</code> (which is a safe approximation).
	 * </p>
	 *
	 * @param expr
	 * @param type
	 * @return A pair on successful extraction, or null if possible extraction.
	 */
	public static Pair<Decl.Variable, Type> extractTypeTest(Expr expr, Type type) {
		if (expr instanceof Expr.VariableAccess) {
			Expr.VariableAccess var = (Expr.VariableAccess) expr;
			return new Pair<>(var.getVariableDeclaration(), type);
		} else if (expr instanceof Expr.RecordAccess) {
			Expr.RecordAccess ra = (Expr.RecordAccess) expr;
			Expr ra_operand = ra.getOperand();
			Type.Record ra_operandT = ra_operand.getType().as(Type.Record.class);
			if (ra_operandT != null) {
				Tuple<Type.Field> ra_fields = ra_operandT.getFields();
				Type.Field[] fields = new Type.Field[ra_fields.size()];
				//
				for (int i = 0; i != ra_fields.size(); ++i) {
					Type.Field f = ra_fields.get(i);
					if (f.getName().equals(ra.getField())) {
						fields[i] = new Type.Field(f.getName(), type);
					} else {
						fields[i] = f;
					}
				}
				Type.Record recT = new Type.Record(ra_operandT.isOpen(), new Tuple<>(fields));
				return extractTypeTest(ra.getOperand(), recT);
			}
		}
		// no extraction is possible
		return null;
	}

	/**
	 * Determine the set of modifier variables for a given statement block. A
	 * modified variable is one which is assigned.
	 *
	 * @param block
	 */
	public static Tuple<Decl.Variable> determineModifiedVariables(Stmt.Block block) {
		HashSet<Decl.Variable> modified = new HashSet<>();
		determineModifiedVariables(block, modified);
		return new Tuple<>(modified);
	}

	public static void determineModifiedVariables(Stmt.Block block, Set<Decl.Variable> modified) {
		for (int i = 0; i != block.size(); ++i) {
			Stmt stmt = block.get(i);
			switch (stmt.getOpcode()) {
			case STMT_assign: {
				Stmt.Assign s = (Stmt.Assign) stmt;
				for (LVal lval : s.getLeftHandSide()) {
					addAssignedVariables(lval, modified);
					// FIXME: this is not an ideal solution long term. In
					// particular, we really need this method to detect not
					// just modified variables, but also modified locations
					// in general (e.g. assignments through references, etc)
					continue;
				}
				break;
			}
			case STMT_dowhile: {
				Stmt.DoWhile s = (Stmt.DoWhile) stmt;
				determineModifiedVariables(s.getBody(), modified);
				break;
			}
			case STMT_if:
			case STMT_ifelse: {
				Stmt.IfElse s = (Stmt.IfElse) stmt;
				determineModifiedVariables(s.getTrueBranch(), modified);
				if (s.hasFalseBranch()) {
					determineModifiedVariables(s.getFalseBranch(), modified);
				}
				break;
			}
			case STMT_namedblock: {
				Stmt.NamedBlock s = (Stmt.NamedBlock) stmt;
				determineModifiedVariables(s.getBlock(), modified);
				break;
			}
			case STMT_switch: {
				Stmt.Switch s = (Stmt.Switch) stmt;
				for (Stmt.Case c : s.getCases()) {
					determineModifiedVariables(c.getBlock(), modified);
				}
				break;
			}
			case STMT_while: {
				Stmt.While s = (Stmt.While) stmt;
				determineModifiedVariables(s.getBody(), modified);
				break;
			}
			}
		}
	}

	/**
	 * Determine the modified variable for a given LVal. Almost all lvals modify
	 * exactly one variable, though dereferences don't.
	 *
	 * @param lval
	 * @param modified
	 * @return
	 */
	public static void addAssignedVariables(LVal lval, Set<Decl.Variable> modified) {
		if (lval instanceof Expr.VariableAccess) {
			Expr.VariableAccess lv = (Expr.VariableAccess) lval;
			modified.add(lv.getVariableDeclaration());
		} else if (lval instanceof Expr.RecordAccess) {
			Expr.RecordAccess e = (Expr.RecordAccess) lval;
			addAssignedVariables((LVal) e.getOperand(), modified);
		} else if (lval instanceof Expr.ArrayAccess) {
			Expr.ArrayAccess e = (Expr.ArrayAccess) lval;
			addAssignedVariables((LVal) e.getFirstOperand(), modified);
		} else if (lval instanceof Expr.TupleInitialiser) {
			Expr.TupleInitialiser e = (Expr.TupleInitialiser) lval;
			Tuple<Expr> operands = e.getOperands();
			for (int i = 0; i != operands.size(); ++i) {
				addAssignedVariables((LVal) operands.get(i), modified);
			}
		} else if (lval instanceof Expr.Dereference || lval instanceof Expr.FieldDereference) {

		} else {
			syntaxError(lval, WyilFile.INVALID_LVAL_EXPRESSION);
		}
	}

	// ===============================================================================================================
	// isPure
	// ===============================================================================================================

	public static class PurityVisitor extends AbstractVisitor {
		public boolean pure = true;

		public PurityVisitor(Build.Meter meter) {
			super(meter);
		}

		@Override
		public void visitExternalUnit(Decl.Unit unit) {
			// Terminate
		}

		@Override
		public void visitDeclaration(Decl type) {
			// Terminate
		}

		@Override
		public void visitStatement(Stmt stmt) {
			// Terminate
		}

		@Override
		public void visitStaticVariableAccess(Expr.StaticVariableAccess expr) {
			pure = false;
		}

		@Override
		public void visitNew(Expr.New expr) {
			pure = false;
		}

		@Override
		public void visitDereference(Expr.Dereference expr) {
			pure = false;
		}

		@Override
		public void visitFieldDereference(Expr.FieldDereference expr) {
			pure = false;
		}

		@Override
		public void visitInvoke(Expr.Invoke expr) {
			Decl.Link<Decl.Callable> l = expr.getLink();
			if (l.getTarget() instanceof Decl.Method) {
				// This expression is definitely not pure
				pure = false;
			}
		}

		@Override
		public void visitIndirectInvoke(Expr.IndirectInvoke expr) {
			Type.Callable sourceType = expr.getSource().getType().as(Type.Callable.class);
			if (sourceType instanceof Type.Method) {
				pure = false;
			}
		}

		@Override
		public void visitType(Type type) {
			// Terminate
		}
	};

	// ===============================================================================================================
	// Environment
	// ===============================================================================================================

	/**
	 * The bottom environment represents the root environment (in some sense) which
	 * maps no variables.
	 */
	public static final Environment BOTTOM = new Environment();

	/**
	 * Provides a very simple typing environment which defaults to using the
	 * declared type for a variable (this is the "null" case). However, the
	 * environment can also be updated to override the declared type with a new type
	 * as appropriate.
	 *
	 * @author David J. Pearce
	 *
	 */
	public static class Environment extends Subtyping.AbstractEnvironment {
		private final Map<Decl.Variable, Type> refinements;
		private final Map<String, String[]> withins;

		public Environment() {
			this.refinements = new HashMap<>();
			this.withins = new HashMap<>();
		}

		public Environment(Map<Decl.Variable, Type> refinements, Map<String, String[]> withins) {
			this.refinements = new HashMap<>(refinements);
			this.withins = new HashMap<>(withins);
		}

		public Type getType(Decl.Variable var) {
			Type refined = refinements.get(var);
			if (refined == null) {
				return var.getType();
			} else {
				return refined;
			}
		}

		public Environment refineType(Decl.Variable var, Type refinement) {
			if (getType(var).equals(refinement)) {
				// No refinement necessary
				return this;
			} else {
				Environment r = new Environment(this.refinements, this.withins);
				r.refinements.put(var, refinement);
				return r;
			}
		}

		public Set<Decl.Variable> getRefinedVariables() {
			return refinements.keySet();
		}

		@Override
		protected boolean isSubtype(Tuple<Expr> lhs, Tuple<Expr> rhs) {
			// NOTE: in principle, we could potentially do more here.
			return lhs.size() == 0 || lhs.equals(rhs);
		}

		@Override
		protected Subtyping.Constraints isSubtype(Type.Record t1, Type.Record t2, BinaryRelation<Type> cache) {
			Tuple<Type.Field> t1_fields = t1.getFields();
			Tuple<Type.Field> t2_fields = t2.getFields();
			// Sanity check number of fields are reasonable.
			if (t1_fields.size() > t2_fields.size()) {
				return IncrementalSubtypeConstraints.BOTTOM;
			} else if (t2.isOpen() && !t1.isOpen()) {
				return IncrementalSubtypeConstraints.BOTTOM;
			} else if (!t1.isOpen() && t1_fields.size() != t2.getFields().size()) {
				return IncrementalSubtypeConstraints.BOTTOM;
			}
			Subtyping.Constraints constraints = TOP;
			// NOTE: the following is O(n^2) but, in reality, will be faster than the
			// alternative (sorting fields into an array). That's because we expect a very
			// small number of fields in practice.
			for (int i = 0; i != t1_fields.size(); ++i) {
				Type.Field f1 = t1_fields.get(i);
				boolean matched = false;
				for (int j = 0; j != t2_fields.size(); ++j) {
					Type.Field f2 = t2_fields.get(j);
					if (f1.getName().equals(f2.getName())) {
						Subtyping.Constraints other = isSubtype(f1.getType(), f2.getType(), cache);
						// Matched field
						matched = true;
						constraints = constraints.intersect(other);
					}
				}
				// Check we actually matched the field!
				if (!matched) {
					return IncrementalSubtypeConstraints.BOTTOM;
				}
			}
			// Done
			return constraints;
		}

		@Override
		protected Subtyping.Constraints isSubtype(Type.Callable t1, Type.Callable t2, BinaryRelation<Type> cache) {
			Type t1_params = t1.getParameter();
			Type t2_params = t2.getParameter();
			Type t1_return = t1.getReturn();
			Type t2_return = t2.getReturn();
			// Eliminate easy cases first
			if(!isCallableSubtype(t1.getOpcode(),t2.getOpcode())) {
				return IncrementalSubtypeConstraints.BOTTOM;
			}
			// Check parameters (contra-variant)
			Subtyping.Constraints c_params = isSubtype(t2_params, t1_params, cache);
			// Check returns (co-variant)
			Subtyping.Constraints c_returns = isSubtype(t1_return, t2_return, cache);
			//
			if (t1 instanceof Type.Method && t2 instanceof Type.Method) {
				// Check lifetimes
				Type.Method m1 = (Type.Method) t1;
				Type.Method m2 = (Type.Method) t2;
				Tuple<Identifier> m1_lifetimes = m1.getLifetimeParameters();
				Tuple<Identifier> m2_lifetimes = m2.getLifetimeParameters();
				Tuple<Identifier> m1_captured = m1.getCapturedLifetimes();
				Tuple<Identifier> m2_captured = m2.getCapturedLifetimes();
				// FIXME: it's not clear to me what we need to do here. I think one problem is
				// that we must normalise lifetimes somehow.
				if (m1_lifetimes.size() > 0 || m2_lifetimes.size() > 0) {
					throw new RuntimeException("must implement this!");
				} else if (m1_captured.size() > 0 || m2_captured.size() > 0) {
					throw new RuntimeException("must implement this!");
				}
			}
			// Done
			return c_params.intersect(c_returns);
		}

		@Override
		public String toString() {
			String r = "{";
			boolean firstTime = true;
			for (Decl.Variable var : refinements.keySet()) {
				if (!firstTime) {
					r += ", ";
				}
				firstTime = false;
				r += var.getName() + "->" + getType(var);
			}
			r = r + "}{";
			firstTime = true;
			for (Map.Entry<String, String[]> w : withins.entrySet()) {
				if (!firstTime) {
					r += ", ";
				}
				firstTime = false;
				r = r + w.getKey() + " < " + Arrays.toString(w.getValue());
			}
			return r + "}";
		}

		@Override
		public boolean isWithin(String inner, String outer) {
			//
			if (outer.equals("*") || inner.equals(outer)) {
				// Cover easy cases first
				return true;
			} else {
				String[] outers = withins.get(inner);
				return outers != null && (ArrayUtils.firstIndexOf(outers, outer) >= 0);
			}
		}

		public Environment declareWithin(String inner, Tuple<Identifier> outers) {
			String[] outs = new String[outers.size()];
			for (int i = 0; i != outs.length; ++i) {
				outs[i] = outers.get(i).get();
			}
			return declareWithin(inner, outs);
		}

		public Environment declareWithin(String inner, Identifier... outers) {
			String[] outs = new String[outers.length];
			for (int i = 0; i != outs.length; ++i) {
				outs[i] = outers[i].get();
			}
			return declareWithin(inner, outs);
		}

		public Environment declareWithin(String inner, String... outers) {
			Environment nenv = new Environment(refinements, withins);
			nenv.withins.put(inner, outers);
			return nenv;
		}
	}

	private static boolean isCallableSubtype(int lhs, int rhs) {
		switch(lhs) {
		case TYPE_method:
			return true;
		case TYPE_function:
			return rhs == TYPE_function || rhs == TYPE_property;
		default:
			return rhs == TYPE_property;
		}
	}

	// ===============================================================================================================
	// Typing
	// ===============================================================================================================

	/**
	 * Represents the high-level data structure used in typing expressions.
	 *
	 * @author David J. Pearce
	 *
	 */
	public static final class Typing {
		private final Subtyping.AbstractEnvironment subtyping;
		/**
		 * The sequence of frames identifies (sub)expressions being typed and the type
		 * variable(s) allocated for them. First such type variable in each frame always
		 * represents the return type of the expression.
		 */
		private final ArrayList<Finaliser> frames;
		/**
		 * Tracks the number of allocated typing variables so we can allocate fresh
		 * variables as necessary.
		 */
		private int nVariables;

		private Row[] matrix;

		public Typing(Subtyping.AbstractEnvironment subtyping) {
			this.subtyping = subtyping;
			this.frames = new ArrayList<>();
			this.nVariables = 1;
			this.matrix = new Row[] { new Row(subtyping.TOP) };
		}

		private Typing(Subtyping.AbstractEnvironment subtyping, List<Finaliser> frames, int nVariables, Row[] matrix) {
			this.subtyping = subtyping;
			this.frames = new ArrayList<>(frames);
			this.nVariables = nVariables;
			this.matrix = matrix;
		}

		public boolean isEmpty() {
			return matrix.length == 0;
		}

		public int height() {
			return matrix.length;
		}

		public Tuple<Type> types(int index) {
			Type[] types = new Type[matrix.length];
			for(int i=0;i!=matrix.length;++i) {
				types[i] = matrix[i].get(index);
			}
			return new Tuple<>(types);
		}

		public Tuple<Tuple<Type>> types(int[] indices) {
			Tuple<Type>[] types = new Tuple[matrix.length];
			for(int i=0;i!=matrix.length;++i) {
				Type[] ith = new Type[indices.length];
				for(int j=0;j!=ith.length;++j) {
					ith[j] = matrix[i].get(indices[j]);
				}
				types[i] = new Tuple<>(ith);
			}
			return new Tuple<>(types);
		}

		public int top() {
			return nVariables-1;
		}

		public int[] top(int n) {
			return ArrayUtils.range(nVariables - n, nVariables);
		}

		public Typing invalidate() {
			return new Typing(subtyping, frames, nVariables, new Row[0]);
		}

		public void register(Predicate<Row[]> finaliser) {
			frames.add(new Finaliser(finaliser));
		}

		public Typing push(Type type) {
			return map(row -> row.add(type));
		}

		public Typing pushAll(int n, BiFunction<Row,Integer,Type[]> projection) {
			return project(row -> row.addAll(n,projection));
		}

		public Typing pull(int lhs, Function<Row, Type> projection) {
			return map(row -> {
				Type upper = row.get(lhs);
				Type lower = projection.apply(row);
				//
				if (upper instanceof Type.Void && lower instanceof Type.Void) {
					// Target happy to accept void, and we have void.
					return row;
				} else if (upper instanceof Type.Void) {
					// Indicates target happy to accept anything. Therefore, back propagate what we
					// have.
					return row.set(lhs, lower);
				} else if (lower instanceof Type.Void) {
					return null;
				} else {
					row = row.set(lhs, lower);
					// Target requires something, source has something. Hence, check they are
					// compatible.
					Subtyping.Constraints constraints = subtyping.isSubtype(upper, lower);
					// Done
					return row.intersect(constraints);
				}
			});
		}

		public Typing pull(int lhs, int rhs, Function<Type, Type> projection) {
			return map(row -> {
				Type upper = row.get(lhs);
				Type lower = projection.apply(row.get(rhs));
				//
				if (upper instanceof Type.Void && lower instanceof Type.Void) {
					// Target happy to accept void, and we have void.
					return row;
				} else if (lower instanceof Type.Void || lower == null) {
					return null;
				} else if (upper instanceof Type.Void) {
					// Indicates target happy to accept anything. Therefore, back propagate what we
					// have.
					return row.set(lhs, lower);
				} else {
					row = row.set(lhs, lower);
					// Target requires something, source has something. Hence, check they are
					// compatible.
					Subtyping.Constraints constraints = subtyping.isSubtype(upper, lower);
					// Done
					return row.intersect(constraints);
				}
			});
		}

		public Typing project(Function<Row,Row[]> projection) {
			ArrayList<Row> nRows = new ArrayList<>();
			// Sanity check existing rows
			for (int i = 0; i != matrix.length; ++i) {
				Row ith = matrix[i];
				Row[] nith = projection.apply(ith);
				//
				for(int j=0;j!=nith.length;++j) {
					nRows.add(nith[j]);
				}
			}
			// Remove any invalid rows
			Row[] arr = nRows.toArray(new Row[nRows.size()]);
			// Recalculate number of variables
			int nVariables = arr.length > 0 ? arr[0].size() : 0;
			// Create new typing
			return new Typing(subtyping, frames, nVariables, arr);
		}

		public Typing map(Function<Row,Row> fn) {
			Row[] nRows = matrix;
			// Sanity check existing rows
			for (int i = 0; i != nRows.length; ++i) {
				Row ith = nRows[i];
				Row nith = fn.apply(ith);
				// Sanity check current type
				if(ith != nith && matrix == nRows) {
					nRows = Arrays.copyOf(nRows,nRows.length);
				}
				nRows[i] = nith;
			}
			if(matrix == nRows) {
				return Typing.this;
			} else {
				// Remove any invalid rows
				nRows = ArrayUtils.removeAll(nRows, null);
				// Recalculate number of variables
				int nVariables = nRows.length > 0 ? nRows[0].size() : 0;
				// Create new typing
				return new Typing(subtyping, frames, nVariables, nRows);
			}
		}

		public Typing fold(Comparator<Row> comparator)  {
			if (matrix.length <= 1) {
				return this;
			} else {
				Row[] nrows = Arrays.copyOf(matrix, matrix.length);
				for (int i = 0; i != nrows.length; ++i) {
					Row ith = nrows[i];
					if (ith == null) {
						continue;
					}
					for (int j = i + 1; j < nrows.length; ++j) {
						Row jth = nrows[j];
						if (jth == null) {
							continue;
						}
						int c = comparator.compare(ith, jth);
						if (c < 0) {
							nrows[j] = null;
						} else if (c > 0) {
							nrows[i] = null;
						}
					}
				}
				nrows = ArrayUtils.removeAll(nrows, null);
				return new Typing(subtyping, frames, nVariables, nrows);
			}
		}

		/**
		 * Concretise every type within this typing by substitution each type variable
		 * for its current best solution.
		 *
		 * @return
		 */
		public Typing concretise() {
			return map(r -> r.concretise(subtyping));
		}

		/**
		 * Attempt to finalise the typing by giving a type to all expressions. This
		 * requires that we can boil all valid typings down to a single winning typing.
		 *
		 * @param typing
		 * @param environment
		 * @return
		 */
		public boolean finalise() {
			boolean r = true;
			// Finally, run every registered finaliser.
			for (Finaliser f : frames) {
				r &= f.finalise(matrix);
			}
			return r;
		}

		@Override
		public String toString() {
			return nVariables + ":" + Arrays.toString(matrix);
		}

		public final static class Row {
			/**
			 * Default comparator for typing rows.
			 */
			public static Comparator<Row> COMPARATOR(Subtyping.Environment env) {
				return new Comparator<Row>() {

					@Override
					public int compare(Row o1, Row o2) {
						Type[] o1_types = o1.types;
						Type[] o2_types = o2.types;
						if(o1_types.length < o2_types.length) {
							return -1;
						} else if(o1_types.length > o2_types.length) {
							return 1;
						}
						//
						for(int i=0;i!=o1_types.length;++i) {
							Type t1 = o1_types[i];
							Type t2 = o2_types[i];
							boolean left = env.isSatisfiableSubtype(t1, t2);
							boolean right = env.isSatisfiableSubtype(t2, t1);
							//
							if(left && !right) {
								return -1;
							} else if(!left && right) {
								return 1;
							}
						}
						return 0;
					}
				};
			}

			private Subtyping.Constraints constraints;
			private Type[] types;

			public Row(Subtyping.Constraints constraints, Type... types) {
				this.constraints = constraints;
				this.types = types;
			}

			public Type get(int index) {
				return types[index];
			}

			public Type[] getAll(int... indices) {
				Type[] ts = new Type[indices.length];
				for (int i = 0; i != indices.length; ++i) {
					ts[i] = types[indices[i]];
				}
				return ts;
			}

			public int size() {
				return types.length;
			}

			/**
			 * Allocate a given number of fresh type variables within this typing
			 * environment.
			 *
			 * @param n
			 * @return
			 */
			public wycc.util.Pair<Row,Type.Existential[]> fresh(int n) {
				int m = constraints.maxVariable() + 1;
				Type.Existential[] vars = new Type.Existential[n];
				for (int i = 0; i != vars.length; ++i) {
					vars[i] = new Type.Existential(m + i);
				}
				Typing.Row nrow = new Typing.Row(constraints.fresh(n), types);
				return new wycc.util.Pair<>(nrow,vars);
			}

			public Row set(int index, Type type) {
				if(type == null || type instanceof Type.Void) {
					return null;
				} else {
					Type[] nTypes = Arrays.copyOf(types, types.length);
					nTypes[index] = type;
					return new Row(constraints, nTypes);
				}
			}

			public Row add(Type type) {
				Type[] nTypes = Arrays.copyOf(types, types.length + 1);
				nTypes[types.length] = type;
				return new Row(constraints, nTypes);
			}

			public Row addAll(int n, Type type) {
				Type[] nTypes = Arrays.copyOf(types, types.length + n);
				for(int i=0;i<n;++i) {
					nTypes[i+types.length] = type;
				}
				return new Row(constraints, nTypes);
			}

			public Row[] addAll(int n, BiFunction<Row, Integer, Type[]> fn) {
				if(n == 0) {
					return new Row[] {this};
				} else {
					int m = 0;
					Type[][] nTypes = new Type[n][];
					for (int i = 0; i < n; ++i) {
						nTypes[i] = fn.apply(this, i);
						m = Math.max(m, nTypes[i].length);
					}
					Row[] nRows = new Row[m];
					for (int i = 0; i != m; ++i) {
						Type[] ith = Arrays.copyOf(types, types.length + n);
						for (int j = 0; j < n; ++j) {
							ith[types.length + j] = nTypes[j][i];
						}
						nRows[i] = new Row(constraints, ith);
					}
					return nRows;
				}
			}

			public Row intersect(Subtyping.Constraints constraints) {
				Subtyping.Constraints cs = this.constraints.intersect(constraints);
				if (!cs.isSatisfiable()) {
					return null;
				} else {
					return new Row(cs, types);
				}
			}

			public Row concretise(Subtyping.AbstractEnvironment subtyping) {
				int n = constraints.maxVariable();
				Subtyping.Constraints.Solution solution = constraints.solve(n);
				// Creating the necessary binding function for substitution
				Function<Object, SyntacticItem> binder = o -> o instanceof Integer ? solution.get((Integer) o) : null;
				Type[] nTypes = substitute(types,binder);
				// Create new row only if something changes
				if(nTypes == types) {
					return this;
				} else {
					return new Row(subtyping.TOP,nTypes);
				}
			}

			@Override
			public String toString() {
				return constraints + ":" + Arrays.toString(types);
			}
		}

		private class Finaliser {
			private final Predicate<Row[]> fn;

			public Finaliser(Predicate<Row[]> fn) {
				this.fn = fn;
			}

			public boolean finalise(Row[] solution) {
				return fn.test(solution);
			}

			@Override
			public String toString() {
				return fn.toString();
			}
		}

		private static List<Type.Tuple> filterApplicableTuples(int n, Type type) {
			List<Type.Tuple> ts = type.filter(Type.Tuple.class);
			for (int i = 0; i != ts.size(); ++i) {
				Type.Tuple r = ts.get(0);
				if (r.size() != n) {
					ts.remove(i--);
				}
			}
			return ts;
		}

		private static List<Type.Record> filterApplicableRecords(Tuple<Identifier> fields, Type type) {
			List<Type.Record> ts = type.filter(Type.Record.class);
			for(int i=0;i!=ts.size();++i) {
				Type.Record r = ts.get(0);
				if(!hasFields(r,fields)) {
					ts.remove(i--);
				}
			}
			return ts;
		}

		public static boolean hasFields(Type.Record rec, Tuple<Identifier> fields) {
			Tuple<Type.Field> fs = rec.getFields();
			if (!rec.isOpen() && fs.size() != fields.size()) {
				return false;
			} else if (rec.isOpen() && fs.size() > fields.size()) {
				return false;
			} else {
				for (int i = 0; i != fields.size(); ++i) {
					Identifier ith = fields.get(i);
					if (rec.getField(ith) == null) {
						return false;
					}
				}
				return true;
			}
		}
	}

	private static Type[] substitute(Type[] types, Function<Object, SyntacticItem> binder) {
		// NOTE: optimisation to prevent allocation
		Type[] nTypes = types;
		for (int j = 0; j != nTypes.length; ++j) {
			Type before = types[j];
			Type after = before.substitute(binder);
			if (before != after && nTypes == types) {
				nTypes = Arrays.copyOf(types, types.length);
			}
			nTypes[j] = after;
		}
		return nTypes;
	}

	// ===============================================================================================================
	// Binding
	// ===============================================================================================================

	/**
	 * Represents a candidate binding between a lambda type and declaration. For
	 * example, consider this:
	 *
	 * <pre>
	 * function id<T>(T x) -> (T x):
	 *    return 1
	 * </pre>
	 *
	 * Typing an invocation <code>id(1)</code> which will generate at least one
	 * binding. The <i>candidate</i> type will be
	 * <code>function(T)->(T)<code>, whilst the concrete type is <code>function(int)->(int)</code>.
	 * This binding additionally clarifies how the concrete type is derived from the
	 * candidate type using a mapping from template variables to types (e.g. in this
	 * case <code>{T=>int}</code>).
	 *
	 * @author David J. Pearce
	 *
	 */
	public static class Binding {
		private final Tuple<SyntacticItem> arguments;
		private final Decl.Callable candidate;
		private final Type.Callable concreteType;

		public Binding(Decl.Callable candidate, Tuple<SyntacticItem> arguments, Type.Callable concreteType) {
			this.candidate = candidate;
			this.arguments = arguments;
			this.concreteType = concreteType;
		}

		public Decl.Callable getCandidateDeclaration() {
			return candidate;
		}

		public Type.Callable getConcreteType() {
			return concreteType;
		}

		public Tuple<SyntacticItem> getArguments() {
			return arguments;
		}

		@Override
		public String toString() {
			Tuple<Template.Variable> variables = candidate.getTemplate();
			String r = "";
			for (int i = 0; i != variables.size(); ++i) {
				if (i != 0) {
					r = r + ",";
				}
				r += variables.get(i);
				r += "=";
				r += arguments.get(i);
			}
			return "{" + r + "}:" + candidate.getType();
		}
	}

	/**
	 * <p>
	 * Given a list of candidate bindings, determine the most precise match for the
	 * supplied argument types. The winning candidate must be a subtype of all
	 * candidates. For example, consider this:
	 * </p>
	 *
	 * <pre>
	 * function f(int|bool x) -> (int r):
	 *    ...
	 *
	 * function f(int x) -> (int r):
	 *    ...
	 * </pre>
	 *
	 * <p>
	 * Typing an invocation <code>f(1)</code> will generate two candidate bindings
	 * (i.e. one for each declaration above). The candidate corresponding to
	 * <code>function(int)->(int)</code> will be chosen over the other because its
	 * signature is a subtype of the other.
	 * </p>
	 *
	 * @param candidates
	 * @param args
	 * @return
	 */
	public static Binding selectCallableCandidate(List<Binding> candidates, Subtyping.Environment subtyping) {
		Binding best = null;
		Type.Callable bestType = null;
		boolean bestValidWinner = false;
		//
		for (int i = 0; i != candidates.size(); ++i) {
			Binding candidate = candidates.get(i);
			Type.Callable candidateType = candidate.getConcreteType();
			if (best == null) {
				// No other candidates are applicable so far. Hence, this
				// one is automatically promoted to the best seen so far.
				best = candidate;
				bestType = candidateType;
				bestValidWinner = true;
			} else {
				boolean csubb = subtyping.isSatisfiableSubtype(bestType, candidateType);
				boolean bsubc = subtyping.isSatisfiableSubtype(candidateType, bestType);
				//
				if (csubb && !bsubc) {
					// This candidate is a subtype of the best seen so far. Hence, it is now the
					// best seen so far.
					best = candidate;
					bestType = candidate.getConcreteType();
					bestValidWinner = true;
				} else if (bsubc && !csubb) {
					// This best so far is a subtype of this candidate. Therefore, we can simply
					// discard this candidate from consideration since it's definitely not the best.
				} else if (!csubb && !bsubc) {
					// This is the awkward case. Neither the best so far, nor the candidate, are
					// subtypes of each other. In this case, we report an error. NOTE: must perform
					// an explicit equality check above due to the present of type invariants.
					// Specifically, without this check, the system will treat two declarations with
					// identical raw types (though non-identical actual types) as the same.
					return null;
				} else {
					// This is a tricky case. We have two types after instantiation which are
					// considered identical under the raw subtype test. As such, they may not be
					// actually identical (e.g. if one has a type invariant). Furthermore, we cannot
					// stop at this stage as, in principle, we could still find an outright winner.
					bestValidWinner = false;
				}
			}
		}
		return bestValidWinner ? best : null;
	}
}
