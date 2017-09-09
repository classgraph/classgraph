/*
 * This file is part of FastClasspathScanner.
 * 
 * Author: Sergey Bespalov
 * 
 * Hosted at: https://github.com/lukehutch/fast-classpath-scanner
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
package io.github.lukehutch.fastclasspathscanner.classloaderhandler;

import io.github.lukehutch.fastclasspathscanner.scanner.ClasspathFinder;
import io.github.lukehutch.fastclasspathscanner.scanner.ScanSpec;
import io.github.lukehutch.fastclasspathscanner.utils.LogNode;
import io.github.lukehutch.fastclasspathscanner.utils.ReflectionUtils;

public class WebsphereTraditionalClassLoaderHandler implements ClassLoaderHandler {
    // All three class loaders implement the getClassPath method call.
    public static final String[] HANDLED_CLASSLOADERS = { "com.ibm.ws.classloader.CompoundClassLoader",
            "com.ibm.ws.classloader.ProtectionClassLoader", "com.ibm.ws.bootstrap.ExtClassLoader" };

    @Override
    public DelegationOrder getDelegationOrder(final ClassLoader classLoaderInstance) {
        // TODO: Read correct delegation order from ClassLoader
        return DelegationOrder.PARENT_FIRST;
    }

    @Override
    public void handle(final ClassLoader classloader, final ClasspathFinder classpathFinder,
            final ScanSpec scanSpec, final LogNode log) throws Exception {
        final String classpath = (String) ReflectionUtils.invokeMethod(classloader, "getClassPath");
        classpathFinder.addClasspathElements(classpath, classloader, log);
    }
}
