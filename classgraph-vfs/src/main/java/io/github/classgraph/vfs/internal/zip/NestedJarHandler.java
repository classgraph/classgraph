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
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.util.AbstractMap.SimpleEntry;
import java.util.Locale;
import java.util.Map.Entry;

import io.github.classgraph.base.LogNode;
import io.github.classgraph.base.internal.concurrency.SingletonMap.NewInstanceException;
import io.github.classgraph.base.internal.concurrency.SingletonMap.NullSingletonException;
import io.github.classgraph.base.internal.concurrency.SingletonMap;
import io.github.classgraph.base.internal.path.FastPathResolver;
import io.github.classgraph.base.internal.path.FileUtils;
import io.github.classgraph.base.internal.path.PathSyntax;
import io.github.classgraph.vfs.internal.VfsSession;
import org.jspecify.annotations.Nullable;

/**
 * Resolve a classpath element path, which may name a jarfile nested within one or more enclosing jarfiles, to the
 * {@link LogicalZipFile} for the innermost jarfile and the package root within it, opening and caching each zipfile
 * along the way.
 *
 * <p>
 * Everything this opens is registered with the {@link VfsSession} it was given, which owns those resources and
 * releases them. This handler owns only the caches below, which are dropped by {@link #dropCaches()} as the first
 * step of the session's teardown.
 */
public class NestedJarHandler {
    /** The session that the zipfiles opened by this handler are registered with. */
    private final VfsSession session;

    /**
     * A singleton map from a zipfile's {@link File} to the {@link PhysicalZipFile} for that file, used to ensure
     * that the {@link RandomAccessFile} and {@link FileChannel} for any given zipfile is opened only once. Dropped
     * by {@link #dropCaches()}.
     */
    private final SingletonMap<File, PhysicalZipFile, IOException> canonicalFileToPhysicalZipFileMap;

    /**
     * A singleton map from a zipfile's {@link Path} to the {@link PhysicalZipFile} for that path, used to ensure
     * that a zipfile in a filesystem that has no {@link File} representation, such as a zipfile within a zipfile
     * mounted as a filesystem, is opened only once. Dropped by {@link #dropCaches()}.
     */
    private final SingletonMap<Path, PhysicalZipFile, IOException> pathToPhysicalZipFileMap;

    /**
     * A singleton map from a {@link FastZipEntry} to the {@link ZipFileSlice} wrapping either the zip entry data,
     * if the entry is stored, or a ByteBuffer, if the zip entry was inflated to memory, or a physical file on disk
     * if the zip entry was inflated to a temporary file. Dropped by {@link #dropCaches()}.
     */
    private final SingletonMap<FastZipEntry, ZipFileSlice, IOException> fastZipEntryToZipFileSliceMap;

    /**
     * A singleton map from a {@link ZipFileSlice} to the {@link LogicalZipFile} for that slice. Dropped by
     * {@link #dropCaches()}.
     */
    private final SingletonMap<ZipFileSlice, LogicalZipFile, IOException> zipFileSliceToLogicalZipFileMap;

    /**
     * A singleton map from nested jarfile path to a tuple of the logical zipfile for the path, and the package root
     * within the logical zipfile. Dropped by {@link #dropCaches()}.
     */
    private final SingletonMap<String, Entry<LogicalZipFile, String>, IOException> //
    nestedPathToLogicalZipFileAndPackageRootMap;

