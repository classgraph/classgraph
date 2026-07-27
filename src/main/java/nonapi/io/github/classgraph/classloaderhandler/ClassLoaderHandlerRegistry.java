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

import java.lang.reflect.Method;
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
                    new ClassLoaderHandlerRegistryEntry(AntClassLoaderHandler.class),
                    new ClassLoaderHandlerRegistryEntry(EquinoxClassLoaderHandler.class),
                    new ClassLoaderHandlerRegistryEntry(EquinoxContextFinderClassLoaderHandler.class),
                    new ClassLoaderHandlerRegistryEntry(FelixClassLoaderHandler.class),
                    new ClassLoaderHandlerRegistryEntry(JBossClassLoaderHandler.class),
                    new ClassLoaderHandlerRegistryEntry(WeblogicClassLoaderHandler.class),
                    new ClassLoaderHandlerRegistryEntry(WebsphereLibertyClassLoaderHandler.class),
                    new ClassLoaderHandlerRegistryEntry(WebsphereTraditionalClassLoaderHandler.class),
                    new ClassLoaderHandlerRegistryEntry(OSGiDefaultClassLoaderHandler.class),
                    new ClassLoaderHandlerRegistryEntry(SpringBootRestartClassLoaderHandler.class),
                    new ClassLoaderHandlerRegistryEntry(TomcatWebappClassLoaderBaseHandler.class),
                    new ClassLoaderHandlerRegistryEntry(CxfContainerClassLoaderHandler.class),
                    new ClassLoaderHandlerRegistryEntry(PlexusClassWorldsClassRealmClassLoaderHandler.class),
                    new ClassLoaderHandlerRegistryEntry(QuarkusClassLoaderHandler.class),
                    new ClassLoaderHandlerRegistryEntry(UnoOneJarClassLoaderHandler.class),

                    // For unit testing of PARENT_LAST delegation order
                    new ClassLoaderHandlerRegistryEntry(ParentLastDelegationOrderTestClassLoaderHandler.class),

                    // JPMS support (this handler does nothing, since modules are handled separately)
                    new ClassLoaderHandlerRegistryEntry(JPMSClassLoaderHandler.class),

                    // Java 7/8 URLClassLoader support (should be second-to-last, so that subclasses of
                    // URLClassLoader are handled by more specific handlers above)
                    new ClassLoaderHandlerRegistryEntry(URLClassLoaderHandler.class),

                    // Placeholder for delegation to a ClassGraphClassLoader instance from an outer nested scan
                    new ClassLoaderHandlerRegistryEntry(ClassGraphClassLoaderHandler.class)

            // FallbackClassLoaderHandler.class is registered separately below
            ));

    /** Fallback ClassLoaderHandler. */
    public static final ClassLoaderHandlerRegistryEntry FALLBACK_HANDLER = new ClassLoaderHandlerRegistryEntry(
            FallbackClassLoaderHandler.class);

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
     * The package root prefixes for classpath elements that have no automatic package roots at all.
     */
    public static final String[] NO_PACKAGE_ROOT_PREFIXES = {};

    /**
     * The package root prefixes of the standard packaged-archive layouts: Spring-Boot executable jars, and wars.
     *
     * <p>
     * These are safe to look for in any classpath element, whatever classloader it came from, because neither
     * {@code "BOOT-INF"} nor {@code "WEB-INF"} can ever be a real package name -- a hyphen is not a legal character
     * in a Java identifier, so a directory with one of these names is unambiguously a package root rather than a
     * package.
     */
    public static final String[] ARCHIVE_PACKAGE_ROOT_PREFIXES = {
            // Spring-Boot
            "BOOT-INF/classes/",
            // War files
            "WEB-INF/classes/" };

    /**
     * The package root prefixes to look for in classpath elements from a general-purpose classloader, which could
     * have been handed a classpath element in any of the common build-tool or packaged-archive layouts.
     *
     * <p>
     * Note that unlike {@link #ARCHIVE_PACKAGE_ROOT_PREFIXES}, {@code "classes"} and {@code "test-classes"} are
     * both legal Java package names, so treating them as automatic package roots is a heuristic, not a certainty:
     * a real package named {@code classes} is misread as a package root, and its classes are silently dropped
     * (#929). The heuristic is nevertheless load-bearing for general-purpose classloaders -- see
     * {@code Issue420Test} and {@code Issue766Test} -- so it can only be removed once package roots are verified
     * against the declared name of a classfile found beneath them, rather than assumed from the directory name.
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
     * A list of fully-qualified ClassLoader class names paired with the ClassLoaderHandler that can handle them.
     */
    public static class ClassLoaderHandlerRegistryEntry {
        /** canHandle method. */
        private final Method canHandleMethod;

        /** findClassLoaderOrder method. */
        private final Method findClassLoaderOrderMethod;

        /** findClasspathOrder method. */
        private final Method findClasspathOrderMethod;

        /** The package root prefixes for classpath elements found by this handler. */
        private final String[] packageRootPrefixes;

        /** The ClassLoaderHandler class. */
        public final Class<? extends ClassLoaderHandler> classLoaderHandlerClass;

        /**
         * Constructor.
         *
         * @param classLoaderHandlerClass
         *            The ClassLoaderHandler class.
         */
        private ClassLoaderHandlerRegistryEntry(final Class<? extends ClassLoaderHandler> classLoaderHandlerClass) {
            // TODO: replace these with MethodHandles for speed
            // TODO: (although MethodHandles are disabled for now, due to Animal Sniffer bug):
            // https://github.com/mojohaus/animal-sniffer/issues/67
            this.classLoaderHandlerClass = classLoaderHandlerClass;
            try {
                canHandleMethod = classLoaderHandlerClass.getDeclaredMethod("canHandle", Class.class,
                        LogNode.class);
            } catch (final Exception e) {
                throw new RuntimeException(
                        "Could not find canHandle method for " + classLoaderHandlerClass.getName(), e);
            }
            try {
                findClassLoaderOrderMethod = classLoaderHandlerClass.getDeclaredMethod("findClassLoaderOrder",
                        ClassLoader.class, ClassLoaderOrder.class, LogNode.class);
            } catch (final Exception e) {
                throw new RuntimeException(
                        "Could not find findClassLoaderOrder method for " + classLoaderHandlerClass.getName(), e);
            }
            try {
                findClasspathOrderMethod = classLoaderHandlerClass.getDeclaredMethod("findClasspathOrder",
                        ClassLoader.class, ClasspathOrder.class, ScanSpec.class, LogNode.class);
            } catch (final Exception e) {
                throw new RuntimeException(
                        "Could not find findClasspathOrder method for " + classLoaderHandlerClass.getName(), e);
            }
            try {
                packageRootPrefixes = (String[]) classLoaderHandlerClass
                        .getDeclaredMethod("getPackageRootPrefixes").invoke(null);
            } catch (final Exception e) {
                throw new RuntimeException(
                        "Could not call getPackageRootPrefixes method for " + classLoaderHandlerClass.getName(), e);
            }
        }

        /**
         * The automatic package root prefixes (e.g. {@code "BOOT-INF/classes/"}) that should be looked for, and
         * stripped from resource paths, in classpath elements obtained from the associated
         * {@link ClassLoaderHandler}.
         *
         * @return the package root prefixes.
         */
        public String[] getPackageRootPrefixes() {
            return packageRootPrefixes;
        }

        /**
         * Call the static method canHandle(ClassLoader) for the associated {@link ClassLoaderHandler}.
         *
         * @param classLoader
         *            the {@link ClassLoader}.
         * @param log
         *            the log.
         * @return true, if this {@link ClassLoaderHandler} can handle the {@link ClassLoader}.
         */
        public boolean canHandle(final Class<?> classLoader, final LogNode log) {
            try {
                return (boolean) canHandleMethod.invoke(null, classLoader, log);
            } catch (final Throwable e) {
                throw new RuntimeException(
                        "Exception while calling canHandle for " + classLoaderHandlerClass.getName(), e);
            }
        }

        /**
         * Call the static method findClassLoaderOrder(ClassLoader, ClassLoaderOrder) for the associated
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
            try {
                findClassLoaderOrderMethod.invoke(null, classLoader, classLoaderOrder, log);
            } catch (final Throwable e) {
                throw new RuntimeException(
                        "Exception while calling findClassLoaderOrder for " + classLoaderHandlerClass.getName(), e);
            }
        }

        /**
         * Call the static method findClasspathOrder(ClassLoader, ClasspathOrder) for the associated
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
            try {
                findClasspathOrderMethod.invoke(null, classLoader, classpathOrder, scanSpec, log);
            } catch (final Throwable e) {
                throw new RuntimeException(
                        "Exception while calling findClassLoaderOrder for " + classLoaderHandlerClass.getName(), e);
            }
        }
    }
}
