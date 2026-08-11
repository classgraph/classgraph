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

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.jspecify.annotations.Nullable;

import io.github.classgraph.base.internal.concurrency.SingletonMap.NewInstanceException;
import io.github.classgraph.base.internal.concurrency.SingletonMap.NullSingletonException;
import io.github.classgraph.base.internal.utils.FastPathResolver;
import io.github.classgraph.base.internal.utils.FileUtils;
import io.github.classgraph.base.internal.utils.JarUtils;
import io.github.classgraph.base.internal.utils.LogNode;
import io.github.classgraph.classpath.internal.ClasspathExpander;
import io.github.classgraph.vfs.internal.spec.VfsScanSpec;
import io.github.classgraph.vfs.internal.zip.LogicalZipFile;
import io.github.classgraph.vfs.internal.zip.NestedJarHandler;

/**
 * Expands the classpath elements that the classloaders declared with the classpath elements that those in turn
 * declare: the jarfiles in their automatic lib dirs, and the entries of their manifests' {@code Class-Path} and
 * {@code Bundle-Classpath} attributes. Each of those can declare more of them, so this is recursive.
 *
 * <p>
 * The expanded classpath is in the order a classloader would search it: each classpath element is followed by the
 * elements it declares, depth first. A classpath element that is reached more than once is listed only at the first
 * position it is reached at, which is the position that decides which copy of a duplicated class is loaded.
 */
final class ClasspathExpansion {
    /** The settings that govern how the jarfiles are read. */
    private final VfsScanSpec vfsScanSpec;

    /** Opens the jarfiles, so that their manifests can be read. */
    private final NestedJarHandler nestedJarHandler;

    /** The log node, or null to skip logging. */
    private final @Nullable LogNode log;

    /** The locations that have already been added, so that no classpath element is listed twice. */
    private final Set<String> alreadyAdded = new HashSet<>();

    /** The expanded classpath. */
    private final List<ClasspathEntry> expanded = new ArrayList<>();

    /**
     * Constructor.
     *
     * @param vfsScanSpec
     *            the settings that govern how the jarfiles are read.
     * @param nestedJarHandler
     *            opens the jarfiles, so that their manifests can be read.
     * @param log
     *            the log node, or null to skip logging.
     */
    private ClasspathExpansion(final VfsScanSpec vfsScanSpec, final NestedJarHandler nestedJarHandler,
            final @Nullable LogNode log) {
        this.vfsScanSpec = vfsScanSpec;
        this.nestedJarHandler = nestedJarHandler;
        this.log = log;
    }

    /**
     * Expand the classpath elements that the classloaders declared with the classpath elements that those in turn
     * declare.
     *
     * @param entries
     *            the classpath elements that the classloaders declared.
     * @param vfsScanSpec
     *            the settings that govern how the jarfiles are read.
     * @param nestedJarHandler
     *            opens the jarfiles, so that their manifests can be read.
     * @param log
     *            the log node, or null to skip logging.
     * @return the expanded classpath.
     * @throws InterruptedException
     *             if the thread was interrupted.
     */
    static List<ClasspathEntry> expand(final List<ClasspathEntry> entries, final VfsScanSpec vfsScanSpec,
            final NestedJarHandler nestedJarHandler, final @Nullable LogNode log) throws InterruptedException {
        final var expansion = new ClasspathExpansion(vfsScanSpec, nestedJarHandler, log);
        for (final ClasspathEntry entry : entries) {
            expansion.addRec(entry);
        }
        return expansion.expanded;
    }

    /**
     * Add a classpath element, then add the classpath elements it declares, and so on.
     *
     * @param entry
     *            the classpath element.
     * @throws InterruptedException
     *             if the thread was interrupted.
     */
    private void addRec(final ClasspathEntry entry) throws InterruptedException {
        if (!alreadyAdded.add(entry.location())) {
            // The classpath element was already reached by a shorter route, so it keeps its earlier position
            return;
        }
        expanded.add(entry);
        for (final String childLocation : childLocations(entry.location())) {
            // A child classpath element is loaded by the classloader of the element that declared it, and inherits
            // its package roots
            addRec(new ClasspathEntry(childLocation, entry.classLoaderName(), entry.packageRootPrefixes()));
        }
    }