    /**
     * A handler for nested jars.
     *
     * @param session
     *            the session to register the opened zipfiles with.
     */
    public NestedJarHandler(final VfsSession session) {
        this.session = session;

        // Every cache is only valid while the session is open, so each is handed the session's own closed flag and
        // turns a lookup away itself once the session is closed, rather than each caller having to ask first
        final var closed = session.closedFlag();

        canonicalFileToPhysicalZipFileMap = new SingletonMap<>(closed) {
            @Override
            public PhysicalZipFile newInstance(final File canonicalFile, final @Nullable LogNode log)
                    throws IOException {
                return new PhysicalZipFile(canonicalFile, session, log);
            }
        };

        pathToPhysicalZipFileMap = new SingletonMap<>(closed) {
            @Override
            public PhysicalZipFile newInstance(final Path path, final @Nullable LogNode log) throws IOException {
                return new PhysicalZipFile(path, session, log);
            }
        };

        fastZipEntryToZipFileSliceMap = new SingletonMap<>(closed) {
            @Override
            public ZipFileSlice newInstance(final FastZipEntry childZipEntry, final @Nullable LogNode log)
                    throws IOException, InterruptedException {
                return sliceOfNestedZipEntry(childZipEntry, log);
            }
        };

        zipFileSliceToLogicalZipFileMap = new SingletonMap<>(closed) {
            @Override
            public LogicalZipFile newInstance(final ZipFileSlice zipFileSlice, final @Nullable LogNode log)
                    throws IOException, InterruptedException {
                // Read the central directory for the zipfile
                return new LogicalZipFile(zipFileSlice, session, log,
                        session.vfsSpec.isMultiReleaseVersionsEnabled());
            }
        };

        nestedPathToLogicalZipFileAndPackageRootMap = new SingletonMap<>(closed) {
            @Override
            public Entry<LogicalZipFile, String> newInstance(final String nestedJarPathRaw,
                    final @Nullable LogNode log) throws IOException, InterruptedException {
                final var nestedJarPath = FastPathResolver.resolve(nestedJarPathRaw);
                // A '!' is only a nested jar separator if the outermost path component names an existing jarfile --
                // it is otherwise a legal filename character (#903)
                final var lastPlingIdx = PathSyntax.lastIndexOfNestedJarSeparator(nestedJarPath);
                return lastPlingIdx < 0 ? openTopLevelJar(nestedJarPath, log)
                        : openNestedJar(nestedJarPath, lastPlingIdx, log);
            }
        };
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Get the map from nested jarfile path to the logical zipfile for the path and the package root within that
     * zipfile. A lookup in the map is turned away once the session has been closed.
     *
     * @return the map
     */
    public SingletonMap<String, Entry<LogicalZipFile, String>, IOException> //
            nestedPathToLogicalZipFileAndPackageRootMap() {
        return nestedPathToLogicalZipFileAndPackageRootMap;
    }

    /**
     * Wrap a zip entry holding a nested zipfile in a {@link ZipFileSlice}, inflating the entry first if it is
     * deflated.
     *
     * @param childZipEntry
     *            the zip entry holding the nested zipfile.
     * @param log
     *            the log.
     * @return the slice wrapping the nested zipfile.
     * @throws IOException
     *             if the entry could not be read.
     * @throws InterruptedException
     *             if the thread was interrupted.
     */
    private ZipFileSlice sliceOfNestedZipEntry(final FastZipEntry childZipEntry, final @Nullable LogNode log)
            throws IOException, InterruptedException {
        if (!childZipEntry.isDeflated) {
            // The child zip entry is a stored nested zipfile -- wrap it in a new ZipFileSlice. Hopefully nested
            // zipfiles are stored, not deflated, as this is the fast path.
            return new ZipFileSlice(childZipEntry);
        }

        // If child entry is deflated i.e. (for a deflated nested zipfile), must inflate the contents of the
        // entry before its central directory can be read (most of the time nested zipfiles are stored, not
        // deflated, so this should be rare)
        if (log != null) {
            log.log("Inflating nested zip entry: " + childZipEntry + " ; uncompressed size: "
                    + childZipEntry.uncompressedSize);
        }

        // Read the InputStream for the child zip entry to a RAM buffer, or spill to disk if it's too large.
        // (The stream is opened here, so it is closed here -- PhysicalZipFile does not close what it reads.)
        final PhysicalZipFile physicalZipFile;
        try (InputStream childZipEntryInputStream = childZipEntry.getSlice().open()) {
            // The uncompressed size is a length rather than a hint that may have to fit in an array: an entry too
            // long to buffer in RAM is spilled straight to disk, which needs the real length rather than -1
            physicalZipFile = new PhysicalZipFile(childZipEntryInputStream, childZipEntry.uncompressedSize,
                    childZipEntry.entryName, session, log);
        }

        // Create a new logical slice of the extracted inner zipfile
        try {
            return new ZipFileSlice(physicalZipFile, childZipEntry);
        } catch (final RuntimeException | Error e) {
            // The cache records the failure rather than inflating the entry again, so nothing would ever
            // reach what was just inflated
            releaseUnreachable(physicalZipFile, e);
            throw e;
        }
    }

    /**
     * Open a jarfile named by a {@link Path}. Use this only for a {@link Path} that is not in the default
     * filesystem, since a path in the default filesystem can name a jarfile nested within another jarfile, which
     * {@link #nestedPathToLogicalZipFileAndPackageRootMap()} resolves and this method does not.
     *
     * @param path
     *            the path of the jarfile.
     * @param log
     *            the log node, or null to skip logging.
     * @return the {@link LogicalZipFile} for the jarfile.
     * @throws IOException
     *             if the jarfile could not be opened.
     * @throws InterruptedException
     *             if the thread was interrupted.
     */
    public LogicalZipFile openJarFromPath(final Path path, final @Nullable LogNode log)
            throws IOException, InterruptedException {
        try {
            final var physicalZipFile = pathToPhysicalZipFileMap.get(path, log);
            return zipFileSliceToLogicalZipFileMap.get(new ZipFileSlice(physicalZipFile), log);
        } catch (final NullSingletonException | NewInstanceException e) {
            // Chain the cause, as well as naming it in the message -- otherwise the reason the jarfile could not be
            // opened is not reachable from the stack trace
            final var cause = e.getCause() == null ? e : e.getCause();
            throw new IOException("Could not open " + path + " : " + cause, cause);
        }
    }

    /**
     * Open a jarfile read from an {@link InputStream}. The stream is read to an array in RAM, or spilled to a
     * temporary file if it is longer than the configured maximum, since a zipfile's central directory is at the end
     * of the file and so cannot be reached by reading forwards.
     *
     * <p>
     * The result is not cached, since each call reads a different stream.
     *
     * @param inputStream
     *            the stream to read the jarfile from. The caller retains ownership of the stream, and this method
     *            does not close it.
     * @param inputStreamLengthHint
     *            the number of bytes to read from {@code inputStream}, or -1 if unknown.
     * @param name
     *            a name for the jarfile, used in log messages and in the paths of its entries.
     * @param log
     *            the log node, or null to skip logging.
     * @return the {@link LogicalZipFile} for the jarfile.
     * @throws IOException
     *             if the jarfile could not be read.
     * @throws InterruptedException
     *             if the thread was interrupted.
     */
    public LogicalZipFile openJarFromInputStream(final InputStream inputStream, final long inputStreamLengthHint,
            final String name, final @Nullable LogNode log) throws IOException, InterruptedException {
        final var physicalZipFile = new PhysicalZipFile(inputStream, inputStreamLengthHint, name, session, log);
        try {
            // The zipfile slice cache cannot be used here: two PhysicalZipFile instances compare equal if they have
            // the same path, so two different streams read under the same name would be treated as the same jarfile
            return new LogicalZipFile(new ZipFileSlice(physicalZipFile), session, log,
                    session.vfsSpec.isMultiReleaseVersionsEnabled());
        } catch (final IOException | RuntimeException | Error e) {
            // Nothing is cached here, so the caller can only try again by reading the stream again, and nothing
            // would ever reach what was just read
            releaseUnreachable(physicalZipFile, e);
            throw e;
        }
    }

    /**
     * Release a {@link PhysicalZipFile} that nothing will ever be able to reach, because the operation that opened
     * it failed. Without this, the file handle and memory mapping behind it would be held until the session is
     * closed, even though nothing can read through them.
     *
     * @param physicalZipFile
     *            the zipfile to release.
     * @param failure
     *            the failure that stopped the zipfile from being handed over, for any failure to release it to be
     *            recorded within.
     */
    private static void releaseUnreachable(final PhysicalZipFile physicalZipFile, final Throwable failure) {
        try {
            physicalZipFile.slice.close();
        } catch (final IOException | RuntimeException | Error e) {
            failure.addSuppressed(e);
        }
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
        final var isURL = PathSyntax.hasURLScheme(nestedJarPath);
        final PhysicalZipFile physicalZipFile;
        // A downloaded jarfile is not cached, so this method owns it until it has been handed over to the logical
        // zipfile cache, and has to release it if it never gets there. A jarfile opened from a local path comes
        // from a cache that keeps it, and another path that resolves to the same file will be handed the same
        // instance, so it must not be released here.
        final boolean ownedUntilHandedOver;
        if (isURL) {
            // URL schemes are case-insensitive, and are denied in lowercase, so the scheme has to be lowercased
            // before it is looked up -- otherwise "HTTP://host/x.jar" is fetched even though "http" was denied
            final var scheme = nestedJarPath.substring(0, nestedJarPath.indexOf(':')).toLowerCase(Locale.ROOT);
            // Whatever the JVM can open is opened, so only a scheme the caller took away is refused here. A
            // scheme that nothing has registered a handler for is left to the download below, which reports the
            // JVM's own reason for not being able to open it. ("file:" and "jar:" never reach here --
            // FastPathResolver.resolve() has already stripped them)
            if (session.vfsSpec.getDeniedURLSchemes().contains(scheme)) {
                throw new IOException("Fetching a jarfile over \"" + scheme
                        + ":\" is not allowed -- cannot read classpath element: " + nestedJarPath);
            }

            // Download jar from URL to a ByteBuffer in RAM, or to a temp file on disk
            physicalZipFile = JarURLDownloader.downloadJarFromURL(nestedJarPath, session, log);
            ownedUntilHandedOver = true;

        } else {
            // Jarfile should be a local file -- wrap in a PhysicalZipFile instance
            try {
                // Get canonical file, so that the same jarfile reached through two different paths is opened once
                final var canonicalFile = FileUtils.canonicalize(new File(nestedJarPath));
                // Get or create a PhysicalZipFile instance for the canonical file
                physicalZipFile = canonicalFileToPhysicalZipFileMap.get(canonicalFile, log);
                ownedUntilHandedOver = false;
            } catch (final NullSingletonException | NewInstanceException e) {
                // If getting PhysicalZipFile failed, re-wrap in IOException, chaining the cause as well as naming
                // it in the message, so that the reason is reachable from the stack trace
                final var cause = e.getCause() == null ? e : e.getCause();
                throw new IOException("Could not get PhysicalZipFile for path " + nestedJarPath + " : " + cause,
                        cause);
            } catch (final SecurityException e) {
                // getCanonicalFile() failed (it may have also failed with IOException)
                throw new IOException("Path component " + nestedJarPath + " could not be canonicalized: " + e, e);
            }
        }

        // Create a new logical slice of the whole physical zipfile
        final LogicalZipFile logicalZipFile;
        try {
            final var topLevelSlice = new ZipFileSlice(physicalZipFile);
            try {
                logicalZipFile = zipFileSliceToLogicalZipFileMap.get(topLevelSlice, log);
            } catch (final NullSingletonException | NewInstanceException e) {
                // Chain the cause, as well as naming it in the message, so that the reason is reachable from the
                // stack trace
                final var cause = e.getCause() == null ? e : e.getCause();
                throw new IOException("Could not get toplevel slice " + topLevelSlice + " : " + cause, cause);
            }
        } catch (final IOException | InterruptedException | RuntimeException | Error e) {
            if (ownedUntilHandedOver) {
                // The cache of resolved nested paths records the failure rather than downloading the jarfile
                // again, so nothing would ever reach what was just downloaded
                releaseUnreachable(physicalZipFile, e);
            }
            throw e;
        }

        // Return new logical zipfile with an empty package root
        return new SimpleEntry<>(logicalZipFile, "");
    }

    /**
     * Open a jarfile whose path has one or more {@code '!'} sections, by recursively resolving the path to the left
     * of the last {@code '!'}, then looking up the path to the right of it within the jarfile that resolving the
     * left-hand path produced.
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
        // childPath begins with the '/' of the "!/" separator, so strip it, along with any trailing '/'
        // and any "." or ".." segments, to leave the entry name relative to the root of the parent jarfile
        childPath = PathSyntax.sanitizeEntryPath(childPath, /* removeInitialSlash = */ true,
                /* removeFinalSlash = */ true);

