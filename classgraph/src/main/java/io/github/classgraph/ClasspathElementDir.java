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
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;

import io.github.classgraph.ScanSpec.ScanSpecPathMatch;
import io.github.classgraph.Scanner.ClasspathEntryWorkUnit;
import io.github.classgraph.base.LogNode;
import io.github.classgraph.base.internal.path.FileUtils;
import io.github.classgraph.base.internal.path.URLPaths;
import io.github.classgraph.classpath.internal.ClasspathExpander;
import io.github.classgraph.vfs.Vfs;
import io.github.classgraph.vfs.VfsEntry;
import io.github.classgraph.vfs.VfsRoot;
import io.github.classgraph.vfs.VfsVisitor;
import org.jspecify.annotations.Nullable;

/** A directory classpath element, using the {@link Path} API. */
final class ClasspathElementDir extends ClasspathElement {
    /** The directory at the root of the classpath element. */
    private final Path classpathEltPath;

    /** The virtual filesystem that the directory is enumerated and read through. */
    private final Vfs vfs;

    /**
     * A directory classpath element.
     *
     * @param workUnit
     *            the work unit
     * @param classpathEltPath
     *            the normalized classpath entry of the work unit, which is the directory
     * @param vfs
     *            the virtual filesystem to enumerate and read the directory through
     * @param scanSpec
     *            the scan spec
     */
    ClasspathElementDir(final ClasspathEntryWorkUnit workUnit, final Path classpathEltPath, final Vfs vfs,
            final ScanSpec scanSpec) {
        super(workUnit.classLoaderStr(), workUnit.packageRootPrefix(), workUnit.packageRootPrefixes(),
                workUnit.libDirPrefixes(), vfs, scanSpec);
        this.classpathEltPath = classpathEltPath;
        this.vfs = vfs;
    }

    @Override
    void open(final WorkQueue<ClasspathEntryWorkUnit> workQueue, final @Nullable LogNode log) {
        if (!scanSpec.scanDirs) {
            if (log != null) {
                log.log("Skipping classpath element, since dir scanning is disabled: " + classpathEltPath);
            }
            skipClasspathElement = true;
            return;
        }
        try {
            // Schedule the child classpath elements in the order the classloader would look in them, since the
            // classpath order determines which of two copies of the same class masks the other
            final var root = vfs.open(classpathEltPath);
            readModuleNameFromManifest(root, log);
            final var childScheduler = new ChildClasspathElementScheduler(workQueue, classpathEltPath);

            // A package root within the directory is a classpath element in its own right. It comes first, because
            // a classloader that serves a directory with a package root looks there before it looks in the
            // directory's lib dirs. Only look for a package root if this classpath element is not already one,
            // since a package root does not contain another.
            if (packageRootPrefix.isEmpty()) {
                for (final String packageRootPrefix : packageRootPrefixes) {
                    final var packageRoot = classpathEltPath.resolve(packageRootPrefix);
                    if (FileUtils.canReadAndIsDir(packageRoot)) {
                        if (log != null) {
                            log.log("Found package root: " + packageRootPrefix);
                        }
                        childScheduler.schedule(packageRoot, packageRootPrefix);
                    }
                }
            }

            // Then the classpath entries the directory's manifest declares, and last the jarfiles in its lib dirs
            // -- an exploded jarfile in a directory declares the same classpath elements that the jarfile it was
            // exploded from declares
            for (final var childEntry : ClasspathExpander.childEntries(root, libDirPrefixes,
                    vfsSpec.isNestedJarsEnabled(), log)) {
                if (log != null) {
                    log.log(childEntry.origin().getLogMessage() + ": " + childEntry.location());
                }
                // A classpath element that this one declares has to pass the user's classpath element filters, the
                // same as one that was named directly. The location is tested rather than the path, since the
                // string form of a path within a jarfile leaves out the path of the jarfile.
                if (!scanSpec.classpathSpec.acceptsClasspathElement(/* classpathElementURL = */ null,
                        childEntry.location())) {
                    if (log != null) {
                        log(classpathElementIdx, "Classpath element did not match filter criterion, skipping: "
                                + childEntry.location(), log);
                    }
                    continue;
                }
                final var childPath = childEntry.path();
                childScheduler.schedule(childPath == null ? childEntry.location() : childPath,
                        /* childPackageRootPrefix = */ "");
            }
        } catch (final IOException _) {
            if (log != null) {
                log.log("Skipping classpath element, since dir cannot be accessed: " + classpathEltPath);
            }
            skipClasspathElement = true;
        }
    }

