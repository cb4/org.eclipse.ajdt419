/**********************************************************************
 Copyright (c) 2002, 2006 IBM Corporation and others.
 All rights reserved. This program and the accompanying materials
 are made available under the terms of the Eclipse Public License v1.0
 which accompanies this distribution, and is available at
 http://www.eclipse.org/legal/epl-v10.html
 Contributors:
 Adrian Colyer, Andy Clement, Tracy Gardner - initial version
 Julie Waterhouse - added code to popup AJDTPrefConfigWizard - August 3, 2003
 Julie Waterhouse - removed method calls for new aspect and AspectJ project.  
 This functionality has moved to the plugin.xml. - August 13, 2003.
 Helen Hawkins - updated for new ajde interface (bug 148190)
 ...
 **********************************************************************/
package org.eclipse.ajdt.internal.utils;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.List;

import org.aspectj.asm.IProgramElement;
import org.aspectj.bridge.IMessage;
import org.aspectj.weaver.ShadowMunger;
import org.eclipse.ajdt.core.AJLog;
import org.eclipse.ajdt.core.AspectJPlugin;
import org.eclipse.ajdt.core.CoreUtils;
import org.eclipse.ajdt.core.builder.AJBuilder;
import org.eclipse.ajdt.core.javaelements.AJCompilationUnitManager;
import org.eclipse.ajdt.internal.javamodel.AJCompilationUnitUtils;
import org.eclipse.ajdt.internal.ui.ajde.AJDTErrorHandler;
import org.eclipse.ajdt.internal.ui.dialogs.MessageDialogWithToggle;
import org.eclipse.ajdt.internal.ui.lazystart.Utils;
import org.eclipse.ajdt.internal.ui.markers.MarkerUpdating;
import org.eclipse.ajdt.internal.ui.preferences.AspectJPreferences;
import org.eclipse.ajdt.internal.ui.text.UIMessages;
import org.eclipse.ajdt.pde.internal.core.AJDTWorkspaceModelManager;
import org.eclipse.ajdt.ui.AspectJUIPlugin;
import org.eclipse.ajdt.ui.IAJModelMarker;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.Path;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IJavaModelMarker;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IPackageFragment;
import org.eclipse.jdt.core.IPackageFragmentRoot;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.internal.ui.packageview.PackageExplorerPart;
import org.eclipse.jdt.ui.JavaElementImageDescriptor;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.resource.ImageDescriptor;
import org.eclipse.pde.core.plugin.IPluginImport;
import org.eclipse.pde.core.plugin.IPluginModel;
import org.eclipse.pde.internal.core.natures.PDE;
import org.eclipse.pde.internal.ui.IPDEUIConstants;
import org.eclipse.pde.internal.ui.editor.plugin.DependenciesPage;
import org.eclipse.pde.internal.ui.editor.plugin.ManifestEditor;
import org.eclipse.swt.graphics.Point;
import org.eclipse.ui.IEditorReference;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.actions.WorkspaceModifyOperation;
import org.eclipse.ui.progress.UIJob;

/**
 * A utility class to capture all those little functions that keep cropping up.
 * Converting IPaths to fully-qualified native filenames being one of the most
 * common!!
 */
public class AJDTUtils {

	// icon sizes
	private static final Point SMALL_SIZE = new Point(16, 16);

	private static final Point BIG_SIZE = new Point(22, 16);

	private static final int SMALL_ICONS_MASK = 0x020;

	private static Hashtable imageDescriptorCache = new Hashtable();

	private static Job refreshJob;

	private static int previousExecutionTime;
	
	/**
	 * Return the fully-qualifed native OS path of the workspace. e.g.
	 * D:\eclipse\workspace
	 */
	public static String getWorkspacePath() {
		return AspectJPlugin.getWorkspace().getRoot().getLocation()
				.toOSString();
	}

	/**
	 * Return the fully-qualified native OS path of a project resource
	 */
	public static String getResourcePath(IResource resource) {
		return resource.getLocation().toOSString();
	}

	/**
	 * decorate an icon given a set of adornment flags. Constants for flag
	 * values are defined in JavaElementImageDescriptor
	 */
	public static ImageDescriptor decorate(ImageDescriptor base, int decorations) {

		Point size = useSmallSize(decorations) ? SMALL_SIZE : BIG_SIZE;
		// Check the image descriptor cache
		String key = new String(base.toString() + ":::" + decorations + ":::" //$NON-NLS-1$ //$NON-NLS-2$
				+ size.toString());
		// Example key is
		// "URLImageDescriptor(platform:/plugin/org.aspectj.ajde_1.1.0/icons/structure/file-lst.gif):::0:::Point
		// {22, 16}"
		// or
		// "URLImageDescriptor(platform:/plugin/org.eclipse.ui_2.0.2/icons/full/obj16/fldr_obj.gif):::0:::Point
		// {22, 16}"
		if (imageDescriptorCache.get(key) != null) {
			return (ImageDescriptor) imageDescriptorCache.get(key);
		}
		ImageDescriptor imageDescriptor = new JavaElementImageDescriptor(
				base, decorations, size);
		imageDescriptorCache.put(key, imageDescriptor);
		//System.err.println("IDCache mis for "+key);
		return imageDescriptor;
	}

	/**
	 * determine which icon size to use for a given set of decorations
	 */
	private static boolean useSmallSize(int flags) {
		return (flags & SMALL_ICONS_MASK) != 0;
	}

	/**
	 * Adds the AspectJ Nature to the project
	 * 
	 * @param project
	 * @param prompt whether to prompt the user, or go with the defaults
	 * @throws CoreException
	 */
	public static void addAspectJNature(final IProject project, final boolean prompt)
			throws CoreException {
		// wrap up the operation so that an autobuild is not triggered in the
		// middle of the conversion
		WorkspaceModifyOperation op = new WorkspaceModifyOperation() {
			protected void execute(IProgressMonitor monitor)
					throws CoreException {
				internal_addAspectJNature(project, prompt);
			}
		};
		try {
			op.run(null);
		} catch (InvocationTargetException ex) {
		} catch (InterruptedException e) {
		}
	}
	
