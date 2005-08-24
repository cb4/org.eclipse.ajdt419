/*******************************************************************************
 * Copyright (c) 2005 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials 
 * are made available under the terms of the Common Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/cpl-v10.html
 * 
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *     Sian January  - initial version
 *******************************************************************************/

package org.eclipse.ajdt.ui.tests;

import junit.framework.TestCase;

public aspect Enforcement {

	declare error: execution(* TestCase+.*(..)) && !execution(* UITestCase+.*(..)):
		"All test classes should extend UITestCase";
	
	declare warning: call(* UITestCase.deleteProject(..)) && !within(UITestCase):
		"Projects are automatically deleted for you at the end of each test.";
}
