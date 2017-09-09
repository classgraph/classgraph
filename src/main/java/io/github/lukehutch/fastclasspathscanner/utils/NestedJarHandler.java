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
 * Copyright (c) 2016 Luke Hutchison
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
package io.github.lukehutch.fastclasspathscanner.utils;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Unzip a jarfile within a jarfile to a temporary file on disk. Also handles the download of jars from http(s) URLs
 * to temp files.
 * 
 * Somewhat paradoxically, the fastest way to support scanning zipfiles-within-zipfiles is to unzip the inner
 * zipfile to a temporary file on disk, because the inner zipfile can only be read using ZipInputStream, not ZipFile
 * (the ZipFile constructors only take a File argument). ZipInputStream doesn't have methods for reading the zip
 * directory at the beginning of the stream, so using ZipInputStream rather than ZipFile, you have to decompress the
 * entire zipfile to read all the directory entries. However, there may be many non-whitelisted entries in the
 * zipfile, so this could be a lot of wasted work.
 * 
 * FastClasspathScanner makes two passes, one to read the zipfile directory, which whitelist and blacklist criteria
 * are applied to (this is a fast operation when using ZipFile), and then an additional pass to read only
 * whitelisted (non-blacklisted) entries. Therefore, in the general case, the ZipFile API is always going to be
 * faster than ZipInputStream. Therefore, decompressing the inner zipfile to disk is the only efficient option.
 */
public class NestedJarHandler {
    private final ConcurrentLinkedDeque<File> tempFiles = new ConcurrentLinkedDeque<>();
    private final SingletonMap<String, File> nestedPathToJarfileMap;
    private final SingletonMap<String, Recycler<ZipFile, IOException>> canonicalPathToZipFileRecyclerMap;
    private final InterruptionChecker interruptionChecker;
    private final LogNode log;

    public static final String TEMP_FILENAME_LEAF_SEPARATOR = "---";

