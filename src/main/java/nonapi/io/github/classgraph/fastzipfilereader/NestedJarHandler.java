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
 * Copyright (c) 2026 Luke Hutchison
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
import java.io.EOFException;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.JarURLConnection;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLConnection;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.FileSystem;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.AbstractMap.SimpleEntry;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
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
import nonapi.io.github.classgraph.recycler.Resettable;
import nonapi.io.github.classgraph.reflection.ReflectionUtils;
import nonapi.io.github.classgraph.scanspec.ScanSpec;
import nonapi.io.github.classgraph.utils.FastPathResolver;
import nonapi.io.github.classgraph.utils.FileUtils;
import nonapi.io.github.classgraph.utils.JarUtils;
import nonapi.io.github.classgraph.utils.LogNode;
import nonapi.io.github.classgraph.utils.VersionFinder;
import nonapi.io.github.classgraph.utils.VersionFinder.OperatingSystem;

/** Open and read jarfiles, which may be nested within other jarfiles. */
public class NestedJarHandler {
    /** The {@link ScanSpec}. */
    public final ScanSpec scanSpec;

    /** The reflection utils instance. */
    public ReflectionUtils reflectionUtils;

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
                // The child zip entry is a stored nested zipfile -- wrap it in a new
                // ZipFileSlice.
                // Hopefully nested zipfiles are stored, not deflated, as this is the fast path.
                childZipEntrySlice = new ZipFileSlice(childZipEntry);

            } else {
                // If child entry is deflated i.e. (for a deflated nested zipfile), must inflate
                // the contents of the entry before its central directory can be read (most of
                // the time nested zipfiles are stored, not deflated, so this should be rare)
                if (log != null) {
                    log.log("Inflating nested zip entry: " + childZipEntry + " ; uncompressed size: "
                            + childZipEntry.uncompressedSize);
                }

                // Read the InputStream for the child zip entry to a RAM buffer, or spill to
                // disk if it's too large
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

    /**
     * A singleton map from a {@link ZipFileSlice} to the {@link LogicalZipFile} for that slice.
     */
    private SingletonMap<ZipFileSlice, LogicalZipFile, IOException> //
    zipFileSliceToLogicalZipFileMap = new SingletonMap<ZipFileSlice, LogicalZipFile, IOException>() {
        @Override
        public LogicalZipFile newInstance(final ZipFileSlice zipFileSlice, final LogNode log)
                throws IOException, InterruptedException {
            // Read the central directory for the zipfile
            return new LogicalZipFile(zipFileSlice, NestedJarHandler.this, log,
                    scanSpec.enableMultiReleaseVersions);
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
                    // A '!' is only a nested jar separator if the outermost path component names an existing
                    // jarfile -- it is otherwise a legal filename character (#903)
                    final int lastPlingIdx = JarUtils.lastIndexOfNestedJarSeparator(nestedJarPath);
                    if (lastPlingIdx < 0) {
                        // nestedJarPath is a simple file path or URL (i.e. doesn't have any '!'
                        // sections).
                        // This is also the last frame of recursion for the 'else' clause below.

                        // If the path starts with "http://" or "https://" or any other URI/URL scheme,
                        // download the jar to a temp file or to a ByteBuffer in RAM. ("jar:" and
                        // "file:"
                        // have already been stripped from any URL/URI.)
                        final boolean isURL = JarUtils.URL_SCHEME_PATTERN.matcher(nestedJarPath).matches();
                        PhysicalZipFile physicalZipFile;
                        if (isURL) {
                            // URL schemes are case-insensitive, and are registered in lowercase, so the scheme
                            // has to be lowercased before it is looked up -- otherwise "S3://bucket/x.jar" is
                            // rejected as not enabled even though the "s3" scheme was enabled
                            final String scheme = nestedJarPath.substring(0, nestedJarPath.indexOf(':'))
                                    .toLowerCase(Locale.ROOT);
                            if (scanSpec.allowedURLSchemes == null
                                    || !scanSpec.allowedURLSchemes.contains(scheme)) {
                                // No URL schemes other than "file:" (with optional "jar:" prefix) allowed
                                // (these
                                // schemes were already stripped by FastPathResolver.resolve(nestedJarPathRaw))
                                throw new IOException("Scanning of URL scheme \"" + scheme
                                        + "\" has not been enabled -- cannot scan classpath element: "
                                        + nestedJarPath);
                            }

                            // Download jar from URL to a ByteBuffer in RAM, or to a temp file on disk
                            physicalZipFile = downloadJarFromURL(nestedJarPath, log);

                        } else {
                            // Jarfile should be a local file -- wrap in a PhysicalZipFile instance
                            try {
                                // Get canonical file, so that the same jarfile reached through two
                                // different paths is opened once
                                final File canonicalFile = FileUtils.canonicalize(new File(nestedJarPath));
                                // Get or create a PhysicalZipFile instance for the canonical file
                                physicalZipFile = canonicalFileToPhysicalZipFileMap.get(canonicalFile, log);
                            } catch (final NullSingletonException | NewInstanceException e) {
                                // If getting PhysicalZipFile failed, re-wrap in IOException
                                throw new IOException("Could not get PhysicalZipFile for path " + nestedJarPath
                                        + " : " + (e.getCause() == null ? e : e.getCause()));
                            } catch (final SecurityException e) {
                                // canonicalize() failed (it may have also failed with IOException)
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
                        } catch (final NewInstanceException e) {
                            throw new IOException("Could not get toplevel slice " + topLevelSlice, e);
                        }

                        // Return new logical zipfile with an empty package root
                        return new SimpleEntry<>(logicalZipFile, "");

                    } else {
                        // This path has one or more '!' sections.
                        final String parentPath = nestedJarPath.substring(0, lastPlingIdx);
                        String childPath = nestedJarPath.substring(lastPlingIdx + 1);
                        // "file.jar!/path" -> "file.jar!path"
                        childPath = FileUtils.sanitizeEntryPath(childPath, /* removeInitialSlash = */ true,
                                /* removeFinalSlash = */ true);

                        // Recursively remove one '!' section at a time, back towards the beginning of
                        // the URL or
                        // file path. At the last frame of recursion, the toplevel jarfile will be
                        // reached and
                        // returned. The recursion is guaranteed to terminate because parentPath gets
                        // one
                        // '!'-section shorter with each recursion frame.
                        Entry<LogicalZipFile, String> parentLogicalZipFileAndPackageRoot;
                        try {
                            parentLogicalZipFileAndPackageRoot = nestedPathToLogicalZipFileAndPackageRootMap
                                    .get(parentPath, log);
                        } catch (final NullSingletonException e) {
                            throw new IOException("Could not get parent logical zipfile " + parentPath + " : " + e);
                        } catch (final NewInstanceException e) {
                            throw new IOException("Could not get parent logical zipfile " + parentPath, e);
                        }

                        // Only the last item in a '!'-delimited list can be a non-jar path, so the
                        // parent must
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
                            // with a name matching the child path (LogicalZipFile discards directory
                            // entries
                            // ending with a slash when reading the central directory of a zipfile).
                            // N.B. We perform an O(N) search here because we assume the number of classpath
                            // elements containing "!" sections is relatively small compared to the total
                            // number
                            // of entries in all jarfiles (i.e. building a HashMap of entry path to entry
                            // for
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
                            // If there is no non-directory zipfile entry with a name matching the child
                            // path,
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

                        // The child path corresponds to a non-directory zip entry, so it must be a
                        // nested jar
                        // (since non-jar nested files cannot be used on the classpath). Map the nested
                        // jar as
                        // a new ZipFileSlice if it is stored, or inflate it to RAM or to a temporary
                        // file if
                        // it is deflated, then create a new ZipFileSlice over the temporary file or
                        // ByteBuffer.

                        // Get zip entry as a ZipFileSlice, possibly inflating to disk or RAM

                        final ZipFileSlice childZipEntrySlice;
                        try {
                            childZipEntrySlice = fastZipEntryToZipFileSliceMap.get(childZipEntry, log);
                        } catch (final NullSingletonException e) {
                            throw new IOException(
                                    "Could not get child zip entry slice " + childZipEntry + " : " + e);
                        } catch (final NewInstanceException e) {
                            throw new IOException("Could not get child zip entry slice " + childZipEntry, e);
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
                        } catch (final NewInstanceException e) {
                            throw new IOException("Could not get child logical zipfile " + childZipEntrySlice, e);
                        }

                        // Return new logical zipfile with an empty package root
                        return new SimpleEntry<>(childLogicalZipFile, "");
                    }
                }
            };

    /**
     * A singleton map from a {@link ModuleRef} to a {@link ModuleReaderProxy} recycler for the module.
     */
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

    /** {@link FileSlice} instances that are currently open. */
    private Set<Slice> openSlices = Collections.newSetFromMap(new ConcurrentHashMap<Slice, Boolean>());

    /** Any temporary files created while scanning. */
    private Set<File> tempFiles = Collections.newSetFromMap(new ConcurrentHashMap<File, Boolean>());

    /** The separator between random temp filename part and leafname. */
    public static final String TEMP_FILENAME_LEAF_SEPARATOR = "---";

    /** True if {@link #close(LogNode)} has been called. */
    private final AtomicBoolean closed = new AtomicBoolean(false);

    /** True if a file was mapped that only the garbage collector can unmap. */
    // #939
    private final AtomicBoolean filesAwaitingUnmapping = new AtomicBoolean(false);

    /** The interruption checker. */
    public InterruptionChecker interruptionChecker;

    /** The default size of a file buffer. */
    private static final int DEFAULT_BUFFER_SIZE = 16384;

    /** The maximum initial buffer size. */
    private static final int MAX_INITIAL_BUFFER_SIZE = 16 * 1024 * 1024;

    /** HTTP(S) timeout, ms. */
    private static final int HTTP_TIMEOUT = 5000;

    // -------------------------------------------------------------------------------------------------------------

    /**
     * A handler for nested jars.
     *
     * @param scanSpec
     *            The {@link ScanSpec}.
     * @param interruptionChecker
     *            the interruption checker
     * @param reflectionUtils
     *            the {@link ReflectionUtils} instance
     */
    public NestedJarHandler(final ScanSpec scanSpec, final InterruptionChecker interruptionChecker,
            final ReflectionUtils reflectionUtils) {
        this.scanSpec = scanSpec;
        this.interruptionChecker = interruptionChecker;
        this.reflectionUtils = reflectionUtils;
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
     * Characters that may not appear in a filename. Windows rejects every ASCII control character, and also
     * {@code " * / < > ? \ |}, whereas Linux and macOS reject only {@code /}. Windows accepts {@code :}, but treats
     * it as the start of an NTFS alternate data stream rather than as part of the filename. The remaining
     * characters are legal everywhere, but are replaced anyway so that a temporary filename can be pasted into a
     * shell command or a log message without quoting.
     */
    private static final Pattern UNSAFE_FILENAME_CHARS = Pattern.compile("[\\x00-\\x1f\"*/:<>?\\\\|&= ]");

    /**
     * Sanitize filename, by replacing any character that is not valid in a filename on every supported platform
     * with an underscore. Zip entry names may contain almost any byte, whereas filenames may not, so the temporary
     * file that a nested jar is extracted to cannot simply be named after the zip entry it came from.
     *
     * @param filename
     *            the filename
     * @return the sanitized filename
     */
    private static String sanitizeFilename(final String filename) {
        return UNSAFE_FILENAME_CHARS.matcher(filename).replaceAll("_");
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
     * Remove any temporary files created during the scan, as requested by
     * {@link io.github.classgraph.ClassGraph#removeTemporaryFilesAfterScan()}.
     *
     * <p>
     * If no temporary files were created -- which is the case whenever no nested jars were encountered, i.e. for
     * an ordinary jar or directory classpath -- this does nothing, so that the {@link ScanResult} returned by the
     * scan remains fully usable. Previously this always called {@link #close(LogNode)}, which closes every open
     * {@link Slice} and the inflater {@link Recycler}, so calling {@code removeTemporaryFilesAfterScan()} left the
     * returned {@link ScanResult} unable to read any resource or load any class from a jar, even though
     * {@link ScanResult#isClosed()} still reported {@code false} (#916).
     *
     * <p>
     * If temporary files <i>were</i> created, they back open slices of the extracted nested jars, so they cannot
     * be deleted without closing those slices first -- the whole handler is still torn down in that case.
     *
     * @param log
     *            the log
     * @return true if the handler was closed (i.e. if temporary files existed and had to be removed)
     */
    public boolean removeTemporaryFiles(final LogNode log) {
        final Set<File> tempFilesCurr = tempFiles;
        if (tempFilesCurr == null || tempFilesCurr.isEmpty()) {
            // No temp files were created, so there is nothing to remove, and no need to close anything
            return false;
        }
        close(log);
        return true;
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
        if (tempFiles.remove(tempFile)) {
            Files.delete(tempFile.toPath());
        } else {
            throw new IOException("Not a temp file: " + tempFile);
        }
    }

    /**
     * Mark a {@link Slice} as open, so it can be closed when the {@link ScanResult} is closed.
     *
     * @param slice
     *            the {@link Slice} that was just opened.
     * @throws IOException
     *             Signals that an I/O exception has occurred.
     */
    public void markSliceAsOpen(final Slice slice) throws IOException {
        openSlices.add(slice);
    }

    /**
     * Mark a {@link Slice} as closed. Does nothing once {@link #close(LogNode)} has been called: a slice can be
     * closed after the {@link ScanResult} has been closed (for example when a {@link io.github.classgraph.Resource}
     * that was still open is then closed), and closing something twice has to be harmless.
     *
     * @param slice
     *            the {@link Slice} to close.
     */
    public void markSliceAsClosed(final Slice slice) {
        final Set<Slice> openSlicesCurr = openSlices;
        if (openSlicesCurr != null) {
            openSlicesCurr.remove(slice);
        }
    }

    /**
     * Record that a file was unmapped by dropping the last reference to its mapped buffer, leaving it to the
     * garbage collector to unmap the file, so that {@link #close(LogNode)} knows to ask for a collection.
     */
    // #939
    public void markFileAsAwaitingUnmapping() {
        filesAwaitingUnmapping.set(true);
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
     * @throws InterruptedException
     *             if the thread was interrupted
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
            } catch (final MalformedURLException | IllegalArgumentException | URISyntaxException e2) {
                throw new IOException("Could not parse URL: " + jarURL);
            }
        }

        final String scheme = url.getProtocol();
        if (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https")) {
            // Check if this URL is backed by a filesystem -- if it is, don't download a
            // copy of the file
            // over the URL; instead, access the filesystem directly
            try {
                final Path path = Paths.get(url.toURI());
                // Fails with FileSystemNotFoundException if filesystem not registered for URL
                final FileSystem fs = path.getFileSystem();
                if (log != null) {
                    log.log("URL " + jarURL + " is backed by filesystem " + fs.getClass().getName());
                }
                // Wrap Path in PhysicalZipFile and return it
                return new PhysicalZipFile(path, this, log);
            } catch (final IllegalArgumentException | SecurityException | URISyntaxException e) {
                throw new IOException("Could not convert URL to URI (" + e + "): " + url);
            } catch (final FileSystemNotFoundException e) {
                // Not a custom filesystem
            }
        }
        try (final CloseableUrlConnection urlConn = new CloseableUrlConnection(url)) {
            long contentLengthHint = -1L;
            urlConn.conn.setConnectTimeout(HTTP_TIMEOUT);
            urlConn.conn.connect();
            if (urlConn.httpConn != null) {
                // Get content length from HTTP headers, if available
                if (urlConn.httpConn.getResponseCode() != HttpURLConnection.HTTP_OK) {
                    throw new IOException(
                            "Got response code " + urlConn.httpConn.getResponseCode() + " for URL " + url);
                }
            } else if (url.getProtocol().equalsIgnoreCase("file")) {
                // We ended up with a "file:" URL, which can happen as a result of a custom URL
                // scheme that
                // rewrites its URLs into "file:" URLs (see Issue400.java).
                try {
                    // If this is a "file:" URL, get the file from the URL and return it as a new
                    // PhysicalZipFile
                    // (this avoids going through an InputStream). Throws IOException if the file
                    // cannot be read.
                    final File file = Paths.get(url.toURI()).toFile();
                    return new PhysicalZipFile(file, this, log);

                } catch (final Exception e) {
                    // Fall through -- unknown URL type
                }
            }
            // Try to read content length hint
            contentLengthHint = urlConn.conn.getContentLengthLong();
            if (contentLengthHint < -1L) {
                contentLengthHint = -1L;
            }
            // Fetch content from URL
            final LogNode subLog = log == null ? null : log.log("Downloading jar from URL " + jarURL);
            try (InputStream inputStream = urlConn.conn.getInputStream()) {
                // Fetch the jar contents from the URL's InputStream. If it doesn't fit in RAM,
                // spill over to disk.
                final PhysicalZipFile physicalZipFile = new PhysicalZipFile(inputStream, contentLengthHint, jarURL,
                        this, subLog);
                if (subLog != null) {
                    subLog.addElapsedTime();
                    subLog.log("***** Note that it is time-consuming to scan jars at non-\"file:\" URLs, "
                            + "the URL must be opened (possibly after an http(s) fetch) for every scan, "
                            + "and the same URL must also be separately opened by the ClassLoader *****");
                }
                return physicalZipFile;

            } catch (final MalformedURLException e) {
                throw new IOException("Malformed URL: " + jarURL);
            }
        }
    }

    private static class CloseableUrlConnection implements AutoCloseable {
        public final URLConnection conn;
        public final HttpURLConnection httpConn;

        public CloseableUrlConnection(final URL url) throws IOException {
            conn = url.openConnection();
            // A "jar:" URL connection would otherwise put the jar it names into the JVM-wide jar file cache,
            // which never closes what it holds, so the jar would stay open for the life of the JVM -- and on
            // Windows, an open file cannot be deleted or overwritten. With caching turned off, the jar is this
            // connection's to close.
            conn.setUseCaches(false);
            httpConn = conn instanceof HttpURLConnection ? (HttpURLConnection) conn : null;
        }

        @Override
        public void close() {
            if (httpConn != null) {
                httpConn.disconnect();
            } else if (conn instanceof JarURLConnection) {
                // Closing the connection's InputStream closes the jar, but the InputStream is only opened if
                // the jar is actually read, so close the jar here in case it was not
                try {
                    ((JarURLConnection) conn).getJarFile().close();
                } catch (final IOException e) {
                    // The jar was never opened, or is already closed
                }
            }
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Wrapper class that allows an {@link Inflater} instance to be reset for reuse and then recycled by a
     * {@link Recycler}.
     */
    private static class RecyclableInflater implements Resettable, AutoCloseable {
        /**
         * Create a new {@link Inflater} instance with the "nowrap" option (which is needed for zipfile entries).
         */
        private final Inflater inflater = new Inflater(/* nowrap = */ true);

        /**
         * Get the {@link Inflater} instance.
         *
         * @return the {@link Inflater} instance.
         */
        public Inflater getInflater() {
            return inflater;
        }

        /**
         * Called when an {@link Inflater} instance is recycled, to reset the inflater so it can accept new input.
         */
        @Override
        public void reset() {
            inflater.reset();
        }

        /**
         * Called when the {@link Recycler} instance is closed, to destroy the {@link Inflater} instance.
         */
        @Override
        public void close() {
            inflater.end();
        }
    }

    /**
     * Wrap an {@link InputStream} with an {@link InflaterInputStream}, recycling the {@link Inflater} instance.
     *
     * @param rawInputStream
     *            the raw input stream
     * @return the inflater input stream
     * @throws IOException
     *             Signals that an I/O exception has occurred.
     */
    public InputStream openInflaterInputStream(final InputStream rawInputStream) throws IOException {
        if (closed.get()) {
            throw new IOException("Cannot read from a jarfile after the resources backing the ScanResult "
                    + "have been closed. This happens if the ScanResult was closed (e.g. by leaving the "
                    + "try-with-resources block it was opened in) before the resource was read or the class "
                    + "was loaded, or if ClassGraph#removeTemporaryFilesAfterScan() was called and the scan "
                    + "extracted a nested jarfile to a temporary file, since removing the temporary file "
                    + "requires closing the jarfile that was extracted from it");
        }
        @SuppressWarnings("resource")
        final RecyclableInflater recyclableInflater = inflaterRecycler.acquire();
        final Inflater inflater = recyclableInflater.getInflater();
        return new InputStream() {
            // Gen Inflater instance with nowrap set to true (needed by zip entries)
            private final AtomicBoolean closed = new AtomicBoolean();
            /**
             * The staging buffer that deflated bytes are read into from rawInputStream, and then handed to the
             * inflater as its input. This must never be used as the destination of an inflate() call: the
             * inflater keeps a reference to its input array, so inflating into this array would overwrite
             * deflated bytes that the inflater has not consumed yet.
             */
            private final byte[] buf = new byte[INFLATE_BUF_SIZE];
            /** A separate destination buffer for the single-byte read() method. */
            private final byte[] singleByteBuf = new byte[1];
            /**
             * True once the end of rawInputStream has been reached, and the dummy byte required by the
             * "nowrap" option has been handed to the inflater.
             */
            private boolean suppliedDummyByte;
            private static final int INFLATE_BUF_SIZE = 8192;

            @Override
            public int read() throws IOException {
                if (closed.get()) {
                    throw new IOException("InputStream is already closed");
                } else if (inflater.finished()) {
                    return -1;
                }
                final int numInflatedBytesRead = read(singleByteBuf, 0, 1);
                if (numInflatedBytesRead < 0) {
                    return -1;
                } else {
                    return singleByteBuf[0] & 0xff;
                }
            }

            @Override
            public int read(final byte[] outBuf, final int off, final int len) throws IOException {
                if (closed.get()) {
                    throw new IOException("InputStream is already closed");
                } else if (len < 0) {
                    throw new IllegalArgumentException("len cannot be negative");
                } else if (len == 0) {
                    return 0;
                }
                try {
                    // Keep fetching data from rawInputStream until buffer is full or inflater has
                    // finished
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
                                    if (suppliedDummyByte) {
                                        // The inflater wants more input, but the dummy byte below has
                                        // already been supplied and the raw stream is exhausted, so the
                                        // deflated data was truncated. Without this check, a fresh dummy
                                        // byte would be supplied on every iteration, and this loop would
                                        // never terminate.
                                        throw new EOFException("Unexpected end of deflated zip entry data");
                                    }
                                    suppliedDummyByte = true;
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
                    throw new IOException("InputStream is already closed");
                } else if (numToSkip < 0) {
                    throw new IllegalArgumentException("numToSkip cannot be negative");
                } else if (numToSkip == 0 || inflater.finished()) {
                    // (InputStream#skip returns 0 at the end of the stream, it does not return -1)
                    return 0;
                }
                // (Use a separate destination buffer -- buf is the inflater's input buffer, see above)
                final byte[] skipBuf = new byte[(int) Math.min(numToSkip, INFLATE_BUF_SIZE)];
                long totBytesSkipped = 0L;
                while (totBytesSkipped < numToSkip) {
                    final int readLen = (int) Math.min(numToSkip - totBytesSkipped, skipBuf.length);
                    final int numBytesRead = read(skipBuf, 0, readLen);
                    if (numBytesRead > 0) {
                        totBytesSkipped += numBytesRead;
                    } else {
                        break;
                    }
                }
                return totBytesSkipped;
            }

            @Override
            public int available() throws IOException {
                if (closed.get()) {
                    throw new IOException("InputStream is already closed");
                }
                // We don't know how many bytes are available, but have to return greater than
                // zero if there is still input, according to the API contract. Hopefully
                // nothing
                // relies on this and ends up reading just one byte at a time.
                return inflater.finished() ? 0 : 1;
            }

            /**
             * Mark is not supported by this stream, so this is a no-op, as required by the
             * {@link InputStream} contract when {@link #markSupported()} returns false.
             */
            @Override
            public synchronized void mark(final int readlimit) {
                // No-op
            }

            @Override
            public synchronized void reset() throws IOException {
                throw new IOException("mark/reset not supported");
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
                    } catch (final Exception e) {
                        // Ignore
                    }
                    // Reset and recycle inflater instance
                    inflaterRecycler.recycle(recyclableInflater);
                }
            }
        };
    }

    // -------------------------------------------------------------------------------------------------------------

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
                // inputStreamLengthHint is unknown (-1) or shorter than
                // scanSpec.maxBufferedJarRAMSize,
                // so try reading from the InputStream into an array of size
                // scanSpec.maxBufferedJarRAMSize
                // or inputStreamLengthHint respectively. Also if inputStreamLengthHint == 0,
                // which may or
                // may not be valid, use a buffer size of 16kB to avoid spilling to disk in case
                // this is
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
                    // If bytesRead was zero rather than -1, we need to probe the InputStream (by
                    // reading
                    // one more byte) to see if inputStreamHint underestimated the actual length of
                    // the stream. (The probe is the single-byte InputStream#read, which returns
                    // either a byte value or -1 for the end of the stream -- a probe through
                    // InputStream#read(byte[], int, int) could return zero, which is what made the
                    // probe necessary in the first place, and the stream would be truncated here.)
                    final int overflowByte = inptStream.read();
                    if (overflowByte != -1) {
                        // We were able to read one more byte, so we're still not at the end of the
                        // stream,
                        // and we need to spill to disk, because buf is full
                        return spillToDisk(inptStream, tempFileBaseName, buf, bufBytesUsed,
                                new byte[] { (byte) overflowByte }, log);
                    }
                    // else reached the end of the stream => don't spill to disk
                }
                // Successfully reached end of stream
                if (bufBytesUsed < buf.length) {
                    // Trim array if needed (this is needed if inputStreamLengthHint was -1, or
                    // overestimated
                    // the length of the InputStream)
                    buf = Arrays.copyOf(buf, bufBytesUsed);
                }
                // Return buf as new ArraySlice
                return new ArraySlice(buf, /* isDeflatedZipEntry = */ false, /* inflatedSizeHint = */
                        0L, this);

            }
            // inputStreamLengthHint is longer than scanSpec.maxJarRamSize, so immediately
            // spill to disk
            return spillToDisk(inptStream, tempFileBaseName, /* buf = */ null, /* bufBytesUsed = */ 0,
                    /* overflowBuf = */ null, log);
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
     * @param bufBytesUsed
     *            The number of bytes of {@code buf} that were filled.
     * @param overflowBuf
     *            The second buffer to write to the beginning of the file, or null if none. (Should have same
     *            nullity as buf.)
     * @param log
     *            The log.
     * @return the file slice
     * @throws IOException
     *             If anything went wrong creating or writing to the temp file.
     */
    private FileSlice spillToDisk(final InputStream inputStream, final String tempFileBaseName, final byte[] buf,
            final int bufBytesUsed, final byte[] overflowBuf, final LogNode log) throws IOException {
        // Create temp file
        File tempFile;
        try {
            tempFile = makeTempFile(tempFileBaseName, /* onlyUseLeafname = */ true);
        } catch (final IOException e) {
            // Chain the cause, so that the reason the temporary file could not be created is reachable from the
            // stack trace
            throw new IOException("Could not create temporary file: " + e, e);
        }
        if (log != null) {
            log.log("Could not fit InputStream content into max RAM buffer size, saving to temporary file: "
                    + tempFileBaseName + " -> " + tempFile);
        }

        // Copy everything read so far and the rest of the InputStream to the temporary
        // file
        try (OutputStream outputStream = new BufferedOutputStream(new FileOutputStream(tempFile))) {
            // Write already-read buffered bytes to temp file, if anything was read (buf and overflowBuf always
            // have the same nullity)
            if (buf != null && overflowBuf != null) {
                outputStream.write(buf, 0, bufBytesUsed);
                outputStream.write(overflowBuf);
            }
            // Copy the rest of the InputStream to the file. (The loop ends only at the end of the stream, and not
            // as soon as a read returns zero, because a stream that returns zero from a read of a non-empty buffer
            // would otherwise end the loop early, silently truncating the file.)
            final byte[] copyBuf = new byte[8192];
            for (int bytesRead; (bytesRead = inputStream.read(copyBuf, 0, copyBuf.length)) >= 0;) {
                outputStream.write(copyBuf, 0, bytesRead);
            }
        }

        // Return a new FileSlice for the temporary file
        return new FileSlice(tempFile, this, log);
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
        if (uncompressedLengthHint > FileUtils.MAX_BUFFER_SIZE) {
            throw new IOException("InputStream is too large to read");
        }
        try (InputStream inptStream = inputStream) {
            final int bufferSize = uncompressedLengthHint < 1L
                    // If fileSizeHint is zero or unknown, use default buffer size
                    ? DEFAULT_BUFFER_SIZE
                    // fileSizeHint is just a hint -- limit the max allocated buffer size, so that
                    // invalid ZipEntry
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

                // bytesRead == 0: either the buffer was the correct size and the end of the
                // stream has been
                // reached, or the buffer was too small. Need to try reading one more byte to
                // see which is
                // the case.
                final int extraByte = inptStream.read();
                if (extraByte == -1) {
                    // Reached end of stream
                    break;
                }

                // Haven't reached end of stream yet. Need to grow the buffer (double its size),
                // and append
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
            if (openSlices != null) {
                while (!openSlices.isEmpty()) {
                    for (final Slice slice : new ArrayList<>(openSlices)) {
                        try {
                            slice.close();
                        } catch (final IOException e) {
                            // Ignore
                        }
                        markSliceAsClosed(slice);
                    }
                }
                openSlices.clear();
                openSlices = null;
            }
            if (inflaterRecycler != null) {
                inflaterRecycler.forceClose();
            }
            // Below JDK 22 a file is unmapped only once the garbage collector finds the mapped buffer
            // unreachable, and Windows refuses to delete, rename or overwrite a file while it is mapped. Closing
            // the slices above dropped the last reference to every mapping this scan made, so ask for a
            // collection here, and wait for the collector to unmap the files: without the request, a file that
            // the scan mapped stays locked until the next collection happens to run, which in a large heap can
            // be minutes after the scan finished, or never. This is best effort -- below JDK 22 nothing can
            // unmap a file on demand, and nothing can observe that the collector has done it. Only Windows pays
            // for the collection: every other operating system lets a mapped file be deleted or replaced, so
            // releasing the mapping promptly buys nothing there.
            // #939
            if (filesAwaitingUnmapping.get() && VersionFinder.OS == OperatingSystem.Windows) {
                FileUtils.freeUnreachableBuffers();
            }
            // Temp files have to be deleted last, after all PhysicalZipFiles are closed and
            // files are unmapped
            if (tempFiles != null) {
                final LogNode rmLog = tempFiles.isEmpty() || log == null ? null
                        : log.log("Removing temporary files");
                final List<File> undeleted = new ArrayList<>();
                while (!tempFiles.isEmpty()) {
                    for (final File tempFile : new ArrayList<>(tempFiles)) {
                        try {
                            removeTempFile(tempFile);
                        } catch (IOException | SecurityException e) {
                            undeleted.add(tempFile);
                        }
                    }
                }
                if (!undeleted.isEmpty()) {
                    // Windows refuses to delete a file that is still memory-mapped, and below JDK 22 a mapping
                    // is released only once the garbage collector finds it unreachable -- which closing the
                    // slices above has just made it, so ask for a collection and try again. (This is a second
                    // request on Windows below JDK 22, but the first one is skipped on every other operating
                    // system and JDK, where a delete can still fail for an unrelated reason.) If the JVM was
                    // started with -XX:+DisableExplicitGC then this is a no-op, and the file is left to the
                    // File#deleteOnExit() hook that makeTempFile registered.
                    FileUtils.freeUnreachableBuffers();
                    for (final File tempFile : undeleted) {
                        try {
                            // The file is no longer in tempFiles, since removeTempFile removes it before
                            // attempting the delete, so delete it directly rather than through removeTempFile
                            Files.delete(tempFile.toPath());
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

    /**
     * System.runFinalization() -- deprecated in JDK 18, so accessed by reflection.
     */
    private static volatile Method runFinalizationMethod;

    /** Call {@code System.runFinalization()}, if it is available in this JDK. */
    public void runFinalizationMethod() {
        // Read the volatile field once, so that the method invoked cannot differ from the method tested. Two
        // threads racing here resolve the same method, so whichever write lands last is equivalent.
        Method runFinalizationMethodCached = runFinalizationMethod;
        if (runFinalizationMethodCached == null) {
            runFinalizationMethodCached = reflectionUtils.staticMethodForNameOrNull("System", "runFinalization");
            runFinalizationMethod = runFinalizationMethodCached;
        }
        if (runFinalizationMethodCached != null) {
            try {
                // Call System.runFinalization() (deprecated in JDK 18)
                runFinalizationMethodCached.invoke(null);
            } catch (final Throwable t) {
                // Ignore
            }
        }
    }
}
