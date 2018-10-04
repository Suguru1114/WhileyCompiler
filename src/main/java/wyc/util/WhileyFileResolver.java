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
package wyc.util;

import static wyil.lang.WyilFile.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import wyc.Activator;
import wycc.cfg.Configuration;
import wybs.lang.Build;
import wybs.lang.CompilationUnit;
import wybs.lang.NameID;
import wybs.lang.NameResolver;
import wybs.lang.SyntacticElement;
import wybs.lang.SyntacticHeap;
import wybs.lang.SyntacticItem;
import wybs.util.AbstractCompilationUnit.Value;
import wyfs.lang.Content;
import wyfs.lang.Path;
import wyfs.util.Trie;
import wyil.lang.WyilFile;

/**
 * Responsible for resolving a name which occurs at some position in a WhileyFile.
 * This takes into account the context and, if necessary, will traverse
 * important statements to resolve the query. For example, consider a
 * WhileyFile entitled "file":
 *
 * <pre>
 * import wyal.lang.*
 *
 * assert:
 *    Test.g(0) >= 0
 * </pre>
 *
 * Here the name "<code>g</code>" is not fully qualified. Depending on which
 * file the matching declaration of <code>g</code> occurs will depend on what
 * its fully qualified name is. For example, if <code>g</code> is declared in
 * the current compilation unit then it's fully quaified name would be
 * <code>test.g</code>. However, it could well be declared in a compilation unit
 * matching the import <code>wyal.lang.*</code>.
 *
 * @author David J. Pearce
 *
 */
public final class WhileyFileResolver implements NameResolver {
	private final Build.Project project;

	public WhileyFileResolver(Build.Project project) {
		this.project = project;
	}

	@Override
	public NameID resolve(CompilationUnit.Name name) throws ResolutionError {
		//
		if (name.size() == 1) {
			CompilationUnit.Identifier ident = name.get(0);
			// This name is not fully qualified. Therefore, attempt to resolve
			// it.
			WyilFile enclosing = (WyilFile) name.getHeap();
			if (localNameLookup(ident.get(), enclosing)) {
				return new NameID(enclosing.getEntry().id(), ident.get());
			}
			// Failed local lookup
		}
		// If we get here, then either we failed the local lookup or it was already a
		// partially or fully qualified name. Eitherway, we need to validate that it has
		// indeed been imported.
		return nonLocalNameLookup(name);
	}

	@Override
	public <T extends Declaration> T resolveExactly(CompilationUnit.Name name, Class<T> kind) throws ResolutionError {
		List<T> matches = resolveAll(name, kind);
		if (matches.size() == 1) {
			return matches.get(0);
		} else {
			throw new NameResolver.AmbiguousNameError(name);
		}
	}

	@Override
	public <T extends Declaration> List<T> resolveAll(CompilationUnit.Name name, Class<T> kind) throws ResolutionError {
		try {
			NameID nid = resolve(name);
			WyilFile enclosing = loadModule(nid,name);
			ArrayList<T> result = new ArrayList<>();
			// Look through the enclosing file first!
			for (int i = 0; i != enclosing.size(); ++i) {
				SyntacticItem item = enclosing.getSyntacticItem(i);
				if (item instanceof WyilFile.Decl.Named) {
					WyilFile.Decl.Named nd = (WyilFile.Decl.Named) item;
					if (nd.getName().get().equals(nid.name()) && kind.isInstance(nd)) {
						result.add((T) nd);
					}
				}
			}
			//
			if (!result.isEmpty()) {
				//
				return result;
			}
			throw new NameResolver.NameNotFoundError(name);
		} catch (IOException e) {
			// Slight unclear what the best course of action is here.
			throw new NameResolver.NameNotFoundError(name);
		}
	}


	/**
	 * Load a given WyIL file from this project. This will search through local
	 * roots and package dependencies in search of a matching file.
	 *
	 * @param id
	 *            The module ID of the file to load.
	 * @return
	 * @throws IOException
	 */
	public Path.Entry<WyilFile> load(Path.ID id) throws IOException {
		// Check within this project
		for(Path.Root root : project.getRoots()) {
			Path.Entry<WyilFile> e = root.get(id, WyilFile.ContentType);
			if(e != null) {
				return e;
			}
		}
		// Check within dependencies
		for (Build.Package pkg : project.getPackages()) {
			Path.Root root = getPlatformBinaryRoot(pkg);
			//
			Path.Entry<WyilFile> e = root.get(id, WyilFile.ContentType);
			if (e != null) {
				return e;
			}
		}
		//
		return null;
	}


