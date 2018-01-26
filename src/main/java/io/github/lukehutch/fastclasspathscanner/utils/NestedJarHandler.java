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
import java.util.AbstractMap.SimpleEntry;
import java.util.HashSet;
import java.util.List;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Unzip a jarfile within a jarfile to a temporary file on disk. Also handles the download of jars from http(s) URLs
 * to temp files.
 *
 * <p>
 * Somewhat paradoxically, the fastest way to support scanning zipfiles-within-zipfiles is to unzip the inner
 * zipfile to a temporary file on disk, because the inner zipfile can only be read using ZipInputStream, not ZipFile
 * (the ZipFile constructors only take a File argument). ZipInputStream doesn't have methods for reading the zip
 * directory at the beginning of the stream, so using ZipInputStream rather than ZipFile, you have to decompress the
 * entire zipfile to read all the directory entries. However, there may be many non-whitelisted entries in the
 * zipfile, so this could be a lot of wasted work.
 *
 * <p>
 * FastClasspathScanner makes two passes, one to read the zipfile directory, which whitelist and blacklist criteria
 * are applied to (this is a fast operation when using ZipFile), and then an additional pass to read only
 * whitelisted (non-blacklisted) entries. Therefore, in the general case, the ZipFile API is always going to be
 * faster than ZipInputStream. Therefore, decompressing the inner zipfile to disk is the only efficient option.
 */
public class NestedJarHandler {
    private final ConcurrentLinkedDeque<File> tempFiles = new ConcurrentLinkedDeque<>();
    private final SingletonMap<String, Entry<File, Set<String>>> nestedPathToJarfileAndRootRelativePathsMap;
    private final SingletonMap<String, Recycler<ZipFile, IOException>> canonicalPathToZipFileRecyclerMap;
    private final InterruptionChecker interruptionChecker;

    public static final String TEMP_FILENAME_LEAF_SEPARATOR = "---";

