/*
 * This file is part of FastClasspathScanner.
 *
 * Author: Luke Hutchison
 *
 * Hosted at: https://github.com/lukehutch/fast-classpath-scanner
 *
 * --
 *
 * The MIT License (MIT)
 *
 * Copyright (c) 2017 Luke Hutchison
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

import java.io.File;

import io.github.lukehutch.fastclasspathscanner.scanner.ClasspathOrder;
import io.github.lukehutch.fastclasspathscanner.scanner.ScanSpec;
import io.github.lukehutch.fastclasspathscanner.utils.LogNode;
import io.github.lukehutch.fastclasspathscanner.utils.ReflectionUtils;

public class OSGiDefaultClassLoaderHandler implements ClassLoaderHandler {
    @Override
    public String[] handledClassLoaders() {
        return new String[] { "org.eclipse.osgi.internal.baseadaptor.DefaultClassLoader" };
    }

    @Override
    public DelegationOrder getDelegationOrder(final ClassLoader classLoaderInstance) {
        return DelegationOrder.PARENT_FIRST;
    }

    @Override
    public void handle(final ScanSpec scanSpec, final ClassLoader classloader,
            final ClasspathOrder classpathOrderOut, final LogNode log) {
        final Object classpathManager = ReflectionUtils.invokeMethod(classloader, "getClasspathManager", false);
        final Object[] entries = (Object[]) ReflectionUtils.getFieldVal(classpathManager, "entries", false);
        if (entries != null) {
            for (int i = 0; i < entries.length; i++) {
                final Object bundleFile = ReflectionUtils.invokeMethod(entries[i], "getBundleFile", false);
                final File baseFile = (File) ReflectionUtils.invokeMethod(bundleFile, "getBaseFile", false);
                if (baseFile != null) {
                    classpathOrderOut.addClasspathElement(baseFile.getPath(), classloader, log);
                }
            }
        }
    }
}
