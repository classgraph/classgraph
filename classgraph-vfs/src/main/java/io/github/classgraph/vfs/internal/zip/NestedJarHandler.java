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
package io.github.classgraph.vfs.internal.zip;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.util.AbstractMap.SimpleEntry;
import java.util.Locale;
import java.util.Map.Entry;
import java.util.Objects;

import io.github.classgraph.base.internal.concurrency.InterruptionChecker;
import io.github.classgraph.base.internal.concurrency.SingletonMap;
import io.github.classgraph.base.internal.concurrency.SingletonMap.NewInstanceException;
import io.github.classgraph.base.internal.concurrency.SingletonMap.NullSingletonException;
import io.github.classgraph.base.internal.recycler.Recycler;
import io.github.classgraph.base.internal.utils.FastPathResolver;
import io.github.classgraph.base.internal.utils.FileUtils;
import io.github.classgraph.base.internal.utils.JarUtils;
import io.github.classgraph.base.internal.utils.LogNode;
import io.github.classgraph.vfs.internal.ScanResources;
import io.github.classgraph.vfs.internal.slice.Slice;
import io.github.classgraph.vfs.internal.spec.VfsScanSpec;
import org.jspecify.annotations.Nullable;

/**
 * Resolve a classpath element path, which may name a jarfile nested within one or more enclosing jarfiles, to the
 * {@link LogicalZipFile} for the innermost jarfile and the package root within it, opening and caching each zipfile
 * along the way. Also owns the {@link ScanResources} that the opened zipfiles are backed by, and closes them when
 * {@link #close(LogNode)} is called.
 */
public class NestedJarHandler {
    /** The resources opened by this handler. */
    public final ScanResources scanResources;

    /** The settings that govern how archives are read. */
    private final VfsScanSpec vfsScanSpec;