        // Recursively remove one '!' section at a time, back towards the beginning of the URL or file path. At the
        // last frame of recursion, the toplevel jarfile will be reached and returned. The recursion is guaranteed
        // to terminate because parentPath gets one '!'-section shorter with each recursion frame.
        Entry<LogicalZipFile, String> parentLogicalZipFileAndPackageRoot;
        try {
            parentLogicalZipFileAndPackageRoot = nestedPathToLogicalZipFileAndPackageRootMap.get(parentPath, log);
        } catch (final NullSingletonException | NewInstanceException e) {
            // Chain the cause, as well as naming it in the message, so that the reason is reachable from the stack
            // trace
            final var cause = e.getCause() == null ? e : e.getCause();
            throw new IOException("Could not get parent logical zipfile " + parentPath + " : " + cause, cause);
        }

        // Only the last item in a '!'-delimited list can be a non-jar path, so the parent must always be a jarfile.
        final var parentLogicalZipFile = parentLogicalZipFileAndPackageRoot.getKey();

        // Look up the child path within the parent zipfile. Sanitizing the path stripped any trailing slash, so
        // what the child path names is decided by what the parent jarfile holds, not by how the path was written --
        // which is what a path such as "outer.jar!/lib/inner.jar/" needs, since the trailing slash there is a
        // mistake rather than a statement that the nested jarfile is a directory.
        var isDirectory = false;
        // See if there's a non-directory entry with a name matching the child path (LogicalZipFile discards
        // directory entries ending with a slash when reading the central directory of a zipfile)
        final var childZipEntry = findEntry(parentLogicalZipFile, childPath);
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
        if (!session.vfsSpec.isNestedJarsEnabled()) {
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
            zipEntrySlice = fastZipEntryToZipFileSliceMap.get(zipEntry, log);
        } catch (final NullSingletonException | NewInstanceException e) {
            // Chain the cause, as well as naming it in the message, so that the reason is reachable from the stack
            // trace
            final var cause = e.getCause() == null ? e : e.getCause();
            throw new IOException("Could not get child zip entry slice " + zipEntry + " : " + cause, cause);
        }

