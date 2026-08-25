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

import io.github.classgraph.base.ClassGraphLog;
import io.github.classgraph.base.internal.reflection.ReflectionUtils;
import io.github.classgraph.classpath.ClassLoaderHandler;
import io.github.classgraph.classpath.ClassLoaderOrder;
import io.github.classgraph.classpath.ClasspathOrder;
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

    /** The names of {@link #PRE_311_RESOURCE_BASED_ELEMENTS}, as an array, to match an element class against. */
    private static final String[] PRE_311_RESOURCE_BASED_ELEMENT_NAMES = PRE_311_RESOURCE_BASED_ELEMENTS.keySet()
            .toArray(new String[0]);

    /** The classpath element class that a {@code RunnerClassLoader} serves the contents of a jarfile from. */
    private static final String JAR_RESOURCE = "io.quarkus.bootstrap.runner.JarResource";

    /** Constructor. */
    QuarkusClassLoaderHandler() {
    }

    @Override
    public boolean canHandle(final Class<?> classLoaderClass, final @Nullable ClassGraphLog log) {
        return findQuarkusClassLoaderName(classLoaderClass) != null;
    }

    /**
     * Find which of the Quarkus classloaders a classloader class is, extends or implements.
     *
     * <p>
     * The three classloaders are unrelated to each other, so at most one of them can match. This is used both to
     * decide whether this handler can handle a classloader and to decide how to read its classpath entries, so that
     * a subclass of a Quarkus classloader -- which {@link #canHandle(Class, ClassGraphLog)} accepts -- has its
     * classpath entries read by the same code as the classloader it extends.
     *
     * @param classLoaderClass
     *            the classloader class.
     * @return the name of the Quarkus classloader that the class is, extends or implements, or null if it is none
     *         of them.
     */
    private @Nullable String findQuarkusClassLoaderName(final Class<?> classLoaderClass) {
        return findMatchingClassName(classLoaderClass, RUNTIME_CLASSLOADER, QUARKUS_CLASSLOADER,
                RUNNER_CLASSLOADER);
    }

    @Override
    public void findClassLoaderOrder(final ClassLoader classLoader, final ClassLoaderOrder classLoaderOrder,
            final @Nullable ClassGraphLog log) {
        classLoaderOrder.delegateTo(classLoader.getParent(), /* isParent = */ true, log);
        classLoaderOrder.add(classLoader, log);
    }

    @Override
    public void findClasspathOrder(final ClassLoader classLoader, final ClasspathOrder classpathOrder,
            final @Nullable ClassGraphLog log) {
        // Match the classloader the same way canHandle did, so that a subclass of one of the Quarkus classloaders
        // is read as the classloader it extends, rather than falling through every branch and adding nothing
        final var classLoaderName = findQuarkusClassLoaderName(classLoader.getClass());
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
    private void findClasspathOrderForQuarkusClassloader(final ClassLoader classLoader,
            final ClasspathOrder classpathOrder, final @Nullable ClassGraphLog log) {

        final var elements = findQuarkusClassLoaderElements(classLoader);

        for (final Object element : elements) {
            final var elementClassName = findMatchingClassName(element.getClass(),
                    PRE_311_RESOURCE_BASED_ELEMENT_NAMES);
            if (elementClassName != null) {
                classpathOrder.addClasspathEntry(ReflectionUtils.getFieldVal(false, element,
                        PRE_311_RESOURCE_BASED_ELEMENTS.get(elementClassName)), classLoader, log);
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
            final ClasspathOrder classpathOrder, final @Nullable ClassGraphLog log) {
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
    private void findClasspathOrderForRunnerClassloader(final ClassLoader classLoader,
            final ClasspathOrder classpathOrder, final @Nullable ClassGraphLog log) {
        // (getFieldVal returns null if the field is not present -- Quarkus renames these fields between releases,
        // so don't assume the field was found)
        final var resourceDirectoryMap = (Map<String, Object[]>) ReflectionUtils.getFieldVal(false, classLoader,
                "resourceDirectoryMap");
        if (resourceDirectoryMap == null) {
            return;
        }
        for (final Object[] elementArray : resourceDirectoryMap.values()) {
            for (final Object element : elementArray) {
                if (classIsOrExtendsOrImplements(element.getClass(), JAR_RESOURCE)) {
                    classpathOrder.addClasspathEntry(ReflectionUtils.getFieldVal(false, element, "jarPath"),
                            classLoader, log);
                }
            }
        }
    }

}
