/*******************************************************************************
 * Copyright (c) 2006 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials 
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 * 
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *     Matt Chapman - initial version
 *     Helen Hawkins - updated for new ajde interface (bug 148190) 
 *******************************************************************************/
package org.eclipse.ajdt.internal.core.ajde;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;

import org.aspectj.ajde.core.IOutputLocationManager;
import org.eclipse.ajdt.core.AJLog;
import org.eclipse.ajdt.core.AspectJCorePreferences;
import org.eclipse.ajdt.core.CoreUtils;
import org.eclipse.core.resources.IContainer;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.Path;
import org.eclipse.jdt.core.IClasspathAttribute;
import org.eclipse.jdt.core.IClasspathContainer;
import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.internal.core.JavaModelManager;
import org.eclipse.jdt.internal.core.builder.State;
import org.eclipse.jdt.internal.core.builder.StringSet;
import java.util.Comparator;

/**
 * IOutputLocationManager implementation which uses the methods on IJavaProject
 * to work out where the output should be sent.
 * 
 * Important note about paths:
 * Use Path.toOSString when describing a file on the filesystem
 * Use Path.toPortableString when describing a resource in Eclipse's workspace.
 */
public class CoreOutputLocationManager implements IOutputLocationManager {

    /**
     * 
     * @author Andrew Eisenberg
     * @created Apr 9, 2009
     * use this to ensure that the longest strings are looked at first
     * 
     * so, if src and src2 are both source folders, src2 will be
     * examined first
     */
    static class StringLengthComparator implements Comparator {
        public int compare(Object o1, Object o2) {
            if (o1 == null) {
                if (o2 == null) {
                    return 0;
                }
                return -1;
            }
            if (o2 == null) {
                return 1;
            }
            int len1 = o1.toString().length();
            int len2 = o2.toString().length();
            if (len1 > len2) {  // a larger string should come first
                return -1;
            } else if (len1 == len2) {
                // then compare by text:
                return o1.toString().compareTo(o2.toString());
            } else {
                return 1;
            }
        }
        
    }
    
    private static final StringLengthComparator comparator = new StringLengthComparator();
    
	private String projectName;
	private final IProject project;
	private final IJavaProject jProject;

	// if there is more than one output directory then the default output
	// location to use is recorded in the 'defaultOutput' field
	private File defaultOutput;
	
	private Map /*String,File*/ srcFolderToOutput = new TreeMap(comparator);
	
	private Map /*File, IProject*/ binFolderToProject;
	
	// maps files in the file system to IFolders in the workspace
	// this keeps track of output locations
    private final Map /* String,IFolder */ fileSystemPathToIContainer = new TreeMap(comparator);

	private List /*File*/ allOutputFolders = new ArrayList();
	
	// maps file system location to a path within the eclipse workspace
	// needs to take into account linked sources, where the actual
	// file system location may be different from the workspace location
	private Map /*String, String*/ allSourceFolders;
	
	// Bug 243376 
	// Gather all of the files that are touched by this compilation
	// and use it to determine which files need to have their 
	// Relationship maps updated.
	// XXX Really, this logic should not be in this class
	// This class is about output locations.
	// I am waiting for an extension to the compiler so
	// that I can grab this information directly.
	private Set /*String*/ touchedCUs = new HashSet();
	
	private boolean outputIsRoot;
	// if there is only one output directory then this is recorded in the
	// 'commonOutputDir' field.
	private File commonOutputDir;
    private IWorkspaceRoot workspaceRoot;

	public CoreOutputLocationManager(IProject project) {
		this.project = project;
		this.workspaceRoot = ResourcesPlugin.getWorkspace().getRoot();
		jProject = JavaCore.create(project);
        initSourceFolders();
		if (!isUsingSeparateOutputFolders(jProject)) {
			// using the same output directory therefore record this one
			setCommonOutputDir();
			allOutputFolders.add(commonOutputDir);

			if (commonOutputDir != null) {
    			try {
    			    if (outputIsRoot) {
                        fileSystemPathToIContainer.put(commonOutputDir.getAbsolutePath(), 
                                project);
    			    } else {
                        fileSystemPathToIContainer.put(commonOutputDir.getAbsolutePath(), 
                                workspaceRoot.getFolder(jProject.getOutputLocation()));
    			    }
                } catch (JavaModelException e) {
                }
			}
		} else {
			// need to record all possible output directories
			init();
		}
		
	}
	
