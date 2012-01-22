package wyc.core;

import java.util.*;

import wyc.lang.UnresolvedType;
import wyc.lang.WhileyFile;
import static wyc.lang.WhileyFile.*;
import wyil.ModuleLoader;
import wyil.lang.*;
import wyil.util.Pair;
import wyil.util.ResolveError;
/**
 * <p>
 * The global generator is responsible for generating wyil bytecode for "global"
 * items. Essentially, this comes down to type constraints and partial
 * constants. For example:
 * </p>
 * 
 * <pre>
 * define nat as int where $ >= 0
 * 
 * int f(nat x):
 *    return x-1
 * </pre>
 * 
 * <p>
 * The global generator is responsible for generating the code for the
 * constraint on <code>nat</code>. Note, local generator are responsible for
 * inlining that constraint into the body of function <code>f</code>.
 * </p>
 * 
 * <p>
 * The code generated by the global generator for the constraint on
 * <code>nat</code> would look like this:
 * </p>
 * 
 * <pre>
 * define nat as int
 * where:
 *     load $
 *     const 0
 *     ifge goto exit
 *     fail("type constraint not satisfied")
 *  .exit:
 * </pre>
 * 
 * This wyil bytecode simply compares the special variable $ against 0. Here, $
 * represents the value held in a variable of type <code>nat</code>. If the
 * constraint fails, then the given message is printed.
 * 
 * @author David J. Pearce
 * 
 */
public class GlobalGenerator {
	private final CompilationGroup srcfiles;
	private final GlobalResolver resolver;
	private final ModuleLoader loader;
	private final HashMap<NameID,Block> cache = new HashMap<NameID,Block>();
	
	public GlobalGenerator(ModuleLoader loader, GlobalResolver resolver, CompilationGroup files) {
		this.srcfiles = files;
		this.loader = loader;
		this.resolver = resolver;
	}
		
	public Block generate(NameID nid) throws ResolveError {
		Block blk = cache.get(nid);
		if(blk == EMPTY_BLOCK) {
			return null;
		} else if(blk != null) {
			return blk;
		}
		
		// check whether the item in question is in one of the source
		// files being compiled.
		ModuleID mid = nid.module();
		WhileyFile wf = srcfiles.get(mid);
		if(wf != null) {
			// FIXME: the following line is necessary to terminate infinite
			// recursion. However, we really need to do better in the
			// context of recurisve types with constraints.
	
			WhileyFile.TypeDef td = wf.typeDecl(nid.name());
			if(td != null) {
				cache.put(nid, EMPTY_BLOCK);
				blk = generate(td.unresolvedType,td);
				if(td.constraint != null) {								
					if(blk == null) {
						blk = new Block(1);					
					}

					HashMap<String,Integer> environment = new HashMap<String,Integer>();
					environment.put("$",0);
					addExposedNames(td.resolvedType.raw(),environment,blk);
					String lab = Block.freshLabel();
					blk.append(new LocalGenerator(this,td).generateCondition(lab, td.constraint, environment));		
					blk.append(Code.Fail("constraint not satisfied"), td.constraint.attributes());
					blk.append(Code.Label(lab));								
				}
				cache.put(nid, blk);
				return blk;
			} else {
				Value v = resolver.resolveAsConstant(nid);				
				if(v instanceof Value.Set) {
					Value.Set vs = (Value.Set) v;
					Type.Set type = vs.type();
					blk = new Block(1);
					String lab = Block.freshLabel();
					blk.append(Code.Load(type.element(),0));
					blk.append(Code.Const(v));	
					blk.append(Code.IfGoto(vs.type(), Code.COp.ELEMOF, lab));
					// FIXME: missing attributes here.
					blk.append(Code.Fail("constraint not satisfied"));
					blk.append(Code.Label(lab));
					cache.put(nid, blk);
					return blk;
				} 
			}			
		} else {
			// now check whether it's already compiled and available on the
			// WHILEYPATH.
			Module m = loader.loadModule(mid);
			Module.TypeDef td = m.type(nid.name());
			if(td != null) {
				// should I cache this?
				return td.constraint();
			} else {
				return null;
			}
		}
		
		// FIXME: better error message?
		throw new ResolveError("name not found: " + nid);
	}
	
