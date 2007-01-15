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

import java.util.HashMap;
import java.util.Map;

import org.aspectj.ajde.core.AjCompiler;
import org.eclipse.ajdt.internal.core.ajde.ICompilerFactory;
import org.eclipse.core.resources.IProject;

public class UICompilerFactory implements ICompilerFactory {

	private Map compilerMap = new HashMap();
	
	public AjCompiler getCompilerForProject(IProject project) {
		if (compilerMap.get(project) != null) {
			return (AjCompiler) compilerMap.get(project);
		}
		AjCompiler compiler = 
			new AjCompiler(
				project.getName(),
				new UIComplierConfiguration(project),
				new UIBuildProgressMonitor(project),
				new UIMessageHandler(project));
		compilerMap.put(project,compiler);
		return compiler;
	}

	public void removeCompilerForProject(IProject project) {
		// firstly clean up any state associated with the compiler
		getCompilerForProject(project).clearLastState();
		// remove compiler from the map
		compilerMap.remove(project);
	}
	
	public boolean hasCompilerForProject(IProject project) {
		return (compilerMap.get(project) != null);
	}
	
}