	private static void internal_addAspectJNature(IProject project, boolean prompt) throws CoreException {
		checkOutputFoldersForAJFiles(project);
		
		// add the AspectJ Nature
		IProjectDescription description = project.getDescription();
		String[] prevNatures = description.getNatureIds();
		String[] newNatures = new String[prevNatures.length + 1];
		System.arraycopy(prevNatures, 0, newNatures, 1, prevNatures.length);
		newNatures[0] = Utils.ID_NATURE;
		description.setNatureIds(newNatures);
		project.setDescription(description, null);

		// Bugzilla 62625
		// Bugzilla 93532 - just add plugin dependency if there is a plugin.xml file
		// Bugzilla 137922 - also consider bundles without a plugin.xml
		if (project.hasNature(PDE.PLUGIN_NATURE) 
		        && (hasPluginManifest(project)
		        		|| hasBundleManifest(project))) {
			// Dealing with a plugin project. In that case the
			// aspectjrt.jar should be added to the classpath container
			// that lists jars imported from dependent plugins. In order
			// to do this, should add a dependency on the plugin
			// org.aspectj.runtime to the current plugin project.

			// Bugzilla 72007
			// Checks if the plugin already has the plugin dependency
			// before adding it, this avoids duplication
			if (!hasAJPluginDependency(project)) {
			    
			    // Bugzilla 97080
			    // Must close manifest editor after we are done with it 
			    // if it is not already open
			    boolean manifestEditorAlreadyOpen = isManifestEditorOpen(project);
			    
			    ManifestEditor manEd = getAndPrepareToChangePDEModel(project);
				addAJPluginDependency(manEd, prompt);
                if (!manifestEditorAlreadyOpen) {
                    manEd.close(true);
                }
			}
		} else {
			// A Java project that is not a plugin project. Just add
			// the aspectjrt.jar to the build path.
			AspectJUIPlugin.addAjrtToBuildPath(project);
		}

		// bug 129553: include any excluded .aj files
		includeAJfiles(project,prompt);
		
		//crete compilation units for .aj files
		AJCompilationUnitManager.INSTANCE.initCompilationUnits(project);

		if (prompt) {
			checkMyEclipseNature(project);
		}
		
		refreshPackageExplorer();
	}
	
	/*
	 * checks to see if the manifest editor for 
	 * the given project is open
	 */
	private static boolean isManifestEditorOpen(IProject project) {
        // Must have already been validated as a PDE project
        // to get to this method. Now get the id of the plugin
        // being developed in current project.
        String pluginId = (new AJDTWorkspaceModelManager().getWorkspacePluginModel(project))
                                .getPluginBase().getId();


        // cycle through all open editors looking for the manifest.
        IEditorReference[] eRefs = PlatformUI.getWorkbench()
                .getActiveWorkbenchWindow().getActivePage()
                .getEditorReferences();
        for (int i = 0; i < eRefs.length; i++) {
            IEditorReference er = eRefs[i];
            if (er.getId().equals(IPDEUIConstants.MANIFEST_EDITOR_ID)
                    && er.getPartName().equals(pluginId)) {
                return true;
            }
        }// end for

        return false;
    }

    private static boolean hasBundleManifest(IProject project) {
		return project.exists(new Path("META-INF/MANIFEST.MF")); //$NON-NLS-1$
	}
	
	private static boolean hasPluginManifest(IProject project) {
		return project.exists(new Path("plugin.xml")); //$NON-NLS-1$
	}

	/**
	 * Bug 98911: Delete any .aj files from the output folder, if the output
	 * folder and the source folder are not the same.
	 */
	private static void checkOutputFoldersForAJFiles(IProject project)
			throws CoreException {
		IJavaProject jp = JavaCore.create(project);
		if (jp == null) {
			return;
		}
		IPath defaultOutputLocation = jp.getOutputLocation();
		if(defaultOutputLocation.equals(project.getFullPath())) {
			return;
		}
		boolean defaultOutputLocationIsSrcFolder = false;
		List extraOutputLocations = new ArrayList();
		List srcFolders = new ArrayList();
		IClasspathEntry[] cpe = jp.getRawClasspath();
		for (int i = 0; i < cpe.length; i++) {
			if (cpe[i].getEntryKind() == IClasspathEntry.CPE_SOURCE) {
				srcFolders.add(cpe[i]);
				IPath output = cpe[i].getOutputLocation();
				if(output != null) {
					extraOutputLocations.add(output);
				}
			}
		}
		for (Iterator iter = srcFolders.iterator(); iter.hasNext();) {
			IClasspathEntry entry = (IClasspathEntry) iter.next();
			IPath path = entry.getPath();
			if(path.equals(defaultOutputLocation)) {
				defaultOutputLocationIsSrcFolder = true;
			}
			for (Iterator iterator = extraOutputLocations.iterator(); iterator.hasNext();) {
				IPath outputPath = (IPath) iterator.next();
				if(outputPath.equals(path)) {
					iterator.remove();
				}				
			}
		}
		boolean ajFilesFound = false; 
		if(!defaultOutputLocationIsSrcFolder) {
			IFolder folder = project.getWorkspace().getRoot().getFolder(defaultOutputLocation);
			ajFilesFound = containsAJFiles(folder);
		}
		if(!ajFilesFound && extraOutputLocations.size() > 0) {
			for (Iterator iter = extraOutputLocations.iterator(); iter.hasNext();) {
				IPath outputPath = (IPath) iter.next();

				IFolder folder = project.getWorkspace().getRoot().getFolder(outputPath);
				ajFilesFound = ajFilesFound || containsAJFiles(folder);
			}
		}	
		if(ajFilesFound) {
			IWorkbenchWindow window = AspectJUIPlugin.getDefault()
				.getWorkbench().getActiveWorkbenchWindow();
			boolean remove = MessageDialog
				.openQuestion(
					window.getShell(),
					UIMessages.AJFiles_title,
					UIMessages.AJFiles_message);
			if (remove) {
				if(!defaultOutputLocationIsSrcFolder) {
					AJBuilder.cleanAJFilesFromOutputFolder(defaultOutputLocation);
				}
				for (Iterator iter = extraOutputLocations.iterator(); iter.hasNext();) {
					IPath extraLocationPath = (IPath) iter.next();
					AJBuilder.cleanAJFilesFromOutputFolder(extraLocationPath);
				}
			}
		}
	}

