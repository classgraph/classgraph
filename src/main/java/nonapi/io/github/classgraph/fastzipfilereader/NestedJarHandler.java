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

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLConnection;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.util.AbstractMap.SimpleEntry;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;
import java.util.zip.ZipException;

import io.github.classgraph.ModuleReaderProxy;
import io.github.classgraph.ModuleRef;
import io.github.classgraph.ScanResult;
import nonapi.io.github.classgraph.concurrency.InterruptionChecker;
import nonapi.io.github.classgraph.concurrency.SingletonMap;
import nonapi.io.github.classgraph.fileslice.ArraySlice;
import nonapi.io.github.classgraph.fileslice.FileSlice;
import nonapi.io.github.classgraph.fileslice.Slice;
import nonapi.io.github.classgraph.recycler.Recycler;
import nonapi.io.github.classgraph.scanspec.ScanSpec;
import nonapi.io.github.classgraph.utils.FastPathResolver;
import nonapi.io.github.classgraph.utils.FileUtils;
import nonapi.io.github.classgraph.utils.JarUtils;
import nonapi.io.github.classgraph.utils.LogNode;

/** Open and read jarfiles, which may be nested within other jarfiles. */
public class NestedJarHandler {
    /** The {@link ScanSpec}. */
    public final ScanSpec scanSpec;

