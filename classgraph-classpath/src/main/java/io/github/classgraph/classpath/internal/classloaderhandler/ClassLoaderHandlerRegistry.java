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
 * Copyright (c) 2026 Luke Hutchison
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
package io.github.classgraph.classpath.internal.classloaderhandler;

import java.util.List;

import io.github.classgraph.base.ClassGraphLog;
import io.github.classgraph.classpath.ClassLoaderHandler;
import io.github.classgraph.classpath.ClassLoaderOrder;
import io.github.classgraph.classpath.ClasspathOrder;
import org.jspecify.annotations.Nullable;

/** The registry for ClassLoaderHandler classes. */
public final class ClassLoaderHandlerRegistry {
    /**
     * The built-in {@link ClassLoaderHandler}s. If a {@link ClassLoaderHandler} is added to ClassGraph, it should
     * be added to this list.
     *
     * <p>
     * The order of this list does not affect the result of a scan: when several handlers can handle the same
     * classloader, the caller keeps only the ones that handle the most specific classloader class, so a handler for
     * a subclass of {@link java.net.URLClassLoader} always wins over the handler for
     * {@link java.net.URLClassLoader} itself. The list is therefore kept in alphabetical order, which is the
     * easiest order to check a handler's presence in.
     *
     * <p>
     * {@code FallbackClassLoaderHandler} is not in this list -- it is registered separately as
     * {@link #FALLBACK_HANDLER}, since it is only used when no other handler can handle a classloader.
     */
    public static final List<ClassLoaderHandlerRegistryEntry> CLASS_LOADER_HANDLERS = List.of(
            new ClassLoaderHandlerRegistryEntry(new AntClassLoaderHandler()),
            new ClassLoaderHandlerRegistryEntry(new CxfContainerClassLoaderHandler()),
            new ClassLoaderHandlerRegistryEntry(new EquinoxClassLoaderHandler()),
            new ClassLoaderHandlerRegistryEntry(new EquinoxContextFinderClassLoaderHandler()),
            new ClassLoaderHandlerRegistryEntry(new FelixClassLoaderHandler()),
            new ClassLoaderHandlerRegistryEntry(new JBossClassLoaderHandler()),
            new ClassLoaderHandlerRegistryEntry(new JPMSClassLoaderHandler()),
            new ClassLoaderHandlerRegistryEntry(new OSGiDefaultClassLoaderHandler()),
            new ClassLoaderHandlerRegistryEntry(new PlexusClassWorldsClassRealmClassLoaderHandler()),
            new ClassLoaderHandlerRegistryEntry(new QuarkusClassLoaderHandler()),
            new ClassLoaderHandlerRegistryEntry(new SpringBootRestartClassLoaderHandler()),
            new ClassLoaderHandlerRegistryEntry(new TomcatWebappClassLoaderBaseHandler()),
            new ClassLoaderHandlerRegistryEntry(new UnoOneJarClassLoaderHandler()),
            new ClassLoaderHandlerRegistryEntry(new URLClassLoaderHandler()),
            new ClassLoaderHandlerRegistryEntry(new WeblogicClassLoaderHandler()),
            new ClassLoaderHandlerRegistryEntry(new WebsphereLibertyClassLoaderHandler()),
            new ClassLoaderHandlerRegistryEntry(new WebsphereTraditionalClassLoaderHandler()));

    /** Fallback ClassLoaderHandler. */
    public static final ClassLoaderHandlerRegistryEntry FALLBACK_HANDLER = new ClassLoaderHandlerRegistryEntry(
            new FallbackClassLoaderHandler());

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Constructor.
     */
    private ClassLoaderHandlerRegistry() {
        // Cannot be constructed
    }

    /**
     * A single registered {@link ClassLoaderHandler}, whether built-in or registered by the user.
     */
    public static final class ClassLoaderHandlerRegistryEntry {
        /** The {@link ClassLoaderHandler} instance. */
        public final ClassLoaderHandler classLoaderHandler;

        /**
         * Constructor.
         *
         * @param classLoaderHandler
         *            the {@link ClassLoaderHandler}.
         */
        public ClassLoaderHandlerRegistryEntry(final ClassLoaderHandler classLoaderHandler) {
            this.classLoaderHandler = classLoaderHandler;
        }

        /**
         * The name of the associated {@link ClassLoaderHandler} class, for logging.
         *
         * @return the fully-qualified class name of the {@link ClassLoaderHandler}.
         */
        public String getHandlerName() {
            return classLoaderHandler.getClass().getName();
        }

        /**
         * The automatic package root prefixes (e.g. {@code "BOOT-INF/classes/"}) that should be looked for, and
         * stripped from resource paths, in classpath elements obtained from the associated
         * {@link ClassLoaderHandler}.
         *
         * @return the package root prefixes.
         */
        public List<String> getPackageRootPrefixes() {
            return classLoaderHandler.getPackageRootPrefixes();
        }

        /**
         * The lib dirs (e.g. {@code "BOOT-INF/lib/"}) whose jarfiles should be added to the classpath, within
         * classpath elements obtained from the associated {@link ClassLoaderHandler}.
         *
         * @return the lib dir prefixes.
         */
        public List<String> getLibDirPrefixes() {
            return classLoaderHandler.getLibDirPrefixes();
        }

        /**
         * Call {@code canHandle(Class, ClassGraphLog)} on the associated {@link ClassLoaderHandler}.
         *
         * @param classLoaderClass
         *            the {@link ClassLoader} class or one of its superclasses.
         * @param log
         *            the log node, or null to skip logging
         * @return true, if this {@link ClassLoaderHandler} can handle the {@link ClassLoader}.
         */
        public boolean canHandle(final Class<?> classLoaderClass, final @Nullable ClassGraphLog log) {
            return classLoaderHandler.canHandle(classLoaderClass, log);
        }

        /**
         * Call {@code findClassLoaderOrder(ClassLoader, ClassLoaderOrder, ClassGraphLog)} on the associated
         * {@link ClassLoaderHandler}.
         *
         * @param classLoader
         *            the {@link ClassLoader}.
         * @param classLoaderOrder
         *            a {@link ClassLoaderOrder} object.
         * @param log
         *            the log node, or null to skip logging
         */
        public void findClassLoaderOrder(final ClassLoader classLoader, final ClassLoaderOrder classLoaderOrder,
                final @Nullable ClassGraphLog log) {
            classLoaderHandler.findClassLoaderOrder(classLoader, classLoaderOrder, log);
        }

        /**
         * Call {@code findClasspathOrder(ClassLoader, ClasspathOrder, ClassGraphLog)} on the associated
         * {@link ClassLoaderHandler}.
         *
         * @param classLoader
         *            the {@link ClassLoader}.
         * @param classpathOrder
         *            a {@link ClasspathOrder} object.
         * @param log
         *            the log node, or null to skip logging
         */
        public void findClasspathOrder(final ClassLoader classLoader, final ClasspathOrder classpathOrder,
                final @Nullable ClassGraphLog log) {
            classLoaderHandler.findClasspathOrder(classLoader, classpathOrder, log);
        }
    }
}