	/**
	 * Recursive method that checks a resource and all its members for .aj files
	 * @param resource - the resource to check
	 * @return
	 */
	private static boolean containsAJFiles(IResource resource) {
		if(resource instanceof IFile && resource.getName().endsWith(".aj")) { //$NON-NLS-1$
			return true;
		} else if (resource instanceof IFolder && ((IFolder)resource).exists()) {
			IResource[] members;
			try {
				members = ((IFolder)resource).members();			
				for (int i = 0; i < members.length; i++) {
					if(containsAJFiles(members[i])) {
						return true;
					}
				}
			} catch (CoreException e) {}
		}
		return false;
	}

	/**
	 * (Bug 71540) Detect if user is working with MyEclipse plugin and if yes,
	 * pop up a message box that tells to add aspectjrt.jar to the classpath of
	 * application server where the project gets deployed to.
	 * 
	 * @param The
	 *            project to be checked.
	 */
	public static void checkMyEclipseNature(IProject project) {
		try {
			// check project nature
			if (project.hasNature("com.genuitec.eclipse.j2eedt.core.webnature") //$NON-NLS-1$
					|| project
							.hasNature("com.genuitec.eclipse.j2eedt.core.ejbnature")) { //$NON-NLS-1$
				//display message only once per eclipse startup
				if (!myEclipseMessageDisplayed) {
					myEclipseMessageDisplayed = true;

					IWorkbenchWindow window = AspectJUIPlugin.getDefault()
							.getWorkbench().getActiveWorkbenchWindow();
					MessageDialog
							.openInformation(
									window.getShell(),
									UIMessages.myEclipse_natureDetected_title,
									UIMessages.myEclipse_natureDetected_message);
				}
			}
		} catch (CoreException e) {
		}
	}

	//flag used by public static void checkMyEclipseNature(IProject project)
	private static boolean myEclipseMessageDisplayed = false;

	/**
	 * When AspectJ nature is removed, need to remove markers on referencing
	 * projects which say that the current project needs to be built
	 */
	private static void removeMarkerOnReferencingProjects(IProject project) {
		try {
			String errorMessage = UIMessages.AJDTUtils_project_cannot_be_rebuilt
					+ project.getName()
					+ UIMessages.AJDTUtils_cleaning_recommended;

			IProject[] refProjects = project.getReferencingProjects();
			// only get the class folder depending projects here
			IProject[] classFolderReferences = (IProject[]) CoreUtils.getDependingProjects(
					project).get(0);
			IProject[] referencingProjects = new IProject[refProjects.length
					+ classFolderReferences.length];
			for (int i = 0; i < refProjects.length; i++) {
				referencingProjects[i] = refProjects[i];
			}
			for (int i = 0; i < classFolderReferences.length; i++) {
				referencingProjects[i + refProjects.length] = classFolderReferences[i];
			}

			for (int i = 0; i < referencingProjects.length; i++) {
				IProject referencingProject = referencingProjects[i];
				IMarker[] problemMarkers = referencingProject.findMarkers(
						IMarker.PROBLEM, false, IResource.DEPTH_INFINITE);
				if (problemMarkers.length > 0) {
					for (int j = 0; j < problemMarkers.length; j++) {
						IMarker marker = problemMarkers[j];
						int markerSeverity = marker.getAttribute(
								IMarker.SEVERITY, -1);
						String markerMessage = marker.getAttribute(
								IMarker.MESSAGE, UIMessages.AJDTUtils_no_message);

						if (markerSeverity == IMarker.SEVERITY_ERROR
								&& markerMessage.equals(errorMessage)) {
							marker.delete();
						}
					}
				}
			}
		} catch (CoreException e) {
		}
	}

	/**
	 * @param manEd the manifest editor
	 * @param prompt
	 */
	private static void addAJPluginDependency(ManifestEditor manEd, boolean prompt) {
		IWorkbenchWindow window = AspectJUIPlugin.getDefault().getWorkbench()
				.getActiveWorkbenchWindow();

		boolean autoImport = false;
		if (!prompt || (AspectJPreferences.askPDEAutoImport() && confirmPDEAutoAddImport(window))
				|| (AspectJPreferences.doPDEAutoImport())) {
			autoImport = true;
		}

		if (autoImport) {
			importRuntimePlugin(manEd);
		} else {
			MessageDialog
					.openWarning(
							window.getShell(),
							UIMessages.NoAutoPluginImportDialog_title,
							UIMessages.NoAutoPluginImportDialog_message);
		}
	}

