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
 * Copyright (c) 2019 Luke Hutchison, with significant contributions from Davy De Durpel
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

import java.io.File;
import java.lang.reflect.Array;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import nonapi.io.github.classgraph.ScanSpec;
import nonapi.io.github.classgraph.classpath.ClasspathOrder;
import nonapi.io.github.classgraph.utils.FileUtils;
import nonapi.io.github.classgraph.utils.LogNode;
import nonapi.io.github.classgraph.utils.ReflectionUtils;

/**
 * Extract classpath entries from the JBoss ClassLoader. See:
 *
 * <p>
 * https://github.com/jboss-modules/jboss-modules/blob/master/src/main/java/org/jboss/modules/ModuleClassLoader.java
 */
public class JBossClassLoaderHandler implements ClassLoaderHandler {

    /* (non-Javadoc)
     * @see nonapi.io.github.classgraph.classloaderhandler.ClassLoaderHandler#handledClassLoaders()
     */
    @Override
    public String[] handledClassLoaders() {
        return new String[] { "org.jboss.modules.ModuleClassLoader" };
    }

    /* (non-Javadoc)
     * @see nonapi.io.github.classgraph.classloaderhandler.ClassLoaderHandler#getEmbeddedClassLoader(java.lang.ClassLoader)
     */
    @Override
    public ClassLoader getEmbeddedClassLoader(final ClassLoader outerClassLoaderInstance) {
        return null;
    }

    /* (non-Javadoc)
     * @see nonapi.io.github.classgraph.classloaderhandler.ClassLoaderHandler#getDelegationOrder(java.lang.ClassLoader)
     */
    @Override
    public DelegationOrder getDelegationOrder(final ClassLoader classLoaderInstance) {
        return DelegationOrder.PARENT_FIRST;
    }

    /**
     * Handle a resource loader.
     *
     * @param resourceLoader
     *            the resource loader
     * @param classLoader
     *            the classloader
     * @param classpathOrderOut
     *            the classpath order
     * @param log
     *            the log
     */
    private void handleResourceLoader(final Object resourceLoader, final ClassLoader classLoader,
            final ClasspathOrder classpathOrderOut, final LogNode log) {
        if (resourceLoader == null) {
            return;
        }
        // PathResourceLoader has root field, which is a Path object
        final Object root = ReflectionUtils.getFieldVal(resourceLoader, "root", false);
        // type VirtualFile
        final File physicalFile = (File) ReflectionUtils.invokeMethod(root, "getPhysicalFile", false);
        String path = null;
        if (physicalFile != null) {
            final String name = (String) ReflectionUtils.invokeMethod(root, "getName", false);
            if (name != null) {
                // getParentFile() removes "contents" directory
                final File file = new File(physicalFile.getParentFile(), name);
                if (FileUtils.canRead(file)) {
                    path = file.getAbsolutePath();
                } else {
                    // This is an exploded jar or classpath directory
                    path = physicalFile.getAbsolutePath();
                }
            } else {
                path = physicalFile.getAbsolutePath();
            }
        } else {
            path = (String) ReflectionUtils.invokeMethod(root, "getPathName", false);
            if (path == null) {
                // Try Path or File
                final File file = root instanceof Path ? ((Path) root).toFile()
                        : root instanceof File ? (File) root : null;
                if (file != null) {
                    path = file.getAbsolutePath();
                }
            }
        }
        if (path == null) {
            final File file = (File) ReflectionUtils.getFieldVal(resourceLoader, "fileOfJar", false);
            if (file != null) {
                path = file.getAbsolutePath();
            }
        }
        if (path != null) {
            classpathOrderOut.addClasspathEntry(path, classLoader, log);
        } else {
            if (log != null) {
                log.log("Could not determine classpath for ResourceLoader: " + resourceLoader);
            }
        }
    }

    /**
     * Handle a module.
     *
     * @param module
     *            the module
     * @param visitedModules
     *            visited modules
     * @param classLoader
     *            the classloader
     * @param classpathOrderOut
     *            the classpath order
     * @param log
     *            the log
     */
    private void handleRealModule(final Object module, final Set<Object> visitedModules,
            final ClassLoader classLoader, final ClasspathOrder classpathOrderOut, final LogNode log) {
        if (!visitedModules.add(module)) {
            // Avoid extracting paths from the same module more than once
            return;
        }
        ClassLoader moduleLoader = (ClassLoader) ReflectionUtils.invokeMethod(module, "getClassLoader", false);
        if (moduleLoader == null) {
            moduleLoader = classLoader;
        }
        // type VFSResourceLoader[]
        final Object vfsResourceLoaders = ReflectionUtils.invokeMethod(moduleLoader, "getResourceLoaders", false);
        if (vfsResourceLoaders != null) {
            for (int i = 0, n = Array.getLength(vfsResourceLoaders); i < n; i++) {
                // type JarFileResourceLoader for jars, VFSResourceLoader for exploded jars, PathResourceLoader
                // for resource directories, or NativeLibraryResourceLoader for (usually non-existent) native
                // library "lib/" dirs adjacent to the jarfiles that they were presumably extracted from.
                final Object resourceLoader = Array.get(vfsResourceLoaders, i);
                // Could skip NativeLibraryResourceLoader instances altogether, but testing for their existence
                // only seems to add about 3% to the total scan time.
                // if (!resourceLoader.getClass().getSimpleName().equals("NativeLibraryResourceLoader")) {
                handleResourceLoader(resourceLoader, moduleLoader, classpathOrderOut, log);
                //}
            }
        }
    }

    /* (non-Javadoc)
     * @see nonapi.io.github.classgraph.classloaderhandler.ClassLoaderHandler#handle(nonapi.io.github.classgraph.ScanSpec, java.lang.ClassLoader, nonapi.io.github.classgraph.classpath.ClasspathOrder, nonapi.io.github.classgraph.utils.LogNode)
     */
    @Override
    public void handle(final ScanSpec scanSpec, final ClassLoader classLoader,
            final ClasspathOrder classpathOrderOut, final LogNode log) {
        final Object module = ReflectionUtils.invokeMethod(classLoader, "getModule", false);
        final Object callerModuleLoader = ReflectionUtils.invokeMethod(module, "getCallerModuleLoader", false);
        final Set<Object> visitedModules = new HashSet<>();
        @SuppressWarnings("unchecked")
        final Map<Object, Object> moduleMap = (Map<Object, Object>) ReflectionUtils.getFieldVal(callerModuleLoader,
                "moduleMap", false);
        for (final Entry<Object, Object> ent : moduleMap.entrySet()) {
            // type FutureModule
            final Object val = ent.getValue();
            // type Module
            final Object realModule = ReflectionUtils.invokeMethod(val, "getModule", false);
            handleRealModule(realModule, visitedModules, classLoader, classpathOrderOut, log);
        }
        // type Map<String, List<LocalLoader>>
        @SuppressWarnings("unchecked")
        final Map<String, List<?>> pathsMap = (Map<String, List<?>>) ReflectionUtils.invokeMethod(module,
                "getPaths", false);
        for (final Entry<String, List<?>> ent : pathsMap.entrySet()) {
            for (final Object /* ModuleClassLoader$1 */ localLoader : ent.getValue()) {
                // type ModuleClassLoader (outer class)
                final Object moduleClassLoader = ReflectionUtils.getFieldVal(localLoader, "this$0", false);
                // type Module
                final Object realModule = ReflectionUtils.getFieldVal(moduleClassLoader, "module", false);
                handleRealModule(realModule, visitedModules, classLoader, classpathOrderOut, log);
            }
        }
    }
}