	/**
	 * initialize the source folder locations only
	 */
	private void initSourceFolders() {
	    allSourceFolders = new TreeMap(comparator);
	    try {
            IClasspathEntry[] cpe = jProject.getRawClasspath();
            for (int i = 0; i < cpe.length; i++) {
                if (cpe[i].getEntryKind() == IClasspathEntry.CPE_SOURCE) {
                    IPath path = cpe[i].getPath();
                    IPath rawPath;
                    path = path.removeFirstSegments(1).makeRelative();
                    if (path.segmentCount() > 0) {
                        IFolder folder = project.getFolder(path);
                        rawPath = folder.getLocation();
                    } else {
                        rawPath = project.getLocation();
                    }
                    allSourceFolders.put(rawPath.toOSString(), path.toPortableString());
                }
            }
        } catch (JavaModelException e) {
        }
	}
	
	/**
	 * Calculate all the output locations
	 */
	private void init() {
		outputIsRoot = false;
		projectName = jProject.getProject().getName();
		String inpathOutFolder = getInpathOutputFolder();
		boolean isUsingNonDefaultInpathOutfolder = inpathOutFolder != null;

		try {
			IPath outputLocationPath = jProject.getOutputLocation();
            defaultOutput = workspacePathToFile(outputLocationPath);
			allOutputFolders.add(defaultOutput);
			
			
			fileSystemPathToIContainer.put(defaultOutput.getAbsolutePath(), 
			        project.getFullPath().equals(outputLocationPath) 
			                ? (IContainer) project
			                : (IContainer) workspaceRoot.getFolder(outputLocationPath));

			
			// store separate output folders in map
			IClasspathEntry[] cpe = jProject.getRawClasspath();
			outer: for (int i = 0; i < cpe.length; i++) {
                // check to see if on inpath
                if (isUsingNonDefaultInpathOutfolder) {
                    IClasspathAttribute[] attributes = cpe[i].getExtraAttributes();
                    for (int j = 0; j < attributes.length; j++) {
                        if (AspectJCorePreferences.isInPathAttribute(attributes[j])) {
                            IPath path = cpe[i].getPath();
                            File f = workspacePathToFile(path);
                            if (f != null && f.exists()) {
                                // use full path
                                String srcFolder = new Path(f.getPath()).toPortableString();
                                File out = workspacePathToFile(new Path(inpathOutFolder));
                                srcFolderToOutput.put(srcFolder,out);
                            } else {
                                // outfolder does not exist
                                // probably because Project has been renamed
                                // and inpath output location has not been updated.
                                // this is handled with a message to the user
                            }
                            continue outer;
                        }
                    }
                }
                
				if (cpe[i].getEntryKind() == IClasspathEntry.CPE_SOURCE) {
					IPath output = cpe[i].getOutputLocation();
					if (output != null) {
						IPath path = cpe[i].getPath();

						String srcFolder = path.removeFirstSegments(1).toPortableString();
						if (path.segmentCount() == 1) { // output folder is project
							srcFolder = path.toPortableString();
						}
						File out = workspacePathToFile(output);
						srcFolderToOutput.put(srcFolder, out);
						if (!allOutputFolders.contains(out)) {
							allOutputFolders.add(out);

							fileSystemPathToIContainer.put(out.getAbsolutePath(), 
				                    workspaceRoot.getFolder(output));
						}
						if (outputIsRoot) {
							// bug 153682: if the project is the source folder
							// then this output folder will always apply
							defaultOutput = out;
						}
						
					}
				}
			}
		} catch (JavaModelException e) {
		}
	}
	
	public File getOutputLocationForClass(File compilationUnit) {
	    // remember that this file has been asked for
	    // presumably it is being recompiled
	    if (CoreUtils.ASPECTJ_SOURCE_FILTER.accept(compilationUnit.getName())) {     
	        touchedCUs.add(compilationUnit);
	    }
	    
		return getOutputLocationForResource(compilationUnit);
	}

	public File getOutputLocationForResource(File resource) {
		if (!isUsingSeparateOutputFolders(jProject)) {
			return commonOutputDir;
		}
		if (resource == null || resource.toString() == null) {
			return defaultOutput;
		}
		
		// due to linked files, there may be multiple IResource relating to a single File
		IResource[] resources;
		IFile[] files = ResourcesPlugin.getWorkspace().getRoot().findFilesForLocationURI(resource.toURI());
		if (files != null && files.length > 0) {
		    resources = new IResource[files.length];
		    for (int i = 0; i < files.length; i++) {
                resources[i] = files[i];
            }
		} else {
	        IContainer[] containers = ResourcesPlugin.getWorkspace().getRoot().findContainersForLocationURI(resource.toURI());
	        if (containers != null && containers.length > 0) {
	            resources = new IResource[containers.length];
	            for (int i = 0; i < containers.length; i++) {
	                resources[i] = containers[i];
	            }
	        } else {
	            resources = null;
	        }
		}
		
		IResource thisResource = null;
		if (resources != null) {
		    IProject project = jProject.getProject();
		    for (int i = 0; i < resources.length; i++) {
                if (resources[i].getProject().equals(project)) {
                    thisResource = resources[i];
                    break;
                }
            }
		}
		
		if (thisResource != null) {
		    String pathStr = thisResource.getFullPath().removeFirstSegments(1).toPortableString();
		    for (Iterator iter = srcFolderToOutput.keySet().iterator(); iter.hasNext();) {
		        String src = (String) iter.next();
                if (pathStr.startsWith(src)) {
                    File out = (File) srcFolderToOutput.get(src);
                    return out;
                }
		    }
		}
		
		
		// couldn't find anything
		return defaultOutput;
	}