	public Block generate(UnresolvedType t, Context context) {
		Nominal nt = resolver.resolveAsType(t, context);
		Type raw = nt.raw();
		if (t instanceof UnresolvedType.List) {
			UnresolvedType.List lt = (UnresolvedType.List) t;
			Block blk = generate(lt.element, context);			
			if (blk != null) {
				Block nblk = new Block(1);
				String label = Block.freshLabel();
				nblk.append(Code.Load(raw, Code.THIS_SLOT), t.attributes());
				nblk.append(Code.ForAll(raw, Code.THIS_SLOT + 1, label,
						Collections.EMPTY_LIST), t.attributes());
				nblk.append(shiftBlock(1, blk));
				nblk.append(Code.End(label));
				blk = nblk;
			}
			return blk;
		} else if (t instanceof UnresolvedType.Set) {
			UnresolvedType.Set st = (UnresolvedType.Set) t;
			Block blk = generate(st.element, context);			
			if (blk != null) {
				Block nblk = new Block(1);
				String label = Block.freshLabel();
				nblk.append(Code.Load(raw, Code.THIS_SLOT), t.attributes());
				nblk.append(Code.ForAll(raw, Code.THIS_SLOT + 1, label,
						Collections.EMPTY_LIST), t.attributes());
				nblk.append(shiftBlock(1, blk));
				nblk.append(Code.End(label));
				blk = nblk;
			}
			return blk;
		} else if (t instanceof UnresolvedType.Dictionary) {
			UnresolvedType.Dictionary st = (UnresolvedType.Dictionary) t;
			Block blk = null;
			// FIXME: put in constraints. REQUIRES ITERATION OVER DICTIONARIES
			Block key = generate(st.key, context);
			Block value = generate(st.value, context);
			return blk;
		} else if (t instanceof UnresolvedType.Tuple) {
			// At the moment, a tuple is compiled down to a wyil record.
			UnresolvedType.Tuple tt = (UnresolvedType.Tuple) t;
			Type.EffectiveTuple ett = (Type.EffectiveTuple) raw;
			List<Type> ettElements = ett.elements();
			Block blk = null;
			
			int i = 0;
			for (UnresolvedType e : tt.types) {
				Block p = generate(e, context);
				if (p != null) {
					if (blk == null) {
						blk = new Block(1);
					}
					blk.append(Code.Load(raw, Code.THIS_SLOT), t.attributes());
					blk.append(Code.TupleLoad(ett, i), t.attributes());
					blk.append(Code.Store(ettElements.get(i), Code.THIS_SLOT + 1),
							t.attributes());
					blk.append(shiftBlock(1, p));
				}
				i = i + 1;
			}

			return blk;
		} else if (t instanceof UnresolvedType.Record) {
			UnresolvedType.Record tt = (UnresolvedType.Record) t;
			Type.EffectiveRecord ert = (Type.EffectiveRecord) raw;
			Map<String,Type> fields = ert.fields();
			Block blk = null;			
			for (Map.Entry<String, UnresolvedType> e : tt.types.entrySet()) {
				Block p = generate(e.getValue(), context);
				if (p != null) {
					if (blk == null) {
						blk = new Block(1);
					}
					blk.append(Code.Load(raw, Code.THIS_SLOT), t.attributes());
					blk.append(Code.FieldLoad(ert, e.getKey()), t.attributes());
					blk.append(Code.Store(fields.get(e.getKey()), Code.THIS_SLOT + 1),
							t.attributes());
					blk.append(shiftBlock(1, p));
				}
			}
			return blk;
		} else if (t instanceof UnresolvedType.Union) {
			UnresolvedType.Union ut = (UnresolvedType.Union) t;			
			Block blk = new Block(1);
			String exitLabel = Block.freshLabel();
			boolean constraints = false;
			List<UnresolvedType.NonUnion> ut_bounds = ut.bounds;
			
			// TODO: need to really make a decision tree here to handle
			// constrained types with matching underlying types.
			
			for (int i = 0; i != ut_bounds.size(); ++i) {				
				boolean lastBound = (i + 1) == ut_bounds.size();
				UnresolvedType b = ut_bounds.get(i);
				Type bt = resolver.resolveAsType(b, context).raw();
				Block p = generate(b, context);				
				
				if (p != null) {
					// In this case, there are constraints so we check the
					// negated type and branch over the constraint test if we
					// don't have the require type.
					String nextLabel = Block.freshLabel();
					constraints = true;					
					if (!lastBound) {
						blk.append(
								Code.IfType(raw, Code.THIS_SLOT,
										Type.Negation(bt), nextLabel),
								t.attributes());
					}
					blk.append(chainBlock(nextLabel, p));
					blk.append(Code.Goto(exitLabel));
					blk.append(Code.Label(nextLabel));
				} else {
					// In this case, there are no constraints so we can use a
					// direct type test.					
					blk.append(
							Code.IfType(raw, Code.THIS_SLOT, bt, exitLabel),
							t.attributes());
					raw = Type.intersect(raw, Type.Negation(bt));					
				}								
			}

			if (constraints) {
				blk.append(Code.Fail("type constraint not satisfied"),
						ut.attributes());
				blk.append(Code.Label(exitLabel));
			} else {
				blk = null;
			}

			return blk;
		} else if (t instanceof UnresolvedType.Not) {
			UnresolvedType.Not st = (UnresolvedType.Not) t;
			Block p = generate(st.element, context);
			Block blk = null;
			// TODO: need to fix not constraints
			return blk;
		} else if (t instanceof UnresolvedType.Intersection) {
			UnresolvedType.Intersection ut = (UnresolvedType.Intersection) t;
			Block blk = null;			
			for (int i = 0; i != ut.bounds.size(); ++i) {
				UnresolvedType b = ut.bounds.get(i);
				Block p = generate(b, context);
				// TODO: add intersection constraints				
			}
			return blk;
		} else if (t instanceof UnresolvedType.Reference) {
			UnresolvedType.Reference ut = (UnresolvedType.Reference) t;			
			Block blk = generate(ut.element, context);
			// TODO: fix process constraints
			return null;
		} else if (t instanceof UnresolvedType.Nominal) {
			UnresolvedType.Nominal dt = (UnresolvedType.Nominal) t;
			
			try {
				NameID nid = resolver.resolveAsName(dt.names,context);
				return generate(nid);
			} catch (ResolveError rex) {
				syntaxError(rex.getMessage(), context, t, rex);
				return null;
			}
		} else {
			// for base cases
			return null;
		}
	}
	
