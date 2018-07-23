/*
 * This file is part of FastClasspathScanner.
 *
 * Author: Luke Hutchison
 *
 * Hosted at: https://github.com/lukehutch/fast-classpath-scanner
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
package io.github.lukehutch.fastclasspathscanner;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;

import io.github.lukehutch.fastclasspathscanner.ScanSpec.ScanSpecPathMatch;
import io.github.lukehutch.fastclasspathscanner.utils.ClasspathOrModulePathEntry;
import io.github.lukehutch.fastclasspathscanner.utils.FileUtils;
import io.github.lukehutch.fastclasspathscanner.utils.InputStreamOrByteBufferAdapter;
import io.github.lukehutch.fastclasspathscanner.utils.InterruptionChecker;
import io.github.lukehutch.fastclasspathscanner.utils.LogNode;

/** A directory classpath element. */
class ClasspathElementDir extends ClasspathElement {
    private File dir;

    /** A directory classpath element. */
    ClasspathElementDir(final ClasspathOrModulePathEntry classpathEltPath, final ScanSpec scanSpec,
            final boolean scanFiles, final InterruptionChecker interruptionChecker, final LogNode log) {
        super(classpathEltPath, scanSpec, scanFiles, interruptionChecker);
        if (scanFiles) {
            try {
                dir = classpathEltPath.getFile(log);
            } catch (final IOException e) {
                // Technically can't happen, was already checked by caller
                if (log != null) {
                    log.log("Exception while trying to canonicalize path " + classpathEltPath.getResolvedPath(), e);
                }
                skipClasspathElement = true;
                return;
            }
            fileMatches = new ArrayList<>();
            classfileMatches = new ArrayList<>();
            fileToLastModified = new HashMap<>();
        }
    }

