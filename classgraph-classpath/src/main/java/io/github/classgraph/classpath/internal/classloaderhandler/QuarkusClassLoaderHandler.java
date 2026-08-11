/*
 * This file is part of ClassGraph.
 *
 * Author: @mcollovati
 *
 * Hosted at: https://github.com/classgraph/classgraph
 *
 * --
 *
 * The MIT License (MIT)
 *
 * Copyright (c) 2019 @mcollovati, contributed to the ClassGraph project
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

import java.io.IOError;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Map;

import io.github.classgraph.base.internal.reflection.ReflectionUtils;
import io.github.classgraph.base.internal.utils.LogNode;
import io.github.classgraph.classpath.internal.ClassLoaderOrder;
import io.github.classgraph.classpath.internal.ClasspathOrder;
import org.jspecify.annotations.Nullable;

/**
 * Extract classpath entries from the Quarkus ClassLoader.
 */
class QuarkusClassLoaderHandler implements ClassLoaderHandler {
    /** The classloader used up to Quarkus 1.2. */
    private static final String RUNTIME_CLASSLOADER = "io.quarkus.runner.RuntimeClassLoader";

    /** The classloader used since Quarkus 1.3. */
    private static final String QUARKUS_CLASSLOADER = "io.quarkus.bootstrap.classloading.QuarkusClassLoader";

    /** The classloader used since Quarkus 1.13. */
    private static final String RUNNER_CLASSLOADER = "io.quarkus.bootstrap.runner.RunnerClassLoader";

    /**
     * The classpath element classes used prior to Quarkus 3.11, mapped to the name of the field of each that holds
     * the element's location.
     */
    private static final Map<String, String> PRE_311_RESOURCE_BASED_ELEMENTS = Map.of(
            "io.quarkus.bootstrap.classloading.JarClassPathElement", "file",
            "io.quarkus.bootstrap.classloading.DirectoryClassPathElement", "root");

    /** Constructor. */
    QuarkusClassLoaderHandler() {
    }

    @Override
    public boolean canHandle(final Class<?> classLoaderClass, final @Nullable LogNode log) {
        return classIsOrExtendsOrImplements(classLoaderClass, RUNTIME_CLASSLOADER)
                || classIsOrExtendsOrImplements(classLoaderClass, QUARKUS_CLASSLOADER)
                || classIsOrExtendsOrImplements(classLoaderClass, RUNNER_CLASSLOADER);
    }

    @Override
    public void findClassLoaderOrder(final ClassLoader classLoader, final ClassLoaderOrder classLoaderOrder,
            final @Nullable LogNode log) {
        classLoaderOrder.delegateTo(classLoader.getParent(), /* isParent = */ true, log);
        classLoaderOrder.add(classLoader, log);
    }

    @Override
    public void findClasspathOrder(final ClassLoader classLoader, final ClasspathOrder classpathOrder,
            final @Nullable LogNode log) {

        final var classLoaderName = classLoader.getClass().getName();
        if (RUNTIME_CLASSLOADER.equals(classLoaderName)) {
            findClasspathOrderForRuntimeClassloader(classLoader, classpathOrder, log);
        } else if (QUARKUS_CLASSLOADER.equals(classLoaderName)) {
            findClasspathOrderForQuarkusClassloader(classLoader, classpathOrder, log);
        } else if (RUNNER_CLASSLOADER.equals(classLoaderName)) {
            findClasspathOrderForRunnerClassloader(classLoader, classpathOrder, log);
        }
    }

    /**
     * Find the classpath order for {@code io.quarkus.bootstrap.classloading.QuarkusClassLoader} (Quarkus 1.3+).
     *
     * @param classLoader
     *            the classloader
     * @param classpathOrder
     *            the classpath order to add to
     * @param log
     *            the log node, or null to skip logging
     */
    private static void findClasspathOrderForQuarkusClassloader(final ClassLoader classLoader,
            final ClasspathOrder classpathOrder, final @Nullable LogNode log) {

        final var elements = findQuarkusClassLoaderElements(classLoader);

        for (final Object element : elements) {
            final var elementClassName = element.getClass().getName();
            final var fieldName = PRE_311_RESOURCE_BASED_ELEMENTS.get(elementClassName);
            if (fieldName != null) {
                classpathOrder.addClasspathEntry(ReflectionUtils.getFieldVal(false, element, fieldName),
                        classLoader, log);
            } else {
                final var rootPath = ReflectionUtils.invokeMethod(false, element, "getRoot");
                if (rootPath instanceof Path) {
                    classpathOrder.addClasspathEntry(rootPath, classLoader, log);
                }
            }
        }
    }

