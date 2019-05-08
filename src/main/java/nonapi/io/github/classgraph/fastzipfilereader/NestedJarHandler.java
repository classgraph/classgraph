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
package nonapi.io.github.classgraph.fastzipfilereader;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.AbstractMap.SimpleEntry;
import java.util.Map.Entry;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.Inflater;

import io.github.classgraph.ClassGraphException;
import io.github.classgraph.ModuleReaderProxy;
import io.github.classgraph.ModuleRef;
import io.github.classgraph.ScanResult;
import nonapi.io.github.classgraph.concurrency.InterruptionChecker;
import nonapi.io.github.classgraph.concurrency.SingletonMap;
import nonapi.io.github.classgraph.concurrency.SingletonMap.NullSingletonException;
import nonapi.io.github.classgraph.recycler.Recycler;
import nonapi.io.github.classgraph.scanspec.ScanSpec;
import nonapi.io.github.classgraph.utils.FastPathResolver;
import nonapi.io.github.classgraph.utils.FileUtils;
import nonapi.io.github.classgraph.utils.LogNode;

/** Open and read jarfiles, which may be nested within other jarfiles. */
public class NestedJarHandler {
    /** The {@link ScanSpec}. */
    private final ScanSpec scanSpec;

    /**
     * A singleton map from a zipfile's {@link File} to the {@link PhysicalZipFile} for that file, used to ensure
     * that the {@link RandomAccessFile} and {@link FileChannel} for any given zipfile is opened only once.
     */
    private SingletonMap<File, PhysicalZipFile, IOException> //
    canonicalFileToPhysicalZipFileMap = new SingletonMap<File, PhysicalZipFile, IOException>() {
        @Override
        public PhysicalZipFile newInstance(final File canonicalFile, final LogNode log) throws IOException {
            if (closed.get()) {
                throw ClassGraphException
                        .newClassGraphException(NestedJarHandler.class.getSimpleName() + " already closed");
            }
            return new PhysicalZipFile(canonicalFile, NestedJarHandler.this);
        }
    };

    /** {@link PhysicalZipFile} instances created to extract nested jarfiles to disk or RAM. */
    private Queue<PhysicalZipFile> additionalAllocatedPhysicalZipFiles = new ConcurrentLinkedQueue<>();