	/**
	 * @param manEd
	 */
	public static void importRuntimePlugin(ManifestEditor manEd) {
		if (manEd != null) {
			IPluginModel model = (IPluginModel) manEd.getAggregateModel();
			try {
				addImportToPDEModel(model, AspectJPlugin.RUNTIME_PLUGIN_ID);
				manEd.doSave(new NullProgressMonitor());

				// Forced build necessary here. When the project has the new
				// nature given to it a build occurs and - in the scenario
				// where the user is contemplating the "automatically add
				// dependency for you ?" dialog - a build error will occur
				// because the runtime jar cannot be located. If they agree
				// to the automatic dependency import then this build should
				// remove that compile error from their problems view.
				// The above scenario will not occur in the future if the
				// user tells the dialog not to ask again.
//				project.build(IncrementalProjectBuilder.FULL_BUILD,
//						AspectJPlugin.ID_BUILDER, null, null);

			} catch (CoreException e) {
			}
		}// end if we got a reference to the manifest editor
		else {
			MessageDialog
					.openError(
							AspectJUIPlugin.getDefault().getWorkbench()
									.getActiveWorkbenchWindow().getShell(),
									UIMessages.AutoPluginImportDialog_noEditor_title,
									UIMessages.AutoPluginImportDialog_noEditor_message);
		}
	}

	/**
	 * @param model
	 * @param importId
	 * @throws CoreException
	 */
	private static void addImportToPDEModel(IPluginModel model, String importId)
			throws CoreException {
		IPluginImport importNode = model.getPluginFactory().createImport();
		importNode.setId(importId);
		model.getPluginBase().add(importNode);
		IFile manifestFile = (IFile) model.getUnderlyingResource();
		manifestFile.refreshLocal(IResource.DEPTH_INFINITE,
				new NullProgressMonitor());
	}

	/**
	 * Returns the manifest editor if it is open in the workspace. Note: You
	 * should switch to the PDE dependency management page before changing the
	 * dependencies to avoid update inconsistencies across the pages. To do this
	 * use the AJDTUtils.prepareToChangePDEModel(IProject) method.
	 * 
	 * @param project
	 * @return
	 */
	public static ManifestEditor getPDEManifestEditor(IProject project) {
		// Must have already been validated as a PDE project
		// to get to this method. Now get the id of the plugin
		// being developed in current project.
		String pluginId = (new AJDTWorkspaceModelManager().getWorkspacePluginModel(project))
								.getPluginBase().getId();

		// Attempt to get hold of the open manifest editor
		// for the current project.
		ManifestEditor manEd = null;

		IEditorReference[] eRefs = PlatformUI.getWorkbench()
				.getActiveWorkbenchWindow().getActivePage()
				.getEditorReferences();
		for (int i = 0; i < eRefs.length; i++) {
			IEditorReference er = eRefs[i];
			if (er.getId().equals(IPDEUIConstants.MANIFEST_EDITOR_ID)
					&& er.getPartName().equals(pluginId)) {
				IEditorReference manEdRef = er;
				manEd = (ManifestEditor) manEdRef.getPart(true);
				break;
			}
		}// end for

		return manEd;
	}

	/**
	 * It is necessary to call this method before updating the pde model
	 * otherwise the changes may not be consistant across the pages.
	 */
	public static ManifestEditor getAndPrepareToChangePDEModel(IProject project) {
		// Must have already been validated as a PDE project
		// to get to this method. Now get the id of the plugin
		// being developed in current project.

		String pluginId = (new AJDTWorkspaceModelManager().getWorkspacePluginModel(project))
								.getPluginBase().getId();

		// Open the manifest editor if it is not already open.
		ManifestEditor manEd = (ManifestEditor) ManifestEditor.openPluginEditor(pluginId);
//		ManifestEditor manEd = getPDEManifestEditor(project);

		// IMPORTANT
		// Necessary to force the active page to be the dependency management
		// page. If this is not done then there is a chance that the model
		// will not be updated consistently across the pages.
		if (manEd != null) {
			manEd.setActivePage(DependenciesPage.PAGE_ID);
		}
		return manEd;
	}

	
	/**
	 * Removes the AspectJ Nature from an existing AspectJ project.
	 * 
	 * @param project
	 * @throws CoreException
	 */
	public static void removeAspectJNature(IProject project)
			throws CoreException {

		MarkerUpdating.deleteAllMarkers(project);
		
		//remove compilation units for .aj files
		//(the way it is currently implemented, this must happen before nature
		// gets removed)
		AJCompilationUnitUtils.removeCUsfromJavaModelAndCloseEditors(project);

		/* Clear any warnings and errors from the Tasks window BUG-FIX#40344 */
		AJDTUtils.clearProjectMarkers(project, true);

		// bug 129553: exclude .aj files so that the java builder doesnt try to
		// compile them
		excludeAJfiles(project);
		
		// remove the AspectJ Nature
		IProjectDescription description = project.getDescription();
		String[] prevNatures = description.getNatureIds();
		String[] newNatures = new String[prevNatures.length - 1];
		int newPosition = 0;
		for (int i = 0; i < prevNatures.length; i++) {
			if (!prevNatures[i].equals(Utils.ID_NATURE)) {
				// guard against array out of bounds which will occur if we
				// get to here in a project that DOES NOT have the aj nature
				// (should never happen).
				if (newPosition < newNatures.length) {
					newNatures[newPosition++] = prevNatures[i];
				} else {
					// exception... attempt to remove ajnature from a project
					// that
					// doesn't have it. Leave the project natures unchanged.
					newNatures = prevNatures;
					break;
				}// end else
			}// end if
		}// end for
		description.setNatureIds(newNatures);
		project.setDescription(description, null);

		// Bugzilla 62625
		// Bugzilla 93532 - just remove plugin dependency if there is a plugin.xml file
		// Bugzilla 137922 - also consider bundles without a plugin.xml
		if (project.hasNature(PDE.PLUGIN_NATURE) 
		        && (hasPluginManifest(project)
		        		|| hasBundleManifest(project))) {
			// Bugzilla 72007
			// Checks if it was ajdt that added the ajde dependancy and removes
			// it if it was
			if (hasAJPluginDependency(project)) {
			    
                // Bugzilla 97080
                // Must close manifest editor after we are done with it 
                // if it is not already open
			    boolean manifestEditorAlreadyOpen = isManifestEditorOpen(project);
                ManifestEditor manEd = getAndPrepareToChangePDEModel(project);
                removeAJPluginDependency(manEd);
                if (!manifestEditorAlreadyOpen) {
                    manEd.close(true);
                }
	                
			}
		} else {
			// Update the build classpath to try and remove the aspectjrt.jar
			AspectJUIPlugin.removeAjrtFromBuildPath(project);
		}
		
		AspectJPlugin.getDefault().getCompilerFactory().removeCompilerForProject(project);

		removeMarkerOnReferencingProjects(project);

		//Ensures the project icon refreshes
		AJDTUtils.refreshPackageExplorer();
	}

