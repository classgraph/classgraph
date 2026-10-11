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
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Queue;
import java.util.Set;

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
        // All three classloaders look in their own classpath elements before their parent, except a
        // QuarkusClassLoader that was built to look in its parent first, which its "parentFirst" field records (if
        // the field cannot be read, the parent is put first). Each of them also sends the classes of some packages
        // or artifacts to its parent first (the JDK's packages, a configured set of parent-first packages or
        // artifacts, or generated framework classes), which cannot be represented here.
        final var parentFirst = QUARKUS_CLASSLOADER.equals(findQuarkusClassLoaderName(classLoader.getClass()))
                && !Boolean.FALSE.equals(ReflectionUtils.getFieldVal(false, classLoader, "parentFirst"));
        if (parentFirst) {
            classLoaderOrder.delegateToParent(classLoader.getParent(), log);
        }
        classLoaderOrder.add(classLoader, log);
        if (!parentFirst) {
            classLoaderOrder.delegateToParent(classLoader.getParent(), log);
        }
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
        for (final Object element : findQuarkusClassLoaderElements(classLoader)) {
            if (element == null) {
                continue;
            }
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
     * @return a snapshot of the classpath elements (empty if none of the fields were found, or if they do not hold
     *         collections).
     */
    private static Collection<?> findQuarkusClassLoaderElements(final ClassLoader classLoader) {
        // Copy the collections, rather than handing back a live field of a classloader that the running
        // application may still be adding elements to while the scan iterates them
        final Collection<Object> elements = new ArrayList<>();
        if (ReflectionUtils.getFieldVal(false, classLoader,
                "elements") instanceof final Collection<?> singleField) {
            elements.addAll(singleField);
        } else {
            // Since 3.16.x
            for (final String fieldName : new String[] { "normalPriorityElements", "lesserPriorityElements" }) {
                if (ReflectionUtils.getFieldVal(false, classLoader,
                        fieldName) instanceof final Collection<?> fieldVal) {
                    elements.addAll(fieldVal);
                }
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
    private static void findClasspathOrderForRuntimeClassloader(final ClassLoader classLoader,
            final ClasspathOrder classpathOrder, final @Nullable ClassGraphLog log) {
        if (ReflectionUtils.getFieldVal(false, classLoader,
                "applicationClassDirectories") instanceof final Collection<?> applicationClassDirectories) {
            for (final Object element : applicationClassDirectories) {
                if (!(element instanceof final Path path)) {
                    continue;
                }
                try {
                    final var uri = path.toUri();
                    classpathOrder.addClasspathEntryObject(uri, classLoader, log);
                } catch (final IOError _) {
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
    private void findClasspathOrderForRunnerClassloader(final ClassLoader classLoader,
            final ClasspathOrder classpathOrder, final @Nullable ClassGraphLog log) {
        // Quarkus renames and retypes these fields between releases, so neither the field nor the type of its
        // value is assumed (getFieldVal returns null if the field is not present)
        if (!(ReflectionUtils.getFieldVal(false, classLoader,
                "resourceDirectoryMap") instanceof final Map<?, ?> resourceDirectoryMap)) {
            return;
        }
        // Each value lists the jars that serve one resource directory, in classpath order, and the classloader
        // loads a class from the first jar listed for its package that contains it. The map is a HashMap in
        // Quarkus, and the full jar order is not kept, so iterating the map does not give the classpath order.
        // Instead, merge the per-directory orders with a topological sort: a jar comes after every jar listed
        // before it for some directory, and ties go to the jar seen first. This puts any two jars that serve the
        // same directory, and so can hold the same class, in the order the classloader uses.
        final Map<Object, Integer> jarIndex = new IdentityHashMap<>();
        final List<Object> jars = new ArrayList<>();
        final List<Set<Integer>> successors = new ArrayList<>();
        for (final Object value : resourceDirectoryMap.values()) {
            if (!(value instanceof final Object[] elementArray)) {
                continue;
            }
            int prevIndex = -1;
            for (final Object element : elementArray) {
                if (element == null || !classIsOrExtendsOrImplements(element.getClass(), JAR_RESOURCE)) {
                    continue;
                }
                Integer index = jarIndex.get(element);
                if (index == null) {
                    index = jars.size();
                    jarIndex.put(element, index);
                    jars.add(element);
                    successors.add(new HashSet<>());
                }
                if (prevIndex >= 0 && prevIndex != index) {
                    successors.get(prevIndex).add(index);
                }
                prevIndex = index;
            }
        }
        final int[] inDegree = new int[jars.size()];
        for (final Set<Integer> jarSuccessors : successors) {
            for (final int successor : jarSuccessors) {
                inDegree[successor]++;
            }
        }
        final Queue<Integer> ready = new PriorityQueue<>();
        for (int i = 0; i < inDegree.length; i++) {
            if (inDegree[i] == 0) {
                ready.add(i);
            }
        }
        final boolean[] added = new boolean[jars.size()];
        int firstNotAdded = 0;
        while (true) {
            Integer next = ready.poll();
            if (next == null) {
                // Either every jar has been added, or the remaining jars are ordered in a cycle, which Quarkus
                // does not produce. Break a cycle at the jar seen first, rather than leaving its jars out.
                while (firstNotAdded < added.length && added[firstNotAdded]) {
                    firstNotAdded++;
                }
                if (firstNotAdded == added.length) {
                    break;
                }
                next = firstNotAdded;
            }
            if (added[next]) {
                continue;
            }
            added[next] = true;
            classpathOrder.addClasspathEntry(ReflectionUtils.getFieldVal(false, jars.get(next), "jarPath"),
                    classLoader, log);
            for (final int successor : successors.get(next)) {
                if (--inDegree[successor] == 0) {
                    ready.add(successor);
                }
            }
        }
    }
}
