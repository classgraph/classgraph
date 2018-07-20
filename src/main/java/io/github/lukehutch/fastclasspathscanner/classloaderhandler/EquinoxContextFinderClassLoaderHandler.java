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
 * Copyright (c) 2018 Luke Hutchison
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

import io.github.lukehutch.fastclasspathscanner.ClassLoaderHandler;
import io.github.lukehutch.fastclasspathscanner.ScanSpec;
import io.github.lukehutch.fastclasspathscanner.utils.ClasspathOrder;
import io.github.lukehutch.fastclasspathscanner.utils.LogNode;
import io.github.lukehutch.fastclasspathscanner.utils.ReflectionUtils;

/** Extract classpath entries from the Eclipse Equinox ContextFinder ClassLoader. */
public class EquinoxContextFinderClassLoaderHandler implements ClassLoaderHandler {
    @Override
    public String[] handledClassLoaders() {
        return new String[] { "org.eclipse.osgi.internal.framework.ContextFinder" };
    }

    @Override
    public DelegationOrder getDelegationOrder(final ClassLoader classLoaderInstance) {
        return DelegationOrder.PARENT_FIRST;
    }

    // For ContextFinder, all but the last ClassLoader that is tried are pulled from the context stack.
    // FCS already does this when looking for ClassLoaders to try, so all these ClassLoaders should already
    // be handled. However, if all those ClassLoaders fail, as a fallback, ContextFinder calls the
    // parentContextClassLoader, which may not be the same as the actual parent classloader.
    // Therefore, create a ClassLoaderHandler for parentContextClassLoader, if present, and delegate to
    // this ClassLoader here, and ignore the rest of the ClassLoaders in the call stack.
    @Override
    public void handle(final ScanSpec scanSpec, final ClassLoader classLoader,
            final ClasspathOrder classpathOrderOut, final LogNode log) throws Exception {
        // type ClassLoader
        final Object parentContextClassLoader = ReflectionUtils.getFieldVal(classLoader, "parentContextClassLoader",
                false);
        if (parentContextClassLoader != null) {
            final ClassLoaderHandler parentContextClassLoaderHandler = scanSpec
                    .findClassLoaderHandlerForClassLoader(classLoader, log);
            LogNode subLog = log;
            if (log != null) {
                subLog = log.log("Delegating to parentContextClassLoader: " + parentContextClassLoader);
            }
            parentContextClassLoaderHandler.handle(scanSpec, classLoader, classpathOrderOut, subLog);
        }
    }
}