    /**
     * Get the classpath elements of a {@code QuarkusClassLoader}, from either the single {@code elements} field, or
     * (since Quarkus 3.16) the {@code normalPriorityElements} and {@code lesserPriorityElements} fields.
     *
     * @param classLoader
     *            the classloader
     * @return the classpath elements (empty if none of the fields were found).
     */
    @SuppressWarnings("unchecked")
    private static Collection<Object> findQuarkusClassLoaderElements(final ClassLoader classLoader) {
        var elements = (Collection<Object>) ReflectionUtils.getFieldVal(false, classLoader, "elements");
        if (elements == null) {
            elements = new ArrayList<>();
            // Since 3.16.x
            for (final String fieldName : new String[] { "normalPriorityElements", "lesserPriorityElements" }) {
                final var fieldVal = (Collection<Object>) ReflectionUtils.getFieldVal(false, classLoader,
                        fieldName);
                if (fieldVal == null) {
                    continue;
                }
                elements.addAll(fieldVal);
            }
        }
        return elements;
    }

    /**
     * Find the classpath order for {@code io.quarkus.runner.RuntimeClassLoader} (Quarkus 1.2 and earlier).
     *
     * @param classLoader
     *            the classloader
     * @param classpathOrder
     *            the classpath order to add to
     * @param log
     *            the log node, or null to skip logging
     */
    @SuppressWarnings("unchecked")
    private static void findClasspathOrderForRuntimeClassloader(final ClassLoader classLoader,
            final ClasspathOrder classpathOrder, final @Nullable LogNode log) {
        final var applicationClassDirectories = (Collection<Path>) ReflectionUtils.getFieldVal(false, classLoader,
                "applicationClassDirectories");
        if (applicationClassDirectories != null) {
            for (final Path path : applicationClassDirectories) {
                try {
                    final var uri = path.toUri();
                    classpathOrder.addClasspathEntryObject(uri, classLoader, log);
                } catch (IOError | SecurityException e) {
                    if (log != null) {
                        log.log("Could not convert path to URI: " + path);
                    }
                }
            }
        }
    }

    /**
     * Find the classpath order for {@code io.quarkus.bootstrap.runner.RunnerClassLoader} (Quarkus 1.13+).
     *
     * @param classLoader
     *            the classloader
     * @param classpathOrder
     *            the classpath order to add to
     * @param log
     *            the log node, or null to skip logging
     */
    @SuppressWarnings("unchecked")
    private static void findClasspathOrderForRunnerClassloader(final ClassLoader classLoader,
            final ClasspathOrder classpathOrder, final @Nullable LogNode log) {
        // (getFieldVal returns null if the field is not present -- Quarkus renames these fields between releases,
        // so don't assume the field was found)
        final var resourceDirectoryMap = (Map<String, Object[]>) ReflectionUtils.getFieldVal(false, classLoader,
                "resourceDirectoryMap");
        if (resourceDirectoryMap == null) {
            return;
        }
        for (final Object[] elementArray : resourceDirectoryMap.values()) {
            for (final Object element : elementArray) {
                final var elementClassName = element.getClass().getName();
                if ("io.quarkus.bootstrap.runner.JarResource".equals(elementClassName)) {
                    classpathOrder.addClasspathEntry(ReflectionUtils.getFieldVal(false, element, "jarPath"),
                            classLoader, log);
                }
            }
        }
    }

    /**
     * Get the automatic package root prefixes for classpath elements obtained from this classloader.
     *
     * <p>
     * Classpath elements from this classloader can be in any of the common build-tool or packaged-archive layouts,
     * so the default package root prefixes are looked for.
     *
     * @return the package root prefixes.
     */
    @Override
    public String[] getPackageRootPrefixes() {
        return ClassLoaderHandlerRegistry.DEFAULT_PACKAGE_ROOT_PREFIXES;
    }
}
