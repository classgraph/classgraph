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
 * Copyright (c) 2026 Luke Hutchison, with significant contributions from Davy De Durpel
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
package io.github.classgraph.classpath.internal.classloaderhandler;

import java.io.File;
import java.lang.reflect.Array;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import org.jspecify.annotations.Nullable;

import io.github.classgraph.base.ClassGraphLog;
import io.github.classgraph.base.internal.path.FileUtils;
import io.github.classgraph.base.internal.reflection.ReflectionUtils;
import io.github.classgraph.classpath.ClassLoaderHandler;
import io.github.classgraph.classpath.ClassLoaderOrder;
import io.github.classgraph.classpath.ClasspathOrder;

/**
 * Extracts classpath entries from the JBoss {@code org.jboss.modules.ModuleClassLoader}.
 */
class JBossClassLoaderHandler implements ClassLoaderHandler {
    /** Constructor. */
    JBossClassLoaderHandler() {
    }

    @Override
    public boolean canHandle(final Class<?> classLoaderClass, final @Nullable ClassGraphLog log) {
        return classIsOrExtendsOrImplements(classLoaderClass, "org.jboss.modules.ModuleClassLoader");
    }

    @Override
    public void findClassLoaderOrder(final ClassLoader classLoader, final ClassLoaderOrder classLoaderOrder,
            final @Nullable ClassGraphLog log) {
        classLoaderOrder.delegateToParent(classLoader.getParent(), log);
        classLoaderOrder.add(classLoader, log);
    }

    /**
     * Extracts classpath entries from a JBoss {@code ResourceLoader}.
     *
     * @param resourceLoader
     *            the resource loader, or {@code null}.
     * @param classLoader
     *            the classloader.
     * @param classpathOrderOut
     *            the classpath order.
     * @param log
     *            the log node, or {@code null} to skip logging.
     */
    private static void handleResourceLoader(final @Nullable Object resourceLoader, final ClassLoader classLoader,
            final ClasspathOrder classpathOrderOut, final @Nullable ClassGraphLog log) {
        if (resourceLoader == null) {
            return;
        }
        // PathResourceLoader has root field, which is a Path object
        final var root = ReflectionUtils.getFieldVal(false, resourceLoader, "root");

        var foundClasspathEntry = classpathOrderOut.addClasspathEntry(loadJarPathFromClassicVFS(root), classLoader,
                log);
        foundClasspathEntry |= classpathOrderOut.addClasspathEntry(loadJarPathFromNewVFS(root), classLoader, log);
        foundClasspathEntry |= classpathOrderOut.addClasspathEntry(
                ReflectionUtils.getFieldVal(false, resourceLoader, "fileOfJar"), classLoader, log);

        if (!foundClasspathEntry) {
            // A ResourceLoader that wraps another one, e.g. FilteredResourceLoader, has none of the above fields --
            // the only thing it exposes is the location of the loader it delegates to. (type URI)
            classpathOrderOut.addClasspathEntry(ReflectionUtils.invokeMethod(false, resourceLoader, "getLocation"),
                    classLoader, log);
        }
    }

    /**
     * Extracts the physical JAR file for a JBoss VFS root via {@code VFS.getMount(root)}.
     *
     * @param root
     *            the VFS root object, or {@code null}.
     * @return the {@link File} of the JAR file, or {@code null} if it could not be resolved.
     */
    private static @Nullable File loadJarPathFromNewVFS(final @Nullable Object root) {
        if (root == null) {
            return null;
        }
        final Class<?> jbossVFS = getJBossVFSAccess(root);
        if (jbossVFS == null) {
            return null;
        }
        // try to find the mount of the root. Type is org.jboss.vfs.VFS.Mount
        final var mount = ReflectionUtils.invokeStaticMethod(false, jbossVFS, "getMount", root.getClass(), root);
        if (mount == null) {
            return null;
        }
        // try to access the fileSystem of the mount. Type is org.jboss.vfs.spi.FileSystem
        final var fileSystem = ReflectionUtils.invokeMethod(false, mount, "getFileSystem");
        if (fileSystem == null) {
            return null;
        }
        // now access the mount source, which is the file that is used to create the mount.
        final var mountSource = (File) ReflectionUtils.invokeMethod(false, fileSystem, "getMountSource");
        if (mountSource == null) {
            return null;
        }
        // absolute path of the mountSource should be the 'physical' .jar
        return mountSource;
    }

    /**
     * Resolves {@code org.jboss.vfs.VFS} from the classloader of {@code root} or the thread context classloader.
     *
     * @param root
     *            the root {@code VirtualFile} of JBoss VFS.
     * @return the {@code org.jboss.vfs.VFS} class, or {@code null} if not found.
     */
    private static @Nullable Class<?> getJBossVFSAccess(final Object root) {
        // we need access to the class 'VFS' of org.jboss.vfs
        if (root.getClass().getName().contains("org.jboss.vfs")) {
            // first, try the classloader of the root object. Since the root object comes from org.jboss.vfs, it is
            // likely that we can get access to org.jboss.vfs.VFS from this classloader
            final var jbossVFS = loadJBossVFS(root.getClass().getClassLoader());
            if (jbossVFS != null) {
                return jbossVFS;
            }
        }
        // for non org.jboss.vfs objects, and if the classloader of the root object could not see VFS, try the
        // context classloader of the current thread. If there is no VFS present, we can't do anything, so this
        // returns null.
        return loadJBossVFS(Thread.currentThread().getContextClassLoader());
    }

    /**
     * Loads {@code org.jboss.vfs.VFS} from the given classloader.
     *
     * @param classLoader
     *            the classloader to query, or {@code null}.
     * @return the {@code org.jboss.vfs.VFS} class, or {@code null} if not visible.
     */
    private static @Nullable Class<?> loadJBossVFS(final @Nullable ClassLoader classLoader) {
        return ReflectionUtils.classForNameOrNull("org.jboss.vfs.VFS", classLoader);
    }