    /**
     * A singleton map from a zipfile's {@link File} to the {@link PhysicalZipFile} for that file, used to ensure
     * that the {@link RandomAccessFile} and {@link FileChannel} for any given zipfile is opened only once.
     */
    private SingletonMap<File, PhysicalZipFile, IOException> //
    canonicalFileToPhysicalZipFileMap = new SingletonMap<File, PhysicalZipFile, IOException>() {
        @Override
        public PhysicalZipFile newInstance(final File canonicalFile, final LogNode log) throws IOException {
            return new PhysicalZipFile(canonicalFile, NestedJarHandler.this, log);
        }
    };

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
                // The child zip entry is a stored nested zipfile -- wrap it in a new ZipFileSlice.
                // Hopefully nested zipfiles are stored, not deflated, as this is the fast path.
                childZipEntrySlice = new ZipFileSlice(childZipEntry);

            } else {
                // If child entry is deflated i.e. (for a deflated nested zipfile), must inflate
                // the contents of the entry before its central directory can be read (most of
                // the time nested zipfiles are stored, not deflated, so this should be rare)
                if (log != null) {
                    log.log("Deflating nested zip entry: " + childZipEntry + " ; uncompressed size: "
                            + childZipEntry.uncompressedSize);
                }

                // Read the InputStream for the child zip entry to a RAM buffer, or spill to disk if it's too large 
                final PhysicalZipFile physicalZipFile = new PhysicalZipFile(childZipEntry.getSlice().open(),
                        childZipEntry.uncompressedSize >= 0L
                                && childZipEntry.uncompressedSize <= FileUtils.MAX_BUFFER_SIZE
                                        ? (int) childZipEntry.uncompressedSize
                                        : -1,
                        childZipEntry.entryName, NestedJarHandler.this, log);

                // Create a new logical slice of the extracted inner zipfile
                childZipEntrySlice = new ZipFileSlice(physicalZipFile, childZipEntry);
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
            // Read the central directory for the zipfile
            return new LogicalZipFile(zipFileSlice, log);
        }
    };

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
                    final String nestedJarPath = FastPathResolver.resolve(nestedJarPathRaw);
                    final int lastPlingIdx = nestedJarPath.lastIndexOf('!');
                    if (lastPlingIdx < 0) {
                        // nestedJarPath is a simple file path or URL (i.e. doesn't have any '!' sections).
                        // This is also the last frame of recursion for the 'else' clause below.

                        // If the path starts with "http://" or "https://" or any other URI/URL scheme,
                        // download the jar to a temp file or to a ByteBuffer in RAM. ("jar:" and "file:"
                        // have already been stripped from any URL/URI.)
                        final boolean isURL = JarUtils.URL_SCHEME_PATTERN.matcher(nestedJarPath).matches();
                        PhysicalZipFile physicalZipFile;
                        if (isURL) {
                            final String scheme = nestedJarPath.substring(0, nestedJarPath.indexOf(':'));
                            if (scanSpec.allowedURLSchemes == null
                                    || !scanSpec.allowedURLSchemes.contains(scheme)) {
                                // No URL schemes other than "file:" (with optional "jar:" prefix) allowed (these
                                // schemes were already stripped by FastPathResolver.resolve(nestedJarPathRaw))
                                throw new IOException("Scanning of URL scheme \"" + scheme
                                        + "\" has not been enabled -- cannot scan classpath element: "
                                        + nestedJarPath);
                            }

                            // Download jar from URL to a ByteBuffer in RAM, or to a temp file on disk
                            final LogNode subLog = log == null ? null
                                    : log.log("Downloading jar from URL " + nestedJarPath);
                            physicalZipFile = downloadJarFromURL(nestedJarPath, subLog);

                        } else {
                            // Jarfile should be a local file -- wrap in a PhysicalZipFile instance
                            try {
                                // Get canonical file
                                final File canonicalFile = new File(nestedJarPath).getCanonicalFile();
                                // Get or create a PhysicalZipFile instance for the canonical file
                                physicalZipFile = canonicalFileToPhysicalZipFileMap.get(canonicalFile, log);
                            } catch (final NullSingletonException e) {
                                // If getting PhysicalZipFile failed, re-wrap in IOException
                                throw new IOException(
                                        "Could not get PhysicalZipFile for path " + nestedJarPath + " : " + e);
                            } catch (final SecurityException e) {
                                // getCanonicalFile() failed (it may have also failed with IOException)
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
                            // ending with a slash when reading the central directory of a zipfile).
                            // N.B. We perform an O(N) search here because we assume the number of classpath
                            // elements containing "!" sections is relatively small compared to the total number
                            // of entries in all jarfiles (i.e. building a HashMap of entry path to entry for
                            // every jarfile would generally be more expensive than performing this linear
                            // search, and unless the classpath is enormous, the overall time performance
                            // will not tend towards O(N^2).
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

                        if (childZipEntry == null /* i.e. if (!isDirectory) */) {
                            throw new IOException(
                                    "Path " + childPath + " does not exist in jarfile " + parentLogicalZipFile);
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
                            return moduleRef.open();
                        }
                    };
                }
            };

    /** A recycler for {@link Inflater} instances. */
    private Recycler<RecyclableInflater, RuntimeException> //
    inflaterRecycler = new Recycler<RecyclableInflater, RuntimeException>() {
        @Override
        public RecyclableInflater newInstance() throws RuntimeException {
            return new RecyclableInflater();
        }
    };

    /** {@link RandomAccessFile} instances that are currently open (typically one per classpath element). */
    private Set<RandomAccessFile> openFiles = Collections
            .newSetFromMap(new ConcurrentHashMap<RandomAccessFile, Boolean>());

    /** Any temporary files created while scanning. */
    private Set<File> tempFiles = Collections.newSetFromMap(new ConcurrentHashMap<File, Boolean>());

    /** The separator between random temp filename part and leafname. */
    public static final String TEMP_FILENAME_LEAF_SEPARATOR = "---";

    /** True if {@link #close(LogNode)} has been called. */
    private final AtomicBoolean closed = new AtomicBoolean(false);

    /** The interruption checker. */
    public InterruptionChecker interruptionChecker;

    /** The default size of a file buffer. */
    private static final int DEFAULT_BUFFER_SIZE = 16384;

    /** The maximum initial buffer size. */
    private static final int MAX_INITIAL_BUFFER_SIZE = 16 * 1024 * 1024;

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
     * @param filePathBase
     *            The path to derive the temporary filename from.
     * @param onlyUseLeafname
     *            If true, only use the leafname of filePath to derive the temporary filename.
     * @return The temporary {@link File}.
     * @throws IOException
     *             If the temporary file could not be created.
     */
    public File makeTempFile(final String filePathBase, final boolean onlyUseLeafname) throws IOException {
        final File tempFile = File.createTempFile("ClassGraph--", TEMP_FILENAME_LEAF_SEPARATOR
                + sanitizeFilename(onlyUseLeafname ? leafname(filePathBase) : filePathBase));
        tempFile.deleteOnExit();
        tempFiles.add(tempFile);
        return tempFile;
    }

    /**
     * Attempt to remove a temporary file.
     *
     * @param tempFile
     *            the temp file
     * @throws IOException
     *             If the temporary file could not be removed.
     * @throws SecurityException
     *             If the temporary file is inaccessible.
     */
    void removeTempFile(final File tempFile) throws IOException, SecurityException {
        if (tempFiles.contains(tempFile)) {
            try {
                Files.delete(tempFile.toPath());
            } finally {
                tempFiles.remove(tempFile);
            }
        } else {
            throw new IOException("Not a temp file: " + tempFile);
        }
    }

    /**
     * Open a file as a {@link RandomAccessFile}.
     * 
     * @param file
     *            the file to open.
     */
    public RandomAccessFile openFile(final File file) throws IOException {
        try {
            final RandomAccessFile raf = new RandomAccessFile(file, "r");
            openFiles.add(raf);
            return raf;
        } catch (final SecurityException e) {
            throw new IOException("Could not open file " + file + " : " + e.getMessage());
        }
    }

    /**
     * Close an open {@link RandomAccessFile}, and remove it from the list of files to close when
     * {@link #close(LogNode)} is called.
     * 
     * @param raf
     *            the {@link RandomAccessFile} to close.
     */
    public void closeOpenFile(final RandomAccessFile raf) {
        openFiles.remove(raf);
        try {
            raf.close();
        } catch (final IOException e) {
            // Ignore
        }
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
        URL url = null;
        try {
            url = new URL(jarURL);
        } catch (final MalformedURLException e1) {
            try {
                url = new URI(jarURL).toURL();
            } catch (final URISyntaxException e2) {
                throw new IOException("Could not parse URL: " + jarURL);
            }
        }

        final URLConnection conn = url.openConnection();
        HttpURLConnection httpConn = null;
        try {
            long contentLengthHint = -1L;
            if (conn instanceof HttpURLConnection) {
                // Get content length from HTTP headers, if available
                httpConn = (HttpURLConnection) url.openConnection();
                contentLengthHint = httpConn.getContentLengthLong();
                if (contentLengthHint < -1L) {
                    contentLengthHint = -1L;
                }
            } else if (conn.getURL().getProtocol().equalsIgnoreCase("file")) {
                // We ended up with a "file:" URL, which can happen as a result of a custom URL scheme that
                // rewrites its URLs into "file:" URLs (see Issue400.java).
                try {
                    // If this is a "file:" URL, get the file from the URL and return it as a new PhysicalZipFile
                    // (this avoids going through an InputStream). Throws IOException if the file cannot be read.
                    final File file = new File(conn.getURL().toURI());
                    return new PhysicalZipFile(file, this, log);

                } catch (final URISyntaxException e) {
                    // Fall through to open URL as InputStream below
                }
            }

            // Fetch content from URL
            try (InputStream inputStream = conn.getInputStream()) {
                // Fetch the jar contents from the URL's InputStream. If it doesn't fit in RAM, spill over to disk.
                final PhysicalZipFile physicalZipFile = new PhysicalZipFile(inputStream, contentLengthHint, jarURL,
                        this, log);
                if (log != null) {
                    log.addElapsedTime();
                    log.log("***** Note that it is time-consuming to scan jars at non-\"file:\" URLs, "
                            + "the URL must be opened (possibly after an http(s) fetch) for every scan, "
                            + "and the same URL must also be separately opened by the ClassLoader *****");
                }
                return physicalZipFile;

            } catch (final MalformedURLException e) {
                throw new IOException("Malformed URL: " + jarURL);
            }
        } finally {
            if (httpConn != null) {
                httpConn.disconnect();
            }
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /** Wrap an {@link InputStream} with an {@link InflaterInputStream}, recycling the {@link Inflater} instance. */
    public InputStream openInflaterInputStream(final InputStream rawInputStream) throws IOException {
        return new InputStream() {
            // Gen Inflater instance with nowrap set to true (needed by zip entries)
            private final RecyclableInflater recyclableInflater = inflaterRecycler.acquire();
            private final Inflater inflater = recyclableInflater.getInflater();
            private final AtomicBoolean closed = new AtomicBoolean();
            private final byte[] buf = new byte[INFLATE_BUF_SIZE];
            private static final int INFLATE_BUF_SIZE = 8192;

            @Override
            public int read() throws IOException {
                if (closed.get()) {
                    throw new IOException("Already closed");
                } else if (inflater.finished()) {
                    return -1;
                }
                final int numDeflatedBytesRead = read(buf, 0, 1);
                if (numDeflatedBytesRead < 0) {
                    return -1;
                } else {
                    return buf[0] & 0xff;
                }
            }

            @Override
            public int read(final byte outBuf[], final int off, final int len) throws IOException {
                if (closed.get()) {
                    throw new IOException("Already closed");
                } else if (len < 0) {
                    throw new IllegalArgumentException("len cannot be negative");
                } else if (len == 0) {
                    return 0;
                }
                try {
                    // Keep fetching data from rawInputStream until 
                    int totInflatedBytes = 0;
                    while (!inflater.finished() && totInflatedBytes < len) {
                        final int numInflatedBytes = inflater.inflate(outBuf, off + totInflatedBytes,
                                len - totInflatedBytes);
                        if (numInflatedBytes == 0) {
                            if (inflater.needsDictionary()) {
                                // Should not happen for jarfiles
                                throw new IOException("Inflater needs preset dictionary");
                            } else if (inflater.needsInput()) {
                                // Read a chunk of data from the raw InputStream
                                final int numRawBytesRead = rawInputStream.read(buf, 0, buf.length);
                                if (numRawBytesRead == -1) {
                                    // An extra dummy byte is needed at the end of the input stream when
                                    // using the "nowrap" Inflater option.
                                    // See: ZipFile.ZipFileInflaterInputStream.fill()
                                    buf[0] = (byte) 0;
                                    inflater.setInput(buf, 0, 1);
                                } else {
                                    // Deflate the chunk of data
                                    inflater.setInput(buf, 0, numRawBytesRead);
                                }
                            }
                        } else {
                            totInflatedBytes += numInflatedBytes;
                        }
                    }
                    if (totInflatedBytes == 0) {
                        // If no bytes were inflated, return -1 as required by read() API contract
                        return -1;
                    }
                    return totInflatedBytes;

                } catch (final DataFormatException e) {
                    throw new ZipException(
                            e.getMessage() != null ? e.getMessage() : "Invalid deflated zip entry data");
                }
            }

            @Override
            public long skip(final long numToSkip) throws IOException {
                if (closed.get()) {
                    throw new IOException("Already closed");
                } else if (numToSkip < 0) {
                    throw new IllegalArgumentException("numToSkip cannot be negative");
                } else if (numToSkip == 0) {
                    return 0;
                } else if (inflater.finished()) {
                    return -1;
                }
                long totBytesSkipped = 0L;
                for (;;) {
                    final int readLen = (int) Math.min(numToSkip - totBytesSkipped, buf.length);
                    final int numBytesRead = read(buf, 0, readLen);
                    if (numBytesRead > 0) {
                        totBytesSkipped -= numBytesRead;
                    } else {
                        break;
                    }
                }
                return totBytesSkipped;
            }

            @Override
            public int available() throws IOException {
                if (closed.get()) {
                    throw new IOException("Already closed");
                }
                // We don't know how many bytes are available, but have to return greater than
                // zero if there is still input, according to the API contract. Hopefully nothing
                // relies on this and ends up reading just one byte at a time.
                return inflater.finished() ? 0 : 1;
            }

            @Override
            public void mark(final int readlimit) {
                throw new IllegalArgumentException("Not supported");
            }

            @Override
            public void reset() throws IOException {
                throw new IllegalArgumentException("Not supported");
            }

            @Override
            public boolean markSupported() {
                return false;
            }

            @Override
            public void close() {
                if (!closed.getAndSet(true)) {
                    try {
                        rawInputStream.close();
                    } catch (final IOException e) {
                        // Ignore
                    } finally {
                        // Reset and recycle inflater instance
                        inflaterRecycler.recycle(recyclableInflater);
                    }
                }
            }
        };
    }

    /**
     * Read all the bytes in an {@link InputStream}, with spillover to a temporary file on disk if a maximum buffer
     * size is exceeded.
     *
     * @param inputStream
     *            the {@link InputStream} to read from.
     * @param tempFileBaseName
     *            the source URL or zip entry that inputStream was opened from (used to name temporary file, if
     *            needed).
     * @param inputStreamLengthHint
     *            the length of inputStream if known, else -1L.
     * @param log
     *            the log.
     * @return if the {@link InputStream} could be read into a byte array, an {@link ArraySlice} will be returned.
     *         If this fails and the {@link InputStream} is spilled over to disk, a {@link FileSlice} will be
     *         returned.
     * 
     * @throws IOException
     *             If the contents could not be read.
     */
    public Slice readAllBytesWithSpilloverToDisk(final InputStream inputStream, final String tempFileBaseName,
            final long inputStreamLengthHint, final LogNode log) throws IOException {
        // Open an InflaterInputStream on the slice
        try (InputStream inptStream = inputStream) {
            if (inputStreamLengthHint <= scanSpec.maxBufferedJarRAMSize) {
                // inputStreamLengthHint is unknown (-1) or shorter than scanSpec.maxBufferedJarRAMSize,
                // so try reading from the InputStream into an array of size scanSpec.maxBufferedJarRAMSize
                // or inputStreamLengthHint respectively. Also if inputStreamLengthHint == 0, which may or
                // may not be valid, use a buffer size of 16kB to avoid spilling to disk in case this is
                // wrong but the file is still small.
                final int bufSize = inputStreamLengthHint == -1L ? scanSpec.maxBufferedJarRAMSize
                        : inputStreamLengthHint == 0L ? 16384
                                : Math.min((int) inputStreamLengthHint, scanSpec.maxBufferedJarRAMSize);
                byte[] buf = new byte[bufSize];
                final int bufLength = buf.length;

                int bufBytesUsed = 0;
                int bytesRead = 0;
                while ((bytesRead = inptStream.read(buf, bufBytesUsed, bufLength - bufBytesUsed)) > 0) {
                    // Fill buffer until nothing more can be read
                    bufBytesUsed += bytesRead;
                }
                if (bytesRead == 0) {
                    // If bytesRead was zero rather than -1, we need to probe the InputStream (by reading
                    // one more byte) to see if inputStreamHint underestimated the actual length of the stream
                    final byte[] overflowBuf = new byte[1];
                    final int overflowBufBytesUsed = inptStream.read(overflowBuf, 0, 1);
                    if (overflowBufBytesUsed == 1) {
                        // We were able to read one more byte, so we're still not at the end of the stream,
                        // and we need to spill to disk, because buf is full
                        return spillToDisk(inptStream, tempFileBaseName, buf, overflowBuf, log);
                    }
                    // else (overflowBufBytesUsed == -1), so reached the end of the stream => don't spill to disk
                }
                // Successfully reached end of stream
                if (bufBytesUsed < buf.length) {
                    // Trim array if needed (this is needed if inputStreamLengthHint was -1, or overestimated
                    // the length of the InputStream)
                    buf = Arrays.copyOf(buf, bufBytesUsed);
                }
                // Return buf as new ArraySlice
                return new ArraySlice(buf, /* isDeflatedZipEntry = */ false, /* inflatedSizeHint = */
                        0L, this);

            }
            // inputStreamLengthHint is longer than scanSpec.maxJarRamSize, so immediately spill to disk
            return spillToDisk(inptStream, tempFileBaseName, /* buf = */ null, /* overflowBuf = */ null, log);
        }
    }

    /**
     * Spill an {@link InputStream} to disk if the stream is too large to fit in RAM.
     * 
     * @param inputStream
     *            The {@link InputStream}.
     * @param tempFileBaseName
     *            The stem to base the temporary filename on.
     * @param buf
     *            The first buffer to write to the beginning of the file, or null if none.
     * @param overflowBuf
     *            The second buffer to write to the beginning of the file, or null if none. (Should have same
     *            nullity as buf.)
     * @param log
     *            The log.
     * @throws IOException
     *             If anything went wrong creating or writing to the temp file.
     */
    private FileSlice spillToDisk(final InputStream inputStream, final String tempFileBaseName, final byte[] buf,
            final byte[] overflowBuf, final LogNode log) throws IOException {
        // Create temp file
        File tempFile;
        try {
            tempFile = makeTempFile(tempFileBaseName, /* onlyUseLeafname = */ true);
        } catch (final IOException e) {
            throw new IOException("Could not create temporary file: " + e.getMessage());
        }
        if (log != null) {
            log.log("Could not fit InputStream content into max RAM buffer size, saving to temporary file: "
                    + tempFileBaseName + " -> " + tempFile);
        }

        // Copy everything read so far and the rest of the InputStream to the temporary file
        try (OutputStream outputStream = new BufferedOutputStream(new FileOutputStream(tempFile))) {
            // Write already-read buffered bytes to temp file, if anything was read
            if (buf != null) {
                outputStream.write(buf);
                outputStream.write(overflowBuf);
            }
            // Copy the rest of the InputStream to the file
            final byte[] copyBuf = new byte[8192];
            for (int bytesRead; (bytesRead = inputStream.read(copyBuf, 0, copyBuf.length)) > 0;) {
                outputStream.write(copyBuf, 0, bytesRead);
            }
        }

        // Return a new FileSlice for the temporary file
        return new FileSlice(tempFile, this);
    }

    /**
     * Read all the bytes in an {@link InputStream}.
     * 
     * @param inputStream
     *            The {@link InputStream}.
     * @param uncompressedLengthHint
     *            The length of the data once inflated from the {@link InputStream}, if known, otherwise -1L.
     * @return The contents of the {@link InputStream} as a byte array.
     * @throws IOException
     *             If the contents could not be read.
     */
    public static byte[] readAllBytesAsArray(final InputStream inputStream, final long uncompressedLengthHint)
            throws IOException {
        try (InputStream inptStream = inputStream) {
            if (uncompressedLengthHint > FileUtils.MAX_BUFFER_SIZE) {
                throw new IOException("InputStream is too large to read");
            }
            final int bufferSize = uncompressedLengthHint < 1L
                    // If fileSizeHint is zero or unknown, use default buffer size 
                    ? DEFAULT_BUFFER_SIZE
                    // fileSizeHint is just a hint -- limit the max allocated buffer size, so that invalid ZipEntry
                    // lengths do not become a memory allocation attack vector
                    : Math.min((int) uncompressedLengthHint, MAX_INITIAL_BUFFER_SIZE);
            byte[] buf = new byte[bufferSize];
            int totBytesRead = 0;
            for (int bytesRead;;) {
                while ((bytesRead = inptStream.read(buf, totBytesRead, buf.length - totBytesRead)) > 0) {
                    // Fill buffer until nothing more can be read
                    totBytesRead += bytesRead;
                }
                if (bytesRead < 0) {
                    // Reached end of stream without filling buf
                    break;
                }

                // Reached end of stream, and buf is full
                final int extraByte;
                try {
                    // bytesRead == 0: either the buffer was the correct size and the end of the stream has been
                    // reached, or the buffer was too small. Need to try reading one more byte to see which is
                    // the case.
                    extraByte = inptStream.read();
                    if (extraByte == -1) {
                        // Reached end of stream
                        break;
                    }
                } catch (final ZipException e) {
                    // FIXME temp
                    throw new RuntimeException(e);
                }

                // Haven't reached end of stream yet. Need to grow the buffer (double its size), and append
                // the extra byte that was just read.
                if (buf.length == FileUtils.MAX_BUFFER_SIZE) {
                    throw new IOException("InputStream too large to read into array");
                }
                buf = Arrays.copyOf(buf, (int) Math.min(buf.length * 2L, FileUtils.MAX_BUFFER_SIZE));
                buf[totBytesRead++] = (byte) extraByte;
            }
            // Return buffer and number of bytes read
            return totBytesRead == buf.length ? buf : Arrays.copyOf(buf, totBytesRead);
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
            boolean interrupted = false;
            if (moduleRefToModuleReaderProxyRecyclerMap != null) {
                boolean completedWithoutInterruption = false;
                while (!completedWithoutInterruption) {
                    try {
                        for (final Recycler<ModuleReaderProxy, IOException> recycler : //
                        moduleRefToModuleReaderProxyRecyclerMap.values()) {
                            recycler.forceClose();
                        }
                        completedWithoutInterruption = true;
                    } catch (final InterruptedException e) {
                        // Try again if interrupted
                        interrupted = true;
                    }
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
                canonicalFileToPhysicalZipFileMap.clear();
                canonicalFileToPhysicalZipFileMap = null;
            }
            if (fastZipEntryToZipFileSliceMap != null) {
                fastZipEntryToZipFileSliceMap.clear();
                fastZipEntryToZipFileSliceMap = null;
            }
            if (openFiles != null) {
                while (!openFiles.isEmpty()) {
                    for (final RandomAccessFile openFile : new ArrayList<>(openFiles)) {
                        try {
                            openFile.close();
                        } catch (final IOException e) {
                            // Ignore
                        }
                        openFiles.remove(openFile);
                    }
                }
                openFiles.clear();
                openFiles = null;
            }
            if (inflaterRecycler != null) {
                inflaterRecycler.forceClose();
                inflaterRecycler = null;
            }
            // Temp files have to be deleted last, after all PhysicalZipFiles are closed and files are unmapped
            if (tempFiles != null) {
                final LogNode rmLog = tempFiles.isEmpty() || log == null ? null
                        : log.log("Removing temporary files");
                while (!tempFiles.isEmpty()) {
                    for (final File tempFile : new ArrayList<>(tempFiles)) {
                        try {
                            removeTempFile(tempFile);
                        } catch (IOException | SecurityException e) {
                            if (rmLog != null) {
                                rmLog.log("Removing temporary file failed: " + tempFile);
                            }
                        }
                    }
                }
                tempFiles = null;
            }
            if (interrupted) {
                interruptionChecker.interrupt();
            }
        }
    }
}
