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
 * Copyright (c) 2018 Luke Hutchison, with significant contributions from Davy De Durpel
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
import java.lang.reflect.Array;
import java.nio.file.Path;
import java.util.Map;

import io.github.classgraph.ScanSpec;
import io.github.classgraph.utils.ClasspathOrder;
import io.github.classgraph.utils.FileUtils;
import io.github.classgraph.utils.LogNode;
import io.github.classgraph.utils.ReflectionUtils;

/**
 * Extract classpath entries from the JBoss ClassLoader. See:
 *
 * <p>
 * https://github.com/jboss-modules/jboss-modules/blob/master/src/main/java/org/jboss/modules/ModuleClassLoader.java
 */
public class JBossClassLoaderHandler implements ClassLoaderHandler {
    @Override
    public String[] handledClassLoaders() {
        return new String[] { "org.jboss.modules.ModuleClassLoader" };
    }

    @Override
    public ClassLoader getEmbeddedClassLoader(final ClassLoader outerClassLoaderInstance) {
        return null;
    }

    @Override
    public DelegationOrder getDelegationOrder(final ClassLoader classLoaderInstance) {
        return DelegationOrder.PARENT_FIRST;
    }

    @Override
    public void handle(final ScanSpec scanSpec, final ClassLoader classLoader,
            final ClasspathOrder classpathOrderOut, final LogNode log) {
        final Object module = ReflectionUtils.invokeMethod(classLoader, "getModule", false);
        final Object callerModuleLoader = ReflectionUtils.invokeMethod(module, "getCallerModuleLoader", false);
        @SuppressWarnings("unchecked")
        final Map<Object, Object> moduleMap = (Map<Object, Object>) ReflectionUtils.getFieldVal(callerModuleLoader,
                "moduleMap", false);
        for (final Object key : moduleMap.keySet()) {
            final Object futureModule = moduleMap.get(key);
            final Object realModule = ReflectionUtils.invokeMethod(futureModule, "getModule", false);
            final Object moduleLoader = ReflectionUtils.invokeMethod(realModule, "getClassLoader", false);

            // type VFSResourceLoader[]
            final Object vfsResourceLoaders = ReflectionUtils.invokeMethod(moduleLoader, "getResourceLoaders",
                    false);
            if (vfsResourceLoaders != null) {
                for (int i = 0, n = Array.getLength(vfsResourceLoaders); i < n; i++) {
                    String path = null;
                    // type VFSResourceLoader
                    final Object resourceLoader = Array.get(vfsResourceLoaders, i);
                    if (resourceLoader != null) {
                        // type VirtualFile
                        final Object root = ReflectionUtils.getFieldVal(resourceLoader, "root", false);
                        final File physicalFile = (File) ReflectionUtils.invokeMethod(root, "getPhysicalFile",
                                false);
                        if (physicalFile != null) {
                            final String name = (String) ReflectionUtils.invokeMethod(root, "getName", false);
                            if (name != null) {
                                final File file = new java.io.File(physicalFile.getParentFile(), name);
                                if (FileUtils.canRead(file)) {
                                    path = file.getAbsolutePath();
                                } else {
                                    path = physicalFile.getAbsolutePath();
                                }
                            } else {
                                path = physicalFile.getAbsolutePath();
                            }
                        } else {
                            // Fallback
                            path = (String) ReflectionUtils.invokeMethod(root, "getPathName", false);
                            if (path == null) {
                                // Try Path or File:
                                Object file;
                                if (root instanceof Path) {
                                    file = ((Path) root).toFile();
                                } else {
                                    file = root;
                                }
                                if (file == null) {
                                    // Try JarFileResource:
                                    file = ReflectionUtils.getFieldVal(resourceLoader, "fileOfJar", false);
                                }
                                path = (String) ReflectionUtils.invokeMethod(file, "getAbsolutePath", false);
                            }
                        }
                    }
                    classpathOrderOut.addClasspathElement(path, (ClassLoader) moduleLoader, log);
                }
            }
        }
    }
}