	/**
	 * The shiftBlock method takes a block and shifts every slot a given amount
	 * to the right. The number of inputs remains the same. This method is used 
	 * 
	 * @param amount
	 * @param blk
	 * @return
	 */
	private static Block shiftBlock(int amount, Block blk) {
		HashMap<Integer,Integer> binding = new HashMap<Integer,Integer>();
		for(int i=0;i!=blk.numSlots();++i) {
			binding.put(i,i+amount);
		}
		Block nblock = new Block(blk.numInputs());
		for(Block.Entry e : blk) {
			Code code = e.code.remap(binding);
			nblock.append(code,e.attributes());
		}
		return nblock.relabel();
	}
	
	/**
	 * The chainBlock method takes a block and replaces every fail statement
	 * with a goto to a given label. This is useful for handling constraints in
	 * union types, since if the constraint is not met that doesn't mean its
	 * game over.
	 * 
	 * @param target
	 * @param blk
	 * @return
	 */
	private static Block chainBlock(String target, Block blk) {	
		Block nblock = new Block(blk.numInputs());
		for (Block.Entry e : blk) {
			if (e.code instanceof Code.Fail) {
				nblock.append(Code.Goto(target), e.attributes());
			} else {
				nblock.append(e.code, e.attributes());
			}
		}
		return nblock.relabel();
	}
	

	/**
	 * The purpose of the exposed names method is capture the case when we have
	 * a define statement like this:
	 * 
	 * <pre>
	 * define tup as {int x, int y} where x < y
	 * </pre>
	 * 
	 * In this case, <code>x</code> and <code>y</code> are "exposed" --- meaning
	 * their real names are different in some way. In this case, the aliases we
	 * have are: x->$.x and y->$.y
	 * 
	 * @param src
	 * @param t
	 * @param environment
	 */
	private void addExposedNames(Type t,
			HashMap<String, Integer> environment, Block blk) {
		// Extended this method to handle lists and sets etc, is very difficult.
		// The primary problem is that we need to expand expressions involved
		// names exposed in this way into quantified
		// expressions.		
		if(t instanceof Type.Record) {
			Type.Record tt = (Type.Record) t;
			for(Map.Entry<String,Type> e : tt.fields().entrySet()) {
				String field = e.getKey();
				Integer i = environment.get(field);
				if(i == null) {
					int slot = environment.size(); 
					environment.put(field, slot);
					blk.append(Code.Load(t, 0));
					blk.append(Code.FieldLoad(tt, field));
					blk.append(Code.Store(e.getValue(), slot));
				}				
			}
		} 		
	}
	
	private static final Block EMPTY_BLOCK = new Block(1);
}
