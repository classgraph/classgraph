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
 * Copyright (c) 2018 Luke Hutchison
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
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;

import nonapi.io.github.classgraph.ScanSpec;
import nonapi.io.github.classgraph.ScanSpec.ScanSpecPathMatch;
import nonapi.io.github.classgraph.classloaderhandler.ClassLoaderHandlerRegistry;
import nonapi.io.github.classgraph.concurrency.WorkQueue;
import nonapi.io.github.classgraph.utils.FileUtils;
import nonapi.io.github.classgraph.utils.InputStreamOrByteBufferAdapter;
import nonapi.io.github.classgraph.utils.LogNode;

/** A directory classpath element. */
class ClasspathElementDir extends ClasspathElement {
    /** The directory at the root of the classpath element. */
    private final File classpathEltDir;

    /** The number of characters to ignore to strip the classpath element path and relativize the path. */
    private int ignorePrefixLen;

    /** Used to ensure that recursive scanning doesn't get into an infinite loop due to a link cycle. */
    private final HashSet<String> scannedCanonicalPaths = new HashSet<>();

    /** A directory classpath element. */
    ClasspathElementDir(final File classpathEltDir, final ClassLoader[] classLoaders, final ScanSpec scanSpec) {
        super(classLoaders, scanSpec);
        this.classpathEltDir = classpathEltDir;
        if (scanSpec.performScan) {
            ignorePrefixLen = classpathEltDir.getPath().length() + 1;
            whitelistedResources = new ArrayList<>();
            whitelistedClassfileResources = new ArrayList<>();
            fileToLastModified = new HashMap<>();
        }
    }

    @Override
    void open(final WorkQueue<String> workQueue, final LogNode log) {
        if (!scanSpec.scanDirs) {
            if (log != null) {
                log.log("Skipping classpath element, since dir scanning is disabled: " + classpathEltDir);
            }
            skipClasspathElement = true;
        }
        // Auto-add nested lib dirs
        for (final String libDirPrefix : ClassLoaderHandlerRegistry.AUTOMATIC_LIB_DIR_PREFIXES) {
            final File libDir = new File(classpathEltDir, libDirPrefix);
            if (libDir.exists() && libDir.isDirectory()) {
                for (final File file : libDir.listFiles()) {
                    if (file.isFile() && file.getName().endsWith(".jar")) {
                        if (log != null) {
                            log.log("Found lib jar: " + file);
                        }
                        addChildClasspathElt(workQueue, file.getPath());
                    }
                }
            }
        }
        // Auto-add nested package root prefixes
        for (final String packageRootPrefix : ClassLoaderHandlerRegistry.AUTOMATIC_PACKAGE_ROOT_PREFIXES) {
            final File packageRootDir = new File(classpathEltDir, packageRootPrefix);
            if (packageRootDir.exists() && packageRootDir.isDirectory()) {
                if (log != null) {
                    log.log("Found package root: " + packageRootDir);
                }
                addChildClasspathElt(workQueue, packageRootDir.getPath());
            }
        }
    }