	/**
	 * @return true if there is more than one output directory being used by
	 *         this project and false otherwise
	 */
	private boolean isUsingSeparateOutputFolders(IJavaProject jp) {
		if (getInpathOutputFolder() != null) {
		    return true;
		}
	    try {
			IClasspathEntry[] cpe = jp.getRawClasspath();
			for (int i = 0; i < cpe.length; i++) {
				if (cpe[i].getEntryKind() == IClasspathEntry.CPE_SOURCE) {
					if (cpe[i].getOutputLocation() != null) {
						return true;
					}
				}
			}
		} catch (JavaModelException e) {
		}
		return false;
	}

	/**
	 * Record the 'common output directory', namely the one where all the output
	 * goes
	 */
	private void setCommonOutputDir() {
		IJavaProject jProject = JavaCore.create(project);
		IPath workspaceRelativeOutputPath;
		try {
			workspaceRelativeOutputPath = jProject.getOutputLocation();
		} catch (JavaModelException e) {
			commonOutputDir = project.getLocation().toFile();
            outputIsRoot = true;
			return;
		}
		if (workspaceRelativeOutputPath.segmentCount() == 1) { 
			commonOutputDir = jProject.getResource().getLocation().toFile();
			outputIsRoot = true;
			return;
		}
		IFolder out = ResourcesPlugin.getWorkspace().getRoot().getFolder(workspaceRelativeOutputPath);
		commonOutputDir = out.getLocation().toFile();
	}

	private File workspacePathToFile(IPath path) {
		if (path.segmentCount() == 1) {
			// bug 153682: getFolder fails when the path is a project
			IResource res = workspaceRoot.findMember(path);
			outputIsRoot = true;
			return res.getLocation().toFile();
		}
		IFolder out = workspaceRoot.getFolder(path);

		IPath outPath = out.getLocation();
		if (outPath != null) {
			return outPath.toFile();
		} else {
			return null;
		}
	}
	

	/**
	 * return all output directories used by this project
	 */
	public List getAllOutputLocations() {
		return allOutputFolders;
	}

	public String getInpathOutputFolder() {
		String inpathOutFolder = AspectJCorePreferences.getProjectInpathOutFolder(project);
		// assume that the folder is valid...
		// null means that the default out folder is used
		return inpathOutFolder;
	}

	public File[] getTouchedClassFiles() {
		return (File[]) touchedCUs.toArray(new File[touchedCUs.size()]);
	}

	public void resetTouchedClassFiles() {
		touchedCUs.clear();
	}

	/**
	 * If there's only one output directory return this one, otherwise return
	 * the one marked as default
	 */
	public File getDefaultOutputLocation() {
		if (!isUsingSeparateOutputFolders(jProject)) {
			return commonOutputDir;
		} else {
			return defaultOutput;
		}
	}

	public String getSourceFolderForFile(File sourceFile) {
		String sourceFilePath = sourceFile.getAbsolutePath();
		for (Iterator pathIter = allSourceFolders.entrySet().iterator(); pathIter.hasNext();) {
		    Map.Entry sourceFolderMapping = (Map.Entry) pathIter.next();
			if (sourceFilePath.startsWith((String) sourceFolderMapping.getKey())) {
				return (String) sourceFolderMapping.getValue();
			}
		}
		return null;
	}

	public void reportFileRemove(String outFileStr, int fileType) {
        for (Iterator pathIter = fileSystemPathToIContainer.entrySet().iterator(); pathIter.hasNext();) {
            Map.Entry entry = (Map.Entry) pathIter.next();
            String outFolderStr = (String)entry.getKey();
            if (outFileStr.startsWith(outFolderStr)) {
                IContainer outFolder = (IContainer) entry.getValue();
                IFile outFile = outFolder.getFile(new Path(outFileStr.substring(outFolderStr.length())));
                try {
                    outFile.refreshLocal(IResource.DEPTH_ZERO, null);
                    return;
                } catch (CoreException e) {
                }
            }
        }

	}


