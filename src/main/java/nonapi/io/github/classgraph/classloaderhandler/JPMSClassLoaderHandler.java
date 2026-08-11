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

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import nonapi.io.github.classgraph.classpath.ClassLoaderOrder;
import nonapi.io.github.classgraph.classpath.ClasspathOrder;
import nonapi.io.github.classgraph.reflection.ReflectionUtils;
import nonapi.io.github.classgraph.scanspec.ScanSpec;
import nonapi.io.github.classgraph.utils.LogNode;
import org.jspecify.annotations.Nullable;

/**
 * A ClassLoaderHandler that matches the JDK's own Java 9+ classloaders. Almost everything these classloaders load
 * comes from modules, which are scanned through the JPMS API rather than as classpath elements, but they can also
 * load classes from a {@code jdk.internal.loader.URLClassPath}, which no public API exposes, so that is read here.
 */
class JPMSClassLoaderHandler implements ClassLoaderHandler {
    /** Constructor. */
    JPMSClassLoaderHandler() {
    }

    @Override
    public boolean canHandle(final Class<?> classLoaderClass, final @Nullable LogNode log) {
        return classIsOrExtendsOrImplements(classLoaderClass, "jdk.internal.loader.ClassLoaders$AppClassLoader")
                || classIsOrExtendsOrImplements(classLoaderClass, "jdk.internal.loader.BuiltinClassLoader");
    }

    /**
     * Get the {@code jdk.internal.loader.URLClassPath} of a classloader.
     *
     * @param classLoader
     *            the classloader.
     * @param reflectionUtils
     *            the reflection utils instance.
     * @return the {@code URLClassPath}, or null if the classloader does not have one, or if the field could not be
     *         read.
     */
    private static @Nullable Object getUcp(final ClassLoader classLoader, final ReflectionUtils reflectionUtils) {
        return reflectionUtils.getFieldVal(false, classLoader, "ucp");
    }

    /**
     * Get the bootstrap classloader.
     *
     * <p>
     * The bootstrap classloader is the parent of the platform classloader, but {@link ClassLoader#getParent()}
     * returns null for the platform classloader, so walking the parent chain never reaches it.
     *
     * @param reflectionUtils
     *            the reflection utils instance.
     * @return the bootstrap classloader, or null if it could not be obtained.
     */
    private static @Nullable ClassLoader getBootClassLoader(final ReflectionUtils reflectionUtils) {
        return (ClassLoader) reflectionUtils.invokeStaticMethod(false,
                reflectionUtils.classForNameOrNull("jdk.internal.loader.ClassLoaders"), "bootLoader");
    }

    @Override
    public void findClassLoaderOrder(final ClassLoader classLoader, final ClassLoaderOrder classLoaderOrder,
            final @Nullable LogNode log) {
        classLoaderOrder.delegateTo(classLoader.getParent(), /* isParent = */ true, log);
        // The bootstrap classloader has a URLClassPath only if the boot classpath was appended to, either with
        // -Xbootclasspath/a or with the Boot-Class-Path attribute of a Java agent's manifest. Those entries are not
        // listed in any system property, and the bootstrap classloader is not reachable through the parent chain,
        // so they are reachable from nowhere else -- splice the bootstrap classloader into the delegation order
        // when it has entries to contribute, so that they are scanned along with everything else.
        final var bootClassLoader = getBootClassLoader(classLoaderOrder.reflectionUtils);
        if (bootClassLoader != null && bootClassLoader != classLoader
                && getUcp(bootClassLoader, classLoaderOrder.reflectionUtils) != null) {
            classLoaderOrder.delegateTo(bootClassLoader, /* isParent = */ true, log);
        }
        classLoaderOrder.add(classLoader, log);
    }

