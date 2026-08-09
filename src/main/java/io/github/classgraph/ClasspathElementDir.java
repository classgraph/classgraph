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
 * Copyright (c) 2019 Luke Hutchison
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
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.classgraph.Scanner.ClasspathEntryWorkUnit;
import nonapi.io.github.classgraph.classloaderhandler.ClassLoaderHandlerRegistry;
import nonapi.io.github.classgraph.concurrency.WorkQueue;
import nonapi.io.github.classgraph.fastzipfilereader.LogicalZipFile;
import nonapi.io.github.classgraph.fastzipfilereader.NestedJarHandler;
import nonapi.io.github.classgraph.fileslice.PathSlice;
import nonapi.io.github.classgraph.fileslice.reader.ClassfileReader;
import nonapi.io.github.classgraph.scanspec.ScanSpec;
import nonapi.io.github.classgraph.scanspec.ScanSpec.ScanSpecPathMatch;
import nonapi.io.github.classgraph.utils.FastPathResolver;
import nonapi.io.github.classgraph.utils.FileUtils;
import nonapi.io.github.classgraph.utils.LogNode;
import org.jspecify.annotations.Nullable;

/** A directory classpath element, using the {@link Path} API. */
class ClasspathElementDir extends ClasspathElement {
    /**
     * The {@link Resource#length} value indicating that the resource length has not
     * yet been read.
     */
    private static final int NOT_YET_LOADED_LENGTH = -2;

    /** The directory at the root of the classpath element. */
    private final Path classpathEltPath;

    /**
     * Used to ensure that recursive scanning doesn't get into an infinite loop due
     * to a link cycle.
     */
    private final Set<Path> scannedCanonicalPaths = new HashSet<>();

    /** The nested jar handler. */
    private final NestedJarHandler nestedJarHandler;

    /**
     * A directory classpath element.
     *
     * @param workUnit         the work unit -- workUnit.classpathEntryObj must be a
     *                         {@link Path} object
     * @param nestedJarHandler the nested jar handler
     * @param scanSpec         the scan spec
     */
    ClasspathElementDir(final ClasspathEntryWorkUnit workUnit, final NestedJarHandler nestedJarHandler,
            final ScanSpec scanSpec) {
        super(workUnit, scanSpec);
        this.classpathEltPath = (Path) Objects.requireNonNull(workUnit.classpathEntryObj);
        this.nestedJarHandler = nestedJarHandler;
    }