    public NestedJarHandler(final InterruptionChecker interruptionChecker, final LogNode log) {
        this.interruptionChecker = interruptionChecker;

        // Set up a singleton map from canonical path to ZipFile recycler
        this.canonicalPathToZipFileRecyclerMap = new SingletonMap<String, Recycler<ZipFile, IOException>>() {
            @Override
            public Recycler<ZipFile, IOException> newInstance(final String canonicalPath, final LogNode log)
                    throws Exception {
                return new Recycler<ZipFile, IOException>() {
                    @Override
                    public ZipFile newInstance() throws IOException {
                        return new ZipFile(canonicalPath);
                    }
                };
            }
        };

        // Create a singleton map from path to zipfile File, in order to eliminate repeatedly unzipping the same
        // file when there are multiple jars-within-jars that need unzipping to temporary files.
        this.nestedPathToJarfileAndRootRelativePathsMap = new SingletonMap<String, Entry<File, Set<String>>>() {
            @Override
            public Entry<File, Set<String>> newInstance(final String nestedJarPath, final LogNode log)
                    throws Exception {
                final int lastPlingIdx = nestedJarPath.lastIndexOf('!');
                if (lastPlingIdx < 0) {
                    // nestedJarPath is a simple file path or URL (i.e. doesn't have any '!' sections). This is also
                    // the last frame of recursion for the 'else' clause below.

                    // If the path starts with "http(s)://", download the jar to a temp file
                    final boolean isRemote = nestedJarPath.startsWith("http://")
                            || nestedJarPath.startsWith("https://");
                    final File pathFile = isRemote ? downloadTempFile(nestedJarPath, log) : new File(nestedJarPath);
                    if (isRemote && pathFile == null) {
                        if (log != null) {
                            log.log(nestedJarPath, "Could not download jarfile " + nestedJarPath);
                        }
                        return null;
                    }
                    File canonicalFile;
                    try {
                        canonicalFile = pathFile.getCanonicalFile();
                    } catch (final IOException | SecurityException e) {
                        if (log != null) {
                            log.log(nestedJarPath,
                                    "Path component " + nestedJarPath + " could not be canonicalized: " + e);
                        }
                        return null;
                    }
                    if (!ClasspathUtils.canRead(canonicalFile)) {
                        if (log != null) {
                            log.log(nestedJarPath, "Path component " + nestedJarPath + " does not exist");
                        }
                        return null;
                    }
                    if (!canonicalFile.isFile()) {
                        if (log != null) {
                            log.log(nestedJarPath,
                                    "Path component " + nestedJarPath + "  is not a file (expected a jarfile)");
                        }
                        return null;
                    }
                    // Handle self-extracting archives (they can be created by Spring-Boot)
                    final File bareJarfile = stripSFXHeader(canonicalFile, log);
                    // Return canonical file as the singleton entry for this path
                    final Set<String> rootRelativePaths = new HashSet<>();
                    return new SimpleEntry<>(bareJarfile, rootRelativePaths);

                } else {
                    // This path has one or more '!' sections.
                    final String parentPath = nestedJarPath.substring(0, lastPlingIdx);
                    String childPath = nestedJarPath.substring(lastPlingIdx + 1);
                    if (childPath.startsWith("/")) {
                        // "file.jar!/path_or_jar" -> "file.jar!path_or_jar"
                        childPath = childPath.substring(1);
                    }
                    // Recursively remove one '!' section at a time, back towards the beginning of the URL or
                    // file path. At the last frame of recursion, the toplevel jarfile will be reached and
                    // returned. The recursion is guaranteed to terminate because parentPath gets one
                    // '!'-section shorter with each recursion frame.
                    final Entry<File, Set<String>> parentJarfileAndRootRelativePaths = //
                            nestedPathToJarfileAndRootRelativePathsMap.getOrCreateSingleton(parentPath, log);
                    // Only the last item in a '!'-delimited list can be a non-jar path, so the parent must
                    // always be a jarfile.
                    final File parentJarFile = parentJarfileAndRootRelativePaths.getKey();
                    if (parentJarFile == null) {
                        // Failed to get parent jarfile
                        return null;
                    }

                    // Avoid decompressing the same nested jarfiles multiple times for different non-canonical
                    // parent paths, by calling getOrCreateSingleton() again using parentJarfile (which has a
                    // canonicalized path). This recursion is guaranteed to terminate after one extra recursion
                    // if File.getCanonicalFile() is idempotent, which it should be by definition.
                    final String parentJarFilePath = FastPathResolver.resolve(parentJarFile.getPath());
                    if (!parentJarFilePath.equals(parentPath)) {
                        return nestedPathToJarfileAndRootRelativePathsMap
                                .getOrCreateSingleton(parentJarFilePath + "!" + childPath, log);
                    }

                    // Handle self-extracting archives (they can be created by Spring-Boot)
                    final File bareParentJarFile = stripSFXHeader(parentJarFile, log);

                    // Get the ZipFile recycler for the parent jar's canonical path
                    final Recycler<ZipFile, IOException> parentJarRecycler = canonicalPathToZipFileRecyclerMap
                            .getOrCreateSingleton(bareParentJarFile.getCanonicalPath(), log);
                    ZipFile parentZipFile = null;
                    try {
                        // Look up the child path within the parent zipfile
                        parentZipFile = parentJarRecycler.acquire();
                        final ZipEntry childZipEntry = parentZipFile.getEntry(childPath);
                        if (childZipEntry == null) {
                            if (log != null) {
                                log.log(nestedJarPath, "Child path component " + childPath
                                        + " does not exist in jarfile " + bareParentJarFile);
                            }
                            return null;
                        }

                        // Make sure path component is a file, not a directory (can't unzip directories)
                        if (childZipEntry.isDirectory()) {
                            if (log != null) {
                                log.log(nestedJarPath,
                                        "Child path component " + childPath + " in jarfile " + bareParentJarFile
                                                + " is a directory, not a file -- using as scanning root");
                            }
                            // Add directory path to parent jarfile root relative paths set
                            parentJarfileAndRootRelativePaths.getValue().add(childPath);
                            // Return parent entry
                            return parentJarfileAndRootRelativePaths;
                        }

                        // Unzip the child zipfile to a temporary file
                        final File childTempFile = unzipToTempFile(parentZipFile, childZipEntry, log);

                        // Return the child temp zipfile as a new entry
                        final Set<String> rootRelativePaths = new HashSet<>();
                        return new SimpleEntry<>(childTempFile, rootRelativePaths);

                    } finally {
                        parentJarRecycler.release(parentZipFile);
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
    public Recycler<ZipFile, IOException> getZipFileRecycler(final String canonicalPath, final LogNode log)
            throws Exception {
        return canonicalPathToZipFileRecyclerMap.getOrCreateSingleton(canonicalPath, log);
    }

    /**
     * Get a File for a given (possibly nested) jarfile path, unzipping the first N-1 segments of an N-segment
     * '!'-delimited path to temporary files, then returning the File reference for the N-th temporary file.
     *
     * <p>
     * If the path does not contain '!', returns the File represented by the path.
     *
     * <p>
     * All path segments should end in a jarfile extension, e.g. ".jar" or ".zip".
     *
     * @return An {@code Entry<File, Set<String>>}, where the {@code File} is the innermost jar, and the
     *         {@code Set<String>} is the set of all relative paths of scanning roots within the innermost jar (may
     *         be empty, or may contain strings like "target/classes" or similar). If there was an issue with the
     *         path, returns null.
     */
    public Entry<File, Set<String>> getInnermostNestedJar(final String nestedJarPath, final LogNode log)
            throws Exception {
        return nestedPathToJarfileAndRootRelativePathsMap.getOrCreateSingleton(nestedJarPath, log);
    }

    /** Download a jar from a URL to a temporary file. */
    private File downloadTempFile(final String jarURL, final LogNode log) {
        final LogNode subLog = log == null ? null : log.log(jarURL, "Downloading URL " + jarURL);
        File tempFile = null;
        try {
            final String suffix = TEMP_FILENAME_LEAF_SEPARATOR + jarURL.replace('/', '_').replace(':', '_')
                    .replace('?', '_').replace('&', '_').replace('=', '_');
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
    private File unzipToTempFile(final ZipFile zipFile, final ZipEntry zipEntry, final LogNode log)
            throws IOException {
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

    /**
     * Strip self-extracting archive header from zipfile, if present. (Simply strips everything before the first
     * "PK".)
     */
    private File stripSFXHeader(final File zipfile, final LogNode log) throws IOException {
        final long sfxHeaderBytes = JarUtils.countBytesBeforePKMarker(zipfile);
        if (sfxHeaderBytes == -1L) {
            throw new IOException("Could not find zipfile \"PK\" marker in file " + zipfile);
        } else if (sfxHeaderBytes == 0L) {
            // No self-extracting zipfile header
            return zipfile;
        } else {
            // Need to strip off ZipSFX header (e.g. Bash script prepended by Spring-Boot)
            final File bareZipfile = File.createTempFile("FastClasspathScanner-",
                    TEMP_FILENAME_LEAF_SEPARATOR + JarUtils.leafName(zipfile.getName()));
            bareZipfile.deleteOnExit();
            tempFiles.add(bareZipfile);
            if (log != null) {
                log.log("Zipfile " + zipfile + " contains a self-extracting executable header of " + sfxHeaderBytes
                        + " bytes. Stripping off header to create bare zipfile " + bareZipfile);
            }
            JarUtils.stripSFXHeader(zipfile, sfxHeaderBytes, bareZipfile);
            return bareZipfile;
        }
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
        List<Recycler<ZipFile, IOException>> recyclers = null;
        try {
            recyclers = canonicalPathToZipFileRecyclerMap.values();
        } catch (final InterruptedException e) {
            // Stop other threads
            interruptionChecker.interrupt();
        }
        if (recyclers != null) {
            for (final Recycler<ZipFile, IOException> recycler : recyclers) {
                recycler.close();
            }
            canonicalPathToZipFileRecyclerMap.clear();
        }
    }
}
