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
package io.github.classgraph.classloaderhandler;

import io.github.classgraph.ScanSpec;
import io.github.classgraph.utils.ClasspathOrder;
import io.github.classgraph.utils.LogNode;
import io.github.classgraph.utils.ReflectionUtils;

/**
 * Handle the WebSphere traditonal ClassLoaders.
 * 
 * @author lukehutch
 */
public class WebsphereTraditionalClassLoaderHandler implements ClassLoaderHandler {
    @Override
    public String[] handledClassLoaders() {
        // All three class loaders implement the getClassPath method call.
        return new String[] { //
                "com.ibm.ws.classloader.CompoundClassLoader", //
                "com.ibm.ws.classloader.ProtectionClassLoader", //
                "com.ibm.ws.bootstrap.ExtClassLoader" };
    }

    @Override
    public ClassLoader getEmbeddedClassLoader(final ClassLoader outerClassLoaderInstance) {
        return null;
    }

    @Override
    public DelegationOrder getDelegationOrder(final ClassLoader classLoaderInstance) {
        // TODO: Read correct delegation order from ClassLoader
        return DelegationOrder.PARENT_FIRST;
    }

    @Override
    public void handle(final ScanSpec scanSpec, final ClassLoader classloader,
            final ClasspathOrder classpathOrderOut, final LogNode log) {
        final String classpath = (String) ReflectionUtils.invokeMethod(classloader, "getClassPath", false);
        classpathOrderOut.addClasspathElements(classpath, classloader, log);
    }
}
