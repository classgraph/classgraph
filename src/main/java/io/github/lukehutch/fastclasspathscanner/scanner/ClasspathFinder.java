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
import java.util.List;

import io.github.lukehutch.fastclasspathscanner.classloaderhandler.ClassLoaderHandler;
import io.github.lukehutch.fastclasspathscanner.classloaderhandler.ClassLoaderHandlerRegistry;
import io.github.lukehutch.fastclasspathscanner.utils.AdditionOrderedSet;
import io.github.lukehutch.fastclasspathscanner.utils.LogNode;

/** A class to find the unique ordered classpath elements. */
public class ClasspathFinder {
    /** The list of raw classpath elements. */
    private final List<String> rawClasspathElements = new ArrayList<>();

    /**
     * Add a classpath element relative to a base file. May be called by a ClassLoaderHandler to add classpath
     * elements that it knows about.
     * 
     * @return true (and add the classpath element) if pathElement is not null or empty, otherwise return false.
     */
    public boolean addClasspathElement(final String pathElement, final LogNode log) {
        if (pathElement != null && !pathElement.isEmpty()) {
            rawClasspathElements.add(pathElement);
            if (log != null) {
                log.log("Adding classpath element: " + pathElement);
            }
            return true;
        }
        return false;
    }

    /**
     * Add classpath elements, separated by the system path separator character. May be called by a
     * ClassLoaderHandler to add a path string that it knows about.
     * 
     * @return true (and add the classpath element) if pathElement is not null or empty, otherwise return false.
     */
    public boolean addClasspathElements(final String pathStr, final LogNode log) {
        if (pathStr != null && !pathStr.isEmpty()) {
            for (final String pathElement : pathStr.split(File.pathSeparator)) {
                addClasspathElement(pathElement, log);
            }
            return true;
        }
        return false;
    }

    // -------------------------------------------------------------------------------------------------------------

    /** A class to find the unique ordered classpath elements. */
    ClasspathFinder(final ScanSpec scanSpec, final LogNode log) {
        if (scanSpec.overrideClasspath != null) {
            // Manual classpath override
            if (scanSpec.overrideClassLoaders) {
                if (log != null) {
                    log.log("It is not possible to override both the classpath and the ClassLoaders -- "
                            + "ignoring the ClassLoader override");
                }
            }
            final LogNode overrideLog = log == null ? null : log.log("Overriding classpath");
            addClasspathElements(scanSpec.overrideClasspath, overrideLog);
        } else {
            // Get all default ClassLoaderHandlers
            final List<ClassLoaderHandler> classLoaderHandlers = new ArrayList<>();
            for (final Class<? extends ClassLoaderHandler> classLoaderHandlerClass : //
            ClassLoaderHandlerRegistry.DEFAULT_CLASS_LOADER_HANDLERS) {
                try {
                    classLoaderHandlers.add(classLoaderHandlerClass.newInstance());
                } catch (InstantiationException | IllegalAccessException e) {
                    if (log != null) {
                        log.log("Could not instantiate " + classLoaderHandlerClass.getName(), e);
                    }
                }
            }
            // Get all manually-added ClassLoaderHandlers
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

    // -------------------------------------------------------------------------------------------------------------

    /** Used for resolving the classes in the call stack. Requires RuntimePermission("createSecurityManager"). */
    private static CallerResolver CALLER_RESOLVER;

    static {
        try {
            // This can fail if the current SecurityManager does not allow
            // RuntimePermission ("createSecurityManager"):
            CALLER_RESOLVER = new CallerResolver();
        } catch (final SecurityException e) {
            // Ignore
        }
    }

    // Using a SecurityManager gets around the fact that Oracle removed sun.reflect.Reflection.getCallerClass, see:
    // https://www.infoq.com/news/2013/07/Oracle-Removes-getCallerClass
    // http://www.javaworld.com/article/2077344/core-java/find-a-way-out-of-the-classloader-maze.html
    private static final class CallerResolver extends SecurityManager {
        @Override
        protected Class<?>[] getClassContext() {
            return super.getClassContext();
        }
    }

    /** Add all parents of a ClassLoader in top-down order, the same as in the JRE. */
    private static void addAllParentClassloaders(final ClassLoader classLoader,
            final AdditionOrderedSet<ClassLoader> classLoadersSetOut) {
        final ArrayList<ClassLoader> callerClassLoaders = new ArrayList<>();
        for (ClassLoader cl = classLoader; cl != null; cl = cl.getParent()) {
            callerClassLoaders.add(cl);
        }
        // OpenJDK calls classloaders in a top-down order
        for (int i = callerClassLoaders.size() - 1; i >= 0; --i) {
            classLoadersSetOut.add(callerClassLoaders.get(i));
        }
    }

    /** Add all parent ClassLoaders of a class in top-down order, the same as in the JRE. */
    private static void addAllParentClassloaders(final Class<?> klass,
            final AdditionOrderedSet<ClassLoader> classLoadersSetOut) {
        addAllParentClassloaders(klass.getClassLoader(), classLoadersSetOut);
    }

    /** Find all unique classloaders. */
    static List<ClassLoader> findAllClassLoaders(final LogNode log) {
        // Need both a set and a list so we can keep them unique, but in an order that (hopefully) reflects
        // the order in which the JDK calls classloaders.
        //
        // See:
        // https://docs.oracle.com/javase/8/docs/technotes/tools/findingclasses.html
        //
        // N.B. probably need to look more closely at the exact ordering followed here, see:
        // www.javaworld.com/article/2077344/core-java/find-a-way-out-of-the-classloader-maze.html?page=2
        //
        final AdditionOrderedSet<ClassLoader> classLoadersSet = new AdditionOrderedSet<>();
        addAllParentClassloaders(ClassLoader.getSystemClassLoader(), classLoadersSet);
        // Look for classloaders on the call stack
        if (CALLER_RESOLVER != null) {
            final Class<?>[] callStack = CALLER_RESOLVER.getClassContext();
            for (final Class<?> callStackClass : callStack) {
                addAllParentClassloaders(callStackClass, classLoadersSet);
            }
        } else {
            if (log != null) {
                log.log(ClasspathFinder.class.getSimpleName() + " could not create "
                        + CallerResolver.class.getSimpleName() + ", current SecurityManager does not grant "
                        + "RuntimePermission(\"createSecurityManager\")");
            }
        }
        addAllParentClassloaders(Thread.currentThread().getContextClassLoader(), classLoadersSet);
        addAllParentClassloaders(ClasspathFinder.class, classLoadersSet);
        final List<ClassLoader> classLoaders = classLoadersSet.getList();
        classLoaders.remove(null);
        if (log != null) {
            for (final ClassLoader classLoader : classLoaders) {
                log.log("Found ClassLoader " + classLoader.toString());
            }
            log.addElapsedTime();
        }
        return classLoaders;
    }
}