	private WyilFile loadModule(NameID nid, CompilationUnit.Name name) throws IOException, ResolutionError {
		WyilFile enclosing = getWhileyFile(name.getHeap());
		if (enclosing.getEntry().id().equals(nid.module())) {
			// This is a local lookup.

			// FIXME: unclear why necessary to distinguish local from non-local
			// look ups. Specifically, the project.get(...) should return
			// enclosing if the module path identifies the enclosing module.

			return enclosing;
		} else {
			// This is a non-local lookup.
			Path.Entry<WyilFile> entry = load(nid.module());
			if (entry != null) {
				return entry.read();
			} else {
				throw new NameResolver.NameNotFoundError(name);
			}
		}
	}

	/**
	 * Look up the given named item in the given heap. The precondition is that
	 * this name has exactly one component.
	 *
	 * @param name
	 * @param heap
	 * @param kind
	 * @return
	 * @throws NameNotFoundError
	 */
	private <T extends Decl.Named> boolean localNameLookup(String name, SyntacticHeap heap) {
		int count = 0;
		// Look through the enclosing file first!
		for (int i = 0; i != heap.size(); ++i) {
			SyntacticItem item = heap.getSyntacticItem(i);
			if (item instanceof WyilFile.Decl.Named) {
				WyilFile.Decl.Named nd = (WyilFile.Decl.Named) item;
				if (nd.getName().get().equals(name)) {
					count = count + 1;
				}
			}
		}
		//
		if (count == 0) {
			return false;
		} else {
			//
			return true;
		}
	}

	/**
	 * Attempt to look up a non-local name. That is, one which may not be
	 * defined in the enclosing module.
	 *
	 * @throws NameNotFoundError
	 */
	private NameID nonLocalNameLookup(CompilationUnit.Name name) throws NameResolver.ResolutionError {
		try {
			WyilFile enclosing = getWhileyFile(name.getHeap());
			List<WyilFile.Decl.Import> imports = getImportsInReverseOrder(enclosing);
			// Check name against import statements
			for (WyilFile.Decl.Import imp : imports) {
				NameID nid = matchImport(imp, name);
				if (nid != null) {
					return nid;
				}
			}
			// Check whether name is fully qualified or not
			NameID nid = name.toNameID();
			if (name.size() > 1) {
				// Could be fully or partially qualified name
				Path.Entry<WyilFile> e = load(nid.module());
				if (e != null) {
					// Look inside to see whether a matching item is found
					if (localNameLookup(nid.name(), e.read())) {
						return nid;
					}
				} else {
					// If we get here, then there is still an actual chance it could
					// be referring to something declared in this compilation unit
					// (i.e. a local lookup with a partially- or fully-qualified
					// name)
					Path.ID localPathID = enclosing.getEntry().id();
					//
					if (matchPartialModulePath(nid.module(), localPathID)) {
						// Yes, ok, we've matched a local item!
						return new NameID(localPathID, nid.name());
					}
					// Otherwise, we really couldn't figure out this name.
				}
			}
		} catch (IOException e) {

		}
		throw new NameResolver.NameNotFoundError(name);
	}

	/**
	 * Extract the list of imports from the given WhileyFile. These are returned
	 * in reverse order, since that is the order in which they will be examined
	 * for the given named item.
	 *
	 * @param heap
	 * @return
	 */
	private List<WyilFile.Decl.Import> getImportsInReverseOrder(SyntacticHeap heap) {
		ArrayList<WyilFile.Decl.Import> imports = new ArrayList<>();
		for (int i = heap.size() - 1; i >= 0; --i) {
			SyntacticElement element = heap.getSyntacticItem(i);
			if (element instanceof WyilFile.Decl.Import) {
				imports.add((WyilFile.Decl.Import) element);
			}
		}
		return imports;
	}

