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
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import io.github.lukehutch.fastclasspathscanner.classloaderhandler.ClassLoaderHandler;
import io.github.lukehutch.fastclasspathscanner.classloaderhandler.EquinoxClassLoaderHandler;
import io.github.lukehutch.fastclasspathscanner.classloaderhandler.JBossClassLoaderHandler;
import io.github.lukehutch.fastclasspathscanner.classloaderhandler.WeblogicClassLoaderHandler;
import io.github.lukehutch.fastclasspathscanner.utils.Join;
import io.github.lukehutch.fastclasspathscanner.utils.LogNode;

public class ClasspathFinder {
    /** The list of raw classpath elements. */
    private final List<String> rawClasspathElements = new ArrayList<>();

    // -------------------------------------------------------------------------------------------------------------

    /** ClassLoaderHandler that is able to extract the URLs from a URLClassLoader. */
    private static class URLClassLoaderHandler implements ClassLoaderHandler {
        @Override
        public boolean handle(final ClassLoader classloader, final ClasspathFinder classpathFinder) {
            if (classloader instanceof URLClassLoader) {
                final URL[] urls = ((URLClassLoader) classloader).getURLs();
                if (urls != null) {
                    for (final URL url : urls) {
                        if (url != null) {
                            classpathFinder.addClasspathElement(url.toString());
                        }
                    }
                }
                return true;
            }
            return false;
        }
    }

    /** Default ClassLoaderHandlers */
    private final List<ClassLoaderHandler> defaultClassLoaderHandlers = Arrays.asList(
            new EquinoxClassLoaderHandler(), new JBossClassLoaderHandler(), new WeblogicClassLoaderHandler());

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Add a classpath element relative to a base file. May be called by a ClassLoaderHandler to add classpath
     * elements that it knows about.
     */
    public void addClasspathElement(final String pathElement) {
        rawClasspathElements.add(pathElement);
    }

    /**
     * Add classpath elements, separated by the system path separator character. May be called by a
     * ClassLoaderHandler to add a path string that it knows about.
     */
    public void addClasspathElements(final String pathStr) {
        if (pathStr != null && !pathStr.isEmpty()) {
            for (final String pathElement : pathStr.split(File.pathSeparator)) {
                addClasspathElement(pathElement);
            }
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    ClasspathFinder(final ScanSpec scanSpec, final LogNode log) {
        if (scanSpec.overrideClasspath != null) {
            // Manual classpath override
            addClasspathElements(scanSpec.overrideClasspath);
        } else {
            // Always include a ClassLoaderHandler for URLClassLoader subclasses as a default, so that we can
            // handle URLClassLoaders (the most common form of ClassLoader) even if ServiceLoader can't find
            // other ClassLoaderHandlers (this can happen if FastClasspathScanner's package is renamed using
            // Maven Shade).
            final List<ClassLoaderHandler> classLoaderHandlers = new ArrayList<>();
            classLoaderHandlers.add(new URLClassLoaderHandler());
            classLoaderHandlers.addAll(defaultClassLoaderHandlers);
            classLoaderHandlers.addAll(scanSpec.extraClassLoaderHandlers);
            if (log != null && !classLoaderHandlers.isEmpty()) {
                final List<String> classLoaderHandlerNames = new ArrayList<>();
                for (final ClassLoaderHandler classLoaderHandler : classLoaderHandlers) {
                    classLoaderHandlerNames.add(classLoaderHandler.getClass().getName());
                }
                log.log("ClassLoaderHandlers loaded: " + Join.join(", ", classLoaderHandlerNames));
            }

            // Try finding a handler for each of the classloaders discovered above
            for (final ClassLoader classLoader : scanSpec.classLoaders) {
                // Iterate through registered ClassLoaderHandlers
                boolean classloaderFound = false;
                for (final ClassLoaderHandler handler : classLoaderHandlers) {
                    try {
                        if (handler.handle(classLoader, this)) {
                            // Sucessfully handled
                            if (log != null) {
                                log.log("Classpath elements from ClassLoader " + classLoader.getClass().getName()
                                        + " were extracted by ClassLoaderHandler " + handler.getClass().getName());
                            }
                            classloaderFound = true;
                            break;
                        }
                    } catch (final Exception e) {
                        if (log != null) {
                            log.log("Exception in " + classLoader.getClass().getName() + ": " + e.toString());
                        }
                    }
                }
                if (!classloaderFound) {
                    if (log != null) {
                        log.log("Found unknown ClassLoader type, cannot scan classes: "
                                + classLoader.getClass().getName());
                    }
                }
            }

            // Add entries found in java.class.path, in case those entries were missed above due to some
            // non-standard classloader that uses this property
            addClasspathElements(System.getProperty("java.class.path"));
        }
    }

    /** Get the raw classpath elements obtained from ClassLoaders. */
    public List<String> getRawClasspathElements() {
        return rawClasspathElements;
    }
}
