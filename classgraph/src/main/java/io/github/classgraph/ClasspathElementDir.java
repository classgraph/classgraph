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
package io.github.classgraph;

import java.io.File;
import java.io.IOError;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

import io.github.classgraph.ScanSpec.ScanSpecPathMatch;
import io.github.classgraph.Scanner.ClasspathEntryWorkUnit;
import io.github.classgraph.base.LogNode;
import io.github.classgraph.base.internal.path.FileUtils;
import io.github.classgraph.base.internal.path.URLPaths;
import io.github.classgraph.classpath.internal.ClasspathExpander;
import io.github.classgraph.vfs.Vfs;
import io.github.classgraph.vfs.VfsEntry;
import io.github.classgraph.vfs.VfsVisitor;
import org.jspecify.annotations.Nullable;

/** A directory classpath element, using the {@link Path} API. */
class ClasspathElementDir extends ClasspathElement {
    /** The directory at the root of the classpath element. */
    private final Path classpathEltPath;

    /** The virtual filesystem that the directory is enumerated and read through. */
    private final Vfs vfs;

    /**
     * A directory classpath element.
     *
     * @param workUnit
     *            the work unit -- workUnit.classpathEntryObj must be a {@link Path} object
     * @param vfs
     *            the virtual filesystem to enumerate and read the directory through
     * @param scanSpec
     *            the scan spec
     */
    ClasspathElementDir(final ClasspathEntryWorkUnit workUnit, final Vfs vfs, final ScanSpec scanSpec) {
        super(workUnit, scanSpec);
        this.classpathEltPath = (Path) Objects.requireNonNull(workUnit.classpathEntryObj);
        this.vfs = vfs;
    }

    @Override
    void open(final WorkQueue<ClasspathEntryWorkUnit> workQueue, final @Nullable LogNode log) {
        if (!scanSpec.scanDirs) {
            if (log != null) {
                log(classpathElementIdx,
                        "Skipping classpath element, since dir scanning is disabled: " + classpathEltPath, log);
            }
            skipClasspathElement = true;
            return;
        }
        try {
            // Auto-add the jarfiles in the lib dirs, and the classpath elements that the directory's manifest
            // declares -- an exploded jarfile in a directory declares the same classpath elements that the jarfile
            // it was exploded from declares. The child classpath entries are added in the order they were found,
            // since the classpath order determines which of two copies of the same class masks the other.
            var childClasspathEntryIdx = 0;
            for (final var childEntry : ClasspathExpander.childEntries(vfs.open(classpathEltPath), libDirPrefixes,
                    vfsSpec.isNestedJarsEnabled(), log)) {
                if (log != null) {
                    log(classpathElementIdx, childEntry.origin().getLogMessage() + ": " + childEntry.location(),
                            log);
                }
                final var childPath = childEntry.path();
                workQueue.addWorkUnit(
                        new ClasspathEntryWorkUnit(childPath == null ? childEntry.location() : childPath,
                                getClassLoaderString(), /* parentClasspathElement = */ this,
                                /* orderWithinParentClasspathElement = */ childClasspathEntryIdx++,
                                /* packageRootPrefix = */ "", packageRootPrefixes, libDirPrefixes));
            }
            // Only look for package roots if the package root is empty
            if (packageRootPrefix.isEmpty()) {
                for (final String packageRootPrefix : packageRootPrefixes) {
                    final var packageRoot = classpathEltPath.resolve(packageRootPrefix);
                    if (FileUtils.canReadAndIsDir(packageRoot)) {
                        if (log != null) {
                            log(classpathElementIdx, "Found package root: " + packageRootPrefix, log);
                        }
                        workQueue.addWorkUnit(new ClasspathEntryWorkUnit(packageRoot, getClassLoaderString(),
                                /* parentClasspathElement = */ this,
                                /* orderWithinParentClasspathElement = */ childClasspathEntryIdx++,
                                packageRootPrefix, packageRootPrefixes, libDirPrefixes));
                    }
                }
            }
        } catch (final IOException | SecurityException e) {
            if (log != null) {
                log(classpathElementIdx,
                        "Skipping classpath element, since dir cannot be accessed: " + classpathEltPath, log);
            }
            skipClasspathElement = true;
        }
    }

    /**
     * Create a new {@link Resource} object for a file discovered while scanning the directory, or looked up by
     * path.
     *
     * @param entry
     *            the entry in the virtual filesystem
     * @return the resource
     */
    private Resource newResource(final VfsEntry entry) {
        return new DirResource(entry);
    }

    /**
     * A {@link Resource} for a file in a directory classpath element.
     */
    private final class DirResource extends Resource {
        /**
         * Constructor.
         *
         * @param entry
         *            the file, as an entry in the virtual filesystem.
         */
        DirResource(final VfsEntry entry) {
            super(ClasspathElementDir.this, entry, stripLeadingSlashes(entry.getName()));
        }

        @Override
        public String getPathRelativeToClasspathElement() {
            return packageRootPrefix.isEmpty() ? getPath() : packageRootPrefix + getPath();
        }
    }

