package org.eclipse.contribution.jdt;

import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.equinox.app.IApplication;
import org.eclipse.equinox.app.IApplicationContext;
import org.eclipse.ui.PlatformUI;

/**
 * Simple application to test if weaving is enabled in this installation
 * 
 * @author Andrew Eisenberg
 * @created Jan 25, 2009
 *
 */
public class WeavingTestApplication implements IApplication {

    private static final int WEAVING_DISABLED = -1;
    private static final int WEAVING_ENABLED = EXIT_OK;

    public Object start(IApplicationContext context) throws Exception {
        System.out.println("Testing to see if weaving service is enabled...");
        if (IsWovenTester.isWeavingActive()) {
            System.out.println("Weaving service is enabled!");
        } else {
            System.out.println("Weaving service is disabled.");
        }
        PlatformUI.getWorkbench().close();
        
        // will not be reached
        return IsWovenTester.isWeavingActive() ? WEAVING_ENABLED : WEAVING_DISABLED;
    }

    public void stop() { }

}
