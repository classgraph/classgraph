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

import java.net.URL;

import nonapi.io.github.classgraph.classpath.ClassLoaderFinder;
import nonapi.io.github.classgraph.classpath.ClassLoaderOrder;
import nonapi.io.github.classgraph.classpath.ClasspathOrder;
import nonapi.io.github.classgraph.scanspec.ScanSpec;
import nonapi.io.github.classgraph.utils.LogNode;

/**
 * A placeloader ClassLoaderHandler that matches Java 9+ classloaders, but does not attempt to extract URLs from
 * them (module scanning uses a different mechanism from classpath scanning).
 */
class JPMSClassLoaderHandler implements ClassLoaderHandler {
    @Override
    public boolean canHandle(Class<?> classLoaderClass, LogNode log) {
        return ClassLoaderFinder.classIsOrExtendsOrImplements(classLoaderClass,
                "jdk.internal.loader.ClassLoaders$AppClassLoader")
                || ClassLoaderFinder.classIsOrExtendsOrImplements(classLoaderClass,
                        "jdk.internal.loader.BuiltinClassLoader");
    }

    @Override
    public void findClassLoaderOrder(ClassLoader classLoader, ClassLoaderOrder classLoaderOrder, LogNode log) {
        classLoaderOrder.delegateTo(classLoader.getParent(), /* isParent = */ true, log);
        classLoaderOrder.add(classLoader, log);
    }

    @Override
    public void findClasspathOrder(ClassLoader classLoader, ClasspathOrder classpathOrder, ScanSpec scanSpec, LogNode log) {
        // The JDK9 classloaders have a field, `URLClassPath ucp`, containing URLs for unnamed modules,
        // but it is not visible. Modules therefore have to be scanned using the JPMS API.
        // However, it is possible for a Java agent to extend UCP by adding directly to the `ucp` field
        // (#537), and there is no way to read this field. Therefore, we need to use Narcissus to break
        // Java's encapsulation to read this, for this small corner case.
        final Object ucpVal = classpathOrder.reflectionUtils.getFieldVal(false, classLoader, "ucp");
        if (ucpVal != null) {
            final URL[] urls = (URL[]) classpathOrder.reflectionUtils.invokeMethod(false, ucpVal, "getURLs");
            classpathOrder.addClasspathEntryObject(urls, classLoader, scanSpec, log);
        }
    }
}
