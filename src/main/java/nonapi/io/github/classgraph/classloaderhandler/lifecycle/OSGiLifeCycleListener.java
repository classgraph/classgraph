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

import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.ScanResult;

/**
 * Register an OSGi {@link Component} to respond to OSGi activation and deactivation (#376). This creates classfile
 * references to OSGi service annotation classes, however this class is never actually referenced by any other class
 * in ClassGraph (it is only included in the classpath so that the OSGi container can locate it using the
 * {@link Component} annotation). Therefore ClassGraph has only a compile-time ("provides"-scoped) dependency on the
 * OSGi annotations, to enable the container to find this class, but a ClassNotFound exception should not be thrown
 * by anything else that uses ClassGraph.
 */
@Component
public class OSGiLifeCycleListener {
    /** The logger. */
    private final Logger log = Logger.getLogger(ClassGraph.class.getName());

    /** Service activated. */
    @Activate
    public void activate() {
        log.info("OSGi service activated -- disabling ClassGraph shutdown hook");
        ClassGraph.disableShutdownHook();
    }

    /** Service deactivated. */
    @Deactivate
    public void deactivate() {
        // Cleanly close down any open {@link ScanResult} instances.
        log.info("OSGi service deactivated -- closing any remaning open ClassGraph ScanResult instances");
        ScanResult.closeAll();
    }
}