    @Override
    public void findClasspathOrder(final ClassLoader classLoader, final ClasspathOrder classpathOrder,
            final ScanSpec scanSpec, final @Nullable LogNode log) {
        // These classloaders load most of what they load from modules, which are scanned through the JPMS API, not
        // as classpath elements. They can also load classes from a `jdk.internal.loader.URLClassPath ucp` field,
        // and some of what is in that field is reachable in no other way:
        //
        // - The application classloader's URLClassPath starts out holding the java.class.path classpath, which is
        //   also scanned from the system property, so that part is a duplicate. But a Java agent can append to it,
        //   by calling Instrumentation.appendToSystemClassLoaderSearch(JarFile), which reaches ucp.addFile()
        //   through ClassLoaders$AppClassLoader.appendToClassPathForInstrumentation(). The javadoc of
        //   appendToSystemClassLoaderSearch states that this "does not change the value of java.class.path", so an
        //   agent's jars are only listed in the ucp field (#537).
        // - The bootstrap classloader's URLClassPath holds the entries appended to the boot classpath, which are
        //   not listed in any system property either (the JVM stores them in the "saved" property
        //   jdk.boot.class.path.append, which is removed from the properties the application can read).
        //
        // The jdk.internal.loader package is exported to only three modules, and is never opened, so the ucp field
        // cannot be read by standard reflection unless the JVM was launched with --add-opens. Narcissus can read
        // it without that, so these classpath entries are found only when Narcissus is on the classpath.
        addUcpClasspathEntries(getUcp(classLoader, classpathOrder.reflectionUtils), classLoader, classpathOrder,
                scanSpec, log);
    }

    /**
     * Add the classpath entries of a {@code jdk.internal.loader.URLClassPath} to the classpath order.
     *
     * @param ucp
     *            the {@code URLClassPath}, or null if there is none.
     * @param classLoader
     *            the classloader the {@code URLClassPath} was obtained from.
     * @param classpathOrder
     *            the classpath order to add to.
     * @param scanSpec
     *            the scan spec.
     * @param log
     *            the log node, or null to skip logging.
     */
    private static void addUcpClasspathEntries(final @Nullable Object ucp, final ClassLoader classLoader,
            final ClasspathOrder classpathOrder, final ScanSpec scanSpec, final @Nullable LogNode log) {
        if (ucp == null) {
            return;
        }
        final var reflectionUtils = classpathOrder.reflectionUtils;

        // A URLClassPath records its entries in three places, and no one of them holds all of them:
        //
        // - `path` holds the search path the URLClassPath was constructed with, plus everything appended to it
        //   since. getURLs() returns a copy of this.
        // - `unopenedUrls` holds the entries of `path` that have not been opened yet, and additionally the entries
        //   expanded from the Class-Path manifest attribute of jars that have been opened. The expansions are
        //   never added to `path`.
        // - `lmap` is keyed by the entries that have been opened, including the Class-Path expansions, which are
        //   removed from `unopenedUrls` as they are opened.
        //
        // A Class-Path expansion is therefore listed in `unopenedUrls` before it is opened and in `lmap`
        // afterwards, so all three have to be read to see everything. (ClassGraph expands the Class-Path manifest
        // attribute itself, so in practice the expansions are duplicates of entries it already has -- they are
        // read here so that nothing is missed if a URLClassPath is given entries by some other route.)
        classpathOrder.addClasspathEntryObject(reflectionUtils.invokeMethod(false, ucp, "getURLs"), classLoader,
                scanSpec, log);

        // The JDK adds to and removes from this deque while holding the deque's own monitor, so hold it too
        final var unopenedUrls = reflectionUtils.getFieldVal(false, ucp, "unopenedUrls");
        if (unopenedUrls instanceof final Collection<?> unopenedUrlsCollection) {
            final List<Object> unopenedUrlsCopy;
            synchronized (unopenedUrls) {
                unopenedUrlsCopy = new ArrayList<>(unopenedUrlsCollection);
            }
            classpathOrder.addClasspathEntryObject(unopenedUrlsCopy, classLoader, scanSpec, log);
        }

        // The JDK adds to this map while holding the URLClassPath's own monitor, so hold that too. (These two
        // monitors are never held at the same time here, so there is no lock ordering to get wrong.)
        final List<Object> openedUrls = new ArrayList<>();
        synchronized (ucp) {
            if (reflectionUtils.getFieldVal(false, ucp, "lmap") instanceof final Map<?, ?> lmap) {
                openedUrls.addAll(lmap.keySet());
            }
        }
        classpathOrder.addClasspathEntryObject(openedUrls, classLoader, scanSpec, log);
    }

    /**
     * Get the automatic package root prefixes for classpath elements obtained from this classloader.
     *
     * <p>
     * Modules always have their classes at the root of the module.
     *
     * @return the package root prefixes.
     */
    @Override
    public String[] getPackageRootPrefixes() {
        return ClassLoaderHandlerRegistry.NO_PACKAGE_ROOT_PREFIXES;
    }
}
