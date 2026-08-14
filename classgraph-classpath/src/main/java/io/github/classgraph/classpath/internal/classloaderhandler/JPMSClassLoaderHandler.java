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

import io.github.classgraph.base.ClassGraphLog;
import io.github.classgraph.base.internal.reflection.ReflectionUtils;
import io.github.classgraph.classpath.ClassLoaderHandler;
import io.github.classgraph.classpath.ClassLoaderOrder;
import io.github.classgraph.classpath.ClasspathOrder;
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
    public boolean canHandle(final Class<?> classLoaderClass, final @Nullable ClassGraphLog log) {
        return classIsOrExtendsOrImplements(classLoaderClass, "jdk.internal.loader.ClassLoaders$AppClassLoader")
                || classIsOrExtendsOrImplements(classLoaderClass, "jdk.internal.loader.BuiltinClassLoader");
    }

    /**
     * Get the bootstrap classloader.
     *
     * <p>
     * The bootstrap classloader is the parent of the platform classloader, but {@link ClassLoader#getParent()}
     * returns null for the platform classloader, so walking the parent chain never reaches it.
     *
     * @return the bootstrap classloader, or null if it could not be obtained.
     */
    private static @Nullable ClassLoader getBootClassLoader() {
        return (ClassLoader) ReflectionUtils.invokeStaticMethod(false,
                ReflectionUtils.classForNameOrNull("jdk.internal.loader.ClassLoaders"), "bootLoader");
    }

    @Override
    public void findClassLoaderOrder(final ClassLoader classLoader, final ClassLoaderOrder classLoaderOrder,
            final @Nullable ClassGraphLog log) {
        classLoaderOrder.delegateTo(classLoader.getParent(), /* isParent = */ true, log);
        // The bootstrap classloader has a URLClassPath only if the boot classpath was appended to, either with
        // -Xbootclasspath/a or with the Boot-Class-Path attribute of a Java agent's manifest. Those entries are not
        // listed in any system property, and the bootstrap classloader is not reachable through the parent chain,
        // so they are reachable from nowhere else -- splice the bootstrap classloader into the delegation order
        // when it has entries to contribute, so that they are scanned along with everything else.
        final var bootClassLoader = getBootClassLoader();
        if (bootClassLoader != null && bootClassLoader != classLoader
                && URLClassPathReader.getUcp(bootClassLoader) != null) {
            classLoaderOrder.delegateTo(bootClassLoader, /* isParent = */ true, log);
        }
        classLoaderOrder.add(classLoader, log);
    }

    @Override
    public void findClasspathOrder(final ClassLoader classLoader, final ClasspathOrder classpathOrder,
            final @Nullable ClassGraphLog log) {
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
        final var ucp = URLClassPathReader.getUcp(classLoader);
        if (ucp != null) {
            URLClassPathReader.addAllClasspathEntries(ucp, classLoader, classpathOrder, log);
        }
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

    /**
     * Get the automatic lib dirs for classpath elements obtained from this classloader.
     *
     * <p>
     * A module has no lib dir, but the classpath entries that this handler reads from the classloader's
     * {@code URLClassPath} are the ordinary classpath, which can contain a Spring-Boot executable jar or a war
     * whose lib dir is not listed as a classpath element.
     *
     * @return the lib dir prefixes.
     */
    @Override
    public String[] getLibDirPrefixes() {
        return ClassLoaderHandlerRegistry.ARCHIVE_LIB_DIR_PREFIXES;
    }
}
