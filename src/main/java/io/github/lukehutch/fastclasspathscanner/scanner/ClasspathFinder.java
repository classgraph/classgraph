/*
 * This file is part of FastClasspathScanner.
 * 
 * Author: Luke Hutchison
 * 
 * Hosted at: https://github.com/lukehutch/fast-classpath-scanner
 * 
 * --
 *
 * The MIT License (MIT)
 *
 * Copyright (c) 2016 Luke Hutchison
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
package io.github.lukehutch.fastclasspathscanner.scanner;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import io.github.lukehutch.fastclasspathscanner.classloaderhandler.ClassLoaderHandler;
import io.github.lukehutch.fastclasspathscanner.classloaderhandler.EquinoxClassLoaderHandler;
import io.github.lukehutch.fastclasspathscanner.classloaderhandler.JBossClassLoaderHandler;
import io.github.lukehutch.fastclasspathscanner.classloaderhandler.URLClassLoaderHandler;
import io.github.lukehutch.fastclasspathscanner.classloaderhandler.WeblogicClassLoaderHandler;
import io.github.lukehutch.fastclasspathscanner.utils.LogNode;

/** A class to find the unique ordered classpath elements. */
public class ClasspathFinder {
    /** The list of raw classpath elements. */
    private final List<String> rawClasspathElements = new ArrayList<>();

    /**
     * Default ClassLoaderHandlers. If a ClassLoaderHandler is added to FastClasspathScanner, it should be added to
     * this list.
     */
    private static final List<Class<? extends ClassLoaderHandler>> DEFAULT_CLASS_LOADER_HANDLERS = Arrays.asList(
            // The main default ClassLoaderHandler -- URLClassLoader is the most common ClassLoader
            URLClassLoaderHandler.class,
            // ClassLoaderHandlers for other ClassLoaders that are handled by FastClasspathScanner
            EquinoxClassLoaderHandler.class, JBossClassLoaderHandler.class, WeblogicClassLoaderHandler.class);

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Add a classpath element relative to a base file. May be called by a ClassLoaderHandler to add classpath
     * elements that it knows about.
     */
    public void addClasspathElement(final String pathElement, final LogNode log) {
        rawClasspathElements.add(pathElement);
        if (log != null) {
            log.log("Adding classpath element: " + pathElement);
        }
    }

    /**
     * Add classpath elements, separated by the system path separator character. May be called by a
     * ClassLoaderHandler to add a path string that it knows about.
     */
    public void addClasspathElements(final String pathStr, final LogNode log) {
        if (pathStr != null && !pathStr.isEmpty()) {
            for (final String pathElement : pathStr.split(File.pathSeparator)) {
                addClasspathElement(pathElement, log);
            }
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /** A class to find the unique ordered classpath elements. */
    ClasspathFinder(final ScanSpec scanSpec, final LogNode log) {
        if (scanSpec.overrideClasspath != null) {
            // Manual classpath override
            final LogNode overrideLog = log == null ? null : log.log("Overriding classpath");
            addClasspathElements(scanSpec.overrideClasspath, overrideLog);
        } else {
            // Always include a ClassLoaderHandler for URLClassLoader subclasses as a default, so that we can
            // handle URLClassLoaders (the most common form of ClassLoader) even if ServiceLoader can't find
            // other ClassLoaderHandlers (this can happen if FastClasspathScanner's package is renamed using
            // Maven Shade).
            final List<ClassLoaderHandler> classLoaderHandlers = new ArrayList<>();
            for (final Class<? extends ClassLoaderHandler> classLoaderHandlerClass : DEFAULT_CLASS_LOADER_HANDLERS) {
                try {
                    classLoaderHandlers.add(classLoaderHandlerClass.newInstance());
                } catch (InstantiationException | IllegalAccessException e) {
                    if (log != null) {
                        log.log("Could not instantiate " + classLoaderHandlerClass.getName(), e);
                    }
                }
            }
            for (final Class<? extends ClassLoaderHandler> classLoaderHandler : scanSpec.extraClassLoaderHandlers) {
                try {
                    classLoaderHandlers.add(classLoaderHandler.newInstance());
                } catch (InstantiationException | IllegalAccessException e) {
                    if (log != null) {
                        log.log("Could not instantiate " + classLoaderHandler.getName(), e);
                    }
                }
            }
            if (log != null) {
                final LogNode classLoaderHandlerLog = log.log("ClassLoaderHandlers loaded:");
                for (final ClassLoaderHandler classLoaderHandler : classLoaderHandlers) {
                    classLoaderHandlerLog.log(classLoaderHandler.getClass().getName());
                }
            }

            // Try finding a handler for each of the classloaders discovered above
            for (final ClassLoader classLoader : scanSpec.classLoaders) {
                final LogNode classLoaderLog = log == null ? null
                        : log.log("Finding classpath elements in ClassLoader " + classLoader);
                // Iterate through registered ClassLoaderHandlers
                boolean classloaderFound = false;
                for (final ClassLoaderHandler handler : classLoaderHandlers) {
                    try {
                        if (handler.handle(classLoader, this, classLoaderLog)) {
                            classloaderFound = true;
                            break;
                        }
                    } catch (final Exception e) {
                        if (classLoaderLog != null) {
                            classLoaderLog.log("Exception in ClassLoaderHandler", e);
                        }
                    }
                }
                if (!classloaderFound) {
                    if (classLoaderLog != null) {
                        classLoaderLog.log("Unknown ClassLoader type, cannot scan classes");
                    }
                }
            }

            if (!scanSpec.overrideClassLoaders) {
                // Add entries found in java.class.path, in case those entries were missed above due to some
                // non-standard classloader that uses this property
                final LogNode sysPropLog = log == null ? null
                        : log.log("Getting classpath entries from java.class.path");
                addClasspathElements(System.getProperty("java.class.path"), sysPropLog);
            }
        }
    }

    /** Get the raw classpath elements obtained from ClassLoaders. */
    public List<String> getRawClasspathElements() {
        return rawClasspathElements;
    }
}