	/**
	 * Match a given import against a given partially or fully quantified name.
	 * For example, we might match <code>import wyal.lang.*</code> against the
	 * name <code>Test.f</code>. This would succeed if the package
	 * <code>wyal.lang</code> contained a module <code>Test</code> which in turn
	 * contained a named declaration <code>f</code>.
	 *
	 * @param imp
	 * @param name
	 * @return
	 * @throws IOException
	 */
	private NameID matchImport(WyilFile.Decl.Import imp, CompilationUnit.Name name) throws IOException {
		NameID nid = name.toNameID();
		if(imp.hasFrom()) {
			if(name.size() == 1 && name.get(0).equals(imp.getFrom())) {
				// Ok, have matched an import from a from component
				Trie pkg = Trie.ROOT;
				Tuple<Identifier> path = imp.getPath();
				for (int i = 0; i < path.size(); ++i) {
					pkg = pkg.append(path.get(i).get());
				}
				return new NameID(pkg, nid.name());
			}
		} else if(name.size() > 1) {
			//
			for (Path.Entry<WyilFile> e : expandImport(imp)) {
				WyilFile module = e.read();
				Path.ID id = toPathID(module.getUnit().getName());
				// Determine whether this concrete module path matches the partial
				// module path or not.
				if (matchPartialModulePath(nid.module(), id)) {
					// Yes, it does match. Therefore, do we now have a valid name
					// identifier?
					if (localNameLookup(nid.name(), module)) {
						// Ok, we have found a matching item. Therefore, we are
						// done.
						return new NameID(id, nid.name());
					}
				}
			}
		}
		//
		return null;
	}

	/**
	 * Match a partial module path (e.g. <code>Test</code>) against a complete
	 * module path (e.g. <code>wyal.lang.Test</code>).
	 *
	 * @param completeModulePath
	 * @param partialModulePath
	 * @return
	 */
	private boolean matchPartialModulePath(Path.ID partialModulePath, Path.ID completeModulePath) {
		int completeSize = completeModulePath.size();
		int partialSize = partialModulePath.size();
		if (partialSize <= completeSize) {
			// The partial module path cannot be larger than the complete module
			// path, otherwise there can be no match.
			for (int i = 1; i <= partialModulePath.size(); ++i) {
				String concrete = completeModulePath.get(completeSize - i);
				String partial = partialModulePath.get(partialSize - i);
				if (!concrete.equals(partial)) {
					// One specified component does not match
					return false;
				}
			}
			return true;
		}
		return false;
	}

	/**
	 * Expand a given import into one or more valid module paths. For example,
	 * <code>wyal.lang.Test</code> will expand into just
	 * <code>wyal.lang.Test</code>. However, <code>wyal.lang.*</code> will
	 * expand into <code>wyal.lang.Test</code> and
	 * <code>wyal.lang.OtherTest</code> if <code>Test</code> and
	 * <code>OtherTest</code> are the only modues in the package
	 * <code>wyal.lang</code>
	 *
	 * @param imp
	 * @return
	 * @throws IOException
	 */
	private List<Path.Entry<WyilFile>> expandImport(WyilFile.Decl.Import imp) throws IOException {
		Trie filter = Trie.ROOT;
		Tuple<Identifier> path = imp.getPath();
		//
		for (int i = 0; i != path.size(); ++i) {
			Identifier component = path.get(i);
			if (component == null) {
				filter = filter.append("*");
			} else {
				filter = filter.append(component.get());
			}
		}
		//
		Content.Filter<WyilFile> cf = Content.filter(filter, WyilFile.ContentType);
		//
		ArrayList<Path.Entry<WyilFile>> matches = new ArrayList<>();
		//
		for(Path.Root root : project.getRoots()) {
			matches.addAll(root.get(cf));
		}
		// Check within dependencies
		for(Build.Package pkg : project.getPackages()) {
			Path.Root root = getPlatformBinaryRoot(pkg);
			matches.addAll(root.get(cf));
		}
		return matches;
	}

	private Path.Root getPlatformBinaryRoot(Build.Package pkg) throws IOException {
		// Extract package configuration. This tells us where the binary root for the
		// "whiley" platform is.
		Configuration configuration = pkg.getConfiguration();
		// Extract the path for the binary root of the Whiley platform.
		Path.ID binroot = Trie.fromString(configuration.get(Value.UTF8.class, Activator.TARGET_CONFIG_OPTION).unwrap());
		// Create relative root from pkg root.
		return pkg.getRoot().createRelativeRoot(binroot);
	}

	public WyilFile getWhileyFile(SyntacticHeap heap) {
		if(heap instanceof WyilFile) {
			return (WyilFile) heap;
		} else {
			return getWhileyFile(heap.getParent());
		}
	}

	private Path.ID toPathID(CompilationUnit.Name name) {
		Trie r = Trie.ROOT;
		for(int i=0;i!=name.size();++i) {
			r = r.append(name.get(i).get());
		}
		return r;
	}
}
