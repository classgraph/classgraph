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
package io.github.classgraph.utils;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.AbstractMap.SimpleEntry;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import io.github.classgraph.ModuleReaderProxy;
import io.github.classgraph.ModuleRef;

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
 * ClassGraph makes two passes, one to read the zipfile directory, which whitelist and blacklist criteria are
 * applied to (this is a fast operation when using ZipFile), and then an additional pass to read only whitelisted
 * (non-blacklisted) entries. Therefore, in the general case, the ZipFile API is always going to be faster than
 * ZipInputStream. Therefore, decompressing the inner zipfile to disk is the only efficient option.
 */
public class NestedJarHandler {
    private final ConcurrentLinkedDeque<File> tempFiles = new ConcurrentLinkedDeque<>();
    private final SingletonMap<String, Entry<File, Set<String>>> nestedPathToJarfileAndRootRelativePathsMap;
    private final SingletonMap<File, Recycler<ZipFile, IOException>> zipFileToRecyclerMap;
    private final ConcurrentHashMap<File, File> innerJarToOuterJarMap;
    private final SingletonMap<File, JarfileMetadataReader> zipFileToJarfileMetadataReaderMap;
    private final SingletonMap<ModuleRef, Recycler<ModuleReaderProxy, IOException>> //
    moduleRefToModuleReaderProxyRecyclerMap;

    /** The separator between random temp filename part and leafname. */
    public static final String TEMP_FILENAME_LEAF_SEPARATOR = "---";

