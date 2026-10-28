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
package nonapi.io.github.classgraph.classloaderhandler;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import nonapi.io.github.classgraph.classpath.ClassLoaderOrder;
import nonapi.io.github.classgraph.classpath.ClasspathOrder;
import nonapi.io.github.classgraph.scanspec.ScanSpec;
import nonapi.io.github.classgraph.utils.LogNode;

/** The registry for ClassLoaderHandler classes. */
public class ClassLoaderHandlerRegistry {
    /**
     * Default ClassLoaderHandlers. If a ClassLoaderHandler is added to ClassGraph, it should be added to this list.
     */
    @SuppressWarnings("null")
    public static final List<ClassLoaderHandlerRegistryEntry> CLASS_LOADER_HANDLERS = //
            Collections.unmodifiableList(Arrays.asList(
                    // ClassLoaderHandlers for other ClassLoaders that are handled by ClassGraph
                    new ClassLoaderHandlerRegistryEntry(new AntClassLoaderHandler()),
                    new ClassLoaderHandlerRegistryEntry(new EquinoxClassLoaderHandler()),
                    new ClassLoaderHandlerRegistryEntry(new EquinoxContextFinderClassLoaderHandler()),
                    new ClassLoaderHandlerRegistryEntry(new FelixClassLoaderHandler()),
                    new ClassLoaderHandlerRegistryEntry(new JBossClassLoaderHandler()),
                    new ClassLoaderHandlerRegistryEntry(new WeblogicClassLoaderHandler()),
                    new ClassLoaderHandlerRegistryEntry(new WebsphereLibertyClassLoaderHandler()),
                    new ClassLoaderHandlerRegistryEntry(new WebsphereTraditionalClassLoaderHandler()),
                    new ClassLoaderHandlerRegistryEntry(new OSGiDefaultClassLoaderHandler()),
                    new ClassLoaderHandlerRegistryEntry(new SpringBootRestartClassLoaderHandler()),
                    new ClassLoaderHandlerRegistryEntry(new TomcatWebappClassLoaderBaseHandler()),
                    new ClassLoaderHandlerRegistryEntry(new CxfContainerClassLoaderHandler()),
                    new ClassLoaderHandlerRegistryEntry(new PlexusClassWorldsClassRealmClassLoaderHandler()),
                    new ClassLoaderHandlerRegistryEntry(new QuarkusClassLoaderHandler()),
                    new ClassLoaderHandlerRegistryEntry(new UnoOneJarClassLoaderHandler()),

                    // For unit testing of PARENT_LAST delegation order
                    new ClassLoaderHandlerRegistryEntry(new ParentLastDelegationOrderTestClassLoaderHandler()),

                    // JPMS support (this handler does nothing, since modules are handled separately)
                    new ClassLoaderHandlerRegistryEntry(new JPMSClassLoaderHandler()),

                    // Java 7/8 URLClassLoader support (should be second-to-last, so that subclasses of
                    // URLClassLoader are handled by more specific handlers above)
                    new ClassLoaderHandlerRegistryEntry(new URLClassLoaderHandler()),

                    // Placeholder for delegation to a ClassGraphClassLoader instance from an outer nested scan
                    new ClassLoaderHandlerRegistryEntry(new ClassGraphClassLoaderHandler())

            // FallbackClassLoaderHandler.class is registered separately below
            ));

    /** Fallback ClassLoaderHandler. */
    public static final ClassLoaderHandlerRegistryEntry FALLBACK_HANDLER = new ClassLoaderHandlerRegistryEntry(
            new FallbackClassLoaderHandler());

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Lib dirs whose jars should be added to the classpath automatically (to compensate for some classloaders not
     * explicitly listing these jars as classpath elements).
     */
    public static final String[] AUTOMATIC_LIB_DIR_PREFIXES = {
            // Spring-Boot
            // https://docs.spring.io/spring-boot/docs/2.3.0.RELEASE/reference/html/appendix-executable-jar-format.html
            "BOOT-INF/lib/",
            // Tomcat
            "WEB-INF/lib/", "WEB-INF/lib-provided/",
            // OSGi
            "META-INF/lib/",
            // Tomcat and others
            "lib/",
            // Extension dir
            "lib/ext/",
            // UnoJar and One-Jar
            "main/" //
    };

