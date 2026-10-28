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
 * Copyright (c) 2026 Luke Hutchison
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
package io.github.classgraph.classpath;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import org.jspecify.annotations.Nullable;

import io.github.classgraph.base.LogNode;
import io.github.classgraph.classpath.internal.ClasspathExpander;
import io.github.classgraph.classpath.internal.ClasspathOrderBuilder;
import io.github.classgraph.vfs.Vfs;

/**
 * Recursively expands initial classpath entries with entries declared in their automatic lib directories and
 * manifest {@code Class-Path} and {@code Bundle-ClassPath} attributes, in depth-first resolution order.
 */
final class TransitiveClasspath {
    /** The virtual filesystem used to open classpath elements. */
    private final Vfs vfs;

    /** The log node, or null to skip logging. */
    private final @Nullable LogNode log;

    /** Filter determining which classpath elements to retain and expand. */
    private final Predicate<? super ClasspathEntry> filter;

    /** Locations already added to the expanded classpath. */
    private final Set<String> alreadyAdded = new HashSet<>();

    /** The expanded classpath entries. */
    private final List<ClasspathEntry> expanded = new ArrayList<>();

    /**
     * Constructor.
     *
     * @param vfs
     *            the virtual filesystem used to open classpath elements.
     * @param log
     *            the log node, or null to skip logging.
     * @param filter
     *            filter determining which classpath elements to retain.
     */
    private TransitiveClasspath(final Vfs vfs, final @Nullable LogNode log,
            final Predicate<? super ClasspathEntry> filter) {
        this.vfs = vfs;
        this.log = log;
        this.filter = filter;
    }

    /**
     * Expands {@code entries} depth-first with transitive classpath entries declared by each accepted entry.
     *
     * @param entries
     *            the initial classpath entries.
     * @param filter
     *            filter determining which classpath entries to keep and expand.
     * @param vfs
     *            the virtual filesystem used to open classpath entries.
     * @param log
     *            the log node, or null to skip logging.
     * @return the expanded, deduplicated list of classpath entries.
     * @throws IllegalStateException
     *             if the thread was interrupted.
     */
    static List<ClasspathEntry> expand(final List<ClasspathEntry> entries,
            final Predicate<? super ClasspathEntry> filter, final Vfs vfs, final @Nullable LogNode log) {
        final var classpath = new TransitiveClasspath(vfs, log, filter);
        classpath.addAll(entries);
        return classpath.expanded;
    }

    /**
     * Performs depth-first expansion of {@code entries}.
     *
     * @param entries
     *            the initial classpath entries.
     */
    private void addAll(final List<ClasspathEntry> entries) {
        // The classpath elements are expanded depth first, with an explicit stack rather than by recursing, since
        // the depth is decided by the jarfiles that are read: a long enough chain of jarfiles that each declare the
        // next one in a Class-Path manifest entry would otherwise overflow the stack
        final Deque<ClasspathEntry> toAdd = new ArrayDeque<>();
        pushInReverse(toAdd, entries);
        while (!toAdd.isEmpty()) {
            final var entry = toAdd.pop();
            final var location = entry.getLocation();
            if (alreadyAdded.contains(location)) {
                // The classpath element was already reached by a shorter route, so it keeps its earlier position
                continue;
            }
            if (!filter.test(entry)) {
                // A rejected location is not recorded as added, so the filter is asked again if another route
                // reaches it
                if (log != null) {
                    log.log("Classpath element did not match filter criterion, skipping: " + location);
                }
                continue;
            }
            alreadyAdded.add(location);
            expanded.add(entry);
            // The children are added before anything that is still on the stack, so that a classpath element is
            // immediately followed by the classpath elements it declares
            pushInReverse(toAdd, children(entry));
        }
    }

    /**
     * Pushes {@code entries} onto {@code toAdd} in reverse order so they pop in list order.
     *
     * @param toAdd
     *            the stack.
     * @param entries
     *            the entries to push.
     */
    private static void pushInReverse(final Deque<ClasspathEntry> toAdd, final List<ClasspathEntry> entries) {
        for (var i = entries.size() - 1; i >= 0; --i) {
            toAdd.push(entries.get(i));
        }
    }

    /**
     * Reads the child classpath entries declared by {@code entry}.
     *
     * @param entry
     *            the parent classpath entry.
     * @return the child classpath entries in declaration order.
     */
    private List<ClasspathEntry> children(final ClasspathEntry entry) {
        final var location = entry.getLocation();
        final List<ClasspathExpander.ChildEntry> childEntries;
        try {
            // The classpath element is opened in the form the classloader named it with, so that a child of it is
            // resolved in the filesystem that it lives in. The root is not closed here, because the virtual
            // filesystem owns it, and hands the same root back to whoever reads the classpath element next.
            final var root = entry.open(vfs);
            childEntries = ClasspathExpander.childEntries(root, entry.getLibDirPrefixes(),
                    vfs.getVfsSpec().isNestedJarsEnabled(), log);
        } catch (final IOException | IllegalArgumentException e) {
            if (Thread.currentThread().isInterrupted()) {
                throw new IllegalStateException("Interrupted while reading the jarfiles on the classpath", e);
            }
            if (log != null) {
                // A classpath element does not have to exist, and does not have to be a jarfile or a directory
                log.log("Could not read " + location + " : " + (e.getCause() == null ? e : e.getCause()));
            }
            return List.of();
        }
        final List<ClasspathEntry> children = new ArrayList<>(childEntries.size());
        for (final var childEntry : childEntries) {
            if (log != null) {
                log.log(childEntry.origin().getLogMessage() + ": " + childEntry.location());
            }
            // A child classpath element is opened as a path of the filesystem that the element that declared it
            // lives in, where it has one, so that a classpath element outside the default filesystem declares
            // classpath elements that can be opened
            final var childPath = childEntry.path();
            final Object childObj = childPath == null ? childEntry.location() : childPath;
            // A child classpath element is located the same way as one that a classloader declared, by the
            // canonical path of its file, so that a jarfile that a manifest names through a symbolic link is the
            // same classpath element as that jarfile reached directly. One the filesystem says is not there, or
            // cannot be read, is skipped, with the reason logged
            final var childLocation = ClasspathOrderBuilder.toLocation(childObj, childEntry.location(), log);
            if (childLocation == null) {
                continue;
            }
            // A child classpath element is loaded by the classloader of the element that declared it, and inherits
            // its package roots and lib dirs
            children.add(ClasspathEntry.of(childObj, childLocation, entry.getClassLoaderName(),
                    entry.getPackageRootPrefixes(), entry.getLibDirPrefixes()));
        }
        return children;
    }
}