    /**
     * Make a {@link Resource} for a file in this directory classpath element.
     *
     * @param entry
     *            the file, as an entry in the virtual filesystem.
     * @return the resource.
     */
    private Resource newResource(final VfsEntry entry) {
        return new Resource(this, entry, stripLeadingSlashes(entry.getPathFromRoot()));
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
     * @return A new {@link Resource} for the given relative path, or null if relativePath does not exist in this
     *         classpath element, or is a path that the scan skips.
     */
    @Override
    @Nullable
    Resource getResource(final String relativePath) {
        if (isSkippedPath(relativePath)) {
            return null;
        }
        try {
            final var entry = vfs.open(classpathEltPath).getEntry(relativePath);
            return entry == null ? null : newResource(entry);
        } catch (final IOException _) {
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
        // in ClasspathElementZip). When disableMultiReleaseVersions() is set, every version is reported under its
        // own versioned path, which recursing into these directories already does.
        if (isIgnoredVersionedPath(dirRelativePathStr)) {
            if (log != null) {
                log.log("Found unexpected nested versioned entry in directory classpath element -- skipping: "
                        + dirRelativePathStr);
            }
            return null;
        }

        final var matchStatus = scanSpec.dirAcceptMatchStatus(dirRelativePathStr);
        if (!scanSpec.classpathElementResourcePathAcceptReject.acceptAndRejectAreEmpty()) {
            // Every file has to be offered to checkResourcePathAcceptReject, as it is in a jarfile or a module, so
            // that a marker file outside the accepted paths still selects or rejects the classpath element. The
            // files of a rejected or non-accepted directory are not accepted, since isAcceptedResourcePath is false
            // for its match status.
            return matchStatus;
        }
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
        } catch (final UnsupportedOperationException _) {
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
        } catch (final UnsupportedOperationException _) {
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
            recordLastModified(isPackageRootDir ? classpathEltPath : classpathEltPath.resolve(dirName));
            return true;
        }

        @Override
        public boolean visitEntry(final VfsEntry entry) {
            final var entryName = entry.getPathFromRoot();
            // Accept/reject classpath elements based on file resource paths, including the paths of files that are
            // not themselves accepted
            if (!checkResourcePathAcceptReject(entryName, subLog)) {
                return false;
            }
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
        } catch (final IOException e) {
            if (subLog != null) {
                subLog.log("Could not scan directory " + classpathEltPath + " : " + e);
            }
        }

        finishScanPaths(subLog);
    }

    /**
     * Get the module name declared by the directory, either by its {@code module-info.class} module descriptor, or
     * by the {@code Automatic-Module-Name} attribute of its manifest file, so that a jarfile exploded into a
     * directory is named the same way the jarfile it was exploded from is.
     *
     * <p>
     * Unlike {@link ClasspathElementZip#getModuleName()}, no name is derived from the name of the directory when it
     * declares none: the module system derives an automatic module name from the filename of a <i>jarfile</i>, and
     * a directory placed on the module path without a module descriptor is not a module at all, so there is no name
     * that a directory would be given if it were placed on the module path.
     *
     * @return the module name, or null if the directory declares none.
     */
    @Override
    public @Nullable String getModuleName() {
        return getDeclaredModuleName();
    }

    /**
     * Read the automatic module name that the manifest describing this classpath element declares, if any. This is
     * read for a directory as well as for a jarfile, so that a jarfile exploded into a directory declares the same
     * module name that the jarfile it was exploded from declares. A manifest that cannot be read is logged, and the
     * directory is still scanned.
     *
     * @param root
     *            the directory, as a root of the virtual filesystem.
     * @param log
     *            the log, or null to skip logging.
     */
    private void readModuleNameFromManifest(final VfsRoot root, final @Nullable LogNode log) {
        final var manifestRoot = openManifestRoot(root);
        if (manifestRoot != null) {
            try {
                moduleNameFromManifestFile = manifestRoot.getModuleName();
            } catch (final IOException e) {
                if (log != null) {
                    log.log("Could not read the manifest of " + classpathEltPath + " : " + e);
                }
            }
        }
    }

    /**
     * Open the root whose manifest describes this classpath element: the directory itself, or, if this classpath
     * element is a package root within a directory, the directory that the package root lies within. A directory
     * opened at a package root is opened as the subdirectory it is, with nothing linking it back to the directory
     * it lies within, so the container is reached by climbing back out of it.
     *
     * @param root
     *            the directory, as a root of the virtual filesystem.
     * @return the root whose manifest describes this classpath element, or null if the container could not be
     *         opened.
     */
    private @Nullable VfsRoot openManifestRoot(final VfsRoot root) {
        if (packageRootPrefix.isEmpty()) {
            return root;
        }
        // Climb out of the package root by one directory per segment of the prefix. The prefix has a trailing '/',
        // so the number of segments is the number of separators in it
        Path containerPath = classpathEltPath;
        for (var i = packageRootPrefix.indexOf('/'); i >= 0 && containerPath != null; //
                i = packageRootPrefix.indexOf('/', i + 1)) {
            containerPath = containerPath.getParent();
        }
        if (containerPath == null) {
            return null;
        }
        try {
            return vfs.open(containerPath);
        } catch (final IOException _) {
            return null;
        }
    }

    /**
     * Get the directory {@link File}.
     *
     * @return The classpath element directory as a {@link File}, or null if the directory is in a filesystem that
     *         supports the {@link Path} API but not the {@link File} API.
     */
    @Override
    public @Nullable File getFile() {
        try {
            return classpathEltPath.toFile();
        } catch (final UnsupportedOperationException _) {
            return null;
        }
    }

    @Override
    URI getURI() {
        return URLPaths.toURI(classpathEltPath);
    }

    @Override
    List<URI> getAllURIs() {
        return List.of(getURI());
    }

    /**
     * Return the classpath element directory as a String.
     *
     * @return the URI of the directory, as a string
     */
    @Override
    public String toString() {
        try {
            // Path.toString() does not include the URI scheme for some reason
            return getURI().toString();
        } catch (final IllegalStateException _) {
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