    /**
     * The package root prefixes for classpath elements that have no automatic package roots.
     */
    public static final String[] NO_PACKAGE_ROOT_PREFIXES = {};

    /**
     * The package root prefixes for packaged archives (Spring-Boot executable jars and WAR files).
     */
    public static final String[] ARCHIVE_PACKAGE_ROOT_PREFIXES = {
            // Spring-Boot
            "BOOT-INF/classes/",
            // War files
            "WEB-INF/classes/" };

    /**
     * The default package root prefixes for general-purpose classloaders, including build tool output directories
     * and packaged archives.
     */
    public static final String[] DEFAULT_PACKAGE_ROOT_PREFIXES = {
            // Ant, Maven, Gradle and other build tool output dirs
            "classes/", "test-classes/",
            // Spring-Boot
            "BOOT-INF/classes/",
            // War files
            "WEB-INF/classes/" };

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Constructor.
     */
    private ClassLoaderHandlerRegistry() {
        // Cannot be constructed
    }

    /**
     * A registry entry for a {@link ClassLoaderHandler}.
     */
    public static class ClassLoaderHandlerRegistryEntry {
        /** The {@link ClassLoaderHandler} instance. */
        public final ClassLoaderHandler classLoaderHandler;

        /**
         * Constructor.
         *
         * @param classLoaderHandler
         *            the {@link ClassLoaderHandler}.
         */
        private ClassLoaderHandlerRegistryEntry(final ClassLoaderHandler classLoaderHandler) {
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
         * Get the automatic package root prefixes to strip from resource paths in classpath elements obtained from
         * the associated {@link ClassLoaderHandler}.
         *
         * @return the package root prefixes.
         */
        public String[] getPackageRootPrefixes() {
            // Do not read this in the constructor and cache it: the entries are constructed by the initializer
            // of CLASS_LOADER_HANDLERS, which runs before the prefix constants declared below it are assigned,
            // so a handler that returns one of those constants would have null cached for it
            return classLoaderHandler.getPackageRootPrefixes();
        }

        /**
         * Call {@code canHandle(Class, LogNode)} on the associated {@link ClassLoaderHandler}.
         *
         * @param classLoader
         *            the {@link ClassLoader}.
         * @param log
         *            the log.
         * @return true, if this {@link ClassLoaderHandler} can handle the {@link ClassLoader}.
         */
        public boolean canHandle(final Class<?> classLoader, final LogNode log) {
            return classLoaderHandler.canHandle(classLoader, log);
        }

        /**
         * Call {@code findClassLoaderOrder(ClassLoader, ClassLoaderOrder, LogNode)} on the associated
         * {@link ClassLoaderHandler}.
         *
         * @param classLoader
         *            the {@link ClassLoader}.
         * @param classLoaderOrder
         *            a {@link ClassLoaderOrder} object.
         * @param log
         *            the log
         */
        public void findClassLoaderOrder(final ClassLoader classLoader, final ClassLoaderOrder classLoaderOrder,
                final LogNode log) {
            classLoaderHandler.findClassLoaderOrder(classLoader, classLoaderOrder, log);
        }

        /**
         * Call {@code findClasspathOrder(ClassLoader, ClasspathOrder, ScanSpec, LogNode)} on the associated
         * {@link ClassLoaderHandler}.
         *
         * @param classLoader
         *            the {@link ClassLoader}.
         * @param classpathOrder
         *            a {@link ClasspathOrder} object.
         * @param scanSpec
         *            the {@link ScanSpec}.
         * @param log
         *            the log.
         */
        public void findClasspathOrder(final ClassLoader classLoader, final ClasspathOrder classpathOrder,
                final ScanSpec scanSpec, final LogNode log) {
            classLoaderHandler.findClasspathOrder(classLoader, classpathOrder, scanSpec, log);
        }
    }
}
