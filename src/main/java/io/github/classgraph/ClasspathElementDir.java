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

import io.github.classgraph.ScanSpec.ScanSpecPathMatch;
import io.github.classgraph.utils.ClasspathOrModulePathEntry;
import io.github.classgraph.utils.FileUtils;
import io.github.classgraph.utils.InputStreamOrByteBufferAdapter;
import io.github.classgraph.utils.LogNode;

/** A directory classpath element. */
class ClasspathElementDir extends ClasspathElement {
    private File classpathEltDir;
    private int ignorePrefixLen;

    /** Used to ensure that recursive scanning doesn't get into an infinite loop due to a link cycle. */
    private final HashSet<String> scannedCanonicalPaths = new HashSet<>();

    /** A directory classpath element. */
    ClasspathElementDir(final ClasspathOrModulePathEntry classpathEltPath, final ScanSpec scanSpec,
            final LogNode log) {
        super(classpathEltPath, scanSpec);
        if (scanSpec.performScan) {
            try {
                classpathEltDir = classpathEltPath.getFile(log);
            } catch (final IOException e) {
                // Technically can't happen, was already checked by caller
                if (log != null) {
                    log.log("Exception while trying to canonicalize path " + classpathEltPath.getResolvedPath(), e);
                }
                skipClasspathElement = true;
                return;
            }
            ignorePrefixLen = classpathEltDir.getPath().length() + 1;

            resourceMatches = new ArrayList<>();
            whitelistedClassfileResources = new ArrayList<>();
            nonBlacklistedClassfileResources = new ArrayList<>();
            fileToLastModified = new HashMap<>();
        }
    }

    /** Create a new {@link Resource} object for a resource or classfile discovered while scanning paths. */
    private Resource newResource(final File classpathEltFile, final String pathRelativeToClasspathElt,
            final File classpathResourceFile) {
        return new Resource() {
            private RandomAccessFile randomAccessFile;
            private FileChannel fileChannel;

            {
                length = classpathResourceFile.length();
            }

            @Override
            public String getPath() {
                return pathRelativeToClasspathElt;
            }

            @Override
            public String getPathRelativeToClasspathElement() {
                return pathRelativeToClasspathElt;
            }

            @Override
            public URL getURL() {
                try {
                    return new File(classpathEltFile, pathRelativeToClasspathElt).toURI().toURL();
                } catch (final MalformedURLException e) {
                    throw new IllegalArgumentException("Could not form URL for dir: " + classpathEltFile
                            + " ; path: " + pathRelativeToClasspathElt);
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
            public ByteBuffer read() throws IOException {
                if (skipClasspathElement) {
                    // Shouldn't happen
                    throw new IOException("Parent directory could not be opened");
                }
                if (byteBuffer != null) {
                    throw new IllegalArgumentException(
                            "Resource is already open -- cannot open it again without first calling close()");
                } else {
                    try {
                        randomAccessFile = new RandomAccessFile(classpathResourceFile, "r");
                        fileChannel = randomAccessFile.getChannel();
                        final MappedByteBuffer buffer = fileChannel.map(FileChannel.MapMode.READ_ONLY, 0,
                                fileChannel.size());
                        buffer.load();
                        byteBuffer = buffer;
                        length = byteBuffer.remaining();
                        return byteBuffer;
                    } catch (final Exception e) {
                        close();
                        throw new IOException("Could not open " + this, e);
                    }
                }
            }

            @Override
            InputStreamOrByteBufferAdapter openOrRead() throws IOException {
                if (length >= FileUtils.FILECHANNEL_FILE_SIZE_THRESHOLD) {
                    return InputStreamOrByteBufferAdapter.create(read());
                } else {
                    return InputStreamOrByteBufferAdapter.create(inputStream = new InputStreamResourceCloser(this,
                            new FileInputStream(classpathResourceFile)));
                }
            }

            @Override
            public InputStream open() throws IOException {
                if (length >= FileUtils.FILECHANNEL_FILE_SIZE_THRESHOLD) {
                    read();
                    return inputStream = new InputStreamResourceCloser(this, byteBufferToInputStream());
                } else {
                    return inputStream = new InputStreamResourceCloser(this,
                            Files.newInputStream(classpathResourceFile.toPath()));
                }
            }

            @Override
            public byte[] load() throws IOException {
                try {
                    final byte[] byteArray;
                    if (length >= FileUtils.FILECHANNEL_FILE_SIZE_THRESHOLD) {
                        read();
                        byteArray = byteBufferToByteArray();
                    } else {
                        open();
                        byteArray = FileUtils.readAllBytesAsArray(inputStream, length, null);
                    }
                    length = byteArray.length;
                    return byteArray;
                } finally {
                    close();
                }
            }

            @Override
            public void close() {
                if (inputStream != null) {
                    try {
                        // Avoid infinite loop with InputStreamResourceCloser trying to close its parent resource
                        final InputStream inputStreamWrapper = inputStream;
                        inputStream = null;
                        inputStreamWrapper.close();
                    } catch (final IOException e) {
                        // Ignore
                    }
                }
                if (byteBuffer != null) {
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
            }
        };
    }

    /** Recursively scan a directory for file path patterns matching the scan spec. */
    private void scanDirRecursively(final File dir, final LogNode log) {
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

        final ScanSpecPathMatch parentMatchStatus = scanSpec.dirWhitelistMatchStatus(dirRelativePath);
        if (parentMatchStatus == ScanSpecPathMatch.HAS_BLACKLISTED_PATH_PREFIX) {
            // Reached a non-whitelisted or blacklisted path -- stop the recursive scan
            if (log != null) {
                log.log("Reached blacklisted directory, stopping recursive scan: " + dirRelativePath);
            }
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
        for (final File fileInDir : filesInDir) {
            if (fileInDir.isDirectory()) {
                // Recurse into subdirectory
                scanDirRecursively(fileInDir, subLog);
                if (subLog != null) {
                    subLog.addElapsedTime();
                }
            } else if (fileInDir.isFile()) {
                final String fileInDirRelativePath = dirRelativePath.isEmpty() || "/".equals(dirRelativePath)
                        ? fileInDir.getName()
                        : dirRelativePath + fileInDir.getName();
                // Add the file path as a Resource
                final Resource resource = newResource(classpathEltDir, fileInDirRelativePath, fileInDir);
                final boolean isWhitelisted = addResource(resource, parentMatchStatus, subLog);
                if (isWhitelisted) {
                    // If the resource file was whitelisted, save last modified time  
                    fileToLastModified.put(fileInDir, fileInDir.lastModified());
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

        final LogNode logNode = log == null ? null
                : log.log(classpathEltPath.getResolvedPath(),
                        "Scanning directory classpath element " + classpathEltPath);
        scanDirRecursively(classpathEltDir, logNode);
    }

    @Override
    void closeRecyclers() {
        // Nothing to do for directories
    }
}