    /**
     * Strip any leading slashes from the name of an entry, to give the path of the resource relative to the package
     * root.
     *
     * @param entryName
     *            the name of the entry.
     * @return the name, without any leading slashes.
     */
    private static String stripLeadingSlashes(final String entryName) {
        var startIdx = 0;
        while (startIdx < entryName.length() && entryName.charAt(startIdx) == '/') {
            startIdx++;
        }
        return startIdx == 0 ? entryName : entryName.substring(startIdx);
    }

    /**
     * Get the {@link Resource} for a given relative path.
     *
     * @param relativePath
     *            The relative path of the {@link Resource} to return.
     * @return The {@link Resource} for the given relative path, or null if relativePath does not exist in this
     *         classpath element.
     */
    @Override
    @Nullable
    Resource getResource(final String relativePath) {
        try {
            final var entry = vfs.open(classpathEltPath).getEntry(relativePath);
            return entry == null ? null : newResource(entry);
        } catch (final IOException | SecurityException e) {
            return null;
        }
    }

    /**
     * Determine whether a directory should be scanned, and if so, how its files match the scan spec.
     *
     * @param dirRelativePathStr
     *            the path of the directory relative to the classpath element root
     * @param log
     *            the log node, or null to skip logging
     * @return the match status of the directory, or null if the recursive scan should stop at this directory.
     */
    private @Nullable ScanSpecPathMatch getDirMatchStatus(final String dirRelativePathStr,
            final @Nullable LogNode log) {
        if (nestedClasspathRootPrefixes != null && nestedClasspathRootPrefixes.contains(dirRelativePathStr)) {
            if (log != null) {
                log.log("Reached nested classpath root, stopping recursion to avoid duplicate scanning: "
                        + dirRelativePathStr);
            }
            return null;
        }

        // Skipping versioned sections in a directory classpath element is what makes a directory scan agree with
        // what the JVM would actually load, so no masking of versioned paths against base paths is needed (unlike
        // in ClasspathElementZip). When enableMultiReleaseVersions() is set, every version is reported under its
        // own versioned path, which recursing into these directories already does.
        if (isIgnoredVersionedPath(dirRelativePathStr)) {
            if (log != null) {
                log.log("Found unexpected nested versioned entry in directory classpath element -- skipping: "
                        + dirRelativePathStr);
            }
            return null;
        }

        // Accept/reject classpath elements based on dir resource paths
        if (!checkResourcePathAcceptReject(dirRelativePathStr, log)) {
            return null;
        }

        final var matchStatus = scanSpec.dirAcceptMatchStatus(dirRelativePathStr);
        if (matchStatus == ScanSpecPathMatch.HAS_REJECTED_PATH_PREFIX) {
            // Reached a rejected path -- stop the recursive scan
            if (log != null) {
                log.log("Reached rejected directory, stopping recursive scan: " + dirRelativePathStr);
            }
            return null;
        }
        if (matchStatus == ScanSpecPathMatch.NOT_WITHIN_ACCEPTED_PATH) {
            // Reached a non-accepted and non-rejected path -- stop the recursive scan
            return null;
        }
        return matchStatus;
    }

    /**
     * Record the last modified time of a directory, so that
     * {@link ScanResult#isClasspathContentsModifiedSinceScan()} can detect changes to it.
     *
     * @param dir
     *            the {@link Path} of the directory
     */
    private void recordLastModified(final Path dir) {
        try {
            final var file = dir.toFile();
            fileToLastModified.put(file, file.lastModified());
        } catch (final UnsupportedOperationException | SecurityException e) {
            // Ignore
        }
    }

    /**
     * Record the last modified time of a file, so that {@link ScanResult#isClasspathContentsModifiedSinceScan()}
     * can detect changes to it.
     *
     * @param entry
     *            the file, as an entry in the virtual filesystem
     */
    private void recordLastModified(final VfsEntry entry) {
        final var path = entry.getNioPath();
        if (path == null) {
            return;
        }
        try {
            fileToLastModified.put(path.toFile(), entry.getLastModifiedMillis());
        } catch (final UnsupportedOperationException | SecurityException e) {
            // Ignore
        }
    }

    /**
     * The {@link VfsVisitor} that applies the scan spec to the directory tree as the virtual filesystem walks it.
     * The walk visits the files of a directory before recursing into its subdirectories, and calls
     * {@link #enterDirectory(String)} for a directory before listing it, so the match status of the directory that
     * contains a file is always known by the time the file is visited.
     */
    private final class DirScanVisitor implements VfsVisitor {
        /** The log node for the classpath element, or null to skip logging. */
        private final @Nullable LogNode subLog;

        /** The match status of the directory currently being visited. */
        private ScanSpecPathMatch parentMatchStatus = ScanSpecPathMatch.NOT_WITHIN_ACCEPTED_PATH;

        /** True if the directory currently being visited is the package root. */
        private boolean isPackageRootDir;

        /** True if this classpath element is a modular jar. */
        private boolean isModularJar;