    public NestedJarHandler(final InterruptionChecker interruptionChecker, final LogNode log) {
        this.interruptionChecker = interruptionChecker;
        this.log = log;

        // Set up a singleton map from canonical path to ZipFile recycler
        this.canonicalPathToZipFileRecyclerMap = new SingletonMap<String, Recycler<ZipFile, IOException>>() {
            @Override
            public Recycler<ZipFile, IOException> newInstance(final String canonicalPath) throws Exception {
                return new Recycler<ZipFile, IOException>() {
                    @Override
                    public ZipFile newInstance() throws IOException {
                        return new ZipFile(canonicalPath);
                    }
                };
            }
        };

        // Create a singleton map from path to zipfile File, in order to eliminate repeatedly unzipping
        // the same file when there are multiple jars-within-jars that need unzipping to temporary files. 
        this.nestedPathToJarfileMap = new SingletonMap<String, File>() {
            @Override
            public File newInstance(final String nestedJarPath) throws Exception {
                final int lastPlingIdx = nestedJarPath.lastIndexOf('!');
                if (lastPlingIdx < 0) {
                    // This portion of the file path is the deepest-nested jar (i.e. doesn't have any '!' sections).
                    // It should end with a jar extension, and the file needs to exist and be a file.

                    // If the path starts with "http(s)://", download the jar to a temp file
                    final boolean isRemote = nestedJarPath.startsWith("http://")
                            || nestedJarPath.startsWith("https://");
                    final File pathFile = isRemote ? downloadTempFile(nestedJarPath, log) : new File(nestedJarPath);
                    if (pathFile == null) {
                        if (log != null) {
                            log.log(nestedJarPath, "Could not download file: " + nestedJarPath);
                        }
                        return null;
                    }
                    File canonicalFile;
                    try {
                        canonicalFile = pathFile.getCanonicalFile();
                    } catch (final IOException | SecurityException e) {
                        if (log != null) {
                            log.log(nestedJarPath, "Path component could not be canonicalized: " + nestedJarPath,
                                    e);
                        }
                        return null;
                    }
                    if (!ClasspathUtils.canRead(canonicalFile)) {
                        if (log != null) {
                            log.log(nestedJarPath, "Path component does not exist: " + nestedJarPath);
                        }
                        return null;
                    }
                    if (!canonicalFile.isFile()) {
                        if (log != null) {
                            log.log(nestedJarPath, "Path component is not a file: " + nestedJarPath);
                        }
                        return null;
                    }
                    if (!JarUtils.isJar(canonicalFile.getPath())) {
                        // Should not happen, this has already been checked for
                        if (log != null) {
                            log.log(nestedJarPath,
                                    "Ignoring classpath element with non-jar path component: " + nestedJarPath);
                        }
                        return null;
                    }
                    // Return canonical file as the singleton entry for this path
                    return canonicalFile;

                } else {
                    // This path has one or more '!' sections
                    final String parentPath = nestedJarPath.substring(0, lastPlingIdx);
                    String childPath = nestedJarPath.substring(lastPlingIdx + 1);
                    if (childPath.startsWith("/")) {
                        childPath = childPath.substring(1);
                    }
                    if (!JarUtils.isJar(childPath)) {
                        // Should not happen, this has already been checked for
                        if (log != null) {
                            log.log(nestedJarPath,
                                    "Ignoring classpath element with non-jar path component: " + nestedJarPath);
                        }
                        return null;
                    }

                    try {
                        // Recursively get next nested jarfile. This is guaranteed to terminate because parentPath
                        // is one '!'-section shorter with each recursion.
                        final File parentJarfile = nestedPathToJarfileMap.getOrCreateSingleton(parentPath);
                        if (parentJarfile == null) {
                            // Failed to get parent jarfile
                            return null;
                        }

                        // Avoid decompressing the same nested jarfiles multiple times for different non-canonical
                        // parent paths. This recursion is guaranteed to terminate after one extra recursion if
                        // File.getCanonicalFile() is idempotent, which it should be by definition.
                        if (!parentJarfile.getPath().equals(parentPath)) {
                            return nestedPathToJarfileMap
                                    .getOrCreateSingleton(parentJarfile.getPath() + "!" + childPath);
                        }

                        // Get the ZipFile recycler for the parent jar's canonical path
                        final Recycler<ZipFile, IOException> parentJarRecycler = canonicalPathToZipFileRecyclerMap
                                .getOrCreateSingleton(parentJarfile.getCanonicalPath());
                        ZipFile parentZipFile = null;
                        try {
                            // Look up the child path within the parent zipfile
                            parentZipFile = parentJarRecycler.acquire();
                            final ZipEntry childZipEntry = parentZipFile.getEntry(childPath);
                            if (childZipEntry == null) {
                                if (log != null) {
                                    log.log(nestedJarPath, "Child path component " + childPath
                                            + " does not exist in jarfile " + parentPath);
                                }
                                return null;
                            }

                            // Unzip the child zipfile to a temporary file
                            final File childTempFile = unzipToTempFile(parentZipFile, childZipEntry);

                            // Stop the nested unzipping process if this thread was interrupted,
                            // and notify other threads
                            if (interruptionChecker.checkAndReturn()) {
                                return null;
                            }

                            // Return the child temp zipfile
                            return childTempFile;

                        } finally {
                            parentJarRecycler.release(parentZipFile);
                        }
                    } catch (final InterruptedException e) {
                        interruptionChecker.interrupt();
                        throw e;
                    }
                }
            }
        };
    }

    /**
     * Get a ZipFile recycler given the (non-nested) canonical path of a jarfile.
     * 
     * @return The ZipFile recycler.
     */
    public Recycler<ZipFile, IOException> getZipFileRecycler(final String canonicalPath) throws Exception {
        try {
            return canonicalPathToZipFileRecyclerMap.getOrCreateSingleton(canonicalPath);
        } catch (final InterruptedException e) {
            interruptionChecker.interrupt();
            throw e;
        }
    }

