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
 * Copyright (c) 2021 Luke Hutchison
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

/** ClassLoaderHandler that is able to extract the URLs from a CxfContainerClassLoader. */
class CxfContainerClassLoaderHandler implements ClassLoaderHandler {
    @Override
    public boolean canHandle(Class<?> classLoaderClass, LogNode log) {
        return ClassLoaderFinder.classIsOrExtendsOrImplements(classLoaderClass,
                "org.apache.openejb.server.cxf.transport.util.CxfContainerClassLoader");
    }

    @Override
    public void findClassLoaderOrder(ClassLoader classLoader, ClassLoaderOrder classLoaderOrder, LogNode log) {
        try {
            classLoaderOrder.delegateTo(
                    Class.forName("org.apache.openejb.server.cxf.transport.util.CxfUtil").getClassLoader(),
                    /* isParent = */ true, log);
        } catch (LinkageError | ClassNotFoundException e) {
            // Ignore
        }
        // tccl = TomcatClassLoader
        classLoaderOrder.delegateTo(
                (ClassLoader) classLoaderOrder.reflectionUtils.invokeMethod(false, classLoader, "tccl"),
                /* isParent = */ false, log);
        // This classloader doesn't actually load any classes, but add it to the order to improve logging
        classLoaderOrder.add(classLoader, log);
    }

    @Override
    public void findClasspathOrder(ClassLoader classLoader, ClasspathOrder classpathOrder, ScanSpec scanSpec, LogNode log) {
        // Classloader doesn't do any classloading of its own, it only delegates to other classloaders
    }
}