	private static void includeAJfiles(IProject project, boolean prompt) {
		IJavaProject jp = JavaCore.create(project);
		try {
			boolean changed = false;
			IClasspathEntry[] cpEntry = jp.getRawClasspath();
			for (int i = 0; i < cpEntry.length; i++) {
				IClasspathEntry entry = cpEntry[i];
				if (entry.getEntryKind() == IClasspathEntry.CPE_SOURCE) {
					IPath[] exc = entry.getExclusionPatterns();
					if (exc != null) {
						List removeList = new ArrayList();
						for (int j = 0; j < exc.length; j++) {
							String ext = exc[j].getFileExtension();
							if ((ext != null) && ext.equals("aj")) { //$NON-NLS-1$
								removeList.add(exc[j]);
							}
						}
						if (removeList.size() > 0) {
							IPath[] exc2 = new IPath[exc.length
									- removeList.size()];
							int ind = 0;
							for (int j = 0; j < exc.length; j++) {
								if (!removeList.contains(exc[j])) {
									exc2[ind++] = exc[j];
								}
							}
							IClasspathEntry classpathEntry = JavaCore
									.newSourceEntry(entry.getPath(), exc2);
							cpEntry[i] = classpathEntry;
							changed = true;
						}
					}
				}
			}
			if (changed) {
				boolean restore = true;
				if (prompt) {
					IWorkbenchWindow window = AspectJUIPlugin.getDefault()
							.getWorkbench().getActiveWorkbenchWindow();
					restore = MessageDialog.openQuestion(window.getShell(),
							UIMessages.ExcludedAJ_title,
							UIMessages.ExcludedAJ_message);
				}
				if (restore) {
					jp.setRawClasspath(cpEntry, null);
				}
			}
		} catch (JavaModelException e) {
		}
	}
	
	private static void excludeAJfiles(IProject project) {
		IJavaProject jp = JavaCore.create(project);
		try {
			boolean changed = false;
			IClasspathEntry[] cpEntry = jp.getRawClasspath();
			for (int i = 0; i < cpEntry.length; i++) {
				IClasspathEntry entry = cpEntry[i];
				if (entry.getEntryKind() == IClasspathEntry.CPE_SOURCE) {
					List excludeList = new ArrayList();
					IPackageFragmentRoot[] roots = jp
							.findPackageFragmentRoots(entry);
					for (int j = 0; j < roots.length; j++) {
						IJavaElement[] rootFragments;
						try {
							rootFragments = roots[j].getChildren();
							for (int k = 0; k < rootFragments.length; k++) {
								if (rootFragments[k] instanceof IPackageFragment) {
									IPackageFragment pack = (IPackageFragment) rootFragments[k];
									ICompilationUnit[] files = pack
											.getCompilationUnits();
									for (int l = 0; l < files.length; l++) {
										IResource resource = files[l]
												.getResource();
										if (resource.getFileExtension().equals(
												"aj")) { //$NON-NLS-1$
											IPath resPath = resource
													.getFullPath();
											int seg = resPath
													.matchingFirstSegments(roots[j]
															.getPath());
											excludeList.add(resPath
													.removeFirstSegments(seg));
										}
									}
								}
							}
						} catch (JavaModelException e) {
						}
					}
					if (excludeList.size() > 0) {
						IPath[] exc = new IPath[excludeList.size()];
						excludeList.toArray(exc);
						IClasspathEntry classpathEntry = JavaCore
								.newSourceEntry(entry.getPath(), exc);
						cpEntry[i] = classpathEntry;
						changed = true;
					}
				}
			}
			if (changed) {
				jp.setRawClasspath(cpEntry, null);
			}
		} catch (JavaModelException e) {
		}
	}
	
