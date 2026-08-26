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
import io.github.classgraph.base.internal.path.PathSyntax;
import io.github.classgraph.classpath.internal.ClasspathExpander;
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
        final String canonicalPath;
        try {
            // The classpath element is opened in the form the classloader named it with, so that a child of it is
            // resolved in the filesystem that it lives in. The root is not closed here, because the virtual
            // filesystem owns it, and hands the same root back to whoever reads the classpath element next.
            final var root = entry.open(vfs);
            canonicalPath = root.getPath();
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
            final var childLocation = spelledAsReached(childEntry.location(), canonicalPath, location);
            if (log != null) {
                log.log(childEntry.origin().getLogMessage() + ": " + childLocation);
            }
            // A child classpath element is loaded by the classloader of the element that declared it, and inherits
            // its package roots and lib dirs. It is opened as a path of the filesystem that the element that
            // declared it lives in, where it has one, so that a classpath element outside the default filesystem
            // declares classpath elements that can be opened.
            final var childPath = childEntry.path();
            children.add(ClasspathEntry.of(childPath == null ? childLocation : childPath, childLocation,
                    entry.getClassLoaderName(), entry.getPackageRootPrefixes(), entry.getLibDirPrefixes()));
        }
        return children;
    }

    /**
     * Spell the path of a child classpath element the way the classpath element that declared it was spelled.
     *
     * <p>
     * A classpath element has to be opened for its manifest to be read, and opening it canonicalizes its path, so
     * that the same jarfile reached by two different paths is only opened once. A classpath element is reported by
     * the path it was reached at though, so without this, a jarfile reached through a symlink (or, on Windows,
     * through an 8.3 short name) would declare classpath elements under a directory that no classpath element was
     * reported under, and the same classpath element reached both ways would be reported twice.
     *
     * @param childPath
     *            the path of the child classpath element, as resolved against the canonical path of the classpath
     *            element that declared it.
     * @param canonicalPath
     *            the canonical path of the classpath element that declared it.
     * @param reachedPath
     *            the path the classpath element that declared it was reached at.
     * @return the path of the child classpath element, spelled the way the classpath element that declared it was
     *         spelled.
     */
    // Visible for testing
    static String spelledAsReached(final String childPath, final String canonicalPath, final String reachedPath) {
        // Only the outermost path component names a file on disk, so only it can be canonicalized. (The paths
        // differ in more than that component if the classpath element is a package root within a jarfile, e.g.
        // "/dir/spring-boot-app.jar!/BOOT-INF/classes", since that is not part of the path of the jarfile.)
        final var canonicalPling = PathSyntax.indexOfNestedJarSeparator(canonicalPath);
        final var reachedPling = PathSyntax.indexOfNestedJarSeparator(reachedPath);
        final var canonicalJarPath = canonicalPling < 0 ? canonicalPath
                : canonicalPath.substring(0, canonicalPling);
        final var reachedJarPath = reachedPling < 0 ? reachedPath : reachedPath.substring(0, reachedPling);
        if (canonicalJarPath.equals(reachedJarPath)) {
            // The path was not changed by canonicalization, which is the usual case
            return childPath;
        }
        // A Bundle-ClassPath entry or a lib dir jar is a path within the classpath element, so it starts with the
        // path of the classpath element itself
        if (isPathWithin(childPath, canonicalJarPath)) {
            return reachedJarPath + childPath.substring(canonicalJarPath.length());
        }
        // A Class-Path entry is resolved against the directory the jarfile is in, so it starts with that directory
        // instead. A Class-Path entry that is an absolute path elsewhere starts with neither, and is left alone.
        final var canonicalDirPath = PathSyntax.getParentDirPath(canonicalJarPath);
        return canonicalDirPath.isEmpty() || !isPathWithin(childPath, canonicalDirPath) ? childPath
                : PathSyntax.getParentDirPath(reachedJarPath) + childPath.substring(canonicalDirPath.length());
    }

    /**
     * Determine whether a path is a given path, or lies within it.
     *
     * <p>
     * The prefix has to be followed by a path separator, so that {@code /dir/lib.jar} is not treated as a prefix of
     * the path of the unrelated file {@code /dir/lib.jar.bak}. The separator is either the {@code '/'} that
     * separates the components of a path, or the {@code '!'} of the {@code "!/"} that separates the path of a
     * jarfile from a path within it.
     *
     * @param path
     *            the path.
     * @param prefix
     *            the path it may lie within, with no trailing separator.
     * @return true if the path is the prefix, or lies within it.
     */
    private static boolean isPathWithin(final String path, final String prefix) {
        if (!path.startsWith(prefix)) {
            return false;
        }
        if (path.length() == prefix.length()) {
            return true;
        }
        final var nextChar = path.charAt(prefix.length());
        return nextChar == '/' || nextChar == '!';
    }
}
