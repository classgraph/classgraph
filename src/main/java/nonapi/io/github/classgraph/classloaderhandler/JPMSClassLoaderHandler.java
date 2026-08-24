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

import java.net.URL;

import nonapi.io.github.classgraph.classpath.ClassLoaderFinder;
import nonapi.io.github.classgraph.classpath.ClassLoaderOrder;
import nonapi.io.github.classgraph.classpath.ClasspathOrder;
import nonapi.io.github.classgraph.scanspec.ScanSpec;
import nonapi.io.github.classgraph.utils.LogNode;
import nonapi.io.github.classgraph.utils.VersionFinder;

/**
 * A ClassLoaderHandler that matches the Java 9+ builtin classloaders. Modules are scanned through the JPMS API
 * rather than as classpath elements, but these classloaders also load classes from a
 * {@code jdk.internal.loader.URLClassPath}, which no public API exposes, so that is read here.
 */
class JPMSClassLoaderHandler implements ClassLoaderHandler {
    /** The name of the application classloader's class. */
    private static final String APP_CLASS_LOADER = "jdk.internal.loader.ClassLoaders$AppClassLoader";

    /** Constructor. */
    JPMSClassLoaderHandler() {
    }

    @Override
    public boolean canHandle(final Class<?> classLoaderClass, final LogNode log) {
        return ClassLoaderFinder.classIsOrExtendsOrImplements(classLoaderClass, APP_CLASS_LOADER)
                || ClassLoaderFinder.classIsOrExtendsOrImplements(classLoaderClass,
                        "jdk.internal.loader.BuiltinClassLoader");
    }

    @Override
    public void findClassLoaderOrder(final ClassLoader classLoader, final ClassLoaderOrder classLoaderOrder,
            final LogNode log) {
        classLoaderOrder.delegateTo(classLoader.getParent(), /* isParent = */ true, log);
        classLoaderOrder.add(classLoader, log);
    }

    @Override
    public void findClasspathOrder(final ClassLoader classLoader, final ClasspathOrder classpathOrder,
            final ScanSpec scanSpec, final LogNode log) {
        // The JDK9 classloaders have a field, `URLClassPath ucp`, containing URLs for unnamed modules,
        // but it is not visible. Modules therefore have to be scanned using the JPMS API.
        // However, a Java agent can append to the `ucp` field by calling
        // Instrumentation#appendToSystemClassLoaderSearch(JarFile), which reaches ucp.addFile() through
        // ClassLoaders$AppClassLoader#appendToClassPathForInstrumentation(). The javadoc of
        // appendToSystemClassLoaderSearch states that this "does not change the value of
        // java.class.path", so the agent's jars are listed only in the `ucp` field (#537). Reading it
        // needs Narcissus to break Java's encapsulation, for this small corner case.
        final Object ucpVal = classpathOrder.reflectionUtils.getFieldVal(false, classLoader, "ucp");
        if (ucpVal != null) {
            final URL[] urls = (URL[]) classpathOrder.reflectionUtils.invokeMethod(false, ucpVal, "getURLs");
            classpathOrder.addClasspathEntryObject(urls, classLoader, scanSpec, log);
        } else if (APP_CLASS_LOADER.equals(classLoader.getClass().getName())) {
            // The application classloader's `ucp` field could not be read, so fall back to the java.class.path
            // system property, which lists the same entries that the URLClassPath was constructed from. Only a
            // Java agent's appended jars are missed, since appendToSystemClassLoaderSearch() does not update the
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
            classpathOrder.addClasspathPathStr(VersionFinder.getProperty("java.class.path"), classLoader, scanSpec,
                    log);
        }
    }

    /**
     * Get the automatic package root prefixes for classpath elements obtained from this classloader.
     *
     * <p>
     * Modules are scanned through the JPMS API rather than as classpath elements, so the only classpath elements
     * this handler contributes are the jarfiles a Java agent appended to the system classloader's search. Those are
     * ordinary jarfiles, which can be in any of the layouts a general-purpose classloader can be handed.
     *
     * @return the package root prefixes.
     */
    @Override
    public String[] getPackageRootPrefixes() {
        return ClassLoaderHandlerRegistry.DEFAULT_PACKAGE_ROOT_PREFIXES;
    }
}
