/********************************************************************
 * Copyright (c) 2006 Contributors. All rights reserved. 
 * This program and the accompanying materials are made available 
 * under the terms of the Eclipse Public License v1.0 
 * which accompanies this distribution and is available at 
 * http://eclipse.org/legal/epl-v10.html 
 *  
 * Contributors: IBM Corporation - initial API and implementation 
 * 				 Helen Hawkins   - initial version
 *******************************************************************/
package org.eclipse.ajdt.internal.ui.ajde;

import org.eclipse.ajdt.core.AJLog;
import org.eclipse.ajdt.core.AspectJPlugin;
import org.eclipse.ajdt.core.TimerLogEvent;
import org.eclipse.ajdt.core.builder.IAJCompilerMonitor;
import org.eclipse.ajdt.internal.ui.text.UIMessages;
import org.eclipse.ajdt.ui.AspectJUIPlugin;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.Path;
import org.eclipse.osgi.util.NLS;

public class UIBuildProgressMonitor implements IAJCompilerMonitor {
	
	
	public UIBuildProgressMonitor(IProject project) {
		this.project = project;
	}
	
	/**
     * Monitor progress against Ajde max
     */
    private int currentAjdeProgress;
    
	/**
	 * Indicates whether the build is for one particular AspectJ project only
	 * (i.e. caused by the build action button being clicked) or else is part of
	 * a build of all projects in workspace (i.e. caused by a rebuild-all
	 * action).
	 */
	public static boolean isLocalBuild = false;
	
    /**
     * Ajde informs us that a compilation has finished. We may under some
     * circumstances get multiple calls to finish. This method is marked
     * synchronized to let one finish finish before another finish gets in!
     */
    public synchronized void finish(boolean wasFullBuild) {
        AJLog.log(AJLog.COMPILER,"AJDE Callback: finish. Was full build: "+wasFullBuild); //$NON-NLS-1$
        // AMC - moved this next monitor var set outside of thread -
        // this status change must be instantly visible
        compilationInProgress = false;
        ((UIMessageHandler)AspectJPlugin.getDefault().getCompilerFactory()
				.getCompilerForProject(project).getMessageHandler()).setLastBuildType(wasFullBuild);

        if (AspectJUIPlugin.getDefault().getDisplay().isDisposed())
        	AJLog.log("Not finishing with bpm, display is disposed!"); //$NON-NLS-1$
        else 
            AspectJUIPlugin.getDefault().getDisplay().asyncExec(new Runnable() {
                public void run() {

                    if (monitor != null) {
                        // ask the project to perform a refresh to pick up the
                        // newly generated classfiles - bug 30462
                        // It think during the processing in refreshOutputDir -
                        // monitor can go null...
                        //                    if (monitor!=null) monitor.setTaskName("");
                        if (monitor != null)
                            monitor.worked(AspectJUIPlugin.PROGRESS_MONITOR_MAX);
                        if (monitor != null)
                            monitor.done();
                        monitor = null;
                    }
                }
            });
    }


	public boolean isCancelRequested() {
		buildWasCancelled = (monitor != null ? monitor.isCanceled() : false);
		return buildWasCancelled;
	}

	public void setProgress(double percentDone) {
		if (percentDone >= currentAjdeProgress) {
            incrementProgressBarVal("setProgress() delegating to "); //$NON-NLS-1$			
		}
	}

