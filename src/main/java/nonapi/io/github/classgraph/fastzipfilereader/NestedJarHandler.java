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
package nonapi.io.github.classgraph.fastzipfilereader;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.AbstractMap.SimpleEntry;
import java.util.Map.Entry;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.Inflater;

import io.github.classgraph.ModuleReaderProxy;
import io.github.classgraph.ModuleRef;
import io.github.classgraph.ScanResult;
import nonapi.io.github.classgraph.concurrency.SingletonMap;
import nonapi.io.github.classgraph.recycler.Recycler;
import nonapi.io.github.classgraph.utils.FastPathResolver;
import nonapi.io.github.classgraph.utils.FileUtils;
import nonapi.io.github.classgraph.utils.LogNode;
import nonapi.io.github.classgraph.utils.ScanSpec;

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
    /** A singleton map from a zipfile's {@link File} to the {@link PhysicalZipFile} for that file. */
    private SingletonMap<File, PhysicalZipFile> canonicalFileToPhysicalZipFileMap;

    /** {@link PhysicalZipFile} instances created to extract nested jarfiles to disk or RAM. */
    private Queue<PhysicalZipFile> additionalAllocatedPhysicalZipFiles;

    /**
     * A singleton map from a {@link FastZipEntry} to the {@link ZipFileSlice} wrapping either the zip entry data,
     * if the entry is stored, or a ByteBuffer, if the zip entry was inflated to memory, or a physical file on disk
     * if the zip entry was inflated to a temporary file.
     */
    private SingletonMap<FastZipEntry, ZipFileSlice> fastZipEntryToZipFileSliceMap;

    /** A singleton map from a {@link ZipFileSlice} to the {@link LogicalZipFile} for that slice. */
    private SingletonMap<ZipFileSlice, LogicalZipFile> zipFileSliceToLogicalZipFileMap;

    /**
     * A singleton map from nested jarfile path to a tuple of the logical zipfile for the path, and the package root
     * within the logical zipfile.
     */
    public SingletonMap<String, Entry<LogicalZipFile, String>> //
    nestedPathToLogicalZipFileAndPackageRootMap;

    /** A singleton map from a {@link ModuleRef} to a {@link ModuleReaderProxy} recycler for the module. */
    public SingletonMap<ModuleRef, Recycler<ModuleReaderProxy, IOException>> //
    moduleRefToModuleReaderProxyRecyclerMap;

    /** A recycler for {@link Inflater} instances. */
    public Recycler<RecyclableInflater, RuntimeException> inflaterRecycler;

    /** Any temporary files created while scanning. */
    private ConcurrentLinkedDeque<File> tempFiles = new ConcurrentLinkedDeque<>();

    /** The separator between random temp filename part and leafname. */
    public static final String TEMP_FILENAME_LEAF_SEPARATOR = "---";

    /**
     * The threshold uncompressed size at which nested deflated jars are inflated to a temporary file on disk,
     * rather than to RAM.
     */
    private static final int INFLATE_TO_DISK_THRESHOLD = 32 * 1024 * 1024;

    private final AtomicBoolean closed = new AtomicBoolean(false);

    // -------------------------------------------------------------------------------------------------------------

    /**
     * A handler for nested jars.
     * 
     * @param scanSpec
     *            The {@link ScanSpec}.
     * @param log
     *            The log.
     */
    public NestedJarHandler(final ScanSpec scanSpec, final LogNode log) {
        // Set up a recycler for Inflater instances.
        this.inflaterRecycler = new Recycler<RecyclableInflater, RuntimeException>() {
            @Override
            public RecyclableInflater newInstance() throws RuntimeException {
                if (closed.get()) {
                    throw new RuntimeException(getClass().getSimpleName() + " already closed");
                }
                return new RecyclableInflater();
            }
        };

        // Set up a singleton map from canonical File to PhysicalZipFile instance, so that the RandomAccessFile
        // and FileChannel for any given zipfile is opened only once
        this.canonicalFileToPhysicalZipFileMap = new SingletonMap<File, PhysicalZipFile>() {
            @Override
            public PhysicalZipFile newInstance(final File canonicalFile, final LogNode log) throws IOException {
                if (closed.get()) {
                    throw new IOException(getClass().getSimpleName() + " already closed");
                }
                return new PhysicalZipFile(canonicalFile, NestedJarHandler.this);
            }
        };

        // Allocate a queue of additional allocated PhysicalZipFile instances
        this.additionalAllocatedPhysicalZipFiles = new ConcurrentLinkedQueue<>();

        // Set up a singleton map that wraps nested stored jarfiles in a ZipFileSlice, or inflates nested
        // deflated jarfiles to a temporary file if they are large, or inflates nested deflated jarfiles
        // to RAM if they are small (or if a temporary file could not be created)
        this.fastZipEntryToZipFileSliceMap = new SingletonMap<FastZipEntry, ZipFileSlice>() {
            @Override
            public ZipFileSlice newInstance(final FastZipEntry childZipEntry, final LogNode log) throws Exception {
                ZipFileSlice childZipEntrySlice;
                if (!childZipEntry.isDeflated) {
                    // Wrap the child entry (a stored nested zipfile) in a new ZipFileSlice -- there is
                    // nothing else to do. (Most nested zipfiles are stored, not deflated, so this fast
                    // path will be followed most often.)
                    childZipEntrySlice = new ZipFileSlice(childZipEntry);

                } else {
                    // If child entry is deflated i.e. (for a deflated nested zipfile), must inflate
                    // the contents of the entry before its central directory can be read (most of
                    // the time nested zipfiles are stored, not deflated, so this should be rare)
                    if ((childZipEntry.uncompressedSize < 0L
                            || childZipEntry.uncompressedSize >= INFLATE_TO_DISK_THRESHOLD)) {
                        // If child entry's size is unknown or the file is large, inflate to disk
                        File tempFile = null;
                        try {
                            // Create temp file
                            tempFile = makeTempFile(childZipEntry.entryName, /* onlyUseLeafname = */ true);
                            if (log != null) {
                                log.log("Extracting deflated nested jarfile " + childZipEntry.entryName
                                        + " to temporary file " + tempFile);
                            }

                            // Inflate zip entry to temp file
                            try (InputStream inputStream = childZipEntry.open()) {
                                Files.copy(inputStream, tempFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                            }

                            // Get or create a PhysicalZipFile instance for the new temp file
                            final PhysicalZipFile physicalZipFile = canonicalFileToPhysicalZipFileMap.get(tempFile,
                                    log);
                            additionalAllocatedPhysicalZipFiles.add(physicalZipFile);

                            // Create a new logical slice of the whole physical zipfile
                            childZipEntrySlice = new ZipFileSlice(physicalZipFile);

                        } catch (final IOException e) {
                            // Could not make temp file, or failed to extract entire contents of entry
                            if (tempFile != null) {
                                // Delete temp file, in case it contains partially-extracted data
                                // due to running out of disk space
                                tempFile.delete();
                            }
                            childZipEntrySlice = null;
                        }
                    } else {
                        childZipEntrySlice = null;
                    }
                    if (childZipEntrySlice == null) {
                        // If the uncompressed size unknown or small, or inflating to temp file failed,
                        // inflate to a ByteBuffer in memory instead
                        if (childZipEntry.uncompressedSize > FileUtils.MAX_BUFFER_SIZE) {
                            // Impose 2GB limit (i.e. a max of one ByteBuffer chunk) on inflation to memory
                            throw new IOException(
                                    "Uncompressed size of zip entry (" + childZipEntry.uncompressedSize
                                            + ") is too large to inflate to memory: " + childZipEntry.entryName);
                        }

                        // Open the zip entry to fetch inflated data, and read the whole contents of the
                        // InputStream to a byte[] array, then wrap it in a ByteBuffer
                        ByteBuffer byteBuffer;
                        try (InputStream inputStream = childZipEntry.open()) {
                            byteBuffer = ByteBuffer.wrap(
                                    FileUtils.readAllBytesAsArray(inputStream, childZipEntry.uncompressedSize));
                        }

                        // Create a new PhysicalZipFile that wraps the ByteBuffer as if the buffer had been
                        // mmap'd to a file on disk
                        final PhysicalZipFile physicalZipFileInRam = new PhysicalZipFile(byteBuffer,
                                /* outermostFile = */ childZipEntry.parentLogicalZipFile.physicalZipFile.getFile(),
                                childZipEntry.getPath(), NestedJarHandler.this);
                        additionalAllocatedPhysicalZipFiles.add(physicalZipFileInRam);

                        // Create a new logical slice of the whole physical in-memory zipfile
                        childZipEntrySlice = new ZipFileSlice(physicalZipFileInRam, childZipEntry);
                    }
                }
                return childZipEntrySlice;
            }
        };

        // Set up a singleton map from canonical File to PhysicalZipFile instance, so that the RandomAccessFile
        // and FileChannel for any given zipfile is opened only once
        this.zipFileSliceToLogicalZipFileMap = new SingletonMap<ZipFileSlice, LogicalZipFile>() {
            @Override
            public LogicalZipFile newInstance(final ZipFileSlice zipFileSlice, final LogNode log) throws Exception {
                if (closed.get()) {
                    throw new IOException(getClass().getSimpleName() + " already closed");
                }
                // Read the central directory for the logical zipfile slice
                return new LogicalZipFile(zipFileSlice, scanSpec, log);
            }
        };

        // Create a singleton map from path to zipfile File, in order to eliminate repeatedly unzipping the same
        // file when there are multiple jars-within-jars that need unzipping to temporary files.
        this.nestedPathToLogicalZipFileAndPackageRootMap = new SingletonMap< //
                String, Entry<LogicalZipFile, String>>() {
            @Override
            public Entry<LogicalZipFile, String> newInstance(final String nestedJarPathRaw, final LogNode log)
                    throws Exception {
                if (closed.get()) {
                    throw new IOException(getClass().getSimpleName() + " already closed");
                }
                final String nestedJarPath = FastPathResolver.resolve(nestedJarPathRaw);
                final int lastPlingIdx = nestedJarPath.lastIndexOf('!');
                if (lastPlingIdx < 0) {
                    // nestedJarPath is a simple file path or URL (i.e. doesn't have any '!' sections). This is also
                    // the last frame of recursion for the 'else' clause below.

                    // If the path starts with "http(s)://", download the jar to a temp file
                    final boolean isRemote = nestedJarPath.startsWith("http://")
                            || nestedJarPath.startsWith("https://");
                    File canonicalFile;
                    if (isRemote) {
                        canonicalFile = downloadTempFile(nestedJarPath, log);
                        if (canonicalFile == null) {
                            throw new IOException("Could not download jarfile " + nestedJarPath);
                        }
                    } else {
                        try {
                            canonicalFile = new File(nestedJarPath).getCanonicalFile();
                        } catch (final SecurityException e) {
                            throw new IOException(
                                    "Path component " + nestedJarPath + " could not be canonicalized: " + e);
                        }
                    }
                    if (!FileUtils.canRead(canonicalFile)) {
                        throw new IOException("Path component " + nestedJarPath + " does not exist");
                    }
                    if (!canonicalFile.isFile()) {
                        throw new IOException(
                                "Path component " + nestedJarPath + "  is not a file (expected a jarfile)");
                    }

                    // Get or create a PhysicalZipFile instance for the canonical file
                    final PhysicalZipFile physicalZipFile = canonicalFileToPhysicalZipFileMap.get(canonicalFile,
                            log);

                    // Create a new logical slice of the whole physical zipfile
                    final ZipFileSlice topLevelSlice = new ZipFileSlice(physicalZipFile);
                    final LogicalZipFile logicalZipFile = zipFileSliceToLogicalZipFileMap.get(topLevelSlice, log);

                    // Return new logical zipfile with an empty package root
                    return new SimpleEntry<>(logicalZipFile, "");

                } else {
                    // This path has one or more '!' sections.
                    final String parentPath = nestedJarPath.substring(0, lastPlingIdx);
                    String childPath = nestedJarPath.substring(lastPlingIdx + 1);
                    // "file.jar!/path" -> "file.jar!path"
                    childPath = FileUtils.sanitizeEntryPath(childPath);

                    // Recursively remove one '!' section at a time, back towards the beginning of the URL or
                    // file path. At the last frame of recursion, the toplevel jarfile will be reached and
                    // returned. The recursion is guaranteed to terminate because parentPath gets one
                    // '!'-section shorter with each recursion frame.
                    final Entry<LogicalZipFile, String> parentLogicalZipFileAndPackageRoot = //
                            nestedPathToLogicalZipFileAndPackageRootMap.get(parentPath, log);
                    if (parentLogicalZipFileAndPackageRoot == null) {
                        // Failed to get topmost jarfile, e.g. file not found
                        throw new IOException("Could not find parent jarfile " + parentPath);
                    }
                    // Only the last item in a '!'-delimited list can be a non-jar path, so the parent must
                    // always be a jarfile.
                    final LogicalZipFile parentLogicalZipFile = parentLogicalZipFileAndPackageRoot.getKey();
                    if (parentLogicalZipFile == null) {
                        // Failed to get topmost jarfile, e.g. file not found
                        throw new IOException("Could not find parent jarfile " + parentPath);
                    }

                    // Look up the child path within the parent zipfile
                    boolean isDirectory = false;
                    while (childPath.endsWith("/")) {
                        // Child path is definitely a directory, it ends with a slash 
                        isDirectory = true;
                        childPath = childPath.substring(0, childPath.length() - 1);
                    }
                    FastZipEntry childZipEntry = null;
                    if (!isDirectory) {
                        // If child path doesn't end with a slash, see if there's a non-directory entry
                        // with a name matching the child path (LogicalZipFile discards directory entries
                        // ending with a slash when reading the central directory of a zipfile)
                        for (final FastZipEntry entry : parentLogicalZipFile.getEntries()) {
                            if (entry.entryName.equals(childPath)) {
                                childZipEntry = entry;
                                break;
                            }
                        }
                    }
                    if (childZipEntry == null) {
                        // If there is no non-directory zipfile entry with a name matching the child path, 
                        // test to see if any entries in the zipfile have the child path as a dir prefix
                        final String childPathPrefix = childPath + "/";
                        for (final FastZipEntry entry : parentLogicalZipFile.getEntries()) {
                            if (entry.entryName.startsWith(childPathPrefix)) {
                                isDirectory = true;
                                break;
                            }
                        }
                        if (!isDirectory) {
                            throw new IOException(
                                    "Path " + childPath + " does not exist in jarfile " + parentLogicalZipFile);
                        }
                    }
                    // At this point, either isDirectory is true, or childZipEntry is non-null

                    // If path component is a directory, it is a package root
                    if (isDirectory) {
                        if (log != null) {
                            log.log("Path " + childPath + " in jarfile " + parentLogicalZipFile
                                    + " is a directory, not a file -- using as package root");
                        }
                        if (!childPath.isEmpty()) {
                            // Add directory path to parent jarfile root relative paths set
                            // (this has the side effect of adding this parent jarfile root
                            // to the set of roots for all references to the parent path)
                            parentLogicalZipFile.classpathRoots.add(childPath);
                        }
                        // Return parent logical zipfile, and child path as the package root
                        return new SimpleEntry<>(parentLogicalZipFile, childPath);
                    }

                    // Do not extract nested jar, if nested jar scanning is disabled
                    if (!scanSpec.scanNestedJars) {
                        throw new IOException(
                                "Nested jar scanning is disabled -- skipping extraction of nested jar "
                                        + nestedJarPath);
                    }

                    // The child path corresponds to a non-directory zip entry, so it must be a nested jar
                    // (since non-jar nested files cannot be used on the classpath). Map the nested jar as
                    // a new ZipFileSlice if it is stored, or inflate it to RAM or to a temporary file if
                    // it is deflated, then create a new ZipFileSlice over the temporary file or ByteBuffer.
                    try {
                        // Get zip entry as a ZipFileSlice, possibly inflating to disk or RAM
                        final ZipFileSlice childZipEntrySlice = fastZipEntryToZipFileSliceMap.get(childZipEntry,
                                log);
                        if (childZipEntrySlice == null) {
                            throw new IOException(
                                    "Could not open nested jarfile entry: " + childZipEntry.getPath());
                        }

                        // Get or create a new LogicalZipFile for the child zipfile
                        final LogicalZipFile childLogicalZipFile = zipFileSliceToLogicalZipFileMap
                                .get(childZipEntrySlice, log);

                        // Return new logical zipfile with an empty package root
                        return new SimpleEntry<>(childLogicalZipFile, "");

                    } catch (final IOException e) {
                        // Thrown if the inner zipfile could nat be extracted
                        throw new IOException("File does not appear to be a zipfile: " + childPath);
                    }
                }
            }
        };

        // Set up a singleton map from ModuleRef object to ModuleReaderProxy recycler
        this.moduleRefToModuleReaderProxyRecyclerMap = //
                new SingletonMap<ModuleRef, Recycler<ModuleReaderProxy, IOException>>() {

                    @Override
                    public Recycler<ModuleReaderProxy, IOException> newInstance(final ModuleRef moduleRef,
                            final LogNode log) {
                        return new Recycler<ModuleReaderProxy, IOException>() {
                            @Override
                            public ModuleReaderProxy newInstance() throws IOException {
                                if (closed.get()) {
                                    throw new IOException(getClass().getSimpleName() + " already closed");
                                }
                                return moduleRef.open();
                            }
                        };
                    }

                };
    }

    // -------------------------------------------------------------------------------------------------------------

    private String leafname(final String path) {
        return path.substring(path.lastIndexOf('/') + 1);
    }

    private String sanitizeFilename(final String filename) {
        return filename.replace('/', '_').replace('\\', '_').replace(':', '_').replace('?', '_').replace('&', '_')
                .replace('=', '_').replace(' ', '_');
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
    private File makeTempFile(final String filePath, final boolean onlyUseLeafname) throws IOException {
        final File tempFile = File.createTempFile("ClassGraph--",
                TEMP_FILENAME_LEAF_SEPARATOR + sanitizeFilename(onlyUseLeafname ? leafname(filePath) : filePath));
        tempFile.deleteOnExit();
        tempFiles.add(tempFile);
        return tempFile;
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

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Close zipfiles, modules, and recyclers, and delete temporary files. Called by {@link ScanResult#close()}.
     * 
     * @param log
     *            The log.
     */
    public void close(final LogNode log) {
        if (!closed.getAndSet(true)) {
            if (inflaterRecycler != null) {
                inflaterRecycler.forceClose();
                inflaterRecycler = null;
            }
            if (moduleRefToModuleReaderProxyRecyclerMap != null) {
                try {
                    for (final Recycler<ModuleReaderProxy, IOException> recycler : //
                    moduleRefToModuleReaderProxyRecyclerMap.values()) {
                        recycler.forceClose();
                    }
                } catch (final InterruptedException e) {
                }
                moduleRefToModuleReaderProxyRecyclerMap.clear();
                moduleRefToModuleReaderProxyRecyclerMap = null;
            }
            if (zipFileSliceToLogicalZipFileMap != null) {
                zipFileSliceToLogicalZipFileMap.clear();
                zipFileSliceToLogicalZipFileMap = null;
            }
            if (nestedPathToLogicalZipFileAndPackageRootMap != null) {
                nestedPathToLogicalZipFileAndPackageRootMap.clear();
                nestedPathToLogicalZipFileAndPackageRootMap = null;
            }
            if (canonicalFileToPhysicalZipFileMap != null) {
                try {
                    for (final PhysicalZipFile physicalZipFile : canonicalFileToPhysicalZipFileMap.values()) {
                        physicalZipFile.close();
                    }
                } catch (final InterruptedException e) {
                }
                canonicalFileToPhysicalZipFileMap.clear();
                canonicalFileToPhysicalZipFileMap = null;
            }
            if (additionalAllocatedPhysicalZipFiles != null) {
                for (PhysicalZipFile physicalZipFile; (physicalZipFile = additionalAllocatedPhysicalZipFiles
                        .poll()) != null;) {
                    physicalZipFile.close();
                }
                additionalAllocatedPhysicalZipFiles.clear();
                additionalAllocatedPhysicalZipFiles = null;
            }
            if (fastZipEntryToZipFileSliceMap != null) {
                fastZipEntryToZipFileSliceMap.clear();
                fastZipEntryToZipFileSliceMap = null;
            }
            if (tempFiles != null) {
                final LogNode rmLog = tempFiles.isEmpty() || log == null ? null
                        : log.log("Removing temporary files");
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
                        rmLog.log((success ? "Removed" : "Unable to remove") + " " + path
                                + (e == null ? "" : " : " + e));
                    }
                }
                tempFiles = null;
            }
        }
    }
}