	// Bugzilla 72007
	// This method checks whether the project already has
	// org.aspectj.runtime imported. Returns true if it does.
	public static boolean hasAJPluginDependency(IProject project) {

		ManifestEditor manEd = getPDEManifestEditor(project);
		IPluginModel model = null;
		IPluginImport[] imports = null;

		if (manEd != null) {
			model = (IPluginModel) manEd.getAggregateModel();
			imports = model.getPluginBase().getImports();
		} else {
			try {
				//checks the classpath for plugin dependencies
				IPackageFragmentRoot[] dependencies = JavaCore.create(project)
						.getPackageFragmentRoots();
				for (int i = 0; i < dependencies.length; i++) {
					if (dependencies[i].getElementName().equals(
							"aspectjrt.jar")) //$NON-NLS-1$
						return true;
				}
			} catch (JavaModelException e) {
			}
			return false;
		}

		for (int i = 0; i < imports.length; i++) {
			IPluginImport importObj = imports[i];
			if (importObj.getId().equals(AspectJPlugin.RUNTIME_PLUGIN_ID)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * @param manEd
	 */
	private static void removeAJPluginDependency(ManifestEditor manEd) {
		IWorkbenchWindow window = AspectJUIPlugin.getDefault().getWorkbench()
		.getActiveWorkbenchWindow();
		if ((AspectJPreferences.askPDEAutoRemoveImport() && confirmPDEAutoRemoveImport(window))
				|| (AspectJPreferences.doPDEAutoRemoveImport())) {

	
			if (manEd != null) {
				IPluginModel model = (IPluginModel) manEd.getAggregateModel();
				try {
					removeImportFromPDEModel(model,
							AspectJPlugin.RUNTIME_PLUGIN_ID);
					manEd.doSave(new NullProgressMonitor());
				} catch (CoreException e) {
					AJDTErrorHandler.handleAJDTError(
									UIMessages.AutoPluginRemoveErrorDialog_title,
									UIMessages.AutoPluginRemoveErrorDialog_message,
									e);
				}
			}// end if we got a reference to the manifest editor
			else {
				MessageDialog
						.openError(
								AspectJUIPlugin.getDefault().getWorkbench()
										.getActiveWorkbenchWindow().getShell(),
										UIMessages.AutoPluginRemoveDialog_noEditor_title,
										UIMessages.AutoPluginRemoveDialog_noEditor_message);
			}
		}
	}

	/**
	 * @param model
	 * @param importId
	 * @throws CoreException
	 */
	public static void removeImportFromPDEModel(IPluginModel model,
			String importId) throws CoreException {
		IPluginImport[] imports = model.getPluginBase().getImports();
		IPluginImport doomed = null;

		for (int i = 0; i < imports.length; i++) {
			IPluginImport importObj = imports[i];
			if (importObj.getId().equals(importId)) {
				doomed = importObj;
				break;
			}
		}// end for

		if (doomed != null) {
			model.getPluginBase().remove(doomed);
		}

		IFile manifestFile = (IFile) model.getUnderlyingResource();
		manifestFile.refreshLocal(IResource.DEPTH_INFINITE,
				new NullProgressMonitor());
	}
	
	/**
	 * @param current
	 */
	public static void verifyAjrtVersion(IProject current) {
		IJavaProject javaProject = JavaCore.create(current);
		String ajrtPath = CoreUtils.getAspectjrtClasspath();
		try {
			IClasspathEntry[] originalCP = javaProject.getRawClasspath();
			ArrayList tempCP = new ArrayList();

			boolean changed = false;

			// Go through each current classpath entry one at a time. If it is a
			// reference to aspectjrt.jar
			// replace it - I could look through each reference to check if it
			// is now invalid - but I don't ...
			for (int i = 0; i < originalCP.length; i++) {
				IPath path = originalCP[i].getPath();
				if (path.toOSString().endsWith("aspectjrt.jar")) { //$NON-NLS-1$
					IClasspathEntry ajrtCP = JavaCore.newLibraryEntry(new Path(
							ajrtPath), // library location
							null, // no source
							null // no source
							);
					tempCP.add(ajrtCP);
					changed = true;
					AJLog.log("In project " //$NON-NLS-1$
							+ current.getName() + " - replacing " //$NON-NLS-1$
							+ originalCP[i].getPath() + " with " //$NON-NLS-1$
							+ ajrtCP.getPath());
				} else {
					tempCP.add(originalCP[i]);
				}

			}

			// Set the classpath with only those elements that survived the
			// above filtration process.
			if (changed) {
				IClasspathEntry[] newCP = (IClasspathEntry[]) tempCP
						.toArray(new IClasspathEntry[tempCP.size()]);
				javaProject.setRawClasspath(newCP, new NullProgressMonitor());
			}
		} catch (JavaModelException e) {
			// Thrown if attempted to add a duplicate classpath entry.
		}
	}

	/**
	 * Prompts the user for whether to auto import aspectj runtime plugin when
	 * giving aspectj nature to PDE project.
	 * 
	 * @return <code>true</code> if it's OK to import, <code>false</code>
	 *         otherwise
	 */
	private static boolean confirmPDEAutoAddImport(IWorkbenchWindow window) {

		MessageDialogWithToggle dialog = MessageDialogWithToggle
				.openQuestion(
						window.getShell(),
						UIMessages.PluginImportDialog_importConfirmTitle,
						UIMessages.PluginImportDialog_importConfirmMsg,
						UIMessages.PluginImportDialog_importConfirmToggleMsg,
						false); // toggle is initially unchecked

		int result = dialog.getReturnCode();

		if (result >= 0 && dialog.getToggleState()) {
			if (result == 0) {
				// User chose Yes/Don't ask again, so always switch
				AspectJPreferences.setDoPDEAutoImport(true);
				AspectJPreferences.setAskPDEAutoImport(false);
			} else {
				// User chose No/Don't ask again, so never switch
				AspectJPreferences.setDoPDEAutoImport(false);
				AspectJPreferences.setAskPDEAutoImport(false);
			}
		}// end if
		return result == 0;
	}

	/**
	 * Prompts the user for whether to automatically remove the AspectJ runtime plug-in 
	 * dependency when removing AspectJ nature from a PDE project.
	 * 
	 * @return <code>true</code> if it's OK to import, <code>false</code>
	 *         otherwise
	 */
	private static boolean confirmPDEAutoRemoveImport(IWorkbenchWindow window) {

		MessageDialogWithToggle dialog = MessageDialogWithToggle
				.openQuestion(
						window.getShell(),
						UIMessages.PluginImportDialog_removeImportConfirmTitle,
						UIMessages.PluginImportDialog_removeImportConfirmMsg,
						UIMessages.PluginImportDialog_removeImportConfirmToggleMsg,
						false); // toggle is initially unchecked

		int result = dialog.getReturnCode();

		if (result >= 0 && dialog.getToggleState()) {
			if (result == 0) {
				// User chose Yes/Don't ask again, so always switch
				AspectJPreferences.setDoPDEAutoRemoveImport(true);
				AspectJPreferences.setAskPDEAutoRemoveImport(false);
			} else {
				// User chose No/Don't ask again, so never switch
				AspectJPreferences.setDoPDEAutoRemoveImport(false);
				AspectJPreferences.setAskPDEAutoRemoveImport(false);
			}
		}// end if
		return result == 0;
	}
	
	/**
	 * Decorate icon based on modifiers, errors etc. Possible decorations are:
	 * abstract, final, synchronized, static, runnable, warning, error,
	 * overrides, implements
	 */
	public static ImageDescriptor decorate(ImageDescriptor base,
			IProgramElement pNode) {
		int flags = 0;
		if (pNode != null) {
			List modifiers = pNode.getModifiers();
			if (modifiers != null) {
				if (modifiers.contains(IProgramElement.Modifiers.ABSTRACT)) {
					flags = flags | JavaElementImageDescriptor.ABSTRACT;
				}
				if (modifiers.contains(IProgramElement.Modifiers.FINAL)) {
					flags = flags | JavaElementImageDescriptor.FINAL;
				}
				if (modifiers.contains(IProgramElement.Modifiers.SYNCHRONIZED)) {
					flags = flags | JavaElementImageDescriptor.SYNCHRONIZED;
				}
				if (modifiers.contains(IProgramElement.Modifiers.STATIC)) {
					flags = flags | JavaElementImageDescriptor.STATIC;
				}
			}
			if (pNode.getKind() == IProgramElement.Kind.CONSTRUCTOR
					|| pNode.getKind() == IProgramElement.Kind.INTER_TYPE_CONSTRUCTOR) {
				flags = flags | JavaElementImageDescriptor.CONSTRUCTOR;
			}
			if (pNode.isRunnable()) {
				flags = flags | JavaElementImageDescriptor.RUNNABLE;
			}
			if (pNode.isOverrider()) {
				flags = flags | JavaElementImageDescriptor.OVERRIDES;
			}
			if (pNode.isImplementor()) {
				flags = flags | JavaElementImageDescriptor.IMPLEMENTS;
			}
			IMessage sMessage = pNode.getMessage();
			if (sMessage != null) {
				if (sMessage.getKind() == IMessage.ERROR) {
					flags = flags | JavaElementImageDescriptor.ERROR;
				} else if (sMessage.getKind() == IMessage.WARNING) {
					flags = flags | JavaElementImageDescriptor.WARNING;
				}
			}
		}
		return decorate(base, flags);
	}

	public static void refreshPackageExplorer() {
		int delay = 5*previousExecutionTime;
		if (delay < 250) {
			delay = 250;
		} else if (delay > 5000) {
			delay = 5000;
		}
		getRefreshPackageExplorerJob().schedule(delay);
	}

	// reuse the same Job to avoid excessive updates
	private static Job getRefreshPackageExplorerJob() {
		if (refreshJob == null) {
			refreshJob = new RefreshPackageExplorerJob();
		}
		return refreshJob;
	}

	private static class RefreshPackageExplorerJob extends UIJob {
		RefreshPackageExplorerJob() {
			super(UIMessages.utils_refresh_explorer_job);
		}

		public IStatus runInUIThread(IProgressMonitor monitor) {
			long start = System.currentTimeMillis();
			PackageExplorerPart pep = PackageExplorerPart
					.getFromActivePerspective();
			if (pep != null) {
				pep.getTreeViewer().refresh();
			}
			previousExecutionTime = (int)(System.currentTimeMillis() - start);
			//System.out.println("refresh explorer: elapsed="+previousExecutionTime);
			return Status.OK_STATUS;
		}
	}

	/**
	 * @param types
	 * @param j
	 * @return
	 */
	public static char[][] getEnclosingTypes(IType startType) {
		char[][] enclosingTypes = new char[0][];
		IType type = startType.getDeclaringType();
		List enclosingTypeList = new ArrayList();
		while(type != null) {
			char[] typeName = type.getElementName().toCharArray();
			enclosingTypeList.add(0, typeName);
			type = type.getDeclaringType();
		}
		if(enclosingTypeList.size() > 0) {
			enclosingTypes = new char[enclosingTypeList.size()][];
			for (int k = 0; k < enclosingTypeList.size(); k++) {
				char[] typeName = (char[]) enclosingTypeList.get(k);
				enclosingTypes[k] = typeName;
			}
		}
		return enclosingTypes;
	}
	
	/**
	 * Get a new filename for the given project.  Returns the name without the file extension.
	 * @param project
	 * @param defaultFileName
	 * @param extension
	 * @return a string that is NOT the name of a current file in the project
	 */
	public static String getFreeFileName(IProject project, String defaultFileName,
			String extension) {	
		int counter = 0;
		if(project != null) {
			boolean foundFreeName = false;
			while (!foundFreeName) {
				String name = counter==0 ? defaultFileName : defaultFileName+counter;
				IPath path = project.getFullPath().append(name + "." + extension); //$NON-NLS-1$
				if(!AspectJPlugin.getWorkspace().getRoot().getFile(path).exists()) {
					foundFreeName = true;
				} else {
					counter++;
				}
			}
		}
		return counter==0 ? defaultFileName : defaultFileName+counter;
	}
	
	/**
	 * Clear all problem markers for the given project. If recurse is false 
	 * then only the markers on the top level resource (the project) are removed.
	 */
	public static void clearProjectMarkers(IProject project, boolean recurse) {
		try {
			project.deleteMarkers(IJavaModelMarker.JAVA_MODEL_PROBLEM_MARKER,
							false, (recurse ? IResource.DEPTH_INFINITE
									: IResource.DEPTH_ZERO));
			project.deleteMarkers(IAJModelMarker.AJDT_PROBLEM_MARKER, true,
							(recurse ? IResource.DEPTH_INFINITE
									: IResource.DEPTH_ZERO));
			project.deleteMarkers(IMarker.TASK, true,
							(recurse ? IResource.DEPTH_INFINITE
									: IResource.DEPTH_ZERO));
		} catch (Exception ex) {
		}
	}

	/**
	 * Return the IResource within the workspace that maps to the given File
	 */
	public static IResource findResource(String fullPath) {
		IWorkspaceRoot root = ResourcesPlugin.getWorkspace().getRoot();
		IPath path = new Path(fullPath);
		return root.getFileForLocation(path);
	}

	/*
	 * Lightweight form of canonical path conversion - only converts
	 * windows-style drive letters to uppercase.
	 */
	private static String toCanonical(String path) {
		if ((path.charAt(1) == ':')
				&& (((path.charAt(0) >= 'a') && (path.charAt(0) <= 'z')) || ((path
						.charAt(0) >= 'A') && (path.charAt(0) <= 'Z')))) {
			return Character.toUpperCase(path.charAt(0)) + path.substring(1);
		} else {
			return path;
		}
	}
	
	/**
	 * On windows, returns whether or not we have a match regardless of
	 * case - bug 82341
	 */
	private static boolean caseInsensitiveMatch(String toMatch, IResource resource) {
		if((toMatch.charAt(1) == ':')) {
			return toMatch.toLowerCase().startsWith(resource.getLocation()
					.toString().toLowerCase());
		}
		return false;
	}

	/**
	 * Return the IResource within the project that maps to the given File
	 */
	public static IResource findResource(String fullPath, IProject p) {

		// full path contains absolute file system paths, we need to undo the
		// effects of any "symbolic linking" in the workspace to ensure that we
		// return the correct IResource.
		String toMatch = toCanonical(fullPath.replace('\\', '/'));
		try {
			IJavaProject jp = JavaCore.create(p);
			IClasspathEntry[] cpes = jp.getRawClasspath();
			for (int i = 0; i < cpes.length; i++) {
				IClasspathEntry e = cpes[i];
				if (e.getEntryKind() == IClasspathEntry.CPE_SOURCE) {
					IPath pe = e.getPath();
					if (pe.segment(0).equals(p.getName())) {
						IResource ires = p
								.findMember(pe.removeFirstSegments(1));
						if (ires instanceof IFolder) {
							IFolder f = (IFolder) ires;
							// bug 82341 - adding extra check for a match
							// regardless of case on windows
							if (toMatch.startsWith(toCanonical(f.getLocation().toString()))
									|| caseInsensitiveMatch(toMatch,f)) {
								// this is what it was all about!
								// we have a possible symbolic link within our
								// project to the file
								String postfix = toMatch.substring(f
										.getLocation().toString().length());
								IPath postfixPath = new Path(postfix);
								if (f.exists(postfixPath)) {
									return f.findMember(postfixPath);
								}
							} 
						} else if (ires instanceof IProject) {
							// I think this is when the project has no src/bin
							// dirs
							IProject iproj = ((IProject) ires);
     						// bug 82341 - adding extra check for a match
							// regardless of case on windows
							if (toMatch.startsWith(toCanonical(iproj.getLocation()
									.toString()))
									|| caseInsensitiveMatch(toMatch,iproj)) {
								// this is what it was all about!
								// we have a possible symbolic link within our
								// project to the file
								String postfix = toMatch.substring(iproj
										.getLocation().toString().length());
								IPath postfixPath = new Path(postfix);
								if (iproj.exists(postfixPath)) {
									return iproj.findMember(postfixPath);
								}
							}
						}
					}
				}
			}
		} catch (JavaModelException ex) {
		}
		
		String projectPathStr = p.getLocation().toString();
		try {
			projectPathStr = p.getLocation().toFile().getCanonicalPath();
		} catch (IOException e) {
		}
		IPath projectPath = new Path(projectPathStr);
		IPath filePath = new Path(fullPath);
		if (projectPath.isPrefixOf(filePath)) {
			filePath = filePath.removeFirstSegments(projectPath.segmentCount());
		}
		IResource ret = p.findMember(filePath);
		return ret;
	}

    // Bug 119853
    // paths are different on Mac
    public static boolean isMacOS() {
        String os = System.getProperty("os.name"); //$NON-NLS-1$
        return os.startsWith("Mac"); //$NON-NLS-1$
    }

    /**
     * extracts a project name from a full path of a binary aspect path.
     * A binary aspect path is like a normal path, but contains a '!' at the 
     * package root.  Assume that the filename looks like this:<br>
     * <br>
     *  <code>/full/path/to/project/<project_folder>/path/to/package/root!qualified/name.class</code>
     * 
     * cannot assume that the project name is the same as the project folder.  Cannot assume that
     * project folder is on the workspace path.
     * 
     * @See {@link ShadowMunger.getBinarySourceLocation}
     */
    public static IJavaProject extractProject(String filepath) {
        // remove class name
        String pathOnly = filepath.substring(0, filepath.lastIndexOf('!'));
        
        // find associated resource
        IResource packageRootRes = findResource(pathOnly);
        if (packageRootRes != null) {
            IProject proj = packageRootRes.getProject();
            if (proj != null) {
                return JavaCore.create(proj);
            }
        }
        return null;
    }

    /**
     * converts an absolute path name to a binary aspect to 
     * the fully qualified name of that aspect.
     */
    public static String extractQualifiedName(String filepath) {
        String namePath = filepath.substring(filepath.lastIndexOf('!')+1);
        String removeFileExtension = namePath.substring(0, namePath.lastIndexOf('.'));
        return removeFileExtension.replace(File.separatorChar, '.');
    }
}