    private void incrementProgressBarVal(String caller) {
        currentAjdeProgress++;
        // Bug 22258 - Reworked internals of run() method, it used to be
        // responsible for
        // increasing currentProgress but I've changed it since if anything
        // causes the execution
        // of the thread to hang then currentProgress doesn't increase and
        // setProgressBarVal()
        // just loops calling this routine.
        if (monitor != null) {
            AspectJUIPlugin.getDefault().getDisplay().asyncExec(new Runnable() {
                public void run() {
                    if (monitor != null)
                        monitor.worked(1);
                }
            });
        }
    }

    
    /**
	 * Ajde wishes to display information about the progress of the compilation.
	 */
    public void setProgressText(String text) {
        if (!reportedCompiledMessages && text.startsWith("compiled: ")) { //$NON-NLS-1$
            reportedCompiledMessages = true;
            AJLog.logEnd(AJLog.COMPILER, TimerLogEvent.FIRST_COMPILED);
        }
        if (!reportedWovenMessages && text.startsWith("woven ")) { //$NON-NLS-1$
            reportedWovenMessages = true;
            AJLog.logEnd(AJLog.COMPILER, TimerLogEvent.FIRST_WOVEN);
        }

        // Three messages are caught here:
        //   compiled:
        //   woven class
        //   woven aspect
        // Each indicates that something has been processed and so will be
        // reported on later.  For this reason we remember that it has been
        // processed so that we can remove markers for it before adding
        // any new ones.  
        // FIXME ASC18022005 this isnt the nicest way to do this, it would be better
        // to ask the state what changed...
        if (text.startsWith("compiled: ") || text.startsWith("woven ")) { //$NON-NLS-1$ //$NON-NLS-2$
            // If a project contains a 'srclink' and that link is to a directory
            // that isn't defined in another eclipse project, then we may get 
        	// resource paths here that cannot be found in eclipse. So the 
        	// entry added to affectedResources will be null. However, as
            // this code is only used to ensure we tidy up markers, that does
            // not matter - if it does not exist, it cannot have 
        	// outstanding markers.
            IPath resourcePath = null;
            if (text.startsWith("compiled: ")) { //$NON-NLS-1$
            	resourcePath = new Path(text.substring(10));
            } else {
            	// woven messages look like this: 'woven class XXXX (from c:\fullpathhere)'
            	int fromLoc = text.indexOf("from "); //$NON-NLS-1$
            	int endLoc = text.lastIndexOf(")"); //$NON-NLS-1$
            	if (fromLoc!=-1 && endLoc>fromLoc) { // guards guards
            		resourcePath = new Path(text.substring(fromLoc+5,endLoc));
            	}
            }
            IWorkspaceRoot workspaceRoot = AspectJPlugin.getWorkspace()
                    .getRoot();

            if (linked) {
                IFile[] files = workspaceRoot
                        .findFilesForLocation(resourcePath);
                for (int i = 0; i < files.length; i++) {
                	((UIMessageHandler)AspectJPlugin.getDefault().getCompilerFactory()
            				.getCompilerForProject(project).getMessageHandler()).addAffectedResource(files[i]);
                }
            } else {
                IFile file = workspaceRoot.getFileForLocation(resourcePath);
                if (file == null) {
                	AJLog.log(AJLog.COMPILER,"Processing progress message: Can't find eclipse resource for file with path " //$NON-NLS-1$
                                    + text);
                } else {
                	((UIMessageHandler)AspectJPlugin.getDefault().getCompilerFactory()
            				.getCompilerForProject(project).getMessageHandler()).addAffectedResource(file);
                }
            }
        }

        final String amendedText = removePrefix(text);
        AJLog.log(AJLog.COMPILER_PROGRESS,"AJC: " + text); //$NON-NLS-1$ //$NON-NLS-2$
        if (monitor != null) {
            AspectJUIPlugin.getDefault().getDisplay().asyncExec(new Runnable() {
                public void run() {
                    if (monitor != null)
                        monitor.subTask(amendedText);
                }
            });
        }

    }

    /**
     * A compiler message has been emitted, which potentially includes the fully
     * qualifed path of a resource. Chop it down to just the project-relative
     * portion.
     */
    private String removePrefix(String msg) {
        String ret = msg;
        //IProject p = AspectJPlugin.getDefault().getCurrentProject();
        IProject p = project;
        
        //Bug 150936                   
        if (p == null || p.getLocation() == null) {
        	AJLog.log("Could not find project location, " + p); //$NON-NLS-1$
        	return ret;
        };
        	
        String projectLocation = p.getLocation().toOSString() + "\\"; //$NON-NLS-1$
        
        if (msg.indexOf(projectLocation) != -1) {
            ret = msg.substring(0, msg.indexOf(projectLocation))
                    + msg.substring(msg.indexOf(projectLocation)
                            + projectLocation.length());
        } else {
            projectLocation = projectLocation.replace('\\', '/');
            if (msg.indexOf(projectLocation) != -1) {
                ret = msg.substring(0, msg.indexOf(projectLocation))
                        + msg.substring(msg.indexOf(projectLocation)
                                + projectLocation.length());
            }
        }

        // special cases...
        // this message is too long to be meaningful
        if (ret.startsWith("might need to weave")) { //$NON-NLS-1$
            ret = UIMessages.CompilerMonitor_weaving;
        }
        // we always get this next one, and it seems to be nonsense
        if (ret.startsWith("directory classpath entry does not exist: null")) { //$NON-NLS-1$
            ret = ""; //$NON-NLS-1$
        }

        // chop (from x\y\z\a\b\C.java) to just (C.java)
        if (ret.startsWith("woven") && ret.indexOf("(from") != -1) { //$NON-NLS-1$ //$NON-NLS-2$
            int loc = ret.indexOf("(from"); //$NON-NLS-1$
            if (loc != -1) {
                String fromPiece = ret.substring(loc);
                int lastSlash = fromPiece.lastIndexOf("/"); //$NON-NLS-1$
                if (lastSlash == -1)
                    lastSlash = fromPiece.lastIndexOf("\\"); //$NON-NLS-1$
                if (lastSlash != -1) {
                    fromPiece = fromPiece.substring(lastSlash + 1);
                    ret = ret.substring(0, loc) + " (" + fromPiece; //$NON-NLS-1$
                } else {
                    int space = fromPiece.indexOf(" "); //$NON-NLS-1$
                    if (space != -1)
                        ret = ret.substring(0, loc) + " (" //$NON-NLS-1$
                                + fromPiece.substring(space + 1);
                }
            }
        }
        return ret;
    }
    