    /**
     * Find the locations of the classpath elements that a classpath element declares.
     *
     * @param location
     *            the location of the classpath element.
     * @return the locations of the classpath elements it declares, in the order they must be added to the
     *         classpath.
     * @throws InterruptedException
     *             if the thread was interrupted.
     */
    private List<String> childLocations(final String location) throws InterruptedException {
        try {
            final var file = new File(location);
            if (file.isDirectory()) {
                // A directory has no manifest, so the only classpath elements it declares are the jarfiles in its
                // automatic lib dirs
                return ClasspathExpander.libJarsInDir(file.toPath()).stream()
                        .map(libJarPath -> FastPathResolver.resolve(FileUtils.currDirPath(), libJarPath.toString()))
                        .toList();
            }
        } catch (final SecurityException e) {
            return List.of();
        }
        // Open the jarfile, so that its manifest can be read
        final var logicalZipFile = openJar(location);
        if (logicalZipFile == null) {
            return List.of();
        }
        final var zipFilePathResolved = FastPathResolver.resolve(FileUtils.currDirPath(), logicalZipFile.getPath());
        final List<String> childLocations = new ArrayList<>();
        for (final var childEntry : ClasspathExpander.childEntries(logicalZipFile, zipFilePathResolved,
                vfsScanSpec.scanNestedJars)) {
            final var childLocation = spelledAsReached(childEntry.path(), zipFilePathResolved, location);
            if (log != null) {
                log.log(childEntry.origin().getLogMessage() + ": " + childLocation);
            }
            childLocations.add(childLocation);
        }
        return childLocations;
    }

    /**
     * Spell the path of a child classpath element the way the classpath element that declared it was spelled.
     *
     * <p>
     * A jarfile has to be opened for its manifest to be read, and opening it canonicalizes its path, so that the
     * same jarfile reached by two different paths is only opened once. A classpath element is reported by the path
     * it was reached at though, so without this, a jarfile reached through a symlink (or, on Windows, through an
     * 8.3 short name) would declare classpath elements under a directory that no classpath element was reported
     * under, and the same classpath element reached both ways would be reported twice.
     *
     * @param childPath
     *            the path of the child classpath element, as resolved against the canonical path of the jarfile
     *            that declared it.
     * @param canonicalPath
     *            the canonical path of the jarfile that declared it.
     * @param reachedPath
     *            the path the classpath element that declared it was reached at.
     * @return the path of the child classpath element, spelled the way the classpath element that declared it was
     *         spelled.
     */
    private static String spelledAsReached(final String childPath, final String canonicalPath,
            final String reachedPath) {
        // Only the outermost path component names a file on disk, so only it can be canonicalized. (The paths
        // differ in more than that component if the classpath element is a package root within a jarfile, e.g.
        // "/dir/spring-boot-app.jar!/BOOT-INF/classes", since that is not part of the path of the jarfile.)
        final var canonicalPling = JarUtils.indexOfNestedJarSeparator(canonicalPath);
        final var reachedPling = JarUtils.indexOfNestedJarSeparator(reachedPath);
        final var canonicalJarPath = canonicalPling < 0 ? canonicalPath
                : canonicalPath.substring(0, canonicalPling);
        final var reachedJarPath = reachedPling < 0 ? reachedPath : reachedPath.substring(0, reachedPling);
        if (canonicalJarPath.equals(reachedJarPath)) {
            // The path was not changed by canonicalization, which is the usual case
            return childPath;
        }
        // A Bundle-Classpath entry is a path within the jarfile, so it starts with the path of the jarfile itself
        if (childPath.startsWith(canonicalJarPath)) {
            return reachedJarPath + childPath.substring(canonicalJarPath.length());
        }
        // A Class-Path entry or a lib dir jar is resolved against the directory the jarfile is in, so it starts
        // with that directory instead. A Class-Path entry that is an absolute path elsewhere starts with neither,
        // and is left alone.
        final var canonicalDirPath = FileUtils.getParentDirPath(canonicalJarPath);
        return canonicalDirPath.isEmpty() || !childPath.startsWith(canonicalDirPath + "/") ? childPath
                : FileUtils.getParentDirPath(reachedJarPath) + childPath.substring(canonicalDirPath.length());
    }

    /**
     * Open a jarfile, so that its manifest can be read.
     *
     * @param location
     *            the location of the jarfile.
     * @return the opened jarfile, or null if it could not be opened -- a classpath element does not have to exist,
     *         and does not have to be a jarfile.
     * @throws InterruptedException
     *             if the thread was interrupted.
     */
    private @Nullable LogicalZipFile openJar(final String location) throws InterruptedException {
        try {
            return nestedJarHandler.nestedPathToLogicalZipFileAndPackageRootMap().get(location, log).getKey();
        } catch (final IOException | IllegalArgumentException | NullSingletonException | NewInstanceException e) {
            if (log != null) {
                log.log("Could not read the manifest of " + location + " : "
                        + (e.getCause() == null ? e : e.getCause()));
            }
            return null;
        }
    }
}
