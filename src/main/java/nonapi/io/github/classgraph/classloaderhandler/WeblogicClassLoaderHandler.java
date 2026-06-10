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

import nonapi.io.github.classgraph.classpath.ClassLoaderFinder;
import nonapi.io.github.classgraph.classpath.ClassLoaderOrder;
import nonapi.io.github.classgraph.classpath.ClasspathOrder;
import nonapi.io.github.classgraph.scanspec.ScanSpec;
import nonapi.io.github.classgraph.utils.LogNode;

/** Extract classpath entries from the Weblogic ClassLoaders. */
class WeblogicClassLoaderHandler implements ClassLoaderHandler {
    @Override
    public boolean canHandle(final Class<?> classLoaderClass, final LogNode log) {
        return ClassLoaderFinder.classIsOrExtendsOrImplements(classLoaderClass,
                "weblogic.utils.classloaders.ChangeAwareClassLoader")
                || ClassLoaderFinder.classIsOrExtendsOrImplements(classLoaderClass,
                        "weblogic.utils.classloaders.GenericClassLoader")
                || ClassLoaderFinder.classIsOrExtendsOrImplements(classLoaderClass,
                        "weblogic.utils.classloaders.FilteringClassLoader")
                // TODO: The following two known classloader names have not been tested, and the fields/methods
                // may not match those of the above classloaders.
                || ClassLoaderFinder.classIsOrExtendsOrImplements(classLoaderClass,
                        "weblogic.servlet.jsp.JspClassLoader")
                || ClassLoaderFinder.classIsOrExtendsOrImplements(classLoaderClass,
                        "weblogic.servlet.jsp.TagFileClassLoader");
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
        classpathOrder.addClasspathPathStr( //
                (String) classpathOrder.reflectionUtils.invokeMethod(false, classLoader, "getFinderClassPath"),
                classLoader, scanSpec, log);
        classpathOrder.addClasspathPathStr( //
                (String) classpathOrder.reflectionUtils.invokeMethod(false, classLoader, "getClassPath"),
                classLoader, scanSpec, log);
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