	public Map getInpathMap() {
		return Collections.EMPTY_MAP;
	}


	public void reportFileWrite(String outFileStr, int fileType) {
	    try {
	        outer:
	        for (Iterator pathIter = fileSystemPathToIContainer.entrySet().iterator(); pathIter.hasNext();) {
	            Map.Entry entry = (Map.Entry) pathIter.next();
	            String outFolderStr = (String)entry.getKey();
	            if (outFileStr.startsWith(outFolderStr)) {
	                IContainer outFolder = (IContainer) entry.getValue();
	                IFile outFile = outFolder.getFile(new Path(outFileStr.substring(outFolderStr.length())));
	                
	                outFile.refreshLocal(IResource.DEPTH_ZERO, null);
	                
	                if (outFile.exists()) {
	                    
	                    // if this is a resource whose source folder and out folder are the same,
	                    // do not mark as derived
	                    boolean outputIsSourceFolder = isOutFolderASourceFolder(outFolder);
	                    if (! isResourceInSourceFolder(outFile, outputIsSourceFolder)) {
	                        outFile.setDerived(true);
	                    }
	                    
	                    // only do this if output is not a source folder
	                    if (!outputIsSourceFolder) {
	                        IContainer parent = outFile.getParent();
	                        inner:
	                        while (! parent.equals(outFolder) ) {
	                            parent.setDerived(true);
	                            parent = parent.getParent();
	                            if (parent == null) {
	                                break inner;
	                            }
	                        }
	                    }
	                    break outer;
	                }
	            }
	            
	        }
	    } catch (CoreException e) {
	    }
	}

    private boolean isResourceInSourceFolder(IFile outFile,
            boolean outputIsSourceFolder) {
        return !(outFile.getFileExtension() != null && outFile.getFileExtension().equals("class"))
                && outputIsSourceFolder;
    }
    
    private boolean isOutFolderASourceFolder(IContainer outFolder) {
        return outputIsRoot || srcFolderToOutput.containsKey(outFolder.getFullPath().removeFirstSegments(1).makeRelative().toPortableString());
    }
	
	/**
	 * Return the Java project that has outputFolder as an output location, or null if it is
	 * not recognized.
	 * 
	 * This method can return null if outputFolder is not found 
	 * in any declaring project
	 * 
	 */
	protected IProject findDeclaringProject(File outputFolder) {
	    if (binFolderToProject == null) {
	        initDeclaringProjectsMap();
	    }
	    return (IProject) binFolderToProject.get(outputFolder);
	}

	
	/**
	 * the field binFolderToProject must be refreshed before each build
	 * because we are not sure if any bin folders in downstream projects 
	 * have changed.
	 * 
	 * See bug 270335
	 */
	protected void zapBinFolderToProjectMap() {
	    binFolderToProject = null;
	}

	/**
	 * Initialize the binFolderToProject map so that the map contains 
	 * java.io.File -> IProject  where the file is an output location
	 * and the project is where this output location is defined
	 */
	private void initDeclaringProjectsMap() {
	    
	    AJLog.logStart("OutputLocationManager: binary folder to declaring project map creation: " + project);
	    binFolderToProject = new HashMap();
	    IJavaProject jp = jProject;
        try {
            mapProject(jp);
        } catch (JavaModelException e) {
        }
        AJLog.logEnd(AJLog.BUILDER_CLASSPATH, "OutputLocationManager: binary folder to declaring project map creation: " + project);
    }

    private void mapProject(IJavaProject jp) throws JavaModelException {
        IClasspathEntry[] cpes = jp.getRawClasspath();
        for (int i = 0; i < cpes.length; i++) {
            if (cpes[i].isExported() || 
                    cpes[i].getEntryKind() == IClasspathEntry.CPE_SOURCE || 
                    jp == jProject) {
                handleClassPathEntry(jp, cpes[i]);
            }
        }
    }

