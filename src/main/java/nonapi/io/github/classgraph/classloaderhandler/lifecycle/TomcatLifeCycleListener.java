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

import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import javax.servlet.annotation.WebListener;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.ScanResult;

/**
 * Register a {@link WebListener} to respond to Tomcat servlet context shutdown (#376). This creates classfile
 * references to the classes {@link WebListener}, {@link ServletContextListener} and {@link ServletContextEvent},
 * however this class {@link TomcatLifeCycleListener} is never actually referenced by any other class in ClassGraph
 * (it is only included in the classpath so that Tomcat can locate it using the {@link WebListener} annotation).
 * Therefore ClassGraph has only a compile-time ("provides"-scoped) dependency on Tomcat to enable Tomcat to find
 * this class, but a ClassNotFound exception should not be thrown by anything else that uses ClassGraph.
 */
@WebListener
public class TomcatLifeCycleListener implements ServletContextListener {
    /**
     * Context initialized.
     *
     * @param event
     *            the event
     */
    @Override
    public void contextInitialized(final ServletContextEvent event) {
        ClassGraph.disableShutdownHook();
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
        ScanResult.closeAll();
    }
}