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

import io.github.classgraph.base.internal.reflection.ReflectionUtils;
import io.github.classgraph.base.internal.utils.LogNode;
import io.github.classgraph.classpath.internal.ClassLoaderOrder;
import io.github.classgraph.classpath.internal.ClasspathOrder;
import io.github.classgraph.classpath.internal.spec.ClasspathSpec;
import org.jspecify.annotations.Nullable;

/** Extract classpath entries from the Weblogic ClassLoaders. */
class WeblogicClassLoaderHandler implements ClassLoaderHandler {
    /** Constructor. */
    WeblogicClassLoaderHandler() {
    }

    @Override
    public boolean canHandle(final Class<?> classLoaderClass, final @Nullable LogNode log) {
        return classIsOrExtendsOrImplements(classLoaderClass, "weblogic.utils.classloaders.ChangeAwareClassLoader")
                || classIsOrExtendsOrImplements(classLoaderClass, "weblogic.utils.classloaders.GenericClassLoader")
                || classIsOrExtendsOrImplements(classLoaderClass,
                        "weblogic.utils.classloaders.FilteringClassLoader")
                // The two JSP classloaders below cannot be tested without a WebLogic install, so it is not known
                // whether they expose the same two methods. Listing them anyway costs nothing: findClasspathOrder
                // looks the methods up reflectively without throwing, so a name that is not present simply adds no
                // classpath entries, and every other handler that matches the classloader still runs.
                || classIsOrExtendsOrImplements(classLoaderClass, "weblogic.servlet.jsp.JspClassLoader")
                || classIsOrExtendsOrImplements(classLoaderClass, "weblogic.servlet.jsp.TagFileClassLoader");
    }

    @Override
    public void findClassLoaderOrder(final ClassLoader classLoader, final ClassLoaderOrder classLoaderOrder,
            final @Nullable LogNode log) {
        classLoaderOrder.delegateTo(classLoader.getParent(), /* isParent = */ true, log);
        classLoaderOrder.add(classLoader, log);
    }

    @Override
    public void findClasspathOrder(final ClassLoader classLoader, final ClasspathOrder classpathOrder,
            final ClasspathSpec classpathSpec, final @Nullable LogNode log) {
        classpathOrder.addClasspathPathStr( //
                (String) ReflectionUtils.invokeMethod(false, classLoader, "getFinderClassPath"), classLoader,
                classpathSpec, log);
        classpathOrder.addClasspathPathStr( //
                (String) ReflectionUtils.invokeMethod(false, classLoader, "getClassPath"), classLoader,
                classpathSpec, log);
    }

    /**
     * Get the automatic package root prefixes for classpath elements obtained from this classloader.
     *
     * <p>
     * Classpath elements from this classloader may be Spring-Boot executable jars or wars.
     *
     * @return the package root prefixes.
     */
    @Override
    public String[] getPackageRootPrefixes() {
        return ClassLoaderHandlerRegistry.DEFAULT_PACKAGE_ROOT_PREFIXES;
    }
}
