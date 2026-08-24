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
import io.github.classgraph.base.internal.utils.VersionFinder;
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
    /** The name of the application classloader's class. */
    private static final String APP_CLASS_LOADER = "jdk.internal.loader.ClassLoaders$AppClassLoader";

    /** Constructor. */
    JPMSClassLoaderHandler() {
    }

    @Override
    public boolean canHandle(final Class<?> classLoaderClass, final @Nullable ClassGraphLog log) {
        return classIsOrExtendsOrImplements(classLoaderClass, APP_CLASS_LOADER)
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
        } else if (APP_CLASS_LOADER.equals(classLoader.getClass().getName())) {
            // The application classloader's URLClassPath could not be read, so fall back to the java.class.path
            // system property, which lists the same entries that URLClassPath was constructed from. Only a Java
            // agent's appended jars are missed, since appendToSystemClassLoaderSearch() does not update the
            // property (#537).
            //
            // This has to happen here rather than after all the classloaders have been visited, because these are
            // the application classloader's own classpath entries, and so must take the application classloader's
            // place in the delegation order. Adding them at the end would put them after the entries of every
            // classloader that delegates to the application classloader, which inverts the masking order: a class
            // on the application classpath would appear to be masked by a copy in a child classloader, when the
            // JVM's parent-first delegation loads the application classloader's copy instead.
            if (log != null) {
                log.log("Could not read the application classloader's URLClassPath, so getting its classpath "
                        + "entries from the java.class.path system property instead");
            }
            classpathOrder.addClasspathPathStr(VersionFinder.getProperty("java.class.path"), classLoader, log);
        }
    }

}
