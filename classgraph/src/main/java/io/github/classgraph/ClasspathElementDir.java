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
import java.io.InputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import io.github.classgraph.Scanner.ClasspathEntryWorkUnit;
import io.github.classgraph.base.internal.concurrency.WorkQueue;
import io.github.classgraph.base.internal.utils.FastPathResolver;
import io.github.classgraph.base.internal.utils.FileUtils;
import io.github.classgraph.base.internal.utils.LogNode;
import io.github.classgraph.classpath.internal.ClasspathExpander;
import io.github.classgraph.internal.scanspec.ScanSpec;
import io.github.classgraph.internal.scanspec.ScanSpec.ScanSpecPathMatch;
import io.github.classgraph.vfs.internal.ScanResources;
import io.github.classgraph.vfs.internal.slice.PathSlice;
import io.github.classgraph.vfs.internal.slice.reader.ClassfileReader;
import org.jspecify.annotations.Nullable;

/** A directory classpath element, using the {@link Path} API. */
class ClasspathElementDir extends ClasspathElement {
    /**
     * The {@link Resource#length} value indicating that the resource length has not yet been read.
     */
    private static final int NOT_YET_LOADED_LENGTH = -2;

    /** The directory at the root of the classpath element. */
    private final Path classpathEltPath;

    /**
     * Used to ensure that recursive scanning doesn't get into an infinite loop due to a link cycle.
     */
    private final Set<Path> scannedCanonicalPaths = new HashSet<>();

    /** The resources owned by the scan. */
    private final ScanResources scanResources;

