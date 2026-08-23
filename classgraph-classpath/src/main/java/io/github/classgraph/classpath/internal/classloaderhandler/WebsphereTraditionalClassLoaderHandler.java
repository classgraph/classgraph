/*
 * This file is part of ClassGraph.
 *
 * Author: Sergey Bespalov
 *
 * Hosted at: https://github.com/classgraph/classgraph
 *
 * --
 *
 * The MIT License (MIT)
 *
 * Copyright (c) 2017 Sergey Bespalov
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
import io.github.classgraph.classpath.ClasspathOrder;
import org.jspecify.annotations.Nullable;

/**
 * Handle the WebSphere traditional ClassLoaders.
 *
 * <p>
 * {@code com.ibm.ws.bootstrap.ExtClassLoader} extends {@link java.net.URLClassLoader} -- its {@code findClass()}
 * calls {@code super.findClass()} -- so its URLs are read as well as the classpath string that all of these
 * classloaders expose.
 *
 * @author Luke Hutchison
 */
class WebsphereTraditionalClassLoaderHandler extends URLClassLoaderHandler {
    /** Constructor. */
    WebsphereTraditionalClassLoaderHandler() {
    }

    @Override
    public boolean canHandle(final Class<?> classLoaderClass, final @Nullable ClassGraphLog log) {
        return classIsOrExtendsOrImplements(classLoaderClass, "com.ibm.ws.classloader.CompoundClassLoader")
                || classIsOrExtendsOrImplements(classLoaderClass, "com.ibm.ws.classloader.ProtectionClassLoader")
                || classIsOrExtendsOrImplements(classLoaderClass, "com.ibm.ws.bootstrap.ExtClassLoader");
    }

    @Override
    public void findClasspathOrder(final ClassLoader classLoader, final ClasspathOrder classpathOrder,
            final @Nullable ClassGraphLog log) {
        final var classpath = (String) ReflectionUtils.invokeMethod(false, classLoader, "getClassPath");
        classpathOrder.addClasspathPathStr(classpath, classLoader, log);
        // ExtClassLoader is a URLClassLoader, so add its URLs too
        super.findClasspathOrder(classLoader, classpathOrder, log);
    }

}
