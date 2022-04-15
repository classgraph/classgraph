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
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Arrays;
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
import nonapi.io.github.classgraph.fileslice.FileSlice;
import nonapi.io.github.classgraph.fileslice.reader.ClassfileReader;
import nonapi.io.github.classgraph.scanspec.ScanSpec;
import nonapi.io.github.classgraph.scanspec.ScanSpec.ScanSpecPathMatch;
import nonapi.io.github.classgraph.utils.FastPathResolver;
import nonapi.io.github.classgraph.utils.FileUtils;
import nonapi.io.github.classgraph.utils.LogNode;
import nonapi.io.github.classgraph.utils.VersionFinder;

/** A directory classpath element, using the {@link File} API. */
class ClasspathElementFileDir extends ClasspathElement {
    /** The directory at the root of the classpath element. */
    private final File classpathEltDir;

    /** Used to ensure that recursive scanning doesn't get into an infinite loop due to a link cycle. */
    private final Set<String> scannedCanonicalPaths = new HashSet<>();

    /** The nested jar handler. */
    private final NestedJarHandler nestedJarHandler;

    /**
     * A directory classpath element.
     *
     * @param classpathEltDir
     *            the classpath element directory
     * @param workUnit
     *            the work unit
     * @param nestedJarHandler
     *            the nested jar handler
     * @param scanSpec
     *            the scan spec
     */
    ClasspathElementFileDir(final File classpathEltDir, final ClasspathEntryWorkUnit workUnit,
            final NestedJarHandler nestedJarHandler, final ScanSpec scanSpec) {
        super(workUnit, scanSpec);
        this.classpathEltDir = classpathEltDir;
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
                        "Skipping classpath element, since dir scanning is disabled: " + classpathEltDir, log);
            }
            skipClasspathElement = true;
            return;
        }
        try {
            // Auto-add nested lib dirs
            int childClasspathEntryIdx = 0;
            for (final String libDirPrefix : ClassLoaderHandlerRegistry.AUTOMATIC_LIB_DIR_PREFIXES) {
                final File libDir = new File(classpathEltDir, libDirPrefix);
                if (FileUtils.canReadAndIsDir(libDir)) {
                    // Sort directory entries for consistency
                    final File[] listFiles = libDir.listFiles();
                    if (listFiles != null) {
                        Arrays.sort(listFiles);
                        // Add all jarfiles within lib dir as child classpath entries
                        for (final File file : listFiles) {
                            if (file.isFile() && file.getName().endsWith(".jar")) {
                                if (log != null) {
                                    log(classpathElementIdx, "Found lib jar: " + file, log);
                                }
                                workQueue.addWorkUnit(new ClasspathEntryWorkUnit(file.getPath(), getClassLoader(),
                                        /* parentClasspathElement = */ this,
                                        /* orderWithinParentClasspathElement = */ childClasspathEntryIdx++,
                                        /* packageRootPrefix = */ ""));
                            }
                        }
                    }
                }
            }
            // Only look for package roots if the package root is empty
            if (packageRootPrefix.isEmpty()) {
                for (final String packageRootPrefix : ClassLoaderHandlerRegistry.AUTOMATIC_PACKAGE_ROOT_PREFIXES) {
                    final File packageRoot = new File(classpathEltDir, packageRootPrefix);
                    if (FileUtils.canReadAndIsDir(packageRoot)) {
                        if (log != null) {
                            log(classpathElementIdx, "Found package root: " + packageRoot, log);
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
                        "Skipping classpath element, since dir cannot be accessed: " + classpathEltDir, log);
            }
            skipClasspathElement = true;
        }
    }

    /**
     * Create a new {@link Resource} object for a resource or classfile discovered while scanning paths.
     *
     * @param pathRelativeToPackageRoot
     *            the path of the resource relative to the package root
     * @param resourceFile
     *            the {@link File} for the resource
     * @param nestedJarHandler
     *            the nested jar handler
     * @return the resource
     */
    private Resource newResource(final String pathRelativeToPackageRoot, final File resourceFile,
            final NestedJarHandler nestedJarHandler) {
        return new Resource(this, resourceFile.length()) {
            /** The {@link FileSlice} opened on the file. */
            private FileSlice fileSlice;

            /** True if the resource is open. */
            private final AtomicBoolean isOpen = new AtomicBoolean();

            @Override
            public String getPath() {
                String path = FastPathResolver.resolve(pathRelativeToPackageRoot);
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
                return resourceFile.lastModified();
            }

            @SuppressWarnings("null")
            @Override
            public Set<PosixFilePermission> getPosixFilePermissions() {
                Set<PosixFilePermission> posixFilePermissions = null;
                try {
                    posixFilePermissions = Files.readAttributes(resourceFile.toPath(), PosixFileAttributes.class)
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
                fileSlice = new FileSlice(resourceFile, nestedJarHandler, /* log = */ null);
                length = fileSlice.sliceLength;
                byteBuffer = fileSlice.read();
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
                // Classfile won't be compressed, so wrap it in a new FileSlice and then open it
                fileSlice = new FileSlice(resourceFile, nestedJarHandler, /* log = */ null);
                length = fileSlice.sliceLength;
                return new ClassfileReader(fileSlice, this);
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
                fileSlice = new FileSlice(resourceFile, nestedJarHandler, /* log = */ null);
                inputStream = fileSlice.open(this);
                length = fileSlice.sliceLength;
                return inputStream;
            }

            @Override
            public byte[] load() throws IOException {
                read();
                try (Resource res = this) { // Close this after use
                    fileSlice = new FileSlice(resourceFile, nestedJarHandler, /* log = */ null);
                    final byte[] bytes = fileSlice.load();
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
                    if (fileSlice != null) {
                        fileSlice.close();
                        nestedJarHandler.markSliceAsClosed(fileSlice);
                        fileSlice = null;
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
     * @param pathRelativeToPackageRoot
     *            The relative path of the {@link Resource} to return.
     * @return The {@link Resource} for the given relative path, or null if relativePath does not exist in this
     *         classpath element.
     */
    @Override
    Resource getResource(final String pathRelativeToPackageRoot) {
        final File resourceFile = new File(classpathEltDir, pathRelativeToPackageRoot);
        return FileUtils.canReadAndIsFile(resourceFile)
                ? newResource(pathRelativeToPackageRoot, resourceFile, nestedJarHandler)
                : null;
    }

    /**
     * Recursively scan a directory for file path patterns matching the scan spec.
     *
     * @param dir
     *            the directory
     * @param log
     *            the log
     */
    private void scanDirRecursively(final File dir, final LogNode log) {
        if (skipClasspathElement) {
            return;
        }
        // See if this canonical path has been scanned before, so that recursive scanning doesn't get stuck in an
        // infinite loop due to symlinks
        String canonicalPath;
        try {
            canonicalPath = dir.getCanonicalPath();
            if (!scannedCanonicalPaths.add(canonicalPath)) {
                if (log != null) {
                    log.log("Reached symlink cycle, stopping recursion: " + dir);
                }
                return;
            }
        } catch (final IOException | SecurityException e) {
            if (log != null) {
                log.log("Could not canonicalize path: " + dir, e);
            }
            return;
        }

        final String dirPath = dir.getPath();
        final int ignorePrefixLen = classpathEltDir.getPath().length() + 1;
        final String dirRelativePath = ignorePrefixLen > dirPath.length() ? "/" //
                : dirPath.substring(ignorePrefixLen).replace(File.separatorChar, '/') + "/";
        final boolean isDefaultPackage = "/".equals(dirRelativePath);

        if (nestedClasspathRootPrefixes != null && nestedClasspathRootPrefixes.contains(dirRelativePath)) {
            if (log != null) {
                log.log("Reached nested classpath root, stopping recursion to avoid duplicate scanning: "
                        + dirRelativePath);
            }
            return;
        }

        // Ignore versioned sections in exploded jars -- they are only supposed to be used in jars.
        // TODO: is it necessary to support multi-versioned exploded jars anyway? If so, all the paths in a
        // directory classpath entry will have to be pre-scanned and masked, as happens in ClasspathElementZip.
        if (dirRelativePath.startsWith(LogicalZipFile.MULTI_RELEASE_PATH_PREFIX)) {
            if (log != null) {
                log.log("Found unexpected nested versioned entry in directory classpath element -- skipping: "
                        + dirRelativePath);
            }
            return;
        }

        // Accept/reject classpath elements based on dir resource paths
        checkResourcePathAcceptReject(dirRelativePath, log);
        if (skipClasspathElement) {
            return;
        }

        final ScanSpecPathMatch parentMatchStatus = scanSpec.dirAcceptMatchStatus(dirRelativePath);
        if (parentMatchStatus == ScanSpecPathMatch.HAS_REJECTED_PATH_PREFIX) {
            // Reached a non-accepted or rejected path -- stop the recursive scan
            if (log != null) {
                log.log("Reached rejected directory, stopping recursive scan: " + dirRelativePath);
            }
            return;
        }
        if (parentMatchStatus == ScanSpecPathMatch.NOT_WITHIN_ACCEPTED_PATH) {
            // Reached a non-accepted and non-rejected path -- stop the recursive scan
            return;
        }

        final LogNode subLog = log == null ? null
                // Log dirs after files (addAcceptedResources() precedes log entry with "0:")
                : log.log("1:" + canonicalPath, "Scanning directory: " + dir
                        + (dir.getPath().equals(canonicalPath) ? "" : " ; canonical path: " + canonicalPath));

        final File[] filesInDir = dir.listFiles();
        if (filesInDir == null) {
            if (log != null) {
                log.log("Invalid directory " + dir);
            }
            return;
        }
        Arrays.sort(filesInDir);

        // Determine whether this is a modular jar running under JRE 9+
        final boolean isModularJar = VersionFinder.JAVA_MAJOR_VERSION >= 9 && getModuleName() != null;

        // Only scan files in directory if directory is not only an ancestor of an accepted path
        if (parentMatchStatus != ScanSpecPathMatch.ANCESTOR_OF_ACCEPTED_PATH) {
            // Do preorder traversal (files in dir, then subdirs), to reduce filesystem cache misses
            for (final File fileInDir : filesInDir) {
                // Process files in dir before recursing
                if (fileInDir.isFile()) {
                    final String fileInDirRelativePath = dirRelativePath.isEmpty() || isDefaultPackage
                            ? fileInDir.getName()
                            : dirRelativePath + fileInDir.getName();
                    // If this is a modular jar, ignore all classfiles other than "module-info.class" in the
                    // default package, since these are disallowed.
                    if (isModularJar && isDefaultPackage && fileInDirRelativePath.endsWith(".class")
                            && !fileInDirRelativePath.equals("module-info.class")) {
                        continue;
                    }

                    // Accept/reject classpath elements based on file resource paths
                    checkResourcePathAcceptReject(fileInDirRelativePath, subLog);
                    if (skipClasspathElement) {
                        return;
                    }

                    // If relative path is accepted
                    if (parentMatchStatus == ScanSpecPathMatch.HAS_ACCEPTED_PATH_PREFIX
                            || parentMatchStatus == ScanSpecPathMatch.AT_ACCEPTED_PATH
                            || (parentMatchStatus == ScanSpecPathMatch.AT_ACCEPTED_CLASS_PACKAGE
                                    && scanSpec.classfileIsSpecificallyAccepted(fileInDirRelativePath))) {
                        // Resource is accepted
                        final Resource resource = newResource(fileInDirRelativePath, fileInDir, nestedJarHandler);
                        addAcceptedResource(resource, parentMatchStatus, /* isClassfileOnly = */ false, subLog);

                        // Save last modified time  
                        fileToLastModified.put(fileInDir, fileInDir.lastModified());
                    } else {
                        if (subLog != null) {
                            subLog.log("Skipping non-accepted file: " + fileInDirRelativePath);
                        }
                    }
                }
            }
        } else if (scanSpec.enableClassInfo && dirRelativePath.equals("/")) {
            // Always check for module descriptor in package root, even if package root isn't in accept
            for (final File fileInDir : filesInDir) {
                if (fileInDir.getName().equals("module-info.class") && fileInDir.isFile()) {
                    final Resource resource = newResource("module-info.class", fileInDir, nestedJarHandler);
                    addAcceptedResource(resource, parentMatchStatus, /* isClassfileOnly = */ true, subLog);
                    fileToLastModified.put(fileInDir, fileInDir.lastModified());
                    break;
                }
            }
        }
        // Recurse into subdirectories
        for (final File fileInDir : filesInDir) {
            if (fileInDir.isDirectory()) {
                scanDirRecursively(fileInDir, subLog);
                // If a rejected classpath element resource path was found, it will set skipClasspathElement
                if (skipClasspathElement) {
                    if (subLog != null) {
                        subLog.addElapsedTime();
                    }
                    return;
                }
            }
        }

        if (subLog != null) {
            subLog.addElapsedTime();
        }

        // Save the last modified time of the directory
        fileToLastModified.put(dir, dir.lastModified());
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
                : log(classpathElementIdx, "Scanning directory classpath element " + classpathEltDir, log);

        scanDirRecursively(classpathEltDir, subLog);

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
     * @return The classpath element directory as a {@link File}.
     */
    @Override
    public File getFile() {
        return classpathEltDir;
    }

    /* (non-Javadoc)
     * @see io.github.classgraph.ClasspathElement#getURI()
     */
    @Override
    URI getURI() {
        return classpathEltDir.toURI();
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
        return classpathEltDir.toString();
    }

    /* (non-Javadoc)
     * @see java.lang.Object#hashCode()
     */
    @Override
    public int hashCode() {
        return Objects.hash(classpathEltDir);
    }

    /* (non-Javadoc)
     * @see java.lang.Object#equals(java.lang.Object)
     */
    @Override
    public boolean equals(final Object obj) {
        if (obj == this) {
            return true;
        } else if (!(obj instanceof ClasspathElementFileDir)) {
            return false;
        }
        final ClasspathElementFileDir other = (ClasspathElementFileDir) obj;
        return Objects.equals(this.classpathEltDir, other.classpathEltDir);
    }
}