    /**
     * Get a File for a given (possibly nested) jarfile path, unzipping the first N-1 segments of an N-segment
     * '!'-delimited path to temporary files, then returning the File reference for the N-th temporary file.
     * 
     * If the path does not contain '!', returns the File represented by the path.
     * 
     * All path segments should end in a jarfile extension, e.g. ".jar" or ".zip".
     * 
     * @return the File for the innermost jar.
     */
    public File getInnermostNestedJar(final String nestedJarPath) throws Exception {
        try {
            return nestedPathToJarfileMap.getOrCreateSingleton(nestedJarPath);
        } catch (final InterruptedException e) {
            interruptionChecker.interrupt();
            throw e;
        }
    }

    /** Download a jar from a URL to a temporary file. */
    private File downloadTempFile(final String jarURL, final LogNode log) {
        final LogNode subLog = log == null ? null : log.log(jarURL, "Downloading URL " + jarURL);
        File tempFile = null;
        try {
            String suffix = TEMP_FILENAME_LEAF_SEPARATOR + jarURL.replace('/', '_').replace(':', '_')
                    .replace('?', '_').replace('&', '_').replace('=', '_');
            if (!JarUtils.isJar(suffix)) {
                // Ensure there is a jar suffix on file, so that it is not skipped
                // (an HTTP URL may deliver a jarfile as its response regardless of the format of the URL)
                suffix += ".jar";
            }
            tempFile = File.createTempFile("FastClasspathScanner-", suffix);
            tempFile.deleteOnExit();
            tempFiles.add(tempFile);
            final URL url = new URL(jarURL);
            try (InputStream inputStream = url.openStream()) {
                Files.copy(inputStream, tempFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            if (subLog != null) {
                subLog.addElapsedTime();
            }
        } catch (final Exception e) {
            if (subLog != null) {
                subLog.log("Could not download " + jarURL, e);
            }
            return null;
        }
        if (subLog != null) {
            subLog.log("Downloaded to temporary file " + tempFile);
            subLog.log("***** Note that it is time-consuming to scan jars at http(s) addresses, "
                    + "they must be downloaded for every scan, and the same jars must also be "
                    + "separately downloaded by the ClassLoader *****");
        }
        return tempFile;
    }

    /**
     * Unzip a ZipEntry to a temporary file, then return the temporary file. The temporary file will be removed when
     * NestedJarHandler#close() is called.
     */
    public File unzipToTempFile(final ZipFile zipFile, final ZipEntry zipEntry) throws IOException {
        String zipEntryPath = zipEntry.getName();
        if (zipEntryPath.startsWith("/")) {
            zipEntryPath = zipEntryPath.substring(1);
        }
        final String zipEntryLeaf = zipEntryPath.substring(zipEntryPath.lastIndexOf('/') + 1);
        // The following filename format is also expected by JarUtils.leafName()
        final File tempFile = File.createTempFile("FastClasspathScanner-",
                TEMP_FILENAME_LEAF_SEPARATOR + zipEntryLeaf);
        tempFile.deleteOnExit();
        tempFiles.add(tempFile);
        LogNode subLog = null;
        if (log != null) {
            final String qualifiedPath = zipFile.getName() + "!/" + zipEntryPath;
            subLog = log.log(qualifiedPath, "Unzipping zip entry " + qualifiedPath);
            subLog.log("Extracted to temporary file " + tempFile.getPath());
        }
        try (InputStream inputStream = zipFile.getInputStream(zipEntry)) {
            Files.copy(inputStream, tempFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
        if (subLog != null) {
            subLog.addElapsedTime();
        }
        return tempFile;
    }

    /** Delete temporary files and release other resources. */
    public void close(final LogNode log) {
        final LogNode rmLog = tempFiles.isEmpty() || log == null ? null : log.log("Removing temporary files");
        while (!tempFiles.isEmpty()) {
            final File head = tempFiles.remove();
            final String path = head.getPath();
            final boolean success = head.delete();
            if (log != null) {
                rmLog.log((success ? "Removed" : "Unable to remove") + " " + path);
            }
        }
        try {
            for (final Recycler<ZipFile, IOException> recycler : canonicalPathToZipFileRecyclerMap.values()) {
                recycler.close();
            }
            canonicalPathToZipFileRecyclerMap.clear();
        } catch (final InterruptedException e) {
            // Stop other threads
            interruptionChecker.interrupt();
        }
    }
}