    /** Create a new {@link Resource} object for a resource or classfile discovered while scanning paths. */
    private Resource newResource(final File classpathEltFile, final String relativePath,
            final File classpathResourceFile) {
        return new Resource() {
            private RandomAccessFile randomAccessFile;
            private FileChannel fileChannel;

            {
                length = classpathResourceFile.length();
            }

            @Override
            public String getPath() {
                return relativePath;
            }

            @Override
            public String getPathRelativeToClasspathElement() {
                return relativePath;
            }

            @Override
            public URL getURL() {
                try {
                    return new File(classpathEltFile, relativePath).toURI().toURL();
                } catch (final MalformedURLException e) {
                    throw new IllegalArgumentException(
                            "Could not form URL for dir: " + classpathEltFile + " ; path: " + relativePath);
                }
            }

            @Override
            public URL getClasspathElementURL() {
                try {
                    return getClasspathElementFile().toURI().toURL();
                } catch (final MalformedURLException e) {
                    // Shouldn't happen
                    throw new IllegalArgumentException(e);
                }
            }

            @Override
            public File getClasspathElementFile() {
                return classpathEltDir;
            }

            @Override
            public ModuleRef getModuleRef() {
                return null;
            }

            @Override
            public synchronized ByteBuffer read() throws IOException {
                if (skipClasspathElement) {
                    // Shouldn't happen
                    throw new IOException("Parent directory could not be opened");
                }
                markAsOpen();
                try {
                    randomAccessFile = new RandomAccessFile(classpathResourceFile, "r");
                    fileChannel = randomAccessFile.getChannel();
                    MappedByteBuffer buffer = null;
                    try {
                        buffer = fileChannel.map(FileChannel.MapMode.READ_ONLY, 0, fileChannel.size());
                    } catch (final FileNotFoundException e) {
                        throw e;
                    } catch (IOException | OutOfMemoryError e) {
                        // If map failed, try calling System.gc() to free some allocated MappedByteBuffers
                        // (there is a limit to the number of mapped files -- 64k on Linux)
                        // See: http://www.mapdb.org/blog/mmap_files_alloc_and_jvm_crash/
                        System.gc();
                        // Then try calling map again
                        buffer = fileChannel.map(FileChannel.MapMode.READ_ONLY, 0, fileChannel.size());
                    }
                    byteBuffer = buffer;
                    length = byteBuffer.remaining();
                    return byteBuffer;
                } catch (final Exception e) {
                    close();
                    throw new IOException("Could not open " + this, e);
                }
            }

            @Override
            synchronized InputStreamOrByteBufferAdapter openOrRead() throws IOException {
                if (length >= FileUtils.FILECHANNEL_FILE_SIZE_THRESHOLD) {
                    return new InputStreamOrByteBufferAdapter(read());
                } else {
                    return new InputStreamOrByteBufferAdapter(inputStream = new InputStreamResourceCloser(this,
                            new FileInputStream(classpathResourceFile)));
                }
            }

            @Override
            public synchronized InputStream open() throws IOException {
                if (length >= FileUtils.FILECHANNEL_FILE_SIZE_THRESHOLD) {
                    read();
                    return inputStream = new InputStreamResourceCloser(this, byteBufferToInputStream());
                } else {
                    markAsOpen();
                    try {
                        return inputStream = new InputStreamResourceCloser(this,
                                Files.newInputStream(classpathResourceFile.toPath()));
                    } catch (final Exception e) {
                        close();
                        throw new IOException("Could not open " + this, e);
                    }
                }
            }

            @Override
            public synchronized byte[] load() throws IOException {
                try {
                    final byte[] byteArray;
                    if (length >= FileUtils.FILECHANNEL_FILE_SIZE_THRESHOLD) {
                        read();
                        byteArray = byteBufferToByteArray();
                    } else {
                        open();
                        byteArray = FileUtils.readAllBytesAsArray(inputStream, length);
                    }
                    length = byteArray.length;
                    return byteArray;
                } finally {
                    close();
                }
            }

            @Override
            public synchronized void close() {
                super.close(); // Close inputStream
                if (byteBuffer != null) {
                    FileUtils.closeDirectByteBuffer(byteBuffer, /* log = */ null);
                    byteBuffer = null;
                }
                if (fileChannel != null) {
                    try {
                        fileChannel.close();
                    } catch (final IOException e) {
                        // Ignore
                    }
                    fileChannel = null;
                }
                if (randomAccessFile != null) {
                    try {
                        randomAccessFile.close();
                    } catch (final IOException e) {
                        // Ignore
                    }
                    randomAccessFile = null;
                }
                markAsClosed();
            }
        };
    }

    /**
     * @param relativePath
     *            The relative path of the {@link Resource} to return.
     * @return The {@link Resource} for the given relative path, or null if relativePath does not exist in this
     *         classpath element.
     */
    @Override
    Resource getResource(final String relativePath) {
        final File resourceFile = new File(classpathEltDir, relativePath);
        return resourceFile.canRead() && resourceFile.isFile()
                ? newResource(classpathEltDir, relativePath, resourceFile)
                : null;
    }

    /** Recursively scan a directory for file path patterns matching the scan spec. */
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
        final String dirRelativePath = ignorePrefixLen > dirPath.length() ? "/" //
                : dirPath.substring(ignorePrefixLen).replace(File.separatorChar, '/') + "/";

        if (nestedClasspathRootPrefixes != null) {
            if (nestedClasspathRootPrefixes.contains(dirRelativePath)) {
                if (log != null) {
                    log.log("Reached nested classpath root, stopping recursion to avoid duplicate scanning: "
                            + dirRelativePath);
                }
                return;
            }
        }

        // Whitelist/blacklist classpath elements based on dir resource paths
        if (!scanSpec.classpathElementResourcePathWhiteBlackList.whitelistAndBlacklistAreEmpty()) {
            if (scanSpec.classpathElementResourcePathWhiteBlackList.isBlacklisted(dirRelativePath)) {
                if (log != null) {
                    log.log("Reached blacklisted classpath element resource path, stopping scanning: "
                            + dirRelativePath);
                }
                skipClasspathElement = true;
                return;
            }
            if (scanSpec.classpathElementResourcePathWhiteBlackList.isSpecificallyWhitelisted(dirRelativePath)) {
                if (log != null) {
                    log.log("Reached specifically whitelisted classpath element resource path: " + dirRelativePath);
                }
                containsSpecificallyWhitelistedClasspathElementResourcePath = true;
            }
        }

        final ScanSpecPathMatch parentMatchStatus = scanSpec.dirWhitelistMatchStatus(dirRelativePath);
        if (parentMatchStatus == ScanSpecPathMatch.HAS_BLACKLISTED_PATH_PREFIX) {
            // Reached a non-whitelisted or blacklisted path -- stop the recursive scan
            if (log != null) {
                log.log("Reached blacklisted directory, stopping recursive scan: " + dirRelativePath);
            }
            return;
        }
        if (parentMatchStatus == ScanSpecPathMatch.NOT_WITHIN_WHITELISTED_PATH) {
            // Reached a non-whitelisted and non-blacklisted path -- stop the recursive scan
            return;
        }