    private void handleClassPathEntry(IJavaProject jp, IClasspathEntry cpe) throws JavaModelException {
        switch (cpe.getEntryKind()) {
            case IClasspathEntry.CPE_CONTAINER:
                IClasspathContainer container = 
                    JavaCore.getClasspathContainer(cpe.getPath(), jp);
                if (container != null && container.getKind() != IClasspathContainer.K_DEFAULT_SYSTEM) {
                    IClasspathEntry[] cpes = container.getClasspathEntries();
                    for (int i = 0; i < cpes.length; i++) {
                        handleClassPathEntry(jp, cpes[i]);
                    }
                }
                break;
            case IClasspathEntry.CPE_LIBRARY:
                File libFile = pathToFile(cpe.getPath());
                if (libFile.isDirectory()) {  // ignore jar files
                    if (libFile != null && !binFolderToProject.containsKey(libFile)) {
                        binFolderToProject.put(libFile, jp.getProject());
                    }
                }
                break;
            case IClasspathEntry.CPE_PROJECT:
                IJavaProject jpClasspath = pathToProject(cpe.getPath());
                if (jpClasspath != null) {
                    mapProject(jpClasspath);
                }
                break;
                
            case IClasspathEntry.CPE_SOURCE:
                File outFile = pathToFile(cpe.getOutputLocation() == null ? jp.getOutputLocation() : cpe.getOutputLocation());
                if (outFile != null && ! binFolderToProject.containsKey(outFile)) {
                    binFolderToProject.put(outFile, jp.getProject());
                }
                break;
            case IClasspathEntry.CPE_VARIABLE:
                IClasspathEntry cpeResolved = JavaCore.getResolvedClasspathEntry(cpe);
                if (cpeResolved != null) {
                    handleClassPathEntry(jp, cpeResolved);
                }
                break;
        }
    }
    
    private IJavaProject pathToProject(IPath path) {
        if (path != null && path.segmentCount() > 0) {
            IProject p = ResourcesPlugin.getWorkspace().getRoot().getProject(path.segments()[0]);
            return JavaCore.create(p);
        } else {
            return null;
        }
    }

    private File pathToFile(IPath path) {
        IPath locPath = ResourcesPlugin.getWorkspace().getRoot().getFolder(path).getLocation();
        File f;
        if (locPath != null) {
            f = new File(locPath.toOSString());
        } else {
            f = new File(path.toOSString());
        }
        return f;
    }

    /**
	 * Aim of this callback from the compiler is to ask Eclipse if it knows which project has the 
	 * supplied directory as an output folder, and if that can be determined then look at the 
	 * last structural build time of that project and any structurally changed types since that
	 * build time.  If it doesn't look like anything has changed since the supplied buildtime then
	 * we assume that means nothing changed in the directory and so do not need to check the time
	 * stamp of each file within it.
	 * 
	 * This method does nothing more than a rudimentary analysis - if there are changes then it does
	 * not currently attempt to determine if they are interesting (ie. whether they are changes to 
	 * types that the compiler asking the question depends upon).
	 */
	public int discoverChangesSince(File dir, long buildtime) {
		IProject project = findDeclaringProject(dir);
		// Andys hack to find the project
//		if (project == null) {
//			IProject[] ps;
//			try {
//				ps = this.project.getReferencedProjects();
//			if (ps!=null) {
//			for (int i=0;i<ps.length;i++) {
//				if (ps[i].getName().equals("org.aspectj.ajdt.core")) {
//					project = ps[i];
//				}
//			}
//			}
//			} catch (CoreException e) {
//				e.printStackTrace();
//			}
//		}
		try {
			if (project!=null) {
	            Object s = JavaModelManager.getJavaModelManager().getLastBuiltState(project, null);
	            if (s != null && s instanceof State) {
	                State state = (State) s;
	                long dependeeTime = getLastStructuralBuildTime(state);
	                if (dependeeTime < buildtime) {
	                	StringSet changes = getStructurallyChangedTypes(state);
	                	// this test isn't quite right... but it basically works
	                    if (changes==null || changes.elementSize==0) {
	                    	return 1; // no changes at all (doesnt determine whether they are of interest)
	                    }
	                }
	            }
			}
		} catch (Exception e) {
		}
		return 0; // DONTKNOW - this will cause the caller to do the .class modtime tests
	}

	
    private static long getLastStructuralBuildTime(State state)
            throws Exception {
        if (lastStructuralBuildTimeField == null) {
            lastStructuralBuildTimeField = State.class.getDeclaredField("lastStructuralBuildTime");
            lastStructuralBuildTimeField.setAccessible(true);
        }
        return lastStructuralBuildTimeField.getLong(state);
    }

    private static StringSet getStructurallyChangedTypes(State state)
            throws Exception {
        if (structurallyChangedTypesField == null) {
            structurallyChangedTypesField = State.class.getDeclaredField("structurallyChangedTypes");
            structurallyChangedTypesField.setAccessible(true);
        }
        return (StringSet)structurallyChangedTypesField.get(state);
    }
	
	// Cached for performance reasons
	private static java.lang.reflect.Field lastStructuralBuildTimeField = null;
	private static java.lang.reflect.Field structurallyChangedTypesField = null;
	
}