    private Resource newClasspathResource(final File classpathEltFile, final String pathRelativeToClasspathElt,
            final File classpathResourceFile) {
        return new Resource() {
            private RandomAccessFile randomAccessFile;
            private FileChannel fileChannel;

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
            public ByteBuffer read() throws IOException {
                if (skipClasspathElement) {
                    // Shouldn't happen
                    throw new IOException("Parent directory could not be opened");
                }
                if (byteBuffer != null) {
                    return byteBuffer;
                } else {
                    try {
                        @SuppressWarnings("resource")
                        final RandomAccessFile randomAccessFile = new RandomAccessFile(classpathResourceFile, "r");
                        final FileChannel fileChannel = randomAccessFile.getChannel();
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
                return InputStreamOrByteBufferAdapter.create(read());
            }

            @Override
            public InputStream open() throws IOException {
                read();
                return byteBufferToInputStream();
            }

            @Override
            public byte[] load() throws IOException {
                read();
                final byte[] byteArray = byteBufferToByteArray();
                close();
                return byteArray;
            }

            @Override
            public void close() {
                if (fileChannel != null) {
                    try {
                        fileChannel.close();
                    } catch (final IOException e) {
                    }
                    fileChannel = null;
                }
                if (randomAccessFile != null) {
                    try {
                        randomAccessFile.close();
                    } catch (final IOException e) {
                    }
                    randomAccessFile = null;
                }
                super.close();
            }

            @Override
            protected String toStringImpl() {
                return "[dir " + classpathEltFile + "]/" + pathRelativeToClasspathElt;
            }
        };
    }

    /** Recursively scan a directory for file path patterns matching the scan spec. */
    private void scanDirRecursively(final File classpathElt, final File dir, final int ignorePrefixLen,
            final boolean prevInWhitelistedPath, final HashSet<String> scannedCanonicalPaths, final LogNode log) {
        boolean inWhitelistedPath = prevInWhitelistedPath;
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

        final ScanSpecPathMatch matchStatus = scanSpec.dirWhitelistMatchStatus(dirRelativePath);
        if (matchStatus == ScanSpecPathMatch.NOT_WITHIN_WHITELISTED_PATH
                || matchStatus == ScanSpecPathMatch.HAS_BLACKLISTED_PATH_PREFIX) {
            // Reached a non-whitelisted or blacklisted path -- stop the recursive scan
            if (log != null) {
                log.log("Reached non-whitelisted (or blacklisted) directory: " + dirRelativePath);
            }
            return;
        } else if (matchStatus == ScanSpecPathMatch.AT_WHITELISTED_PATH
                || matchStatus == ScanSpecPathMatch.HAS_WHITELISTED_PATH_PREFIX) {
            // Reached a whitelisted path -- can start scanning directories and files from this point
            inWhitelistedPath = true;
        }

        final File[] filesInDir = dir.listFiles();
        if (filesInDir == null) {
            if (log != null) {
                log.log("Invalid directory " + dir);
            }
            return;
        }
        final LogNode dirLog = log == null ? null
                : log.log(canonicalPath, "Scanning subdirectory path: " + dirRelativePath
                        + (dir.getPath().equals(canonicalPath) ? "" : " ; canonical path: " + canonicalPath));
        for (final File fileInDir : filesInDir) {
            if (fileInDir.isDirectory()) {
                if (inWhitelistedPath //
                        || matchStatus == ScanSpecPathMatch.ANCESTOR_OF_WHITELISTED_PATH) {
                    // Recurse into subdirectory
                    scanDirRecursively(classpathElt, fileInDir, ignorePrefixLen, inWhitelistedPath,
                            scannedCanonicalPaths, dirLog);
                    if (dirLog != null) {
                        dirLog.addElapsedTime();
                    }
                }
            } else if (fileInDir.isFile()) {
                final String fileInDirRelativePath = dirRelativePath.isEmpty() || "/".equals(dirRelativePath)
                        ? fileInDir.getName()
                        : dirRelativePath + fileInDir.getName();

                // Class can only be scanned if it's within a whitelisted path subtree, or if it is a classfile that
                // has been specifically-whitelisted
                if (!inWhitelistedPath && !(matchStatus == ScanSpecPathMatch.AT_WHITELISTED_CLASS_PACKAGE
                        && scanSpec.isSpecificallyWhitelistedClass(fileInDirRelativePath))) {
                    // Ignore files that are siblings of specifically-whitelisted files, but that are not themselves
                    // specifically whitelisted
                    continue;
                }

                if (dirLog != null) {
                    dirLog.log(fileInDirRelativePath, "Found whitelisted file: " + fileInDirRelativePath);
                }

                fileToLastModified.put(fileInDir, fileInDir.lastModified());

                if (scanSpec.enableClassInfo) {
                    // Store relative paths of any classfiles encountered
                    if (FileUtils.isClassfile(fileInDirRelativePath)) {
                        classfileMatches.add(newClasspathResource(classpathElt, fileInDirRelativePath, fileInDir));
                    }
                }

                // Record all classpath resources found in whitelisted paths
                fileMatches.add(newClasspathResource(classpathElt, fileInDirRelativePath, fileInDir));
            }
        }
        if (matchStatus == ScanSpecPathMatch.HAS_WHITELISTED_PATH_PREFIX
                || matchStatus == ScanSpecPathMatch.ANCESTOR_OF_WHITELISTED_PATH) {
            // Need to timestamp whitelisted directories, so that changes to directory content can be detected. Also
            // need to timestamp ancestors of whitelisted directories, in case a new directory is added that matches
            // whitelist criteria.
            fileToLastModified.put(dir, dir.lastModified());
        }
    }

    /** Hierarchically scan directory structure for classfiles and matching files. */
    @Override
    public void scanPaths(final LogNode log) {
        final String path = classpathEltPath.getResolvedPath();
        String canonicalPath = path;
        try {
            canonicalPath = classpathEltPath.getCanonicalPath(log);
        } catch (final IOException e) {
        }
        final LogNode logNode = log == null ? null
                : log.log(canonicalPath, "Scanning directory classpath entry " + classpathEltPath
                        + (path.equals(canonicalPath) ? "" : " ; canonical path: " + canonicalPath));

        final HashSet<String> scannedCanonicalPaths = new HashSet<>();
        scanDirRecursively(dir, dir, /* ignorePrefixLen = */ dir.getPath().length() + 1,
                /* inWhitelistedPath = */ false, scannedCanonicalPaths, logNode);
        if (logNode != null) {
            logNode.addElapsedTime();
        }
    }

    @Override
    public void close() {
        // Nothing to do for directories
    }
}