    /**
     * A handler for nested jars.
     *
     * @param vfsScanSpec
     *            The settings that govern how archives are read.
     * @param interruptionChecker
     *            the interruption checker
     */
    public NestedJarHandler(final VfsScanSpec vfsScanSpec, final InterruptionChecker interruptionChecker) {
        this.vfsScanSpec = vfsScanSpec;
        this.scanResources = new ScanResources(vfsScanSpec, interruptionChecker);
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * A singleton map from a zipfile's {@link File} to the {@link PhysicalZipFile} for that file, used to ensure
     * that the {@link RandomAccessFile} and {@link FileChannel} for any given zipfile is opened only once. Set to
     * null by {@link #close(LogNode)}.
     */
    private @Nullable SingletonMap<File, PhysicalZipFile, IOException> //
    canonicalFileToPhysicalZipFileMap = new SingletonMap<>() {
        @Override
        public PhysicalZipFile newInstance(final File canonicalFile, final @Nullable LogNode log)
                throws IOException {
            return new PhysicalZipFile(canonicalFile, scanResources, log);
        }
    };

    /**
     * Get the map from canonical {@link File} to {@link PhysicalZipFile}.
     *
     * @return the map
     * @throws NullPointerException
     *             if {@link #close(LogNode)} has been called
     */
    private SingletonMap<File, PhysicalZipFile, IOException> canonicalFileToPhysicalZipFileMap() {
        return Objects.requireNonNull(canonicalFileToPhysicalZipFileMap);
    }

    /**
     * A singleton map from a {@link FastZipEntry} to the {@link ZipFileSlice} wrapping either the zip entry data,
     * if the entry is stored, or a ByteBuffer, if the zip entry was inflated to memory, or a physical file on disk
     * if the zip entry was inflated to a temporary file. Set to null by {@link #close(LogNode)}.
     */
    private @Nullable SingletonMap<FastZipEntry, ZipFileSlice, IOException> //
    fastZipEntryToZipFileSliceMap = new SingletonMap<>() {
        @Override
        public ZipFileSlice newInstance(final FastZipEntry childZipEntry, final @Nullable LogNode log)
                throws IOException, InterruptedException {
            ZipFileSlice childZipEntrySlice;
            if (!childZipEntry.isDeflated) {
                // The child zip entry is a stored nested zipfile -- wrap it in a new ZipFileSlice. Hopefully nested
                // zipfiles are stored, not deflated, as this is the fast path.
                childZipEntrySlice = new ZipFileSlice(childZipEntry);

            } else {
                // If child entry is deflated i.e. (for a deflated nested zipfile), must inflate the contents of the
                // entry before its central directory can be read (most of the time nested zipfiles are stored, not
                // deflated, so this should be rare)
                if (log != null) {
                    log.log("Inflating nested zip entry: " + childZipEntry + " ; uncompressed size: "
                            + childZipEntry.uncompressedSize);
                }

                // Read the InputStream for the child zip entry to a RAM buffer, or spill to disk if it's too large
                final PhysicalZipFile physicalZipFile = new PhysicalZipFile(childZipEntry.getSlice().open(),
                        childZipEntry.uncompressedSize >= 0L
                                && childZipEntry.uncompressedSize <= FileUtils.MAX_BUFFER_SIZE
                                        ? (int) childZipEntry.uncompressedSize
                                        : -1,
                        childZipEntry.entryName, scanResources, log);

                // Create a new logical slice of the extracted inner zipfile
                childZipEntrySlice = new ZipFileSlice(physicalZipFile, childZipEntry);
            }
            return childZipEntrySlice;
        }
    };

    /**
     * Get the map from {@link FastZipEntry} to {@link ZipFileSlice}.
     *
     * @return the map
     * @throws NullPointerException
     *             if {@link #close(LogNode)} has been called
     */
    private SingletonMap<FastZipEntry, ZipFileSlice, IOException> fastZipEntryToZipFileSliceMap() {
        return Objects.requireNonNull(fastZipEntryToZipFileSliceMap);
    }

    /**
     * A singleton map from a {@link ZipFileSlice} to the {@link LogicalZipFile} for that slice. Set to null by
     * {@link #close(LogNode)}.
     */
    private @Nullable SingletonMap<ZipFileSlice, LogicalZipFile, IOException> //
    zipFileSliceToLogicalZipFileMap = new SingletonMap<>() {
        @Override
        public LogicalZipFile newInstance(final ZipFileSlice zipFileSlice, final @Nullable LogNode log)
                throws IOException, InterruptedException {
            // Read the central directory for the zipfile
            return new LogicalZipFile(zipFileSlice, scanResources, log, vfsScanSpec.enableMultiReleaseVersions);
        }
    };

    /**
     * Get the map from {@link ZipFileSlice} to {@link LogicalZipFile}.
     *
     * @return the map
     * @throws NullPointerException
     *             if {@link #close(LogNode)} has been called
     */
    private SingletonMap<ZipFileSlice, LogicalZipFile, IOException> zipFileSliceToLogicalZipFileMap() {
        return Objects.requireNonNull(zipFileSliceToLogicalZipFileMap);
    }

    /**
     * A singleton map from nested jarfile path to a tuple of the logical zipfile for the path, and the package root
     * within the logical zipfile. Set to null by {@link #close(LogNode)}.
     */
    private @Nullable SingletonMap<String, Entry<LogicalZipFile, String>, IOException> //
    nestedPathToLogicalZipFileAndPackageRootMap = new SingletonMap<>() {
        @Override
        public Entry<LogicalZipFile, String> newInstance(final String nestedJarPathRaw, final @Nullable LogNode log)
                throws IOException, InterruptedException {
            final var nestedJarPath = FastPathResolver.resolve(nestedJarPathRaw);
            // A '!' is only a nested jar separator if the outermost path component names an existing jarfile --
            // it is otherwise a legal filename character (#903)
            final var lastPlingIdx = JarUtils.lastIndexOfNestedJarSeparator(nestedJarPath);
            return lastPlingIdx < 0 ? openTopLevelJar(nestedJarPath, log)
                    : openNestedJar(nestedJarPath, lastPlingIdx, log);
        }
    };

    /**
     * Get the map from nested jarfile path to the logical zipfile for the path and the package root within that
     * zipfile.
     *
     * @return the map
     * @throws NullPointerException
     *             if {@link #close(LogNode)} has been called
     */
    public SingletonMap<String, Entry<LogicalZipFile, String>, IOException> //
            nestedPathToLogicalZipFileAndPackageRootMap() {
        return Objects.requireNonNull(nestedPathToLogicalZipFileAndPackageRootMap);
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Open a jarfile whose path has no {@code '!'} sections, i.e. a plain file path or a URL. This is also the last
     * frame of the recursion in {@link #openNestedJar(String, int, LogNode)}.
     *
     * @param nestedJarPath
     *            the resolved path of the jarfile
     * @param log
     *            the log node, or null to skip logging
     * @return the {@link LogicalZipFile} for the jarfile, paired with an empty package root
     * @throws IOException
     *             if the jarfile could not be opened
     * @throws InterruptedException
     *             if the thread was interrupted
     */
    private Entry<LogicalZipFile, String> openTopLevelJar(final String nestedJarPath, final @Nullable LogNode log)
            throws IOException, InterruptedException {
        // If the path starts with "http://" or "https://" or any other URI/URL scheme, download the jar to a temp
        // file or to a ByteBuffer in RAM. ("jar:" and "file:" have already been stripped from any URL/URI.)
        final var isURL = JarUtils.URL_SCHEME_PATTERN.matcher(nestedJarPath).matches();
        PhysicalZipFile physicalZipFile;
        if (isURL) {
            // URL schemes are case-insensitive, and are registered in lowercase, so the scheme has to be
            // lowercased before it is looked up -- otherwise "S3://bucket/x.jar" is rejected as not enabled
            // even though the "s3" scheme was enabled
            final var scheme = nestedJarPath.substring(0, nestedJarPath.indexOf(':')).toLowerCase(Locale.ROOT);
            if (vfsScanSpec.allowedURLSchemes == null || !vfsScanSpec.allowedURLSchemes.contains(scheme)) {
                // No URL schemes other than "file:" (with optional "jar:" prefix) allowed (these schemes were
                // already stripped by FastPathResolver.resolve(nestedJarPathRaw))
                throw new IOException("Scanning of URL scheme \"" + scheme
                        + "\" has not been enabled -- cannot scan classpath element: " + nestedJarPath);
            }

            // Download jar from URL to a ByteBuffer in RAM, or to a temp file on disk
            physicalZipFile = JarURLDownloader.downloadJarFromURL(nestedJarPath, scanResources, log);

        } else {
            // Jarfile should be a local file -- wrap in a PhysicalZipFile instance
            try {
                // Get canonical file, so that the same jarfile reached through two different paths is opened once
                final var canonicalFile = FileUtils.canonicalize(new File(nestedJarPath));
                // Get or create a PhysicalZipFile instance for the canonical file
                physicalZipFile = canonicalFileToPhysicalZipFileMap().get(canonicalFile, log);
            } catch (final NullSingletonException | NewInstanceException e) {
                // If getting PhysicalZipFile failed, re-wrap in IOException
                throw new IOException("Could not get PhysicalZipFile for path " + nestedJarPath + " : "
                        + (e.getCause() == null ? e : e.getCause()));
            } catch (final SecurityException e) {
                // getCanonicalFile() failed (it may have also failed with IOException)
                throw new IOException("Path component " + nestedJarPath + " could not be canonicalized: " + e);
            }
        }

        // Create a new logical slice of the whole physical zipfile
        final ZipFileSlice topLevelSlice = new ZipFileSlice(physicalZipFile);
        LogicalZipFile logicalZipFile;
        try {
            logicalZipFile = zipFileSliceToLogicalZipFileMap().get(topLevelSlice, log);
        } catch (final NullSingletonException e) {
            throw new IOException("Could not get toplevel slice " + topLevelSlice + " : " + e);
        } catch (final NewInstanceException e) {
            throw new IOException("Could not get toplevel slice " + topLevelSlice, e);
        }

        // Return new logical zipfile with an empty package root
        return new SimpleEntry<>(logicalZipFile, "");
    }

    /**
     * Open a jarfile whose path has one or more {@code '!'} sections, by recursively resolving the path to the left
     * of the last {@code '!'}, then looking up the path to the right of it within the jarfile that produced.
     *
     * @param nestedJarPath
     *            the resolved path of the jarfile
     * @param lastPlingIdx
     *            the index of the last nested jar separator in {@code nestedJarPath}
     * @param log
     *            the log node, or null to skip logging
     * @return if the child path names a directory, the parent {@link LogicalZipFile} paired with the child path as
     *         its package root; otherwise the child path names a nested jarfile, so the {@link LogicalZipFile} for
     *         that nested jarfile, paired with an empty package root
     * @throws IOException
     *             if the jarfile could not be opened
     * @throws InterruptedException
     *             if the thread was interrupted
     */
    private Entry<LogicalZipFile, String> openNestedJar(final String nestedJarPath, final int lastPlingIdx,
            final @Nullable LogNode log) throws IOException, InterruptedException {
        final var parentPath = nestedJarPath.substring(0, lastPlingIdx);
        var childPath = nestedJarPath.substring(lastPlingIdx + 1);
        // "file.jar!/path" -> "file.jar!path"
        childPath = FileUtils.sanitizeEntryPath(childPath, /* removeInitialSlash = */ true,
                /* removeFinalSlash = */ true);

        // Recursively remove one '!' section at a time, back towards the beginning of the URL or file path. At the
        // last frame of recursion, the toplevel jarfile will be reached and returned. The recursion is guaranteed
        // to terminate because parentPath gets one '!'-section shorter with each recursion frame.
        Entry<LogicalZipFile, String> parentLogicalZipFileAndPackageRoot;
        try {
            parentLogicalZipFileAndPackageRoot = nestedPathToLogicalZipFileAndPackageRootMap().get(parentPath, log);
        } catch (final NullSingletonException e) {
            throw new IOException("Could not get parent logical zipfile " + parentPath + " : " + e);
        } catch (final NewInstanceException e) {
            throw new IOException("Could not get parent logical zipfile " + parentPath, e);
        }

        // Only the last item in a '!'-delimited list can be a non-jar path, so the parent must always be a jarfile.
        final var parentLogicalZipFile = parentLogicalZipFileAndPackageRoot.getKey();

        // Look up the child path within the parent zipfile
        var isDirectory = false;
        while (childPath.endsWith("/")) {
            // Child path is definitely a directory, it ends with a slash
            isDirectory = true;
            childPath = childPath.substring(0, childPath.length() - 1);
        }
        // If child path doesn't end with a slash, see if there's a non-directory entry with a name matching the
        // child path (LogicalZipFile discards directory entries ending with a slash when reading the central
        // directory of a zipfile)
        final var childZipEntry = isDirectory ? null : findEntry(parentLogicalZipFile, childPath);
        if (childZipEntry == null && hasEntriesUnderDir(parentLogicalZipFile, childPath)) {
            // If there is no non-directory zipfile entry with a name matching the child path, the child path is a
            // directory if any entries in the zipfile have it as a dir prefix
            isDirectory = true;
        }
        // At this point, either isDirectory is true, or childZipEntry is non-null

        // If path component is a directory, it is a package root
        if (isDirectory) {
            if (!childPath.isEmpty()) {
                // Add directory path to parent jarfile root relative paths set (this has the side effect of adding
                // this parent jarfile root to the set of roots for all references to the parent path)
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
            throw new IOException("Path " + childPath + " does not exist in jarfile " + parentLogicalZipFile);
        }

        // Do not extract nested jar, if nested jar scanning is disabled
        if (!vfsScanSpec.scanNestedJars) {
            throw new IOException("Nested jar scanning is disabled -- skipping nested jar " + nestedJarPath);
        }

        // The child path corresponds to a non-directory zip entry, so it must be a nested jar (since non-jar nested
        // files cannot be used on the classpath)
        return new SimpleEntry<>(openNestedJarEntry(childZipEntry, log), "");
    }

    /**
     * Find a non-directory zip entry with a given name. {@link LogicalZipFile} discards directory entries ending
     * with a slash when it reads the central directory of a zipfile, so only file entries can be matched.
     *
     * <p>
     * N.B. this performs an O(N) search, because the number of classpath elements containing {@code '!'} sections
     * is assumed to be small relative to the total number of entries in all jarfiles (i.e. building a map from
     * entry path to entry for every jarfile would generally be more expensive than performing this linear search,
     * and unless the classpath is enormous, the overall time performance will not tend towards O(N^2)).
     *
     * @param logicalZipFile
     *            the zipfile to search
     * @param entryName
     *            the entry name to search for
     * @return the matching {@link FastZipEntry}, or null if there is no entry with that name
     */
    private static @Nullable FastZipEntry findEntry(final LogicalZipFile logicalZipFile, final String entryName) {
        for (final FastZipEntry entry : logicalZipFile.entries) {
            if (entry.entryName.equals(entryName)) {
                return entry;
            }
        }
        return null;
    }

    /**
     * Test whether any entry of a zipfile is within a given directory, i.e. whether the directory path is a prefix
     * of any entry name.
     *
     * @param logicalZipFile
     *            the zipfile to search
     * @param dirPath
     *            the directory path, without a trailing slash
     * @return true if at least one entry is within the directory
     */
    private static boolean hasEntriesUnderDir(final LogicalZipFile logicalZipFile, final String dirPath) {
        final var dirPathPrefix = dirPath + "/";
        for (final FastZipEntry entry : logicalZipFile.entries) {
            if (entry.entryName.startsWith(dirPathPrefix)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Open a zip entry that contains a nested jarfile as a {@link LogicalZipFile}. The nested jar is mapped as a
     * new {@link ZipFileSlice} if it is stored, or inflated to RAM or to a temporary file if it is deflated, then a
     * new {@link ZipFileSlice} is created over the temporary file or {@link java.nio.ByteBuffer ByteBuffer}.
     *
     * @param zipEntry
     *            the zip entry containing the nested jarfile
     * @param log
     *            the log node, or null to skip logging
     * @return the {@link LogicalZipFile} for the nested jarfile
     * @throws IOException
     *             if the nested jarfile could not be opened
     * @throws InterruptedException
     *             if the thread was interrupted
     */
    private LogicalZipFile openNestedJarEntry(final FastZipEntry zipEntry, final @Nullable LogNode log)
            throws IOException, InterruptedException {
        // Get zip entry as a ZipFileSlice, possibly inflating to disk or RAM
        final ZipFileSlice zipEntrySlice;
        try {
            zipEntrySlice = fastZipEntryToZipFileSliceMap().get(zipEntry, log);
        } catch (final NullSingletonException e) {
            throw new IOException("Could not get child zip entry slice " + zipEntry + " : " + e);
        } catch (final NewInstanceException e) {
            throw new IOException("Could not get child zip entry slice " + zipEntry, e);
        }

        final var zipSliceLog = log == null ? null
                : log.log("Getting zipfile slice " + zipEntrySlice + " for nested jar " + zipEntry.entryName);

        // Get or create a new LogicalZipFile for the child zipfile
        try {
            return zipFileSliceToLogicalZipFileMap().get(zipEntrySlice, zipSliceLog);
        } catch (final NullSingletonException e) {
            throw new IOException("Could not get child logical zipfile " + zipEntrySlice + " : " + e);
        } catch (final NewInstanceException e) {
            throw new IOException("Could not get child logical zipfile " + zipEntrySlice, e);
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Remove any temporary files that extracted nested jars were spilled to.
     *
     * <p>
     * If no temporary files were created -- which is the case whenever no nested jars were encountered, i.e. for an
     * ordinary jar or directory classpath -- this does nothing, so that the handler remains fully usable. Closing
     * the handler would close every open {@link Slice} and the inflater {@link Recycler}, leaving it unable to read
     * any entry.
     *
     * <p>
     * If temporary files <i>were</i> created, they back memory-mapped slices of the extracted nested jars, so they
     * cannot be deleted without closing those slices first -- the whole handler is still torn down in that case.
     *
     * @param log
     *            the log node, or null to skip logging
     * @return true if the handler was closed (i.e. if temporary files existed and had to be removed)
     */
    // #916
    public boolean removeTemporaryFiles(final @Nullable LogNode log) {
        if (!scanResources.hasTempFiles()) {
            // No temp files were created, so there is nothing to remove, and no need to close anything
            return false;
        }
        close(log);
        return true;
    }

    /**
     * Close zipfiles, modules, and recyclers, and delete temporary files.
     *
     * @param log
     *            The log.
     */
    public void close(final @Nullable LogNode log) {
        if (scanResources.beginClose()) {
            // Drop the zipfile caches first, so that nothing can hand out a slice of a zipfile that is about to be
            // closed, then close the resources the caches were backed by
            final var logicalZipFileMap = zipFileSliceToLogicalZipFileMap;
            if (logicalZipFileMap != null) {
                logicalZipFileMap.clear();
                zipFileSliceToLogicalZipFileMap = null;
            }
            final var nestedPathMap = nestedPathToLogicalZipFileAndPackageRootMap;
            if (nestedPathMap != null) {
                nestedPathMap.clear();
                nestedPathToLogicalZipFileAndPackageRootMap = null;
            }
            final var physicalZipFileMap = canonicalFileToPhysicalZipFileMap;
            if (physicalZipFileMap != null) {
                physicalZipFileMap.clear();
                canonicalFileToPhysicalZipFileMap = null;
            }
            final var zipFileSliceMap = fastZipEntryToZipFileSliceMap;
            if (zipFileSliceMap != null) {
                zipFileSliceMap.clear();
                fastZipEntryToZipFileSliceMap = null;
            }
            // Close the module readers, the open slices and the inflater recycler, then delete the temporary files
            scanResources.close(log);
        }
    }
}