    /**
     * A singleton map from a {@link FastZipEntry} to the {@link ZipFileSlice} wrapping either the zip entry data,
     * if the entry is stored, or a ByteBuffer, if the zip entry was inflated to memory, or a physical file on disk
     * if the zip entry was inflated to a temporary file.
     */
    private SingletonMap<FastZipEntry, ZipFileSlice, IOException> //
    fastZipEntryToZipFileSliceMap = new SingletonMap<FastZipEntry, ZipFileSlice, IOException>() {
        @Override
        public ZipFileSlice newInstance(final FastZipEntry childZipEntry, final LogNode log)
                throws IOException, InterruptedException {
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
                        || childZipEntry.uncompressedSize >= INFLATE_TO_DISK_THRESHOLD
                // Also check compressed size for safety, in case uncompressed size is wrong
                        || childZipEntry.compressedSize >= INFLATE_TO_DISK_THRESHOLD)) {
                    // If child entry's size is unknown or the file is large, inflate to disk
                    File tempFile = null;
                    try {
                        // Create temp file
                        tempFile = makeTempFile(childZipEntry.entryName, /* onlyUseLeafname = */ true);

                        // Inflate zip entry to temp file
                        if (log != null) {
                            log.log("Deflating zip entry to temporary file: " + childZipEntry
                                    + " ; uncompressed size: " + childZipEntry.uncompressedSize + " ; temp file: "
                                    + tempFile);
                        }
                        try (InputStream inputStream = childZipEntry.open()) {
                            Files.copy(inputStream, tempFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                        }

                        // Get or create a PhysicalZipFile instance for the new temp file
                        final PhysicalZipFile physicalZipFile = canonicalFileToPhysicalZipFile(tempFile, log);
                        additionalAllocatedPhysicalZipFiles.add(physicalZipFile);

                        // Create a new logical slice of the whole physical zipfile
                        childZipEntrySlice = new ZipFileSlice(physicalZipFile);

                    } catch (final IllegalArgumentException | IOException e) {
                        // Could not make temp file, or failed to extract entire contents of entry
                        if (log != null) {
                            log.log("Deflating zip entry to temporary file failed: " + e);
                        }
                        if (tempFile != null) {
                            // Delete temp file, in case it contains partially-extracted data
                            // due to running out of disk space
                            try {
                                Files.delete(tempFile.toPath());
                            } catch (final IOException | SecurityException e2) {
                                if (log != null) {
                                    log.log("Removing temporary file failed: " + e2);
                                }
                            }
                        }
                        childZipEntrySlice = null;
                    }
                } else {
                    childZipEntrySlice = null;
                }
                if (childZipEntrySlice == null) {
                    // If the uncompressed size known and small, or inflating to temp file failed,
                    // inflate to a ByteBuffer in memory instead
                    if (childZipEntry.uncompressedSize > FileUtils.MAX_BUFFER_SIZE) {
                        // Impose 2GB limit (i.e. a max of one ByteBuffer chunk) on inflation to memory
                        throw new IOException("Uncompressed size of zip entry (" + childZipEntry.uncompressedSize
                                + ") is too large to inflate to memory: " + childZipEntry.entryName);
                    }

                    // Open the zip entry to fetch inflated data, and read the whole contents of the
                    // InputStream to a byte[] array, then wrap it in a ByteBuffer
                    if (log != null) {
                        log.log("Deflating zip entry to RAM: " + childZipEntry + " ; uncompressed size: "
                                + childZipEntry.uncompressedSize);
                    }
                    ByteBuffer byteBuffer;
                    try (InputStream inputStream = childZipEntry.open()) {
                        byteBuffer = ByteBuffer
                                .wrap(FileUtils.readAllBytesAsArray(inputStream, childZipEntry.uncompressedSize));
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

    /** A singleton map from a {@link ZipFileSlice} to the {@link LogicalZipFile} for that slice. */
    private SingletonMap<ZipFileSlice, LogicalZipFile, IOException> //
    zipFileSliceToLogicalZipFileMap = new SingletonMap<ZipFileSlice, LogicalZipFile, IOException>() {
        @Override
        public LogicalZipFile newInstance(final ZipFileSlice zipFileSlice, final LogNode log)
                throws IOException, InterruptedException {
            if (closed.get()) {
                throw ClassGraphException
                        .newClassGraphException(NestedJarHandler.class.getSimpleName() + " already closed");
            }
            // Read the central directory for the logical zipfile slice
            final LogicalZipFile logicalZipFile = new LogicalZipFile(zipFileSlice, log);
            allocatedLogicalZipFiles.add(logicalZipFile);
            return logicalZipFile;
        }
    };

    /** All allocated LogicalZipFile instances. */
    private final Queue<LogicalZipFile> allocatedLogicalZipFiles = new ConcurrentLinkedQueue<>();

    /**
     * A singleton map from nested jarfile path to a tuple of the logical zipfile for the path, and the package root
     * within the logical zipfile.
     */
    public SingletonMap<String, Entry<LogicalZipFile, String>, IOException> // 
    nestedPathToLogicalZipFileAndPackageRootMap = //
            new SingletonMap<String, Entry<LogicalZipFile, String>, IOException>() {
                @Override
                public Entry<LogicalZipFile, String> newInstance(final String nestedJarPathRaw, final LogNode log)
                        throws IOException, InterruptedException {
                    if (closed.get()) {
                        throw ClassGraphException
                                .newClassGraphException(NestedJarHandler.class.getSimpleName() + " already closed");
                    }
                    final String nestedJarPath = FastPathResolver.resolve(nestedJarPathRaw);
                    final int lastPlingIdx = nestedJarPath.lastIndexOf('!');
                    if (lastPlingIdx < 0) {
                        // nestedJarPath is a simple file path or URL (i.e. doesn't have any '!' sections).
                        // This is also the last frame of recursion for the 'else' clause below.

                        // If the path starts with "http://" or "https://", download the jar to a temp file
                        // or to a ByteBuffer in RAM
                        final boolean isRemote = nestedJarPath.startsWith("http://")
                                || nestedJarPath.startsWith("https://");
                        PhysicalZipFile physicalZipFile;
                        if (isRemote) {
                            // Jarfile is at an http:// or https:// URL
                            if (scanSpec.enableRemoteJarScanning) {
                                // Download jar to a temp file, or if not possible, to a ByteBuffer in RAM
                                physicalZipFile = downloadJarFromURL(nestedJarPath, log);
                            } else {
                                throw new IOException(
                                        "Remote jar scanning has not been enabled, cannot scan classpath element: "
                                                + nestedJarPath);
                            }
                        } else {
                            // Jarfile should be a local file
                            try {
                                final File canonicalFile = new File(nestedJarPath).getCanonicalFile();
                                physicalZipFile = canonicalFileToPhysicalZipFile(canonicalFile, log);
                            } catch (final SecurityException e) {
                                throw new IOException(
                                        "Path component " + nestedJarPath + " could not be canonicalized: " + e);
                            }
                        }

                        // Create a new logical slice of the whole physical zipfile
                        final ZipFileSlice topLevelSlice = new ZipFileSlice(physicalZipFile);
                        LogicalZipFile logicalZipFile;
                        try {
                            logicalZipFile = zipFileSliceToLogicalZipFileMap.get(topLevelSlice, log);
                        } catch (final NullSingletonException e) {
                            throw new IOException("Could not get toplevel slice " + topLevelSlice + " : " + e);
                        }

                        // Return new logical zipfile with an empty package root
                        return new SimpleEntry<>(logicalZipFile, "");

                    } else {
                        // This path has one or more '!' sections.
                        final String parentPath = nestedJarPath.substring(0, lastPlingIdx);
                        String childPath = nestedJarPath.substring(lastPlingIdx + 1);
                        // "file.jar!/path" -> "file.jar!path"
                        childPath = FileUtils.sanitizeEntryPath(childPath, /* removeInitialSlash = */ true);

                        // Recursively remove one '!' section at a time, back towards the beginning of the URL or
                        // file path. At the last frame of recursion, the toplevel jarfile will be reached and
                        // returned. The recursion is guaranteed to terminate because parentPath gets one
                        // '!'-section shorter with each recursion frame.
                        Entry<LogicalZipFile, String> parentLogicalZipFileAndPackageRoot;
                        try {
                            parentLogicalZipFileAndPackageRoot = nestedPathToLogicalZipFileAndPackageRootMap
                                    .get(parentPath, log);
                        } catch (final NullSingletonException e) {
                            throw new IOException("Could not get parent logical zipfile " + parentPath + " : " + e);
                        }

                        // Only the last item in a '!'-delimited list can be a non-jar path, so the parent must
                        // always be a jarfile.
                        final LogicalZipFile parentLogicalZipFile = parentLogicalZipFileAndPackageRoot.getKey();

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
                            for (final FastZipEntry entry : parentLogicalZipFile.entries) {
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
                            for (final FastZipEntry entry : parentLogicalZipFile.entries) {
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
                            if (!childPath.isEmpty()) {
                                // Add directory path to parent jarfile root relative paths set
                                // (this has the side effect of adding this parent jarfile root
                                // to the set of roots for all references to the parent path)
                                if (log != null) {
                                    log.log("Path " + childPath + " in jarfile " + parentLogicalZipFile
                                            + " is a directory, not a file -- using as package root");
                                }
                                parentLogicalZipFile.classpathRoots.add(childPath);
                            }
                            // Return parent logical zipfile, and child path as the package root
                            return new SimpleEntry<>(parentLogicalZipFile, childPath);
                        }

                        // Do not extract nested jar, if nested jar scanning is disabled
                        if (!scanSpec.scanNestedJars) {
                            throw new IOException(
                                    "Nested jar scanning is disabled -- skipping nested jar " + nestedJarPath);
                        }

                        // The child path corresponds to a non-directory zip entry, so it must be a nested jar
                        // (since non-jar nested files cannot be used on the classpath). Map the nested jar as
                        // a new ZipFileSlice if it is stored, or inflate it to RAM or to a temporary file if
                        // it is deflated, then create a new ZipFileSlice over the temporary file or ByteBuffer.

                        // Get zip entry as a ZipFileSlice, possibly inflating to disk or RAM

                        final ZipFileSlice childZipEntrySlice;
                        try {
                            childZipEntrySlice = fastZipEntryToZipFileSliceMap.get(childZipEntry, log);
                        } catch (final NullSingletonException e) {
                            throw new IOException(
                                    "Could not get child zip entry slice " + childZipEntry + " : " + e);
                        }

                        final LogNode zipSliceLog = log == null ? null
                                : log.log("Getting zipfile slice " + childZipEntrySlice + " for nested jar "
                                        + childZipEntry.entryName);

                        // Get or create a new LogicalZipFile for the child zipfile
                        LogicalZipFile childLogicalZipFile;
                        try {
                            childLogicalZipFile = zipFileSliceToLogicalZipFileMap.get(childZipEntrySlice,
                                    zipSliceLog);
                        } catch (final NullSingletonException e) {
                            throw new IOException(
                                    "Could not get child logical zipfile " + childZipEntrySlice + " : " + e);
                        }

                        // Return new logical zipfile with an empty package root
                        return new SimpleEntry<>(childLogicalZipFile, "");
                    }
                }
            };

    /** A singleton map from a {@link ModuleRef} to a {@link ModuleReaderProxy} recycler for the module. */
    public SingletonMap<ModuleRef, Recycler<ModuleReaderProxy, IOException>, IOException> //
    moduleRefToModuleReaderProxyRecyclerMap = //
            new SingletonMap<ModuleRef, Recycler<ModuleReaderProxy, IOException>, IOException>() {
                @Override
                public Recycler<ModuleReaderProxy, IOException> newInstance(final ModuleRef moduleRef,
                        final LogNode ignored) {
                    return new Recycler<ModuleReaderProxy, IOException>() {
                        @Override
                        public ModuleReaderProxy newInstance() throws IOException {
                            if (closed.get()) {
                                throw ClassGraphException.newClassGraphException(
                                        NestedJarHandler.class.getSimpleName() + " already closed");
                            }
                            return moduleRef.open();
                        }
                    };
                }
            };

    /** A recycler for {@link Inflater} instances. */
    Recycler<RecyclableInflater, RuntimeException> //
    inflaterRecycler = new Recycler<RecyclableInflater, RuntimeException>() {
        @Override
        public RecyclableInflater newInstance() throws RuntimeException {
            if (closed.get()) {
                throw ClassGraphException
                        .newClassGraphException(NestedJarHandler.class.getSimpleName() + " already closed");
            }
            return new RecyclableInflater();
        }
    };

    /** Any temporary files created while scanning. */
    private ConcurrentLinkedDeque<File> tempFiles = new ConcurrentLinkedDeque<>();

    /** The separator between random temp filename part and leafname. */
    public static final String TEMP_FILENAME_LEAF_SEPARATOR = "---";

    /**
     * The threshold uncompressed size at which nested deflated jars are inflated to a temporary file on disk,
     * rather than to RAM.
     */
    private static final int INFLATE_TO_DISK_THRESHOLD = 32 * 1024 * 1024;

    /** True if {@link #close(LogNode)} has been called. */
    private final AtomicBoolean closed = new AtomicBoolean(false);

    /** The interruption checker. */
    public InterruptionChecker interruptionChecker;

    // -------------------------------------------------------------------------------------------------------------

    /**
     * A handler for nested jars.
     *
     * @param scanSpec
     *            The {@link ScanSpec}.
     * @param interruptionChecker
     *            the interruption checker
     */
    public NestedJarHandler(final ScanSpec scanSpec, final InterruptionChecker interruptionChecker) {
        this.scanSpec = scanSpec;
        this.interruptionChecker = interruptionChecker;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Get the {@link PhysicalZipFile} for a cononical {@link File}.
     *
     * @param canonicalFile
     *            the canonical file
     * @param log
     *            the log
     * @return the physical zip file
     * @throws IOException
     *             If the {@link File} could not be read, or was not a file.
     * @throws InterruptedException
     *             If the thread was interrupted.
     */
    private PhysicalZipFile canonicalFileToPhysicalZipFile(final File canonicalFile, final LogNode log)
            throws IOException, InterruptedException {
        try {
            // Get or create a PhysicalZipFile instance for the canonical file
            return canonicalFileToPhysicalZipFileMap.get(canonicalFile, log);
        } catch (final NullSingletonException e) {
            throw new IOException("Could not get physical zipfile " + canonicalFile + " : " + e);
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Get the leafname of a path.
     *
     * @param path
     *            the path
     * @return the string
     */
    private static String leafname(final String path) {
        return path.substring(path.lastIndexOf('/') + 1);
    }

    /**
     * Sanitize filename.
     *
     * @param filename
     *            the filename
     * @return the sanitized filename
     */
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

    /**
     * Download a jar from a URL to a temporary file, or to a ByteBuffer if the temporary directory is not writeable
     * or full. The downloaded jar is returned wrapped in a {@link PhysicalZipFile} instance.
     *
     * @param jarURL
     *            the jar URL
     * @param log
     *            the log
     * @return the temporary file or {@link ByteBuffer} the jar was downloaded to, wrapped in a
     *         {@link PhysicalZipFile} instance.
     * @throws IOException
     *             If the jar could not be downloaded, or the jar URL is malformed.
     * @throws IllegalArgumentException
     *             If the temp dir is not writeable, or has insufficient space to download the jar. (This is thrown
     *             as a separate exception from IOException, so that the case of an unwriteable temp dir can be
     *             handled separately, by downloading the jar to a ByteBuffer in RAM.)
     */
    private PhysicalZipFile downloadJarFromURL(final String jarURL, final LogNode log)
            throws IOException, InterruptedException {
        final LogNode subLog = log == null ? null : log.log(jarURL, "Downloading jar from URL " + jarURL);
        final URL url;
        try {
            url = new URL(jarURL);
        } catch (final MalformedURLException e) {
            throw new IOException("Malformed URL: " + jarURL);
        }
        try (final InputStream inputStream = url.openStream()) {
            PhysicalZipFile physicalZipFile = null;
            try {
                // Download jar from inputStream to a temporary file
                final File tempFile = makeTempFile(jarURL, /* onlyUseLeafname = */ true);
                Files.copy(inputStream, tempFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                if (subLog != null) {
                    subLog.log("Downloaded jar to temporary file " + tempFile);
                }
                // Wrap temp file in a PhysicalZipFile
                physicalZipFile = canonicalFileToPhysicalZipFile(tempFile, log);
                // Add temp file to queue of physical zipfiles that have to be unmapped on close
                additionalAllocatedPhysicalZipFiles.add(physicalZipFile);
            } catch (final SecurityException | UnsupportedOperationException | IOException e) {
                // Temp file could not be written, so this is a read-only filesystem, or temp dir does not exist,
                // or temp dir has run out of space. Download the jar to a ByteBuffer in RAM instead.
                if (log != null) {
                    log.log("Could not download jar to temp file (" + e + "), downloading to ByteBuffer instead");
                }
                // Read inputStream into a ByteBuffer
                final ByteBuffer byteBuffer = FileUtils.readAllBytesAsByteBuffer(inputStream, -1L);
                // Wrap ByteBuffer in a PhysicalZipFile. (No need to add to additionalAllocatedPhysicalZipFiles,
                // since byteBuffer is not a DirectByteBuffer, it just wraps a standard Java byte array, so the
                // DirectByteBuffer cleaner does not need to be called on close.)
                physicalZipFile = new PhysicalZipFile(byteBuffer, /* outermostFile = */ null, jarURL,
                        NestedJarHandler.this);
            }
            if (physicalZipFile == null) {
                // Should not happen
                throw ClassGraphException.newClassGraphException("physicalZipFile should not be null");
            }
            return physicalZipFile;
        } finally {
            if (subLog != null) {
                subLog.addElapsedTime();
                subLog.log("***** Note that it is time-consuming to scan jars at http(s) addresses, "
                        + "they must be downloaded for every scan, and the same jars must also be "
                        + "separately downloaded by the ClassLoader *****");
            }
        }
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
                    interruptionChecker.interrupt();
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
            for (LogicalZipFile logicalZipFile; (logicalZipFile = allocatedLogicalZipFiles.poll()) != null;) {
                logicalZipFile.close();
            }
            if (canonicalFileToPhysicalZipFileMap != null) {
                try {
                    for (final PhysicalZipFile physicalZipFile : canonicalFileToPhysicalZipFileMap.values()) {
                        physicalZipFile.close();
                    }
                } catch (final InterruptedException e) {
                    interruptionChecker.interrupt();
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
            // Temp files have to be deleted last, after all PhysicalZipFiles are closed
            if (tempFiles != null) {
                final LogNode rmLog = tempFiles.isEmpty() || log == null ? null
                        : log.log("Removing temporary files");
                while (!tempFiles.isEmpty()) {
                    final File tempFile = tempFiles.removeLast();
                    try {
                        Files.delete(tempFile.toPath());
                    } catch (final IOException | SecurityException e) {
                        if (rmLog != null) {
                            rmLog.log("Removing temporary file failed: " + e);
                        }
                    }
                }
                tempFiles.clear();
                tempFiles = null;
            }
        }
    }
}
