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
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
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
import nonapi.io.github.classgraph.utils.VersionFinder;

/** A directory classpath element, using the {@link Path} API. */
class ClasspathElementDir extends ClasspathElement {
    /** The directory at the root of the classpath element. */
    private final Path classpathEltPath;

    /** Used to ensure that recursive scanning doesn't get into an infinite loop due to a link cycle. */
    private final Set<Path> scannedCanonicalPaths = new HashSet<>();

    /** The nested jar handler. */
    private final NestedJarHandler nestedJarHandler;

    /**
     * A directory classpath element.
     *
     * @param classpathEltPath
     *            the classpath element {@link Path}
     * @param workUnit
     *            the work unit
     * @param nestedJarHandler
     *            the nested jar handler
     * @param scanSpec
     *            the scan spec
     */
    ClasspathElementDir(final Path classpathEltPath, final ClasspathEntryWorkUnit workUnit,
            final NestedJarHandler nestedJarHandler, final ScanSpec scanSpec) {
        super(workUnit, scanSpec);
        this.classpathEltPath = classpathEltPath;
        this.nestedJarHandler = nestedJarHandler;
    }

    /* (non-Javadoc)
     * @see io.github.classgraph.ClasspathElement#open(
     * nonapi.io.github.classgraph.concurrency.WorkQueue, nonapi.io.github.classgraph.utils.LogNode)
     */
    @Override
    void open(final WorkQueue<ClasspathEntryWorkUnit> workQueue, final LogNode log) {
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
            int childClasspathEntryIdx = 0;
            for (final String libDirPrefix : ClassLoaderHandlerRegistry.AUTOMATIC_LIB_DIR_PREFIXES) {
                final Path libDirPath = classpathEltPath.resolve(libDirPrefix);
                if (FileUtils.canReadAndIsDir(libDirPath)) {
                    // Add all jarfiles within the lib dir as child classpath entries
                    try (DirectoryStream<Path> stream = Files.newDirectoryStream(libDirPath)) {
                        for (final Path filePath : stream) {
                            if (Files.isRegularFile(filePath) && filePath.getFileName().endsWith(".jar")) {
                                if (log != null) {
                                    log(classpathElementIdx, "Found lib jar: " + filePath, log);
                                }
                                workQueue.addWorkUnit(new ClasspathEntryWorkUnit(filePath, getClassLoader(),
                                        /* parentClasspathElement = */ this,
                                        /* orderWithinParentClasspathElement = */ childClasspathEntryIdx++,
                                        /* packageRootPrefix = */ ""));
                            }
                        }
                    } catch (final IOException e) {
                        // Ignore -- thrown by Files.newDirectoryStream
                    }
                }
            }
            // Only look for package roots if the package root is empty
            if (packageRootPrefix.isEmpty()) {
                for (final String packageRootPrefix : ClassLoaderHandlerRegistry.AUTOMATIC_PACKAGE_ROOT_PREFIXES) {
                    final Path packageRoot = classpathEltPath.resolve(packageRootPrefix);
                    if (FileUtils.canReadAndIsDir(packageRoot)) {
                        if (log != null) {
                            log(classpathElementIdx, "Found package root: " + packageRootPrefix, log);
                        }
                        workQueue.addWorkUnit(new ClasspathEntryWorkUnit(packageRoot, getClassLoader(),
                                /* parentClasspathElement = */ this,
                                /* orderWithinParentClasspathElement = */ childClasspathEntryIdx++,
                                packageRootPrefix));
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
     * Create a new {@link Resource} object for a resource or classfile discovered while scanning paths.
     *
     * @param resourcePath
     *            the {@link Path} for the resource
     * @param nestedJarHandler
     *            the nested jar handler
     * @return the resource
     */
    private Resource newResource(final Path resourcePath, final NestedJarHandler nestedJarHandler) {
        long length;
        try {
            length = Files.size(resourcePath);
        } catch (IOException | SecurityException e) {
            length = -1L;
        }
        return new Resource(this, length) {
            /** The {@link PathSlice} opened on the file. */
            private PathSlice pathSlice;

            /** True if the resource is open. */
            private final AtomicBoolean isOpen = new AtomicBoolean();

            @Override
            public String getPath() {
                String path = FastPathResolver.resolve(classpathEltPath.relativize(resourcePath).toString());
                while (path.startsWith("/")) {
                    path = path.substring(1);
                }
                return path;
            }

            @Override
            public String getPathRelativeToClasspathElement() {
                return packageRootPrefix.isEmpty() ? getPath() : packageRootPrefix + getPath();
            }

            @Override
            public long getLastModified() {
                try {
                    return resourcePath.toFile().lastModified();
                } catch (final UnsupportedOperationException e) {
                    return 0L;
                }
            }

            @SuppressWarnings("null")
            @Override
            public Set<PosixFilePermission> getPosixFilePermissions() {
                Set<PosixFilePermission> posixFilePermissions = null;
                try {
                    posixFilePermissions = Files.readAttributes(resourcePath, PosixFileAttributes.class)
                            .permissions();
                } catch (UnsupportedOperationException | IOException | SecurityException e) {
                    // POSIX attributes not supported
                }
                return posixFilePermissions;
            }

            @Override
            public ByteBuffer read() throws IOException {
                if (skipClasspathElement) {
                    // Shouldn't happen
                    throw new IOException("Parent directory could not be opened");
                }
                if (isOpen.getAndSet(true)) {
                    throw new IOException(
                            "Resource is already open -- cannot open it again without first calling close()");
                }
                pathSlice = new PathSlice(resourcePath, nestedJarHandler);
                length = pathSlice.sliceLength;
                byteBuffer = pathSlice.read();
                return byteBuffer;
            }

            @Override
            ClassfileReader openClassfile() throws IOException {
                if (skipClasspathElement) {
                    // Shouldn't happen
                    throw new IOException("Parent directory could not be opened");
                }
                if (isOpen.getAndSet(true)) {
                    throw new IOException(
                            "Resource is already open -- cannot open it again without first calling close()");
                }
                // Classfile won't be compressed, so wrap it in a new PathSlice and then open it
                pathSlice = new PathSlice(resourcePath, nestedJarHandler);
                length = pathSlice.sliceLength;
                return new ClassfileReader(pathSlice, this);
            }

            @Override
            public InputStream open() throws IOException {
                if (skipClasspathElement) {
                    // Shouldn't happen
                    throw new IOException("Parent directory could not be opened");
                }
                if (isOpen.getAndSet(true)) {
                    throw new IOException(
                            "Resource is already open -- cannot open it again without first calling close()");
                }
                pathSlice = new PathSlice(resourcePath, nestedJarHandler);
                inputStream = pathSlice.open(this);
                length = pathSlice.sliceLength;
                return inputStream;
            }

            @Override
            public byte[] load() throws IOException {
                read();
                try (Resource res = this) { // Close this after use
                    pathSlice = new PathSlice(resourcePath, nestedJarHandler);
                    final byte[] bytes = pathSlice.load();
                    length = bytes.length;
                    return bytes;
                }
            }

            @Override
            public void close() {
                if (isOpen.getAndSet(false)) {
                    if (byteBuffer != null) {
                        // Any ByteBuffer ref should be a duplicate, so it doesn't need to be cleaned
                        byteBuffer = null;
                    }
                    if (pathSlice != null) {
                        pathSlice.close();
                        nestedJarHandler.markSliceAsClosed(pathSlice);
                        pathSlice = null;
                    }

                    // Close inputStream
                    super.close();
                }
            }
        };
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
    Resource getResource(final String relativePath) {
        final Path resourcePath = classpathEltPath.resolve(relativePath);
        return FileUtils.canReadAndIsFile(resourcePath) ? newResource(resourcePath, nestedJarHandler) : null;
    }

    /**
     * Recursively scan a {@link Path} for sub-path patterns matching the scan spec.
     *
     * @param path
     *            the {@link Path}
     * @param log
     *            the log
     */
    private void scanPathRecursively(final Path path, final LogNode log) {
        if (skipClasspathElement) {
            return;
        }
        // See if this canonical path has been scanned before, so that recursive scanning doesn't get stuck in an
        // infinite loop due to symlinks
        Path canonicalPath;
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

        String dirRelativePathStr = FastPathResolver.resolve(classpathEltPath.relativize(path).toString());
        while (dirRelativePathStr.startsWith("/")) {
            dirRelativePathStr = dirRelativePathStr.substring(1);
        }
        if (!dirRelativePathStr.endsWith("/")) {
            dirRelativePathStr += "/";
        }
        final boolean isDefaultPackage = dirRelativePathStr.equals("/");

        if (nestedClasspathRootPrefixes != null && nestedClasspathRootPrefixes.contains(dirRelativePathStr)) {
            if (log != null) {
                log.log("Reached nested classpath root, stopping recursion to avoid duplicate scanning: "
                        + dirRelativePathStr);
            }
            return;
        }

        // Ignore versioned sections in exploded jars -- they are only supposed to be used in jars.
        // TODO: is it necessary to support multi-versioned exploded jars anyway? If so, all the paths in a
        // directory classpath entry will have to be pre-scanned and masked, as happens in ClasspathElementZip.
        if (dirRelativePathStr.startsWith(LogicalZipFile.MULTI_RELEASE_PATH_PREFIX)) {
            if (log != null) {
                log.log("Found unexpected nested versioned entry in directory classpath element -- skipping: "
                        + dirRelativePathStr);
            }
            return;
        }

        // Accept/reject classpath elements based on dir resource paths
        checkResourcePathAcceptReject(dirRelativePathStr, log);
        if (skipClasspathElement) {
            return;
        }

        final ScanSpecPathMatch parentMatchStatus = scanSpec.dirAcceptMatchStatus(dirRelativePathStr);
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

        final LogNode subLog = log == null ? null
                // Log dirs after files (addAcceptedResources() precedes log entry with "0:")
                : log.log("1:" + canonicalPath,
                        "Scanning Path: " + FastPathResolver.resolve(path.toString()) + (path.equals(canonicalPath)
                                ? ""
                                : " ; canonical path: " + FastPathResolver.resolve(canonicalPath.toString())));

        final List<Path> pathsInDir = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(path)) {
            for (final Path subPath : stream) {
                pathsInDir.add(subPath);
            }
        } catch (IOException | SecurityException e) {
            if (log != null) {
                log.log("Could not read directory " + path + " : " + e.getMessage());
            }
            skipClasspathElement = true;
            return;
        }
        Collections.sort(pathsInDir);

        // Determine whether this is a modular jar running under JRE 9+
        final boolean isModularJar = VersionFinder.JAVA_MAJOR_VERSION >= 9 && getModuleName() != null;

        // Only scan files in directory if directory is not only an ancestor of an accepted path
        if (parentMatchStatus != ScanSpecPathMatch.ANCESTOR_OF_ACCEPTED_PATH) {
            // Do preorder traversal (files in dir, then subdirs), to reduce filesystem cache misses
            for (final Path subPath : pathsInDir) {
                // Process files in dir before recursing
                if (Files.isRegularFile(subPath)) {
                    final Path subPathRelative = classpathEltPath.relativize(subPath);
                    final String subPathRelativeStr = FastPathResolver.resolve(subPathRelative.toString());
                    // If this is a modular jar, ignore all classfiles other than "module-info.class" in the
                    // default package, since these are disallowed.
                    if (isModularJar && isDefaultPackage && subPathRelativeStr.endsWith(".class")
                            && !subPathRelativeStr.equals("module-info.class")) {
                        continue;
                    }

                    // Accept/reject classpath elements based on file resource paths
                    checkResourcePathAcceptReject(subPathRelativeStr, subLog);
                    if (skipClasspathElement) {
                        return;
                    }

                    // If relative path is accepted
                    if (parentMatchStatus == ScanSpecPathMatch.HAS_ACCEPTED_PATH_PREFIX
                            || parentMatchStatus == ScanSpecPathMatch.AT_ACCEPTED_PATH
                            || (parentMatchStatus == ScanSpecPathMatch.AT_ACCEPTED_CLASS_PACKAGE
                                    && scanSpec.classfileIsSpecificallyAccepted(subPathRelativeStr))) {
                        // Resource is accepted
                        final Resource resource = newResource(subPath, nestedJarHandler);
                        addAcceptedResource(resource, parentMatchStatus, /* isClassfileOnly = */ false, subLog);

                        // Save last modified time
                        try {
                            fileToLastModified.put(subPath.toFile(), subPath.toFile().lastModified());
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
        } else if (scanSpec.enableClassInfo && dirRelativePathStr.equals("/")) {
            // Always check for module descriptor in package root, even if package root isn't in accept
            for (final Path subPath : pathsInDir) {
                if (subPath.getFileName().toString().equals("module-info.class") && Files.isRegularFile(subPath)) {
                    final Resource resource = newResource(subPath, nestedJarHandler);
                    addAcceptedResource(resource, parentMatchStatus, /* isClassfileOnly = */ true, subLog);
                    try {
                        fileToLastModified.put(subPath.toFile(), subPath.toFile().lastModified());
                    } catch (final UnsupportedOperationException e) {
                        // Ignore
                    }
                    break;
                }
            }
        }
        // Recurse into subdirectories
        for (final Path subPath : pathsInDir) {
            try {
                if (Files.isDirectory(subPath)) {
                    scanPathRecursively(subPath, subLog);
                    // If a rejected classpath element resource path was found, it will set skipClasspathElement
                    if (skipClasspathElement) {
                        if (subLog != null) {
                            subLog.addElapsedTime();
                        }
                        return;
                    }
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
            final File file = path.toFile();
            fileToLastModified.put(file, file.lastModified());
        } catch (final UnsupportedOperationException e) {
            // Ignore
        }
    }

    /**
     * Hierarchically scan directory structure for classfiles and matching files.
     *
     * @param log
     *            the log
     */
    @Override
    void scanPaths(final LogNode log) {
        if (skipClasspathElement) {
            return;
        }
        if (scanned.getAndSet(true)) {
            // Should not happen
            throw new IllegalArgumentException("Already scanned classpath element " + this);
        }

        final LogNode subLog = log == null ? null
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
    public String getModuleName() {
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
    public File getFile() {
        try {
            return classpathEltPath.toFile();
        } catch (final UnsupportedOperationException e) {
            return null;
        }
    }

    /* (non-Javadoc)
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
        return Collections.singletonList(getURI());
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

    /* (non-Javadoc)
     * @see java.lang.Object#hashCode()
     */
    @Override
    public int hashCode() {
        return Objects.hash(classpathEltPath);
    }

    /* (non-Javadoc)
     * @see java.lang.Object#equals(java.lang.Object)
     */
    @Override
    public boolean equals(final Object obj) {
        if (obj == this) {
            return true;
        } else if (!(obj instanceof ClasspathElementDir)) {
            return false;
        }
        final ClasspathElementDir other = (ClasspathElementDir) obj;
        return Objects.equals(this.classpathEltPath, other.classpathEltPath);
    }
}
