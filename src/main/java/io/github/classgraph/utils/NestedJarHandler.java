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
import io.github.classgraph.ScanSpec;

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
    private final SingletonMap<File, Boolean> mkDirs;

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
            public Recycler<ZipFile, IOException> newInstance(final File zipFile, final LogNode log)
                    throws Exception {
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
            public JarfileMetadataReader newInstance(final File canonicalFile, final LogNode log) throws Exception {
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
                            final LogNode log) throws Exception {
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
                    // Handle self-extracting archives (they can be created by Spring-Boot)
                    final File bareJarfile = scanSpec.stripSFXHeader ? stripSFXHeader(canonicalFile, log)
                            : canonicalFile;
                    // Return canonical file as the singleton entry for this path
                    final Set<String> rootRelativePaths = new HashSet<>();
                    return new SimpleEntry<>(bareJarfile, rootRelativePaths);

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
                            final boolean childPathIsLeaf = childPath.indexOf("!") < 0;
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

                            // Handle self-extracting archives (can be created by Spring-Boot)
                            final File noHeaderChildJarFile = scanSpec.stripSFXHeader
                                    ? stripSFXHeader(childJarFile, log)
                                    : childJarFile;

                            // Record mapping between inner and outer jar
                            innerJarToOuterJarMap.put(noHeaderChildJarFile, parentJarFile);

                            // Return the child temp zipfile as a new entry
                            final Set<String> rootRelativePaths = new HashSet<>();
                            return new SimpleEntry<>(noHeaderChildJarFile, rootRelativePaths);

                        } catch (final IOException e) {
                            // Thrown if the inner zipfile could nat be extracted
                            throw new IOException("File does not appear to be a zipfile: " + childPath);
                        }
                    }
                }
            }
        };

        // Create a singleton map indicating which directories were able to be successfully created (or
        // already existed), to avoid duplicating work calling mkdirs() multiple times for the same directories
        mkDirs = new SingletonMap<File, Boolean>() {
            @Override
            public Boolean newInstance(final File dir, final LogNode log) throws Exception {
                boolean dirExists = dir.exists();
                if (!dirExists) {
                    final File parentDir = dir.getParentFile();
                    // If parentDir == null, then dir in the root directory of the filesystem --
                    // it is unlikely that this is going to work, but try creating dir anyway,
                    // in case this is a RAM disk or something. If parentDir is not null, try
                    // recursively creating parent dir
                    if (parentDir == null || mkDirs.getOrCreateSingleton(parentDir, log)) {
                        // Succeeded in creating parent dir, or parent dir already existed, or parent is root
                        // -- try creating dir
                        dirExists = dir.mkdir();
                        if (!dirExists) {
                            // Check one more time, if mkdir failed, in case there were some existing
                            // symlinks putting the same dir on two physical paths, and another thread
                            // already created the dir. 
                            dirExists = dir.exists();
                        }
                        if (log != null) {
                            if (!dirExists) {
                                log.log("Cannot create directory: " + dir.toPath());
                            } else if (!dir.isDirectory()) {
                                log.log("Can't overwrite a file with a directory: " + dir.toPath());
                            } else {
                                log.log("Creating directory: " + dir.toPath());
                            }
                        }
                        if (!dir.isDirectory()) {
                            dirExists = false;
                        }
                        if (dirExists) {
                            // If dir was able to be created, mark it for removal as a temporary dir
                            dir.deleteOnExit();
                            tempFiles.add(dir);
                        }
                    }
                }
                return dirExists;
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
        final JarfileMetadataReader jarfileMetadataReader = zipFileToJarfileMetadataReaderMap
                .getOrCreateSingleton(zipFile, log);
        return jarfileMetadataReader;
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
        File tempFile = null;
        try {
            final String suffix = TEMP_FILENAME_LEAF_SEPARATOR + sanitizeFilename(leafname(jarURL));
            tempFile = File.createTempFile("ClassGraph--", suffix);
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
     * {@link NestedJarHandler#close()} is called.
     */
    private File unzipToTempFile(final ZipFile zipFile, final ZipEntry zipEntry, final LogNode log)
            throws IOException {
        String zipEntryPath = zipEntry.getName();
        if (zipEntryPath.startsWith("/")) {
            zipEntryPath = zipEntryPath.substring(1);
        }
        // The following filename format is also expected by JarUtils.leafName()
        final File tempFile = File.createTempFile("ClassGraph--",
                TEMP_FILENAME_LEAF_SEPARATOR + sanitizeFilename(leafname(zipEntryPath)));
        tempFile.deleteOnExit();
        tempFiles.add(tempFile);
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

    // There is no longer any need to extract an entire jarfile to disk, since ClassGraphClassLoader can load
    // classfiles using their corresponding Resource, but the unzip code is left here for reference, since it
    // may be needed for some purpose in the future.

    //    /**
    //     * The number of threads to use for unzipping package roots. These unzip threads are started from within a
    //     * worker thread, so this is kept relatively small.
    //     */
    //    private static final int NUM_UNZIP_THREADS = 4;
    //
    //    /**
    //     * Unzip a given package root within a zipfile to a temporary directory, starting several more threads to
    //     * perform the unzip in parallel, then return the temporary directory. The temporary directory and all of its
    //     * contents will be removed when {@code NestedJarHandler#close())} is called.
    //     * 
    //     * <p>
    //     * N.B. standalone code for parallel unzip can be found at https://github.com/lukehutch/quickunzip
    //     * 
    //     * @param jarFile
    //     *            The jarfile.
    //     * @param packageRoot
    //     *            The package root to extract from the jar.
    //     * @param log
    //     *            The log.
    //     * @return The {@link File} object for the temporary directory the package root was extracted to.
    //     * @throws IOException
    //     *             If the package root could not be extracted from the jar.
    //     */
    //    public File unzipToTempDir(final File jarFile, final String packageRoot, final LogNode log) throws IOException {
    //        final LogNode subLog = log == null ? null
    //                : log.log("Unzipping " + jarFile + " from package root " + packageRoot);
    //
    //        // Create temporary directory to unzip into
    //        final Path tempDirPath = Files.createTempDirectory("ClassGraph--"
    //                + sanitizeFilename(leafname(jarFile.getName())) + "--" + sanitizeFilename(packageRoot) + "--");
    //        final File tempDir = tempDirPath.toFile();
    //        tempDir.deleteOnExit();
    //        tempFiles.add(tempDir);
    //
    //        // Get ZipEntries from ZipFile that start with the package root prefix
    //        final List<ZipEntry> zipEntries = new ArrayList<>();
    //        final List<String> zipEntryNamesWithoutPrefix = new ArrayList<>();
    //        final String packageRootPrefix = !packageRoot.endsWith("/") && !packageRoot.isEmpty() ? packageRoot + '/'
    //                : packageRoot;
    //        final int packageRootPrefixLen = packageRootPrefix.length();
    //        try (ZipFile zipFile = new ZipFile(jarFile)) {
    //            for (final Enumeration<? extends ZipEntry> e = zipFile.entries(); e.hasMoreElements();) {
    //                final ZipEntry zipEntry = e.nextElement();
    //                String zipEntryName = zipEntry.getName();
    //                while (zipEntryName.startsWith("/")) {
    //                    // Prevent against "Zip Slip" vulnerability (path starting with '/')
    //                    zipEntryName = zipEntryName.substring(1);
    //                }
    //                if (zipEntryName.startsWith(packageRootPrefix) && zipEntryName.length() > packageRootPrefixLen) {
    //                    // Found a ZipEntry with the correct package prefix
    //                    zipEntries.add(zipEntry);
    //                    // Strip the prefix from the name
    //                    zipEntryNamesWithoutPrefix.add(packageRootPrefixLen == 0 ? zipEntryName
    //                            : zipEntryName.substring(packageRootPrefixLen));
    //                }
    //            }
    //        }
    //
    //        // Start parallel unzip threads
    //        try (final AutoCloseableConcurrentQueue<ZipFile> openZipFiles = new AutoCloseableConcurrentQueue<>();
    //                final AutoCloseableExecutorService executor = new AutoCloseableExecutorService(NUM_UNZIP_THREADS);
    //                final AutoCloseableFutureListWithCompletionBarrier futures = //
    //                        new AutoCloseableFutureListWithCompletionBarrier(zipEntries.size(), subLog)) {
    //
    //            // Iterate through ZipEntries within the package root
    //            for (int i = 0; i < zipEntries.size(); i++) {
    //                final ZipEntry zipEntry = zipEntries.get(i);
    //                final String zipEntryNameWithoutPrefix = zipEntryNamesWithoutPrefix.get(i);
    //                futures.add(executor.submit(new Callable<Void>() {
    //                    @Override
    //                    public Void call() throws Exception {
    //                        ZipFile zipFile;
    //                        final ThreadLocal<ZipFile> zipFileTL = new ThreadLocal<>();
    //                        if ((zipFile = zipFileTL.get()) == null) {
    //                            try {
    //                                // Open one ZipFile instance per thread
    //                                zipFile = new ZipFile(jarFile);
    //                                openZipFiles.add(zipFile);
    //                                zipFileTL.set(zipFile);
    //                            } catch (final IOException e) {
    //                                // Should not happen unless zipfile was just barely deleted, since we
    //                                // opened it already
    //                                if (subLog != null) {
    //                                    subLog.log("Cannot open zipfile: " + jarFile + " : " + e);
    //                                }
    //                                return null;
    //                            }
    //                        }
    //
    //                        try {
    //                            // Make sure we don't allow paths that use "../" to break out of the unzip root dir
    //                            final Path unzippedFilePath = tempDirPath.resolve(zipEntryNameWithoutPrefix);
    //                            if (!unzippedFilePath.startsWith(tempDirPath)) {
    //                                if (subLog != null) {
    //                                    subLog.log("Bad path: " + zipEntry.getName());
    //                                }
    //                            } else {
    //                                final File unzippedFile = unzippedFilePath.toFile();
    //                                if (zipEntry.isDirectory()) {
    //                                    // Recreate directory entries, so that empty directories are recreated
    //                                    // (in case any of the classes that are loaded expect a directory to be
    //                                    // present, even if it is empty)
    //                                    mkDirs.getOrCreateSingleton(/* dir */ unzippedFile, subLog);
    //                                } else {
    //                                    // Create parent directories if needed
    //                                    final File parentDir = unzippedFile.getParentFile();
    //                                    final boolean parentDirExists = mkDirs.getOrCreateSingleton(parentDir, subLog);
    //                                    if (parentDirExists) {
    //                                        // Open ZipEntry as an InputStream
    //                                        try (InputStream inputStream = zipFile.getInputStream(zipEntry)) {
    //                                            if (subLog != null) {
    //                                                subLog.log("Unzipping: " + zipEntry.getName() + " -> "
    //                                                        + unzippedFilePath);
    //                                            }
    //
    //                                            // Copy the contents of the ZipEntry InputStream to the output file,
    //                                            // overwriting existing files of the same name
    //                                            Files.copy(inputStream, unzippedFilePath,
    //                                                    StandardCopyOption.REPLACE_EXISTING);
    //
    //                                            // If file was able to be unzipped, mark it for removal on exit
    //                                            unzippedFile.deleteOnExit();
    //                                            tempFiles.add(parentDir);
    //                                        }
    //                                    }
    //                                }
    //                            }
    //                        } catch (final InvalidPathException ex) {
    //                            if (subLog != null) {
    //                                subLog.log("Invalid path: " + zipEntry.getName());
    //                            }
    //                        }
    //                        // Return placeholder Void result for Future<Void>
    //                        return null;
    //                    }
    //                }));
    //            }
    //        }
    //        return tempDir;
    //    }

    /**
     * Strip self-extracting archive ("ZipSFX") header from zipfile, if present. (Simply strips everything before
     * the first "PK".)
     * 
     * @return The zipfile with the ZipSFX header removed
     * @throws IOException
     *             if the file does not appear to be a zipfile (i.e. if no "PK" marker is found).
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
            final File bareZipfile = File.createTempFile("ClassGraph--",
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
