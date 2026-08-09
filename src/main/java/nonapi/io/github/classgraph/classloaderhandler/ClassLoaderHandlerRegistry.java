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
package nonapi.io.github.classgraph.classloaderhandler;

import java.util.List;

import nonapi.io.github.classgraph.classpath.ClassLoaderOrder;
import nonapi.io.github.classgraph.classpath.ClasspathOrder;
import nonapi.io.github.classgraph.scanspec.ScanSpec;
import nonapi.io.github.classgraph.utils.LogNode;
import org.jspecify.annotations.Nullable;

/** The registry for ClassLoaderHandler classes. */
public final class ClassLoaderHandlerRegistry {
    /**
     * Default ClassLoaderHandlers. If a ClassLoaderHandler is added to ClassGraph,
     * it should be added to this list.
     */
    public static final List<ClassLoaderHandlerRegistryEntry> CLASS_LOADER_HANDLERS = List.of(
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

            // JPMS support (this handler does nothing, since modules are handled
            // separately)
            new ClassLoaderHandlerRegistryEntry(new JPMSClassLoaderHandler()),

            // URLClassLoader support (should be second-to-last, so that subclasses of
            // URLClassLoader are handled by more specific handlers above)
            new ClassLoaderHandlerRegistryEntry(new URLClassLoaderHandler()),

            // Placeholder for delegation to a ClassGraphClassLoader instance from an outer
            // nested scan
            new ClassLoaderHandlerRegistryEntry(new ClassGraphClassLoaderHandler())

            // FallbackClassLoaderHandler.class is registered separately below
            );

    /** Fallback ClassLoaderHandler. */
    public static final ClassLoaderHandlerRegistryEntry FALLBACK_HANDLER = new ClassLoaderHandlerRegistryEntry(
            new FallbackClassLoaderHandler());

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Lib dirs whose jars should be added to the classpath automatically (to
     * compensate for some classloaders not explicitly listing these jars as
     * classpath elements).
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
     * The package root prefixes for classpath elements that have no automatic
     * package roots at all.
     */
    public static final String[] NO_PACKAGE_ROOT_PREFIXES = {};

    /**
     * The package root prefixes of the standard packaged-archive layouts:
     * Spring-Boot executable jars, and wars.
     *
     * <p>
     * These are safe to look for in any classpath element, whatever classloader it
     * came from, because neither {@code "BOOT-INF"} nor {@code "WEB-INF"} can ever
     * be a real package name -- a hyphen is not a legal character in a Java
     * identifier, so a directory with one of these names is unambiguously a package
     * root rather than a package.
     */
    public static final String[] ARCHIVE_PACKAGE_ROOT_PREFIXES = {
            // Spring-Boot
            "BOOT-INF/classes/",
            // War files
            "WEB-INF/classes/" };

    /**
     * The package root prefixes to look for in classpath elements from a
     * general-purpose classloader, which could have been handed a classpath element
     * in any of the common build-tool or packaged-archive layouts.
     *
     * <p>
     * Note that unlike {@link #ARCHIVE_PACKAGE_ROOT_PREFIXES}, {@code "classes"}
     * and {@code "test-classes"} are both legal Java package names, so treating
     * them as automatic package roots is a heuristic, not a certainty: a real
     * package named {@code classes} is misread as a package root, and its classes
     * are silently dropped. The heuristic is nevertheless relied upon for
     * general-purpose classloaders -- see {@code Issue420Test} and
     * {@code Issue766Test} -- so it can only be removed once package roots are
     * verified against the declared name of a classfile found beneath them, rather
     * than assumed from the directory name.
     */
    // #929
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
     * A list of fully-qualified ClassLoader class names paired with the
     * ClassLoaderHandler that can handle them.
     */
    public static final class ClassLoaderHandlerRegistryEntry {
        /** The {@link ClassLoaderHandler} instance. */
        public final ClassLoaderHandler classLoaderHandler;

        /** The package root prefixes for classpath elements found by this handler. */
        private final String[] packageRootPrefixes;

        /**
         * Constructor.
         *
         * @param classLoaderHandler The ClassLoaderHandler class.
         */
        private ClassLoaderHandlerRegistryEntry(final ClassLoaderHandler classLoaderHandler) {
            this.classLoaderHandler = classLoaderHandler;
            this.packageRootPrefixes = classLoaderHandler.getPackageRootPrefixes();
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
         * The automatic package root prefixes (e.g. {@code "BOOT-INF/classes/"}) that
         * should be looked for, and stripped from resource paths, in classpath elements
         * obtained from the associated {@link ClassLoaderHandler}.
         *
         * @return the package root prefixes.
         */
        public String[] getPackageRootPrefixes() {
            return packageRootPrefixes;
        }

        /**
         * Call {@code canHandle(Class, LogNode)} on the associated
         * {@link ClassLoaderHandler}.
         *
         * @param classLoader the {@link ClassLoader}.
         * @param log         the log.
         * @return true, if this {@link ClassLoaderHandler} can handle the
         *         {@link ClassLoader}.
         */
        public boolean canHandle(final Class<?> classLoader, final @Nullable LogNode log) {
            return classLoaderHandler.canHandle(classLoader, log);
        }

        /**
         * Call {@code findClassLoaderOrder(ClassLoader, ClassLoaderOrder, LogNode)} on
         * the associated {@link ClassLoaderHandler}.
         *
         * @param classLoader      the {@link ClassLoader}.
         * @param classLoaderOrder a {@link ClassLoaderOrder} object.
         * @param log              the log
         */
        public void findClassLoaderOrder(final ClassLoader classLoader, final ClassLoaderOrder classLoaderOrder,
                final @Nullable LogNode log) {
            classLoaderHandler.findClassLoaderOrder(classLoader, classLoaderOrder, log);
        }

        /**
         * Call
         * {@code findClasspathOrder(ClassLoader, ClasspathOrder, ScanSpec, LogNode)} on
         * the associated {@link ClassLoaderHandler}.
         *
         * @param classLoader    the {@link ClassLoader}.
         * @param classpathOrder a {@link ClasspathOrder} object.
         * @param scanSpec       the {@link ScanSpec}.
         * @param log            the log.
         */
        public void findClasspathOrder(final ClassLoader classLoader, final ClasspathOrder classpathOrder,
                final ScanSpec scanSpec, final @Nullable LogNode log) {
            classLoaderHandler.findClasspathOrder(classLoader, classpathOrder, scanSpec, log);
        }
    }
}