    /**
     * A handler for nested jars.
     * 
     * @param scanSpec
     *            The {@link ScanSpec}.
     * @param log
     *            The log.
     */
    public NestedJarHandler(final ScanSpec scanSpec, final LogNode log) {
        // Set up a singleton map from canonical path to ZipFile recycler
        this.zipFileToRecyclerMap = new SingletonMap<File, Recycler<ZipFile, IOException>>() {
            @Override
            public Recycler<ZipFile, IOException> newInstance(final File zipFile, final LogNode log) {
                return new Recycler<ZipFile, IOException>() {
                    @Override
                    public ZipFile newInstance() throws IOException {
                        return new ZipFile(zipFile.getPath());
                    }
                };
            }
        };

        // Set up a singleton map from canonical path to FastManifestParser
        this.zipFileToJarfileMetadataReaderMap = new SingletonMap<File, JarfileMetadataReader>() {
            @Override
            public JarfileMetadataReader newInstance(final File canonicalFile, final LogNode log) {
                return new JarfileMetadataReader(canonicalFile, scanSpec, log);
            }
        };

        // Set up a singleton map from inner jar to the outer jar it was extracted from
        this.innerJarToOuterJarMap = new ConcurrentHashMap<>();

        // Set up a singleton map from ModuleRef object to ModuleReaderProxy recycler
        this.moduleRefToModuleReaderProxyRecyclerMap = //
                new SingletonMap<ModuleRef, Recycler<ModuleReaderProxy, IOException>>() {
                    @Override
                    public Recycler<ModuleReaderProxy, IOException> newInstance(final ModuleRef moduleRef,
                            final LogNode log) {
                        return new Recycler<ModuleReaderProxy, IOException>() {
                            @Override
                            public ModuleReaderProxy newInstance() throws IOException {
                                return moduleRef.open();
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
                    final File pathFile;
                    if (isRemote) {
                        pathFile = downloadTempFile(nestedJarPath, log);
                        if (pathFile == null) {
                            throw new IOException("Could not download jarfile " + nestedJarPath);
                        }
                    } else {
                        pathFile = new File(nestedJarPath);
                    }
                    File canonicalFile;
                    try {
                        canonicalFile = pathFile.getCanonicalFile();
                    } catch (final SecurityException e) {
                        throw new IOException(
                                "Path component " + nestedJarPath + " could not be canonicalized: " + e);
                    }
                    if (!FileUtils.canRead(canonicalFile)) {
                        throw new IOException("Path component " + nestedJarPath + " does not exist");
                    }
                    if (!canonicalFile.isFile()) {
                        throw new IOException(
                                "Path component " + nestedJarPath + "  is not a file (expected a jarfile)");
                    }
                    // Return canonical file as the singleton entry for this path
                    final Set<String> rootRelativePaths = new HashSet<>();
                    return new SimpleEntry<>(canonicalFile, rootRelativePaths);

                } else {
                    // This path has one or more '!' sections.
                    final String parentPath = nestedJarPath.substring(0, lastPlingIdx);
                    String childPath = nestedJarPath.substring(lastPlingIdx + 1);
                    while (childPath.startsWith("/")) {
                        // "file.jar!/path_or_jar" -> "file.jar!path_or_jar"
                        childPath = childPath.substring(1);
                    }
                    // Recursively remove one '!' section at a time, back towards the beginning of the URL or
                    // file path. At the last frame of recursion, the toplevel jarfile will be reached and
                    // returned. The recursion is guaranteed to terminate because parentPath gets one
                    // '!'-section shorter with each recursion frame.
                    final Entry<File, Set<String>> parentJarfileAndRootRelativePaths = //
                            nestedPathToJarfileAndRootRelativePathsMap.getOrCreateSingleton(parentPath, log);
                    if (parentJarfileAndRootRelativePaths == null) {
                        // Failed to get topmost jarfile, e.g. file not found
                        throw new IOException("Could not find parent jarfile " + parentPath);
                    }
                    // Only the last item in a '!'-delimited list can be a non-jar path, so the parent must
                    // always be a jarfile.
                    final File parentJarFile = parentJarfileAndRootRelativePaths.getKey();
                    if (parentJarFile == null) {
                        // Failed to get topmost jarfile, e.g. file not found
                        throw new IOException("Could not find parent jarfile " + parentPath);
                    }

                    // Avoid decompressing the same nested jarfiles multiple times for different non-canonical
                    // parent paths, by calling getOrCreateSingleton() again using parentJarfile (which has a
                    // canonicalized path). This recursion is guaranteed to terminate after one extra recursion
                    // if File.getCanonicalFile() is idempotent, which it should be by definition.
                    final String parentJarFilePath = FastPathResolver.resolve(parentJarFile.getPath());
                    if (!parentJarFilePath.equals(parentPath)) {
                        // The path normalization process changed the path -- return a mapping
                        // to the NestedJarHandler resolution of the normalized path
                        final String pathWithinParent = parentJarFilePath + "!" + childPath;
                        final Entry<File, Set<String>> nextLevel = nestedPathToJarfileAndRootRelativePathsMap
                                .getOrCreateSingleton(pathWithinParent, log);
                        if (nextLevel != null) {
                            return nextLevel;
                        } else {
                            throw new IOException("Could not find jarfile path " + pathWithinParent);
                        }
                    }

                    // Get the ZipFile recycler for the parent jar's canonical path
                    final Recycler<ZipFile, IOException> parentJarRecycler = zipFileToRecyclerMap
                            .getOrCreateSingleton(parentJarFile.getCanonicalFile(), log);
                    try (Recycler<ZipFile, IOException>.Recyclable parentRecyclable = parentJarRecycler.acquire()) {
                        final ZipFile parentZipFile = parentRecyclable.get();

                        // Look up the child path within the parent zipfile
                        ZipEntry childZipEntry;
                        if (childPath.endsWith("/")) {
                            childZipEntry = parentZipFile.getEntry(childPath);
                        } else {
                            // Try appending "/" to childPath when fetching the ZipEntry. This will return
                            // the correct directory entry in buggy versions of the JRE, rather than returning
                            // a non-directory entry for directories (Bug #171). See:
                            // http://www.oracle.com/technetwork/java/javase/8u144-relnotes-3838694.html
                            childZipEntry = parentZipFile.getEntry(childPath + "/");
                            if (childZipEntry == null) {
                                // If there was no directory entry ending in "/", then look up the childPath
                                // without the appended "/".
                                childZipEntry = parentZipFile.getEntry(childPath);
                            }
                        }
                        boolean isDirectory = false;
                        if (childZipEntry == null) {
                            // Sometimes zipfiles do not have directory entries added to them. Check to see if
                            // there are any entries in the zipfile that have the child path as a prefix.
                            final String pathPrefix = childPath.endsWith("/") ? childPath : childPath + "/";
                            for (final Enumeration<? extends ZipEntry> zipEntries = parentZipFile
                                    .entries(); zipEntries.hasMoreElements();) {
                                final ZipEntry zipEntry = zipEntries.nextElement();
                                if (zipEntry.getName().startsWith(pathPrefix)) {
                                    isDirectory = true;
                                    break;
                                }
                            }
                            if (!isDirectory) {
                                throw new IOException(
                                        "Path " + childPath + " does not exist in jarfile " + parentJarFile);
                            }
                        } else {
                            isDirectory = childZipEntry.isDirectory();
                        }

                        // If path component is a directory, it is a package root
                        if (isDirectory) {
                            final boolean childPathIsLeaf = !childPath.contains("!");
                            if (!childPathIsLeaf) {
                                // Can only have a package root in the last component of the classpath
                                throw new IOException("Path " + childPath + " in jarfile " + parentJarFile
                                        + " is a " + "directory, but this is not the last \"!\"-delimited section "
                                        + "of the claspath entry URL -- cannot use as package root");
                            }
                            if (log != null) {
                                log.log("Path " + childPath + " in jarfile " + parentJarFile
                                        + " is a directory, not a file -- using as package root");
                            }
                            if (!childPath.isEmpty()) {
                                // Add directory path to parent jarfile root relative paths set
                                parentJarfileAndRootRelativePaths.getValue().add(childPath);
                            }
                            // Return parent entry
                            return parentJarfileAndRootRelativePaths;
                        }

                        // Do not extract nested jar, if nested jar scanning is disabled
                        if (!scanSpec.scanNestedJars) {
                            throw new IOException(
                                    "Nested jar scanning is disabled -- skipping extraction of nested jar "
                                            + nestedJarPath);
                        }

                        // Extract nested jar to a temporary file
                        try {
                            // Unzip the child jarfile to a temporary file
                            final File childJarFile = unzipToTempFile(parentZipFile, childZipEntry, log);

                            // Record mapping between inner and outer jar
                            innerJarToOuterJarMap.put(childJarFile, parentJarFile);

                            // Return the child temp zipfile as a new entry
                            final Set<String> rootRelativePaths = new HashSet<>();
                            return new SimpleEntry<>(childJarFile, rootRelativePaths);

                        } catch (final IOException e) {
                            // Thrown if the inner zipfile could nat be extracted
                            throw new IOException("File does not appear to be a zipfile: " + childPath);
                        }
                    }
                }
            }
        };
    }

    /**
     * Get a ZipFile recycler given the (non-nested) canonical path of a jarfile.
     * 
     * @param zipFile
     *            The zipfile.
     * @param log
     *            The log.
     * @return The ZipFile recycler.
     * @throws Exception
     *             If the zipfile could not be opened.
     */
    public Recycler<ZipFile, IOException> getZipFileRecycler(final File zipFile, final LogNode log)
            throws Exception {
        return zipFileToRecyclerMap.getOrCreateSingleton(zipFile, log);
    }

    /**
     * Get a {@link JarfileMetadataReader} singleton for a given jarfile (so that the manifest and ZipEntries will
     * only be read once).
     * 
     * @param zipFile
     *            The zipfile.
     * @param log
     *            The log.
     * @return The {@link JarfileMetadataReader}.
     * @throws Exception
     *             If the zipfile could not be opened.
     */
    public JarfileMetadataReader getJarfileMetadataReader(final File zipFile, final LogNode log) throws Exception {
        // Get the jarfile metadata reader singleton for this zipfile
        return zipFileToJarfileMetadataReaderMap.getOrCreateSingleton(zipFile, log);
    }

    /**
     * Get a ModuleReaderProxy recycler given a ModuleRef.
     * 
     * @param moduleRef
     *            The {@link ModuleRef}.
     * @param log
     *            The log.
     * @return The ModuleReaderProxy recycler.
     * @throws Exception
     *             If the module could not be opened.
     */
    public Recycler<ModuleReaderProxy, IOException> getModuleReaderProxyRecycler(final ModuleRef moduleRef,
            final LogNode log) throws Exception {
        return moduleRefToModuleReaderProxyRecyclerMap.getOrCreateSingleton(moduleRef, log);
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
     * @param nestedJarPath
     *            The nested jar path.
     * @param log
     *            The log.
     * @return An {@code Entry<File, Set<String>>}, where the {@code File} is the innermost jar, and the
     *         {@code Set<String>} is the set of all relative paths of scanning roots within the innermost jar (may
     *         be empty, or may contain strings like "target/classes" or similar). If there was an issue with the
     *         path, returns null.
     * @throws Exception
     *             If the innermost jarfile could not be extracted.
     */
    public Entry<File, Set<String>> getInnermostNestedJar(final String nestedJarPath, final LogNode log)
            throws Exception {
        return nestedPathToJarfileAndRootRelativePathsMap.getOrCreateSingleton(nestedJarPath, log);
    }

    /**
     * Given a File reference for an inner nested jarfile, find the outermost jarfile it was extracted from.
     * 
     * @param jarFile
     *            The jarfile.
     * @return The outermost jar that the jarfile was contained within.
     */
    public File getOutermostJar(final File jarFile) {
        File lastValid = jarFile;
        for (File curr = jarFile; curr != null; curr = innerJarToOuterJarMap.get(curr)) {
            lastValid = curr;
        }
        return lastValid;
    }

    private String leafname(final String path) {
        return path.substring(path.lastIndexOf('/') + 1);
    }

    private String sanitizeFilename(final String filename) {
        return filename.replace('/', '_').replace('\\', '_').replace(':', '_').replace('?', '_').replace('&', '_')
                .replace('=', '_').replace(' ', '_');
    }

    /** Download a jar from a URL to a temporary file. */
    private File downloadTempFile(final String jarURL, final LogNode log) {
        final LogNode subLog = log == null ? null : log.log(jarURL, "Downloading URL " + jarURL);
        File tempFile;
        try {
            tempFile = makeTempFile(jarURL, /* onlyUseLeafname = */ true);
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
     * {@link NestedJarHandler#close(LogNode)} is called.
     */
    private File unzipToTempFile(final ZipFile zipFile, final ZipEntry zipEntry, final LogNode log)
            throws IOException {
        String zipEntryPath = zipEntry.getName();
        if (zipEntryPath.startsWith("/")) {
            zipEntryPath = zipEntryPath.substring(1);
        }
        // The following filename format is also expected by JarUtils.leafName()
        final File tempFile = makeTempFile(zipEntryPath, /* onlyUseLeafname = */ true);
        LogNode subLog = null;
        if (log != null) {
            subLog = log.log("Unzipping " + (zipFile.getName() + "!/" + zipEntryPath))
                    .log("Extracted to temporary file " + tempFile.getPath());
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
     * Create a temporary file, and mark it for deletion on exit.
     * 
     * @param filePath
     *            The path to derive the temporary filename from.
     * @param onlyUseLeafname
     *            If true, only use the leafname of filePath to derive the temporary filename.
     * @return The temporary {@link File}.
     * @throws IOException
     *             If the temporary file could not be created.
     */
    public File makeTempFile(String filePath, boolean onlyUseLeafname) throws IOException {
        File tempFile = File.createTempFile("ClassGraph--",
                TEMP_FILENAME_LEAF_SEPARATOR + sanitizeFilename(onlyUseLeafname ? leafname(filePath) : filePath));
        markTempFileForDeletion(tempFile);
        return tempFile;
    }

    /** Mark a temp file for deletion on exit. */
    private void markTempFileForDeletion(File file) {
        file.deleteOnExit();
        tempFiles.add(file);
    }

    /**
     * Close zipfiles and modules.
     */
    public void closeRecyclers() {
        if (zipFileToRecyclerMap != null) {
            List<Recycler<ZipFile, IOException>> recyclers = null;
            try {
                recyclers = zipFileToRecyclerMap.values();
            } catch (final InterruptedException e) {
            }
            if (recyclers != null) {
                for (final Recycler<ZipFile, IOException> recycler : recyclers) {
                    recycler.close();
                }
            }
        }
        if (moduleRefToModuleReaderProxyRecyclerMap != null) {
            List<Recycler<ModuleReaderProxy, IOException>> recyclers = null;
            try {
                recyclers = moduleRefToModuleReaderProxyRecyclerMap.values();
            } catch (final InterruptedException e) {
            }
            if (recyclers != null) {
                for (final Recycler<ModuleReaderProxy, IOException> recycler : recyclers) {
                    recycler.close();
                }
            }
        }
    }

    /**
     * Close zipfiles and modules, and delete temporary files.
     * 
     * @param log
     *            The log.
     */
    public void close(final LogNode log) {
        closeRecyclers();
        if (tempFiles != null) {
            final LogNode rmLog = tempFiles.isEmpty() || log == null ? null : log.log("Removing temporary files");
            while (!tempFiles.isEmpty()) {
                final File tempFile = tempFiles.removeLast();
                final String path = tempFile.getPath();
                boolean success = false;
                Throwable e = null;
                try {
                    success = tempFile.delete();
                } catch (final Throwable t) {
                    e = t;
                }
                if (rmLog != null) {
                    rmLog.log(
                            (success ? "Removed" : "Unable to remove") + " " + path + (e == null ? "" : " : " + e));
                }
            }
        }
    }
}