    /**
     * A directory classpath element.
     *
     * @param workUnit
     *            the work unit -- workUnit.classpathEntryObj must be a {@link Path} object
     * @param scanResources
     *            the resources owned by the scan
     * @param scanSpec
     *            the scan spec
     */
    ClasspathElementDir(final ClasspathEntryWorkUnit workUnit, final ScanResources scanResources,
            final ScanSpec scanSpec) {
        super(workUnit, scanSpec);
        this.classpathEltPath = (Path) Objects.requireNonNull(workUnit.classpathEntryObj);
        this.scanResources = scanResources;
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
            // Auto-add nested lib dirs. The child classpath entries are added in the order they were found,
            // since the classpath order determines which of two copies of the same class masks the other.
            var childClasspathEntryIdx = 0;
            for (final Path libJarPath : ClasspathExpander.libJarsInDir(classpathEltPath)) {
                if (log != null) {
                    log(classpathElementIdx, "Found lib jar: " + libJarPath, log);
                }
                workQueue.addWorkUnit(new ClasspathEntryWorkUnit(libJarPath, getClassLoaderString(),
                        /* parentClasspathElement = */ this,
                        /* orderWithinParentClasspathElement = */ childClasspathEntryIdx++,
                        /* packageRootPrefix = */ "", packageRootPrefixes));
            }
            // Only look for package roots if the package root is empty
            if (packageRootPrefix.isEmpty()) {
                for (final String packageRootPrefix : packageRootPrefixes) {
                    final var packageRoot = classpathEltPath.resolve(packageRootPrefix);
                    if (FileUtils.canReadAndIsDir(packageRoot)) {
                        // "classes/" and "test-classes/" are legal package names, so check that the candidate
                        // package root is not simply a package with the same name (#929)
                        final var disprovingClassName = getClassNameDisprovingPackageRoot(packageRoot);
                        if (disprovingClassName != null) {
                            if (log != null) {
                                log(classpathElementIdx,
                                        "\"" + packageRootPrefix + "\" is a package, not a package root, since a "
                                                + "classfile beneath it declares the class " + disprovingClassName,
                                        log);
                            }
                            continue;
                        }
                        if (log != null) {
                            log(classpathElementIdx, "Found package root: " + packageRootPrefix, log);
                        }
                        workQueue.addWorkUnit(new ClasspathEntryWorkUnit(packageRoot, getClassLoaderString(),
                                /* parentClasspathElement = */ this,
                                /* orderWithinParentClasspathElement = */ childClasspathEntryIdx++,
                                packageRootPrefix, packageRootPrefixes));
                    }
                }
            }
        } catch (final SecurityException e) {
            if (log != null) {
                log(classpathElementIdx,
                        "Skipping classpath element, since dir cannot be accessed: " + classpathEltPath, log);
            }
            skipClasspathElement = true;
        }
    }

    /**
     * Find the first classfile beneath a directory, so that the class it declares can be compared to its path.
     *
     * @param dir
     *            the directory to search.
     * @return the first classfile found beneath the directory, or null if there are none. Classfiles beneath a
     *         {@code META-INF} directory are ignored, since the path of a classfile in a multi-release jar layout
     *         ({@code META-INF/versions/N/}) does not correspond to the name of the class it declares.
     */
    private static Path findFirstClassfile(final Path dir) {
        final var firstClassfile = new Path[1];
        try {
            Files.walkFileTree(dir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(final Path subDir, final BasicFileAttributes attrs) {
                    final var subDirName = subDir.getFileName();
                    return subDirName != null && "META-INF".equals(subDirName.toString())
                            ? FileVisitResult.SKIP_SUBTREE
                            : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(final Path file, final BasicFileAttributes attrs) {
                    if (file.getFileName().toString().endsWith(".class")) {
                        firstClassfile[0] = file;
                        return FileVisitResult.TERMINATE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(final Path file, final IOException e) {
                    // Ignore unreadable files
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (final IOException | SecurityException e) {
            // Ignore
        }
        return firstClassfile[0];
    }

    /**
     * Check whether a candidate package root directory is really a package root, or is simply a package that has
     * the same name as one of the automatic package root prefixes.
     *
     * @param packageRoot
     *            the candidate package root directory.
     * @return null if the candidate is a package root, otherwise the name of the class that disproves it (see
     *         {@link ClasspathElement#getClassNameDisprovingPackageRoot(ClassfileReader, String)}).
     */
    // #929
    private static @Nullable String getClassNameDisprovingPackageRoot(final Path packageRoot) {
        final var classfilePath = findFirstClassfile(packageRoot);
        if (classfilePath == null) {
            // There are no classfiles beneath the candidate package root, so there is nothing to check
            return null;
        }
        final var classfileRelativePath = packageRoot.relativize(classfilePath).toString()
                .replace(File.separatorChar, '/');
        try (var inputStream = Files.newInputStream(classfilePath);
                var classfileReader = new ClassfileReader(inputStream, /* resourceToClose = */ null)) {
            return getClassNameDisprovingPackageRoot(classfileReader, classfileRelativePath);
        } catch (final IOException | SecurityException e) {
            // If the classfile cannot be read, give the candidate package root the benefit of the doubt
            return null;
        }
    }

    /**
     * Create a new {@link Resource} object for a resource or classfile discovered while scanning paths.
     *
     * @param resourcePath
     *            the {@link Path} for the resource
     * @param attributes
     *            the file attributes of the resource, or null if not yet known
     * @return the resource
     */
    private Resource newResource(final Path resourcePath, final @Nullable BasicFileAttributes attributes) {
        return newResource(resourcePath,
                FastPathResolver.resolve(classpathEltPath.relativize(resourcePath).toString()), attributes);
    }

    /**
     * Create a new {@link Resource} object for a resource or classfile discovered while scanning paths, where the
     * resolved path of the resource relative to the classpath element has already been computed by the caller.
     *
     * @param resourcePath
     *            the {@link Path} for the resource
     * @param resourcePathRelativeStr
     *            the path of the resource relative to the classpath element root, as already resolved by
     *            {@link FastPathResolver#resolve(String)}
     * @param attributes
     *            the file attributes of the resource, or null if not yet known
     * @return the resource
     */
    private Resource newResource(final Path resourcePath, final String resourcePathRelativeStr,
            final @Nullable BasicFileAttributes attributes) {
        return new DirResource(resourcePath, resourcePathRelativeStr, attributes);
    }

    /**
     * A {@link Resource} for a file in a directory classpath element.
     */
    private class DirResource extends Resource {
        /** The {@link Path} of the file. */
        private final Path resourcePath;

        /** The path of the file relative to the classpath element root, with any leading slashes removed. */
        private final String path;

        /** The file attributes of the file, or null if not yet known. */
        private final @Nullable BasicFileAttributes attributes;

        /** The {@link PathSlice} opened on the file. */
        private @Nullable PathSlice pathSlice;

        /**
         * Constructor.
         *
         * @param resourcePath
         *            the {@link Path} for the resource.
         * @param resourcePathRelativeStr
         *            the path of the resource relative to the classpath element root.
         * @param attributes
         *            the file attributes of the resource, or null if not yet known.
         */
        DirResource(final Path resourcePath, final String resourcePathRelativeStr,
                final @Nullable BasicFileAttributes attributes) {
            super(ClasspathElementDir.this, attributes == null ? NOT_YET_LOADED_LENGTH : attributes.size());
            this.resourcePath = resourcePath;
            this.attributes = attributes;
            var startIdx = 0;
            while (startIdx < resourcePathRelativeStr.length() && resourcePathRelativeStr.charAt(startIdx) == '/') {
                startIdx++;
            }
            this.path = startIdx == 0 ? resourcePathRelativeStr : resourcePathRelativeStr.substring(startIdx);
        }

        @Override
        public long getLength() {
            if (length == NOT_YET_LOADED_LENGTH) {
                try {
                    length = Files.size(resourcePath);
                } catch (IOException | SecurityException e) {
                    length = -1;
                }
            }
            return length;
        }

        @Override
        public String getPath() {
            return path;
        }

        @Override
        public String getPathRelativeToClasspathElement() {
            return packageRootPrefix.isEmpty() ? getPath() : packageRootPrefix + getPath();
        }

        @Override
        public long getLastModifiedMillis() {
            try {
                return attributes == null ? resourcePath.toFile().lastModified()
                        : attributes.lastModifiedTime().toMillis();
            } catch (final UnsupportedOperationException e) {
                return 0L;
            }
        }

        @Override
        public @Nullable Set<PosixFilePermission> getPosixFilePermissions() {
            Set<PosixFilePermission> posixFilePermissions = null;
            try {
                if (attributes instanceof final PosixFileAttributes posixFileAttributes) {
                    posixFilePermissions = posixFileAttributes.permissions();
                } else {
                    posixFilePermissions = Files.readAttributes(resourcePath, PosixFileAttributes.class)
                            .permissions();
                }
            } catch (UnsupportedOperationException | IOException | SecurityException e) {
                // POSIX attributes not supported
            }
            return posixFilePermissions;
        }

        @Override
        public ByteBuffer read() throws IOException {
            byteBuffer = openAndCreateSlice().read();
            return byteBuffer;
        }

        @Override
        ClassfileReader openClassfile() throws IOException {
            // Classfile won't be compressed, so wrap it in a new PathSlice and then open it
            return new ClassfileReader(openAndCreateSlice(), this);
        }

        @Override
        public InputStream open() throws IOException {
            final var slice = openAndCreateSlice();
            inputStream = slice.open(this);
            return inputStream;
        }

        @Override
        public byte[] load() throws IOException {
            try {
                return openAndCreateSlice().load();
            } finally {
                close();
            }
        }

        @Override
        public void close() {
            if (markClosed()) {
                if (byteBuffer != null) {
                    // Any ByteBuffer ref should be a duplicate, so it doesn't need to be cleaned
                    byteBuffer = null;
                }
                final var slice = pathSlice;
                if (slice != null) {
                    // (PathSlice#close() marks the slice as closed)
                    slice.close();
                    pathSlice = null;
                }

                // Close inputStream
                super.close();
            }
        }

        private PathSlice openAndCreateSlice() throws IOException {
            checkCanOpen();
            // (A resource in a directory classpath element is read once and then closed, so it is not worth
            // memory-mapping it, even on a platform where files are memory-mapped)
            final var slice = new PathSlice(resourcePath, scanResources, /* checkAccess = */ false,
                    /* memoryMapWholeFile = */ false, /* log = */ null);
            pathSlice = slice;
            length = slice.sliceLength;
            return slice;
        }
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
        final var resourcePath = classpathEltPath.resolve(relativePath);
        return FileUtils.canReadAndIsFile(resourcePath) ? newResource(resourcePath, null) : null;
    }

    /**
     * Canonicalize a directory path, and check that it has not been reached before, so that recursive scanning
     * doesn't get stuck in an infinite loop due to symlinks.
     *
     * @param path
     *            the directory {@link Path}
     * @param log
     *            the log node, or null to skip logging
     * @return the canonical path, or null if the path could not be canonicalized, or has already been scanned.
     */
    private @Nullable Path canonicalizeIfNotAlreadyScanned(final Path path, final @Nullable LogNode log) {
        try {
            final var canonicalPath = path.toRealPath();
            if (!scannedCanonicalPaths.add(canonicalPath)) {
                if (log != null) {
                    log.log("Reached symlink cycle, stopping recursion: " + path);
                }
                return null;
            }
            return canonicalPath;
        } catch (final IOException | SecurityException e) {
            if (log != null) {
                log.log("Could not canonicalize path: " + path, e);
            }
            return null;
        }
    }

    /**
     * Get the path of a directory relative to the classpath element root, with any leading {@code "/"} removed and
     * a trailing {@code "/"} added.
     *
     * @param path
     *            the directory {@link Path}
     * @return the relative path of the directory
     */
    private String getDirRelativePathStr(final Path path) {
        var dirRelativePathStr = FastPathResolver.resolve(classpathEltPath.relativize(path).toString());
        while (dirRelativePathStr.startsWith("/")) {
            dirRelativePathStr = dirRelativePathStr.substring(1);
        }
        return dirRelativePathStr.endsWith("/") ? dirRelativePathStr : dirRelativePathStr + "/";
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
     * List the entries of a directory, in sorted order.
     *
     * @param path
     *            the directory {@link Path}
     * @param log
     *            the log node, or null to skip logging
     * @return the paths within the directory, or null if the directory could not be read.
     */
    private static @Nullable List<Path> listDirEntries(final Path path, final @Nullable LogNode log) {
        final List<Path> pathsInDir = new ArrayList<>();
        try (var stream = Files.newDirectoryStream(path)) {
            for (final Path subPath : stream) {
                pathsInDir.add(subPath);
            }
        } catch (IOException | SecurityException e) {
            if (log != null) {
                log.log("Could not read directory " + path + " : " + e.getMessage());
            }
            return null;
        }
        Collections.sort(pathsInDir);
        return pathsInDir;
    }

    /**
     * Add the accepted files of a directory as resources, removing them from {@code pathsInDir} so that only
     * subdirectories are left to recurse into.
     *
     * @param pathsInDir
     *            the paths within the directory
     * @param getFileAttributes
     *            the file attribute cache for the directory
     * @param parentMatchStatus
     *            the match status of the directory
     * @param subLog
     *            the log node, or null to skip logging
     * @return false if the classpath element was rejected by one of the file paths, so that scanning should stop.
     */
    private boolean scanFilesInDir(final List<Path> pathsInDir,
            final FileUtils.FileAttributesGetter getFileAttributes, final ScanSpecPathMatch parentMatchStatus,
            final @Nullable LogNode subLog) {
        // Determine whether this is a modular jar
        final var isModularJar = getModuleName() != null;

        // Do preorder traversal (files in dir, then subdirs), to reduce filesystem cache misses
        final var pathsIterator = pathsInDir.iterator();
        while (pathsIterator.hasNext()) {
            final var subPath = pathsIterator.next();
            // Process files in dir before recursing
            final var fileAttributes = getFileAttributes.get(subPath);
            if (fileAttributes.isRegularFile()) {
                pathsIterator.remove();
                final var subPathRelative = classpathEltPath.relativize(subPath);
                final var subPathRelativeStr = FastPathResolver.resolve(subPathRelative.toString());
                if (isIgnoredDefaultPackageClassfile(isModularJar, subPathRelativeStr)) {
                    continue;
                }

                // Accept/reject classpath elements based on file resource paths
                if (!checkResourcePathAcceptReject(subPathRelativeStr, subLog)) {
                    return false;
                }

                if (isAcceptedResourcePath(subPathRelativeStr, parentMatchStatus)) {
                    // Resource is accepted
                    final var resource = newResource(subPath, subPathRelativeStr, fileAttributes);
                    addAcceptedResource(resource, parentMatchStatus, /* isClassfileOnly = */ false, subLog);
                    recordLastModified(subPath, fileAttributes);
                } else {
                    if (subLog != null) {
                        subLog.log("Skipping non-accepted file: " + subPathRelative);
                    }
                }
            }
        }
        return true;
    }

    /**
     * Add the module descriptor of the package root as a resource, if there is one, removing it from
     * {@code pathsInDir}. This is called for the package root even when the package root is not accepted, so that
     * the module name is always known.
     *
     * @param pathsInDir
     *            the paths within the package root
     * @param getFileAttributes
     *            the file attribute cache for the package root
     * @param parentMatchStatus
     *            the match status of the package root
     * @param subLog
     *            the log node, or null to skip logging
     */
    private void scanForModuleDescriptor(final List<Path> pathsInDir,
            final FileUtils.FileAttributesGetter getFileAttributes, final ScanSpecPathMatch parentMatchStatus,
            final @Nullable LogNode subLog) {
        final var pathsIterator = pathsInDir.iterator();
        while (pathsIterator.hasNext()) {
            final var subPath = pathsIterator.next();
            if ("module-info.class".equals(subPath.getFileName().toString())) {
                final var fileAttributes = getFileAttributes.get(subPath);
                if (fileAttributes.isRegularFile()) {
                    pathsIterator.remove();
                    final var resource = newResource(subPath, fileAttributes);
                    addAcceptedResource(resource, parentMatchStatus, /* isClassfileOnly = */ true, subLog);
                    recordLastModified(subPath, fileAttributes);
                    break;
                }
            }
        }
    }

    /**
     * Record the last modified time of a file, so that {@link ScanResult#classpathContentsModifiedSinceScan()} can
     * detect changes to it.
     *
     * @param path
     *            the {@link Path} of the file
     * @param attributes
     *            the file attributes, or null to read the last modified time through the {@link File} API.
     */
    private void recordLastModified(final Path path, final @Nullable BasicFileAttributes attributes) {
        try {
            if (attributes == null) {
                final var file = path.toFile();
                fileToLastModified.put(file, file.lastModified());
            } else {
                fileToLastModified.put(path.toFile(), attributes.lastModifiedTime().toMillis());
            }
        } catch (final UnsupportedOperationException e) {
            // Ignore
        }
    }

    /**
     * Recursively scan a {@link Path} for sub-path patterns matching the scan spec.
     *
     * @param path
     *            the {@link Path}
     * @param log
     *            the log node, or null to skip logging
     */
    private void scanPathRecursively(final Path path, final @Nullable LogNode log) {
        final var canonicalPath = canonicalizeIfNotAlreadyScanned(path, log);
        if (canonicalPath == null) {
            return;
        }
        final var dirRelativePathStr = getDirRelativePathStr(path);
        final var parentMatchStatus = getDirMatchStatus(dirRelativePathStr, log);
        if (parentMatchStatus == null) {
            return;
        }

        final var subLog = log == null ? null
                // Log dirs after files (addAcceptedResources() precedes log entry with "0:")
                : log.log("1:" + canonicalPath,
                        "Scanning Path: " + FastPathResolver.resolve(path.toString()) + (path.equals(canonicalPath)
                                ? ""
                                : " ; canonical path: " + FastPathResolver.resolve(canonicalPath.toString())));

        final var pathsInDir = listDirEntries(path, log);
        if (pathsInDir == null) {
            return;
        }
        final var getFileAttributes = FileUtils.createCachedAttributesGetter();

        // Only scan files in directory if directory is not only an ancestor of an accepted path
        if (parentMatchStatus != ScanSpecPathMatch.ANCESTOR_OF_ACCEPTED_PATH) {
            if (!scanFilesInDir(pathsInDir, getFileAttributes, parentMatchStatus, subLog)) {
                return;
            }
        } else if (scanSpec.enableClassInfo && "/".equals(dirRelativePathStr)) {
            // Always check for module descriptor in package root, even if package root isn't in accept
            scanForModuleDescriptor(pathsInDir, getFileAttributes, parentMatchStatus, subLog);
        }

        // Recurse into subdirectories (the files in the directory have been removed from pathsInDir)
        for (final Path subPath : pathsInDir) {
            try {
                if (getFileAttributes.get(subPath).isDirectory()) {
                    scanPathRecursively(subPath, subLog);
                }
            } catch (final SecurityException e) {
                if (subLog != null) {
                    subLog.log("Could not read sub-directory " + subPath + " : " + e.getMessage());
                }
            }
        }

        if (subLog != null) {
            subLog.addElapsedTime();
        }
        recordLastModified(path, /* attributes = */ null);
    }

    /**
     * Hierarchically scan directory structure for classfiles and matching files.
     *
     * @param log
     *            the log node, or null to skip logging
     */
    @Override
    void scanPaths(final @Nullable LogNode log) {
        if (!checkResourcePathAcceptReject(classpathEltPath.toString(), log)) {
            skipClasspathElement = true;
        }
        if (skipClasspathElement) {
            return;
        }
        if (scanned.getAndSet(true)) {
            // Should not happen
            throw new IllegalStateException("Already scanned classpath element " + this);
        }

        final var subLog = log == null ? null
                : log(classpathElementIdx, "Scanning Path classpath element " + getURI(), log);

        scanPathRecursively(classpathEltPath, subLog);

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
            return classpathEltPath.toUri();
        } catch (IOError | SecurityException e) {
            throw new IllegalStateException("Could not convert to URI: " + classpathEltPath);
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
            return classpathEltPath.toUri().toString();
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