    /*
     * (non-Javadoc)
     *
     * @see io.github.classgraph.ClasspathElement#open(
     * nonapi.io.github.classgraph.concurrency.WorkQueue,
     * nonapi.io.github.classgraph.utils.LogNode)
     */
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
            // Auto-add nested lib dirs
            var childClasspathEntryIdx = 0;
            for (final String libDirPrefix : ClassLoaderHandlerRegistry.AUTOMATIC_LIB_DIR_PREFIXES) {
                final var libDirPath = classpathEltPath.resolve(libDirPrefix);
                if (FileUtils.canReadAndIsDir(libDirPath)) {
                    // Add all jarfiles within the lib dir as child classpath entries
                    try (var stream = Files.newDirectoryStream(libDirPath,
                            filePath -> filePath.toString().toLowerCase().endsWith(".jar")
                                    && Files.isRegularFile(filePath))) {
                        for (final Path filePath : stream) {
                            if (log != null) {
                                log(classpathElementIdx, "Found lib jar: " + filePath, log);
                            }
                            workQueue.addWorkUnit(new ClasspathEntryWorkUnit(filePath, getClassLoader(),
                                    /* parentClasspathElement = */ this,
                                    /* orderWithinParentClasspathElement = */ childClasspathEntryIdx++,
                                    /* packageRootPrefix = */ "", packageRootPrefixes));
                        }
                    } catch (final IOException e) {
                        // Ignore -- thrown by Files.newDirectoryStream
                    }
                }
            }
            // Only look for package roots if the package root is empty
            if (packageRootPrefix.isEmpty()) {
                for (final String packageRootPrefix : packageRootPrefixes) {
                    final var packageRoot = classpathEltPath.resolve(packageRootPrefix);
                    if (FileUtils.canReadAndIsDir(packageRoot)) {
                        // "classes/" and "test-classes/" are legal package names, so check that the
                        // candidate
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
                        workQueue.addWorkUnit(new ClasspathEntryWorkUnit(packageRoot, getClassLoader(),
                                /* parentClasspathElement = */ this,
                                /* orderWithinParentClasspathElement = */ childClasspathEntryIdx++, packageRootPrefix,
                                packageRootPrefixes));
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
     * Find the first classfile beneath a directory, so that the class it declares
     * can be compared to its path.
     *
     * @param dir the directory to search.
     * @return the first classfile found beneath the directory, or null if there are
     *         none. Classfiles beneath a {@code META-INF} directory are ignored,
     *         since the path of a classfile in a multi-release jar layout
     *         ({@code META-INF/versions/N/}) does not correspond to the name of the
     *         class it declares.
     */
    private static Path findFirstClassfile(final Path dir) {
        final var firstClassfile = new Path[1];
        try {
            Files.walkFileTree(dir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(final Path subDir, final BasicFileAttributes attrs) {
                    final var subDirName = subDir.getFileName();
                    return subDirName != null && "META-INF".equals(subDirName.toString()) ? FileVisitResult.SKIP_SUBTREE
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
     * Check whether a candidate package root directory is really a package root, or
     * is simply a package that has the same name as one of the automatic package
     * root prefixes (#929).
     *
     * @param packageRoot the candidate package root directory.
     * @return null if the candidate is a package root, otherwise the name of the
     *         class that disproves it (see
     *         {@link ClasspathElement#getClassNameDisprovingPackageRoot(ClassfileReader, String)}).
     */
    private static @Nullable String getClassNameDisprovingPackageRoot(final Path packageRoot) {
        final var classfilePath = findFirstClassfile(packageRoot);
        if (classfilePath == null) {
            // There are no classfiles beneath the candidate package root, so there is
            // nothing to check
            return null;
        }
        final var classfileRelativePath = packageRoot.relativize(classfilePath).toString().replace(File.separatorChar,
                '/');
        try (var inputStream = Files.newInputStream(classfilePath);
                var classfileReader = new ClassfileReader(inputStream, /* resourceToClose = */ null)) {
            return getClassNameDisprovingPackageRoot(classfileReader, classfileRelativePath);
        } catch (final IOException | SecurityException e) {
            // If the classfile cannot be read, give the candidate package root the benefit
            // of the doubt
            return null;
        }
    }

    /**
     * Create a new {@link Resource} object for a resource or classfile discovered
     * while scanning paths.
     *
     * @param resourcePath the {@link Path} for the resource
     * @param attributes   the file attributes of the resource, or null if not yet
     *                     known
     * @return the resource
     */
    private Resource newResource(final Path resourcePath, final @Nullable BasicFileAttributes attributes) {
        return newResource(resourcePath, FastPathResolver.resolve(classpathEltPath.relativize(resourcePath).toString()),
                attributes);
    }

    /**
     * Create a new {@link Resource} object for a resource or classfile discovered
     * while scanning paths, where the resolved path of the resource relative to the
     * classpath element has already been computed by the caller.
     *
     * @param resourcePath            the {@link Path} for the resource
     * @param resourcePathRelativeStr the path of the resource relative to the
     *                                classpath element root, as already resolved by
     *                                {@link FastPathResolver#resolve(String)}
     * @param attributes              the file attributes of the resource, or null
     *                                if not yet known
     * @return the resource
     */
    private Resource newResource(final Path resourcePath, final String resourcePathRelativeStr,
            final @Nullable BasicFileAttributes attributes) {
        var startIdx = 0;
        while (startIdx < resourcePathRelativeStr.length() && resourcePathRelativeStr.charAt(startIdx) == '/') {
            startIdx++;
        }
        final var path = startIdx == 0 ? resourcePathRelativeStr : resourcePathRelativeStr.substring(startIdx);
        return new Resource(this, attributes == null ? NOT_YET_LOADED_LENGTH : attributes.size()) {
            /** The {@link PathSlice} opened on the file. */
            private @Nullable PathSlice pathSlice;

            /** True if the resource is open. */
            private final AtomicBoolean isOpen = new AtomicBoolean();

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
            public long getLastModified() {
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

            protected void checkCanOpen() {
                if (skipClasspathElement) {
                    // Shouldn't happen
                    throw new IllegalStateException("Classpath element could not be opened");
                }
                if (isOpen.getAndSet(true)) {
                    throw new IllegalStateException(
                            "Resource is already open -- cannot open it again without first calling close()");
                }
                if (scanResult != null && scanResult.isClosed()) {
                    throw new IllegalStateException("Cannot open a resource after the ScanResult is closed");
                }
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
                if (isOpen.getAndSet(false)) {
                    if (byteBuffer != null) {
                        // Any ByteBuffer ref should be a duplicate, so it doesn't need to be cleaned
                        byteBuffer = null;
                    }
                    final var slice = pathSlice;
                    if (slice != null) {
                        slice.close();
                        nestedJarHandler.markSliceAsClosed(slice);
                        pathSlice = null;
                    }

                    // Close inputStream
                    super.close();
                }
            }

            private PathSlice openAndCreateSlice() throws IOException {
                checkCanOpen();
                final var slice = new PathSlice(resourcePath, false, 0L, nestedJarHandler, false);
                pathSlice = slice;
                length = slice.sliceLength;
                return slice;
            }
        };
    }

    /**
     * Get the {@link Resource} for a given relative path.
     *
     * @param relativePath The relative path of the {@link Resource} to return.
     * @return The {@link Resource} for the given relative path, or null if
     *         relativePath does not exist in this classpath element.
     */
    @Override
    @Nullable
    Resource getResource(final String relativePath) {
        final var resourcePath = classpathEltPath.resolve(relativePath);
        return FileUtils.canReadAndIsFile(resourcePath) ? newResource(resourcePath, null) : null;
    }

    /**
     * Recursively scan a {@link Path} for sub-path patterns matching the scan spec.
     *
     * @param path the {@link Path}
     * @param log  the log
     */
    private void scanPathRecursively(final Path path, final @Nullable LogNode log) {
        // See if this canonical path has been scanned before, so that recursive
        // scanning doesn't get stuck in an
        // infinite loop due to symlinks
        final Path canonicalPath;
        try {
            canonicalPath = path.toRealPath();
            if (!scannedCanonicalPaths.add(canonicalPath)) {
                if (log != null) {
                    log.log("Reached symlink cycle, stopping recursion: " + path);
                }
                return;
            }
        } catch (final IOException | SecurityException e) {
            if (log != null) {
                log.log("Could not canonicalize path: " + path, e);
            }
            return;
        }

        var dirRelativePathStr = FastPathResolver.resolve(classpathEltPath.relativize(path).toString());
        while (dirRelativePathStr.startsWith("/")) {
            dirRelativePathStr = dirRelativePathStr.substring(1);
        }
        if (!dirRelativePathStr.endsWith("/")) {
            dirRelativePathStr += "/";
        }
        final var isDefaultPackage = "/".equals(dirRelativePathStr);

        if (nestedClasspathRootPrefixes != null && nestedClasspathRootPrefixes.contains(dirRelativePathStr)) {
            if (log != null) {
                log.log("Reached nested classpath root, stopping recursion to avoid duplicate scanning: "
                        + dirRelativePathStr);
            }
            return;
        }

        // Ignore versioned sections in exploded jars -- they are only supposed to be
        // used in jars.
        // TODO: is it necessary to support multi-versioned exploded jars anyway? If so,
        // all the paths in a
        // directory classpath entry will have to be pre-scanned and masked, as happens
        // in ClasspathElementZip.
        if (!scanSpec.enableMultiReleaseVersions
                && dirRelativePathStr.startsWith(LogicalZipFile.MULTI_RELEASE_PATH_PREFIX)) {
            if (log != null) {
                log.log("Found unexpected nested versioned entry in directory classpath element -- skipping: "
                        + dirRelativePathStr);
            }
            return;
        }

        // Accept/reject classpath elements based on dir resource paths
        if (!checkResourcePathAcceptReject(dirRelativePathStr, log)) {
            return;
        }

        final var parentMatchStatus = scanSpec.dirAcceptMatchStatus(dirRelativePathStr);
        if (parentMatchStatus == ScanSpecPathMatch.HAS_REJECTED_PATH_PREFIX) {
            // Reached a non-accepted or rejected path -- stop the recursive scan
            if (log != null) {
                log.log("Reached rejected directory, stopping recursive scan: " + dirRelativePathStr);
            }
            return;
        }
        if (parentMatchStatus == ScanSpecPathMatch.NOT_WITHIN_ACCEPTED_PATH) {
            // Reached a non-accepted and non-rejected path -- stop the recursive scan
            return;
        }

        final var subLog = log == null ? null
                // Log dirs after files (addAcceptedResources() precedes log entry with "0:")
                : log.log("1:" + canonicalPath,
                        "Scanning Path: " + FastPathResolver.resolve(path.toString()) + (path.equals(canonicalPath) ? ""
                                : " ; canonical path: " + FastPathResolver.resolve(canonicalPath.toString())));

        final List<Path> pathsInDir = new ArrayList<>();
        try (var stream = Files.newDirectoryStream(path)) {
            for (final Path subPath : stream) {
                pathsInDir.add(subPath);
            }
        } catch (IOException | SecurityException e) {
            if (log != null) {
                log.log("Could not read directory " + path + " : " + e.getMessage());
            }
            return;
        }
        Collections.sort(pathsInDir);
        final var getFileAttributes = FileUtils.createCachedAttributesGetter();

        // Determine whether this is a modular jar
        final var isModularJar = getModuleName() != null;

        // Only scan files in directory if directory is not only an ancestor of an
        // accepted path
        if (parentMatchStatus != ScanSpecPathMatch.ANCESTOR_OF_ACCEPTED_PATH) {
            // Do preorder traversal (files in dir, then subdirs), to reduce filesystem
            // cache misses
            final var pathsIterator = pathsInDir.iterator();
            while (pathsIterator.hasNext()) {
                final var subPath = pathsIterator.next();
                // Process files in dir before recursing
                final var fileAttributes = getFileAttributes.get(subPath);
                if (fileAttributes.isRegularFile()) {
                    pathsIterator.remove();
                    final var subPathRelative = classpathEltPath.relativize(subPath);
                    final var subPathRelativeStr = FastPathResolver.resolve(subPathRelative.toString());
                    // If this is a modular jar, ignore all classfiles other than
                    // "module-info.class" in the
                    // default package, since these are disallowed.
                    if (isModularJar && isDefaultPackage && subPathRelativeStr.endsWith(".class")
                            && !"module-info.class".equals(subPathRelativeStr)) {
                        continue;
                    }

                    // Accept/reject classpath elements based on file resource paths
                    if (!checkResourcePathAcceptReject(subPathRelativeStr, subLog)) {
                        return;
                    }

                    // If relative path is accepted
                    if (parentMatchStatus == ScanSpecPathMatch.HAS_ACCEPTED_PATH_PREFIX
                            || parentMatchStatus == ScanSpecPathMatch.AT_ACCEPTED_PATH
                            || (parentMatchStatus == ScanSpecPathMatch.AT_ACCEPTED_CLASS_PACKAGE
                                    && scanSpec.classfileIsSpecificallyAccepted(subPathRelativeStr))) {
                        // Resource is accepted
                        final var resource = newResource(subPath, subPathRelativeStr, fileAttributes);
                        addAcceptedResource(resource, parentMatchStatus, /* isClassfileOnly = */ false, subLog);

                        // Save last modified time
                        try {
                            fileToLastModified.put(subPath.toFile(), fileAttributes.lastModifiedTime().toMillis());
                        } catch (final UnsupportedOperationException e) {
                            // Ignore
                        }
                    } else {
                        if (subLog != null) {
                            subLog.log("Skipping non-accepted file: " + subPathRelative);
                        }
                    }
                }
            }
        } else if (scanSpec.enableClassInfo && "/".equals(dirRelativePathStr)) {
            // Always check for module descriptor in package root, even if package root
            // isn't in accept
            final var pathsIterator = pathsInDir.iterator();
            while (pathsIterator.hasNext()) {
                final var subPath = pathsIterator.next();
                if ("module-info.class".equals(subPath.getFileName().toString())) {
                    final var fileAttributes = getFileAttributes.get(subPath);
                    if (fileAttributes.isRegularFile()) {
                        pathsIterator.remove();
                        final var resource = newResource(subPath, fileAttributes);
                        addAcceptedResource(resource, parentMatchStatus, /* isClassfileOnly = */ true, subLog);
                        try {
                            fileToLastModified.put(subPath.toFile(), fileAttributes.lastModifiedTime().toMillis());
                        } catch (final UnsupportedOperationException e) {
                            // Ignore
                        }
                        break;
                    }
                }
            }
        }
        // Recurse into subdirectories
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

        // Save the last modified time of the directory
        try {
            final var file = path.toFile();
            fileToLastModified.put(file, file.lastModified());
        } catch (final UnsupportedOperationException e) {
            // Ignore
        }
    }

    /**
     * Hierarchically scan directory structure for classfiles and matching files.
     *
     * @param log the log
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
            throw new IllegalArgumentException("Already scanned classpath element " + this);
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
     * @return The classpath element directory as a {@link File}, or null if this
     *         classpath element is not backed by a directory (should not happen).
     */
    @Override
    public @Nullable File getFile() {
        try {
            return classpathEltPath.toFile();
        } catch (final UnsupportedOperationException e) {
            return null;
        }
    }

    /*
     * (non-Javadoc)
     *
     * @see io.github.classgraph.ClasspathElement#getURI()
     */
    @Override
    URI getURI() {
        try {
            return classpathEltPath.toUri();
        } catch (IOError | SecurityException e) {
            throw new IllegalArgumentException("Could not convert to URI: " + classpathEltPath);
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

    /*
     * (non-Javadoc)
     *
     * @see java.lang.Object#hashCode()
     */
    @Override
    public int hashCode() {
        return Objects.hash(classpathEltPath);
    }

    /*
     * (non-Javadoc)
     *
     * @see java.lang.Object#equals(java.lang.Object)
     */
    @Override
    public boolean equals(final @Nullable Object obj) {
        if (obj == this) {
            return true;
        }
        if (!(obj instanceof final ClasspathElementDir other)) {
            return false;
        }
        return Objects.equals(this.classpathEltPath, other.classpathEltPath);
    }
}