    /**
     * Extracts the physical file or path for a JBoss VFS root via {@code getPhysicalFile()} or
     * {@code getPathName()}.
     *
     * @param root
     *            the VFS root object, or {@code null}.
     * @return the {@link File}, {@link Path}, or path string of the JAR or directory, or {@code null} if not found.
     */
    private static @Nullable Object loadJarPathFromClassicVFS(final @Nullable Object root) {
        if (root == null) {
            return null;
        }
        // type VirtualFile
        final var physicalFile = (File) ReflectionUtils.invokeMethod(false, root, "getPhysicalFile");
        if (physicalFile != null) {
            final var name = (String) ReflectionUtils.invokeMethod(false, root, "getName");
            if (name != null) {
                // getParentFile() removes "contents" directory
                final File file = new File(physicalFile.getParentFile(), name);
                if (FileUtils.canRead(file)) {
                    return file;
                } else {
                    // This is an exploded jar or classpath directory
                    return physicalFile;
                }
            } else {
                return physicalFile;
            }
        } else {
            final var path = (String) ReflectionUtils.invokeMethod(false, root, "getPathName");
            if (path != null) {
                return path;
            }
            return root;
        }
    }

    /**
     * Extracts classpath entries from a JBoss {@code Module}.
     *
     * @param module
     *            the module, or {@code null}.
     * @param visitedModules
     *            already-visited modules.
     * @param classLoader
     *            the classloader.
     * @param classpathOrderOut
     *            the classpath order.
     * @param log
     *            the log node, or {@code null} to skip logging.
     */
    private static void handleRealModule(final @Nullable Object module, final Set<@Nullable Object> visitedModules,
            final ClassLoader classLoader, final ClasspathOrder classpathOrderOut,
            final @Nullable ClassGraphLog log) {
        if (!visitedModules.add(module)) {
            // Avoid extracting paths from the same module more than once
            return;
        }
        var moduleLoader = (ClassLoader) ReflectionUtils.invokeMethod(false, module, "getClassLoader");
        if (moduleLoader == null) {
            moduleLoader = classLoader;
        }
        // type VFSResourceLoader[]
        final var vfsResourceLoaders = ReflectionUtils.invokeMethod(false, moduleLoader, "getResourceLoaders");
        if (vfsResourceLoaders != null) {
            for (int i = 0, n = Array.getLength(vfsResourceLoaders); i < n; i++) {
                // type JarFileResourceLoader for jars, VFSResourceLoader for exploded jars, PathResourceLoader for
                // resource directories, or NativeLibraryResourceLoader for (usually non-existent) native library
                // "lib/" dirs adjacent to the jarfiles that they were presumably extracted from.
                final var resourceLoader = Array.get(vfsResourceLoaders, i);
                // A NativeLibraryResourceLoader could be skipped here, since it holds no classes, but the lib dirs
                // it names usually do not exist, and a classpath element that is not there is dropped anyway, which
                // was measured to cost only about 3% of the total scan time.
                handleResourceLoader(resourceLoader, moduleLoader, classpathOrderOut, log);
            }
        }
    }

    @Override
    public void findClasspathOrder(final ClassLoader classLoader, final ClasspathOrder classpathOrder,
            final @Nullable ClassGraphLog log) {
        final var module = ReflectionUtils.invokeMethod(false, classLoader, "getModule");
        final var callerModuleLoader = ReflectionUtils.invokeMethod(false, module, "getCallerModuleLoader");
        final Set<@Nullable Object> visitedModules = new HashSet<>();
        // This adds the roots of every module that the module loader has loaded, then of every module listed in
        // this module's paths map, but not in the order that JBoss searches them. JBoss loads a class from the
        // first loader in the list that the paths map holds for the class's package, and the module map (a
        // ConcurrentHashMap) and the paths map (a HashMap) are both unordered, so the module order here is
        // arbitrary. Only the set of modules found is reliable: when two modules contain the same class, the copy
        // that is found first here may not be the copy that JBoss loads.
        @SuppressWarnings("unchecked")
        final var moduleMap = (Map<Object, Object>) ReflectionUtils.getFieldVal(false, callerModuleLoader,
                "moduleMap");
        final var moduleMapEntries = moduleMap != null ? moduleMap.entrySet() : Set.<Entry<Object, Object>> of();
        for (final Entry<Object, Object> ent : moduleMapEntries) {
            // type FutureModule
            final var val = ent.getValue();
            // type Module
            final var realModule = ReflectionUtils.invokeMethod(false, val, "getModule");
            handleRealModule(realModule, visitedModules, classLoader, classpathOrder, log);
        }
        // type Map<String, List<LocalLoader>>
        @SuppressWarnings("unchecked")
        final var pathsMap = (Map<String, List<?>>) ReflectionUtils.invokeMethod(false, module, "getPaths");
        // (invokeMethod returns null if the method is not present, so don't assume it was found)
        final var pathsMapEntries = pathsMap != null ? pathsMap.entrySet() : Set.<Entry<String, List<?>>> of();
        for (final Entry<String, List<?>> ent : pathsMapEntries) {
            for (final Object /* ModuleClassLoader$1 */ localLoader : ent.getValue()) {
                // type ModuleClassLoader (outer class)
                final var moduleClassLoader = ReflectionUtils.getFieldVal(false, localLoader, "this$0");
                // type Module
                final var realModule = ReflectionUtils.getFieldVal(false, moduleClassLoader, "module");
                handleRealModule(realModule, visitedModules, classLoader, classpathOrder, log);
            }
        }
    }

}