        final var zipSliceLog = log == null ? null
                : log.log("Getting zipfile slice " + zipEntrySlice + " for nested jar " + zipEntry.entryName);

        // Get or create a new LogicalZipFile for the child zipfile
        try {
            return zipFileSliceToLogicalZipFileMap.get(zipEntrySlice, zipSliceLog);
        } catch (final NullSingletonException | NewInstanceException e) {
            // Chain the cause, as well as naming it in the message, so that the reason is reachable from the stack
            // trace
            final var cause = e.getCause() == null ? e : e.getCause();
            throw new IOException("Could not get child logical zipfile " + zipEntrySlice + " : " + cause, cause);
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Drop every cached zipfile, as the first step of tearing down the session: the caller marks the session closed
     * first, so that a lookup in one of them turns away anything that would otherwise hand out a slice of a zipfile
     * that is about to be closed, then calls this, and only then releases the resources the zipfiles were backed
     * by.
     *
     * <p>
     * This releases nothing itself -- the file handles, memory mappings and temporary files behind the cached
     * zipfiles belong to the {@link VfsSession}, and are released by {@link VfsSession#close(LogNode)}.
     */
    public void dropCaches() {
        zipFileSliceToLogicalZipFileMap.clear();
        nestedPathToLogicalZipFileAndPackageRootMap.clear();
        canonicalFileToPhysicalZipFileMap.clear();
        pathToPhysicalZipFileMap.clear();
        fastZipEntryToZipFileSliceMap.clear();
    }
}
