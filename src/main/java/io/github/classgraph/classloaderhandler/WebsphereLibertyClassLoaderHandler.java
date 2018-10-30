/*
 * This file is part of ClassGraph.
 *
 * Author: R. Kempees
 *
 * Hosted at: https://github.com/classgraph/classgraph
 *
 * --
 *
 * The MIT License (MIT)
 *
 * Copyright (c) 2017 R. Kempees
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

import java.io.File;
import java.util.List;

import io.github.classgraph.ScanSpec;
import io.github.classgraph.utils.ClasspathOrder;
import io.github.classgraph.utils.LogNode;
import io.github.classgraph.utils.ReflectionUtils;

/**
 * WebsphereLibertyClassLoaderHandler.
 *
 * <p>
 * Used to support WAS Liberty Profile classloading in io.github.classgraph
 *
 * @author R. Kempees
 */
public class WebsphereLibertyClassLoaderHandler implements ClassLoaderHandler {
    private static final String PKG_PREFIX = "com.ibm.ws.classloading.internal.";
    private static final String IBM_APP_CLASS_LOADER = PKG_PREFIX + "AppClassLoader";
    private static final String IBM_THREAD_CONTEXT_CLASS_LOADER = PKG_PREFIX + "ThreadContextClassLoader";

    @Override
    public String[] handledClassLoaders() {
        return new String[] { IBM_APP_CLASS_LOADER, IBM_THREAD_CONTEXT_CLASS_LOADER };
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
    public void handle(final ScanSpec scanSpec, final ClassLoader classLoader,
            final ClasspathOrder classpathOrderOut, final LogNode log) {
        Object smartClassPath = null;
        final Object appLoader = ReflectionUtils.getFieldVal(classLoader, "appLoader", false);
        if (appLoader != null) {
            smartClassPath = ReflectionUtils.getFieldVal(appLoader, "smartClassPath", false);
        } else {
            smartClassPath = ReflectionUtils.getFieldVal(classLoader, "smartClassPath", false);
        }
        if (smartClassPath != null) {
            final List<?> classPathElements = (List<?>) ReflectionUtils.getFieldVal(smartClassPath, "classPath",
                    false);
            if (classPathElements != null) {
                for (final Object classpath : classPathElements) {
                    final String path = getPath(classpath);
                    if (path != null && path.length() > 0) {
                        classpathOrderOut.addClasspathElement(path, classLoader, log);
                    }
                }
            }
        }
    }

    private String getPath(final Object classpath) {
        final Object container = ReflectionUtils.getFieldVal(classpath, "container", false);
        if (container == null) {
            return "";
        }

        final Object delegate = ReflectionUtils.getFieldVal(container, "delegate", false);
        if (delegate == null) {
            return "";
        }

        final String path = (String) ReflectionUtils.getFieldVal(delegate, "path", false);
        if (path != null && path.length() > 0) {
            return path;
        }

        final Object base = ReflectionUtils.getFieldVal(delegate, "base", false);
        if (base == null) {
            // giving up.
            return "";
        }

        final Object archiveFile = ReflectionUtils.getFieldVal(base, "archiveFile", false);
        if (archiveFile != null) {
            final File file = (File) archiveFile;
            return file.getAbsolutePath();
        }
        return "";
    }
}