        /**
         * Constructor.
         *
         * @param subLog
         *            the log node for the classpath element, or null to skip logging.
         */
        DirScanVisitor(final @Nullable LogNode subLog) {
            this.subLog = subLog;
        }

        @Override
        public boolean enterDirectory(final String dirName) {
            if (containsRejectedClasspathElementResourcePath) {
                // A rejected resource path rejects the whole classpath element, so stop scanning the rest of it.
                // Refusing to enter any further directory ends the walk, since the files of a directory are only
                // visited once the directory has been entered.
                return false;
            }
            final var matchStatus = getDirMatchStatus(dirName, subLog);
            if (matchStatus == null) {
                return false;
            }
            parentMatchStatus = matchStatus;
            isPackageRootDir = "/".equals(dirName);
            // A directory classpath element is a modular jar if it has a module descriptor, which is read from the
            // package root before any other directory is entered
            isModularJar = getModuleName() != null;
            recordLastModified(isPackageRootDir ? classpathEltPath : classpathEltPath.resolve(dirName));
            return true;
        }

        @Override
        public boolean visitEntry(final VfsEntry entry) {
            final var entryName = entry.getName();
            if (parentMatchStatus == ScanSpecPathMatch.ANCESTOR_OF_ACCEPTED_PATH) {
                // The directory is only an ancestor of an accepted path, so none of its files are accepted -- but
                // the module descriptor of the package root is always read, so that the module name is known even
                // when the package root is not accepted
                if (isPackageRootDir && scanSpec.enableClassInfo && "module-info.class".equals(entryName)) {
                    addAcceptedResource(newResource(entry), parentMatchStatus, /* isClassfileOnly = */ true,
                            subLog);
                    recordLastModified(entry);
                }
                return true;
            }
            if (isIgnoredDefaultPackageClassfile(isModularJar, entryName)) {
                return true;
            }
            // Accept/reject classpath elements based on file resource paths
            if (!checkResourcePathAcceptReject(entryName, subLog)) {
                return false;
            }
            if (isAcceptedResourcePath(entryName, parentMatchStatus)) {
                addAcceptedResource(newResource(entry), parentMatchStatus, /* isClassfileOnly = */ false, subLog);
                recordLastModified(entry);
            } else if (subLog != null) {
                subLog.log("Skipping non-accepted file: " + entryName);
            }
            return true;
        }
    }

    /**
     * Hierarchically scan directory structure for classfiles and matching files.
     *
     * @param log
     *            the log node, or null to skip logging
     */
    @Override
    void scanPaths(final @Nullable LogNode log) {
        if (skipClasspathElement) {
            return;
        }
        if (scanned.getAndSet(true)) {
            // Should not happen
            throw new IllegalStateException("Already scanned classpath element " + this);
        }

        final var subLog = log == null ? null
                : log(classpathElementIdx, "Scanning Path classpath element " + getURI(), log);

        try {
            vfs.open(classpathEltPath).walk(new DirScanVisitor(subLog), subLog);
        } catch (final IOException | SecurityException e) {
            if (subLog != null) {
                subLog.log("Could not scan directory " + classpathEltPath + " : " + e);
            }
        }

        finishScanPaths(subLog);
    }

    /**
     * Get the module name from module descriptor.
     *
     * @return the module name
     */
    @Override
    public @Nullable String getModuleName() {
        return moduleNameFromModuleDescriptor == null || moduleNameFromModuleDescriptor.isEmpty() ? null
                : moduleNameFromModuleDescriptor;
    }

    /**
     * Get the directory {@link File}.
     *
     * @return The classpath element directory as a {@link File}, or null if this classpath element is not backed by
     *         a directory (should not happen).
     */
    @Override
    public @Nullable File getFile() {
        try {
            return classpathEltPath.toFile();
        } catch (final UnsupportedOperationException e) {
            return null;
        }
    }

    @Override
    URI getURI() {
        try {
            // On Windows, Path#toUri() puts the server of a UNC path in the URI authority, where java.net.URL
            // does not find it again
            return URLPaths.moveUNCServerIntoPath(classpathEltPath.toUri());
        } catch (IOError | SecurityException e) {
            throw new IllegalStateException("Could not convert to URI: " + classpathEltPath, e);
        }
    }

    @Override
    List<URI> getAllURIs() {
        return List.of(getURI());
    }

    /**
     * Return the classpath element directory as a String.
     *
     * @return the string
     */
    @Override
    public String toString() {
        try {
            // Path.toString() does not include the URI scheme for some reason
            return getURI().toString();
        } catch (IOError | SecurityException e) {
            return classpathEltPath.toString();
        }
    }

    @Override
    public int hashCode() {
        return classpathEltPath.hashCode();
    }

    @Override
    public boolean equals(final @Nullable Object obj) {
        if (obj == this) {
            return true;
        }
        if (!(obj instanceof final ClasspathElementDir other)) {
            return false;
        }
        return this.classpathEltPath.equals(other.classpathEltPath);
    }
}