        final File[] filesInDir = dir.listFiles();
        Arrays.sort(filesInDir);
        if (filesInDir == null) {
            if (log != null) {
                log.log("Invalid directory " + dir);
            }
            return;
        }
        final LogNode subLog = log == null ? null
                : log.log(canonicalPath, "Scanning directory: " + dir
                        + (dir.getPath().equals(canonicalPath) ? "" : " ; canonical path: " + canonicalPath));

        // Only scan files in directory if directory is not only an ancestor of a whitelisted path
        if (parentMatchStatus != ScanSpecPathMatch.ANCESTOR_OF_WHITELISTED_PATH) {
            // Do preorder traversal (files in dir, then subdirs), to reduce filesystem cache misses
            for (final File fileInDir : filesInDir) {
                // Process files in dir before recursing
                if (fileInDir.isFile()) {
                    final String fileInDirRelativePath = dirRelativePath.isEmpty() || "/".equals(dirRelativePath)
                            ? fileInDir.getName()
                            : dirRelativePath + fileInDir.getName();

                    // Whitelist/blacklist classpath elements based on file resource paths
                    if (!scanSpec.classpathElementResourcePathWhiteBlackList.whitelistAndBlacklistAreEmpty()) {
                        if (scanSpec.classpathElementResourcePathWhiteBlackList
                                .isBlacklisted(fileInDirRelativePath)) {
                            if (subLog != null) {
                                subLog.log(
                                        "Reached blacklisted classpath element resource path, stopping scanning: "
                                                + fileInDirRelativePath);
                            }
                            skipClasspathElement = true;
                            return;
                        }
                        if (scanSpec.classpathElementResourcePathWhiteBlackList
                                .isSpecificallyWhitelisted(fileInDirRelativePath)) {
                            if (subLog != null) {
                                subLog.log("Reached specifically whitelisted classpath element resource path: "
                                        + fileInDirRelativePath);
                            }
                            containsSpecificallyWhitelistedClasspathElementResourcePath = true;
                        }
                    }

                    // If relative path is whitelisted
                    if (parentMatchStatus == ScanSpecPathMatch.HAS_WHITELISTED_PATH_PREFIX
                            || parentMatchStatus == ScanSpecPathMatch.AT_WHITELISTED_PATH
                            || (parentMatchStatus == ScanSpecPathMatch.AT_WHITELISTED_CLASS_PACKAGE
                                    && scanSpec.classfileIsSpecificallyWhitelisted(fileInDirRelativePath))) {
                        // Resource is whitelisted
                        final Resource resource = newResource(classpathEltDir, fileInDirRelativePath, fileInDir);
                        addWhitelistedResource(resource, parentMatchStatus, subLog);

                        // Save last modified time  
                        fileToLastModified.put(fileInDir, fileInDir.lastModified());
                    } else {
                        if (subLog != null) {
                            subLog.log("Skipping non-whitelisted file: " + fileInDirRelativePath);
                        }
                    }
                }
            }
        }
        // Recurse into subdirectories
        for (final File fileInDir : filesInDir) {
            if (fileInDir.isDirectory()) {
                scanDirRecursively(fileInDir, subLog);
                if (subLog != null) {
                    subLog.addElapsedTime();
                }
                // If a blacklisted classpath element resource path was found, stop scanning
                if (skipClasspathElement) {
                    return;
                }
            }
        }

        // Save the last modified time of the directory
        fileToLastModified.put(dir, dir.lastModified());
    }

    /** Hierarchically scan directory structure for classfiles and matching files. */
    @Override
    void scanPaths(final LogNode log) {
        if (skipClasspathElement) {
            return;
        }
        if (scanned.getAndSet(true)) {
            // Should not happen
            throw new IllegalArgumentException("Already scanned classpath element " + toString());
        }

        final LogNode subLog = log == null ? null
                : log.log(classpathEltDir.getPath(), "Scanning directory classpath element " + classpathEltDir);

        scanDirRecursively(classpathEltDir, subLog);

        if (subLog != null) {
            if (whitelistedResources.isEmpty() && whitelistedClassfileResources.isEmpty()) {
                subLog.log("No whitelisted classfiles or resources found");
            } else if (whitelistedResources.isEmpty()) {
                subLog.log("No whitelisted resources found");
            } else if (whitelistedClassfileResources.isEmpty()) {
                subLog.log("No whitelisted classfiles found");
            }
        }

        if (subLog != null) {
            subLog.addElapsedTime();
        }
    }

    /** @return The classpath element directory as a {@link File}. */
    public File getDirFile() {
        return classpathEltDir;
    }

    /** Return the classpath element directory. */
    @Override
    public String toString() {
        return classpathEltDir.toString();
    }
}
