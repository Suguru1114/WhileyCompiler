// Copyright (c) 2011, David J. Pearce (djp@ecs.vuw.ac.nz)
// All rights reserved.
//
// Redistribution and use in source and binary forms, with or without
// modification, are permitted provided that the following conditions are met:
//    * Redistributions of source code must retain the above copyright
//      notice, this list of conditions and the following disclaimer.
//    * Redistributions in binary form must reproduce the above copyright
//      notice, this list of conditions and the following disclaimer in the
//      documentation and/or other materials provided with the distribution.
//    * Neither the name of the <organization> nor the
//      names of its contributors may be used to endorse or promote products
//      derived from this software without specific prior written permission.
//
// THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
// ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
// WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
// DISCLAIMED. IN NO EVENT SHALL DAVID J. PEARCE BE LIABLE FOR ANY
// DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
// (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
// LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
// ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
// (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
// SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

package wyjc.util;

import java.io.*;
import java.util.*;

import wyc.builder.WhileyBuilder;
import wyc.lang.WhileyFile;
import wycore.lang.*;
import wycore.lang.SyntaxError.InternalFailure;
import wycore.util.*;
import wyil.Pipeline;
import wyil.lang.WyilFile;

import org.apache.tools.ant.BuildException;
import org.apache.tools.ant.taskdefs.MatchingTask;

/**
 * This class implements an ant task for compiling whiley files via ant and an
 * appropriate build.xml file. The following illustrates how this task can be
 * used in a build.xml file:
 * 
 * <pre>
 * <taskdef name="wyjc" classname="wyjc.util.AntTask" classpath="lib/whiley.jar"/>
 * <wyjc srcdir="stdlib" includes="whiley\/**\/*.whiley" excludes="whiley/io/**" nvc="true"/>
 * </pre>
 * 
 * Here, the first line defines the new task, and requires whiley.jar (which
 * contains this class) to be on the classpath; The second invokes the task to
 * compile all files rooted in the stdlib/ directory which in the whiley/
 * package, excluding those in whiley/io. The nvc="true" switch indicates no
 * verification checking should be performed; a similar switch, nrc="true", can
 * be used to turn off generation of runtime checks as well.
 * 
 * @author David J. Pearce
 * 
 */
public class AntTask extends MatchingTask {
	
	/**
	 * The master project content type registry.
	 */
	public static final Content.Registry registry = new Content.Registry() {
	
		public void associate(Path.Entry e) {
			if(e.suffix().equals("whiley")) {
				e.associate(WhileyFile.ContentType, null);
			} else if(e.suffix().equals("class")) {
				// this could be either a normal JVM class, or a Wyil class. We
				// need to determine which.
				try { 
					WyilFile c = WyilFile.ContentType.read(e);
					if(c != null) {
						e.associate(WyilFile.ContentType,c);
					}
				} catch(Exception ex) {
					// hmmm, not exactly ideal
				}
			} 
		}
		
		public String suffix(Content.Type<?> t) {
			if(t == WhileyFile.ContentType) {
				return "whiley";
			} else if(t == WyilFile.ContentType) {
				return "wyil";
			} else {
				return ".dat";
			}
		}
	};
	
	public static final FileFilter fileFilter = new FileFilter() {
		public boolean accept(File f) {
			return f.getName().endsWith(".whiley") || f.getName().endsWith(".class") || f.isDirectory();
		}
	};
	
	ArrayList<Path.Root> bootpath = new ArrayList<Path.Root>();
	ArrayList<Path.Root> whileypath = new ArrayList<Path.Root>();
	private File srcdir;
	private File destdir;
	private boolean verbose = false;
		
    public void setSrcdir (File srcdir) {
        this.srcdir = srcdir;
    }

    public void setDestdir (File destdir) {
        this.destdir = destdir;
    }
    
    public void setWhileyPath (org.apache.tools.ant.types.Path path) throws IOException {
    	whileypath.clear(); // just to be sure
    	for(String file : path.list()) {
    		if(file.endsWith(".jar")) {
    			whileypath.add(new JarFileRoot(file,registry));
    		} else {
    			whileypath.add(new DirectoryRoot(file,registry));
    		}
    	}
    }
    
    public void setBootPath (org.apache.tools.ant.types.Path path) throws IOException {
    	bootpath.clear(); // just to be sure
    	for(String file : path.list()) {
    		if(file.endsWith(".jar")) {
    			bootpath.add(new JarFileRoot(file,registry));
    		} else {
    			bootpath.add(new DirectoryRoot(file,registry));
    		}
    	}
    }
    
    public void setVerbose(boolean b) {
    	verbose=b;
    }
    
    public void execute() throws BuildException {
        if (srcdir == null) {
            throw new BuildException("srcdir must be specified");
        }
        log("dir = " + srcdir, org.apache.tools.ant.Project.MSG_DEBUG);

       
        if(!compile()) {
        	throw new BuildException("compilation errors");
        }        	
                
        srcdir = null; // release file
    }
    	
    protected boolean compile() {
    	try {
    		// first, initialise source and target roots
    		ArrayList<Path.Root> roots = new ArrayList<Path.Root>();
        	DirectoryRoot source = new DirectoryRoot(srcdir,fileFilter,registry); 
    		roots.add(source);    
        		        	
        	DirectoryRoot target = null;
        	if(destdir != null) {
        		target = new DirectoryRoot(destdir,fileFilter,registry);        	
        		roots.add(target);
        	}
        	    	       	
        	wyjc.Main.initialiseBootPath(bootpath);        	;        	
        	roots.addAll(whileypath);
        	roots.addAll(bootpath);
    		        	
    		// second, construct the module loader    		
    		Project project = new Project(new StandardNameSpace(roots));    		

    		if(verbose) {			
    			project.setLogger(new Logger.Default(System.err));
    		}
    		
    		// third, initialise the pipeline    		    	
    		Pipeline pipeline = new Pipeline(Pipeline.defaultPipeline);
    		
    		// fourth initialise the builder
    		WhileyBuilder builder = new WhileyBuilder(project,pipeline);
    		Path.Filter<WhileyFile> srcFilter = RegexFilter.create(WhileyFile.ContentType,"**");    		
			StandardBuildRule rule = new StandardBuildRule(builder);			
			if(target != null) {
				rule.add(source, target, srcFilter);
			} else {
				rule.add(source, source, srcFilter);
			}			
			project.add(rule);
    		
			// Now, touch all source files which have modification date after
			// their corresponding binary.	
    		int count = 0;    					
			for (Path.Entry<WhileyFile> e : source.get(srcFilter)) {
				Path.Entry<WyilFile> binary = target.get(e.id(),
						WyilFile.ContentType);
				if (binary == null || binary.lastModified() < e.lastModified()) {
					count++;
					e.touch();
				}
			}		
    		
			log("Compiling " + count + " source file(s)");
			
    		// finally, compile away!
    		project.build();
    		
    		return true;
    	} catch (InternalFailure e) {
    		e.outputSourceError(System.err);
    		if (verbose) {
    			e.printStackTrace(System.err);
    		}
    		return false;
    	} catch (SyntaxError e) {
    		e.outputSourceError(System.err);
    		if (verbose) {
    			e.printStackTrace(System.err);
    		}
    		return false;
    	} catch (Throwable e) {
    		System.err.println("internal failure: " + e.getMessage());
    		if (verbose) {
    			e.printStackTrace(System.err);
    		}
    		return false;
    	}
    	
    	
    }
    
}
