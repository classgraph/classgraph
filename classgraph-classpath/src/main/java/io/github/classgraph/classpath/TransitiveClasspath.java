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

import io.github.classgraph.base.LogNode;
import io.github.classgraph.classpath.internal.ClasspathExpander;
import io.github.classgraph.classpath.internal.ClasspathOrderBuilder;
import io.github.classgraph.vfs.Vfs;
import io.github.classgraph.vfs.VfsSpec;
import org.jspecify.annotations.Nullable;

/**
 * Expands the classpath elements that the classloaders declared with the classpath elements that those in turn
 * declare: the jarfiles in their automatic lib dirs, and the entries of their manifests' {@code Class-Path} and
 * {@code Bundle-ClassPath} attributes. Each of those can declare more of them, so this is recursive.
 *
 * <p>
 * The expanded classpath is in the order a classloader would search it: each classpath element is followed by the
 * elements it declares, depth first. A classpath element that is reached more than once is listed only at the first
 * position it is reached at, which is the position that decides which copy of a duplicated class is loaded.
 */
final class TransitiveClasspath {
    /** Opens the classpath elements, so that their manifests and their lib dirs can be read. */
    private final Vfs vfs;

    /** The settings that govern how the jarfiles are read. */
    private final VfsSpec vfsSpec;

    /** The log node, or null to skip logging. */
    private final @Nullable LogNode log;

    /** The locations that have already been added, so that no classpath element is listed twice. */
    private final Set<String> alreadyAdded = new HashSet<>();

    /** The expanded classpath. */
    private final List<ClasspathEntry> expanded = new ArrayList<>();

    /**
     * Constructor.
     *
     * @param vfs
     *            opens the classpath elements, so that their manifests and their lib dirs can be read.
     * @param vfsSpec
     *            the settings that govern how the jarfiles are read.
     * @param log
     *            the log node, or null to skip logging.
     */
    private TransitiveClasspath(final Vfs vfs, final VfsSpec vfsSpec, final @Nullable LogNode log) {
        this.vfs = vfs;
        this.vfsSpec = vfsSpec;
        this.log = log;
    }

    /**
     * Expand the classpath elements that the classloaders declared with the classpath elements that those in turn
     * declare.
     *
     * @param entries
     *            the classpath elements that the classloaders declared.
     * @param vfs
     *            opens the classpath elements, so that their manifests and their lib dirs can be read.
     * @param vfsSpec
     *            the settings that govern how the jarfiles are read.
     * @param log
     *            the log node, or null to skip logging.
     * @return the expanded classpath.
     * @throws IllegalStateException
     *             if the thread was interrupted.
     */
    static List<ClasspathEntry> expand(final List<ClasspathEntry> entries, final Vfs vfs, final VfsSpec vfsSpec,
            final @Nullable LogNode log) {
        final var classpath = new TransitiveClasspath(vfs, vfsSpec, log);
        classpath.addAll(entries);
        return classpath.expanded;
    }

    /**
     * Add classpath elements, then add the classpath elements each of them declares, and so on.
     *
     * @param entries
     *            the classpath elements.
     */
    private void addAll(final List<ClasspathEntry> entries) {
        // The classpath elements are expanded depth first, with an explicit stack rather than by recursing, since
        // the depth is decided by the jarfiles that are read: a long enough chain of jarfiles that each declare the
        // next one in a Class-Path manifest entry would otherwise overflow the stack
        final Deque<ClasspathEntry> toAdd = new ArrayDeque<>();
        pushInReverse(toAdd, entries);
        while (!toAdd.isEmpty()) {
            final var entry = toAdd.pop();
            if (!alreadyAdded.add(entry.getLocation())) {
                // The classpath element was already reached by a shorter route, so it keeps its earlier position
                continue;
            }
            expanded.add(entry);
            // The children are added before anything that is still on the stack, so that a classpath element is
            // immediately followed by the classpath elements it declares
            pushInReverse(toAdd, children(entry));
        }
    }

    /**
     * Push classpath elements onto the stack in reverse order, so that they are popped in the order they are listed
     * in.
     *
     * @param toAdd
     *            the stack.
     * @param entries
     *            the classpath elements to push.
     */
    private static void pushInReverse(final Deque<ClasspathEntry> toAdd, final List<ClasspathEntry> entries) {
        for (var i = entries.size() - 1; i >= 0; --i) {
            toAdd.push(entries.get(i));
        }
    }

    /**
     * Find the classpath elements that a classpath element declares.
     *
     * @param entry
     *            the classpath element.
     * @return the classpath elements it declares, in the order they must be added to the classpath.
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
                    vfsSpec.isNestedJarsEnabled(), log);
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
            // A child classpath element is located the same way as one that a classloader declared, by the canonical
            // path of its file, so that a jarfile that a manifest names through a symbolic link is the same classpath
            // element as that jarfile reached directly. One the filesystem says is not there, or cannot be read, is
            // skipped, with the reason logged
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