	public void begin() {
        currentAjdeProgress = 0;
        if (monitor != null) {
            AspectJUIPlugin.getDefault().getDisplay().asyncExec(new Runnable() {
                public void run() {
                    if (monitor != null) {
                        if (isLocalBuild) {
                        	monitor.setTaskName(NLS.bind(UIMessages.CompilerMonitor_building_Project,project.getName()));
                        }
                    }// end if
                }// end run method
            });
        }	}

	// --------------------- IAJCompilerMonitor impl -------------

    /**
     * Project being built
     */
    private IProject project;
    
    /**
     * Determine if a linked folder exists in the buildList
     */
    private boolean linked;
    
    /**
     * Which Eclipse IProgressMonitor should this CoreCompilerMonitor keep updating?
     */
    private IProgressMonitor monitor = null;
    
    /**
     * Is this CoreCompilerMonitor instance currently 'in use' ?
     */
    private boolean compilationInProgress = false;

    private boolean reportedCompiledMessages;

    private boolean reportedWovenMessages;
    
    private boolean buildWasCancelled = false;

	
//    /**
//     * Has the most recent compile finished?
//     */
//    public boolean finished() {
//        return !compilationInProgress;
//    }

    /**
     * Called from the Builder to set up the compiler for a new build.
     */
//    public void prepare(IProject project, List buildList,
//            IProgressMonitor eclipseMonitor) {
//    	this.project = project;
//    	
//        //check if the folder contains linked resources
//        linked = false;
//        IResource[] res = null;
//
//        try {
//            res = project.members();
//        } catch (CoreException e) {
//            //should not occur but for some reason one of the following it
//            // true:
//            //1. This resource does not exist.
//            //2. includePhantoms is false and resource does not exist.
//            //3. includePhantoms is false and this resource is a project that
//            // is not open.
//        }
//
//        for (int i = 0; (linked == false) && (i < res.length); i++) {
//            if (res[i].getType() == IResource.FOLDER) {
//                linked = res[i].isLinked();
//            }
//        }
//
//        monitor = eclipseMonitor;
//        if (monitor != null) {
//            monitor.beginTask(UIMessages.ajCompilation,
//                    AspectJUIPlugin.PROGRESS_MONITOR_MAX);
//        }
//
//        AJLog.logStart(TimerLogEvent.FIRST_COMPILED);
//        AJLog.logStart(TimerLogEvent.FIRST_WOVEN);
//        reportedCompiledMessages = false;
//        reportedWovenMessages = false;
//        compilationInProgress = true;
//
//    }
    public void prepare(IProgressMonitor eclipseMonitor) {
    	buildWasCancelled = false;
        //check if the folder contains linked resources
        linked = false;
        IResource[] res = null;

        try {
            res = project.members();
        } catch (CoreException e) {
            //should not occur but for some reason one of the following it
            // true:
            //1. This resource does not exist.
            //2. includePhantoms is false and resource does not exist.
            //3. includePhantoms is false and this resource is a project that
            // is not open.
        }

        for (int i = 0; (linked == false) && (i < res.length); i++) {
            if (res[i].getType() == IResource.FOLDER) {
                linked = res[i].isLinked();
            }
        }

        monitor = eclipseMonitor;
        if (monitor != null) {
            monitor.beginTask(UIMessages.ajCompilation,
                    AspectJUIPlugin.PROGRESS_MONITOR_MAX);
        }

        AJLog.logStart(TimerLogEvent.FIRST_COMPILED);
        AJLog.logStart(TimerLogEvent.FIRST_WOVEN);
        reportedCompiledMessages = false;
        reportedWovenMessages = false;
        compilationInProgress = true;

    }

	public boolean buildWasCancelled() {
		return buildWasCancelled;
	}

	
}
