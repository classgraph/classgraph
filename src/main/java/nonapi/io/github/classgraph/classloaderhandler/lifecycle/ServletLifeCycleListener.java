/*
 * This file is part of ClassGraph.
 *
 * Author: Luke Hutchison
 *
 * Hosted at: https://github.com/classgraph/classgraph
 *
 * --
 *
 * The MIT License (MIT)
 *
 * Copyright (c) 2019 Luke Hutchison
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
 * documentation files (the "Software"), to deal in the Software without restriction, including without
 * limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of
 * the Software, and to permit persons to whom the Software is furnished to do so, subject to the following
 * conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies or substantial
 * portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT
 * LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO
 * EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN
 * AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE
 * OR OTHER DEALINGS IN THE SOFTWARE.
 */
package nonapi.io.github.classgraph.classloaderhandler.lifecycle;

import java.util.logging.Logger;

import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import javax.servlet.annotation.WebListener;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.ScanResult;

/**
 * Register a {@link WebListener} to respond to servlet context shutdown by closing any remaining open ScanResult
 * instances (#376). This creates classfile references to javax.servlet classes, however this class is never
 * referenced by any other class in ClassGraph (it is only included in the classpath so that the servlet container
 * can locate it using the {@link WebListener} annotation). Therefore ClassGraph has only a compile-time
 * ("provides"-scoped) dependency on the servlet container to enable the container to find this class.
 */
@WebListener
public class ServletLifeCycleListener implements ServletContextListener {
    /** The logger. */
    private final Logger log = Logger.getLogger(ClassGraph.class.getName());

    /**
     * Context initialized.
     *
     * @param event
     *            the event
     */
    @Override
    public void contextInitialized(final ServletContextEvent event) {
        log.info("Servlet context initialized");
    }

    /**
     * Context destroyed.
     *
     * @param event
     *            the event
     */
    @Override
    public void contextDestroyed(final ServletContextEvent event) {
        // Cleanly close down any open {@link ScanResult} instances.
        log.info("Servlet context destroyed -- closing any remaning open ClassGraph ScanResult instances");
        ScanResult.closeAll();
    }
}