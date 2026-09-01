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
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.util.Objects;

import io.github.classgraph.base.LogNode;
import io.github.classgraph.base.internal.path.FastPathResolver;
import io.github.classgraph.base.internal.path.FileUtils;
import io.github.classgraph.vfs.internal.VfsSession;
import io.github.classgraph.vfs.internal.slice.PathSlice;
import io.github.classgraph.vfs.internal.slice.Slice;
import org.jspecify.annotations.Nullable;

/**
 * A physical zipfile, backed by a {@link File} (which may be mmap'd using a {@link FileChannel}), a {@link Path},
 * or a byte array in RAM.
 */
class PhysicalZipFile {
    /** The {@link Path} backing this {@link PhysicalZipFile}, if any. */
    private @Nullable Path path;

    /** The {@link File} backing this {@link PhysicalZipFile}, if any. */
    private @Nullable File file;

    /** The path to the zipfile. */
    private final String pathStr;

    /** The {@link Slice} for the zipfile. */
    final Slice slice;

    /** The session that owns what was opened to read this zipfile. */
    private final VfsSession session;

    /** The lock guarding {@link #logicalZipFile} and {@link #logicalZipFileFailure}. */
    private final Object logicalZipFileLock = new Object();

    /** The zipfile spanning the whole of this physical zipfile, or null if it has not been read yet. */
    private @Nullable LogicalZipFile logicalZipFile;

    /** The failure that stopped the central directory from being read, or null if it has not been tried or read. */
    private @Nullable Throwable logicalZipFileFailure;

    /**
     * Construct a {@link PhysicalZipFile} from a file on disk.
     *
     * @param file
     *            the file
     * @param session
     *            the session that owns what is opened
     * @param log
     *            the log node, or null to skip logging
     * @throws IOException
     *             if an I/O exception occurs.
     */
    PhysicalZipFile(final File file, final VfsSession session, final @Nullable LogNode log) throws IOException {
        this.file = file;
        this.session = session;
        this.pathStr = FastPathResolver.resolve(FileUtils.currDirPath(), file.getPath());
        this.slice = new PathSlice(file, session, log);
    }

    /**
     * Construct a {@link PhysicalZipFile} from a {@link Path}.
     *
     * @param path
     *            the path
     * @param session
     *            the session that owns what is opened
     * @param log
     *            the log node, or null to skip logging
     * @throws IOException
     *             if an I/O exception occurs.
     */
    PhysicalZipFile(final Path path, final VfsSession session, final @Nullable LogNode log) throws IOException {
        this.path = path;
        this.session = session;
        this.pathStr = FileUtils.pathStr(path);
        this.slice = new PathSlice(path, session, log);
    }

    /**
     * Construct a {@link PhysicalZipFile} by reading from the {@link InputStream} to an array in RAM, or spill to
     * disk if the {@link InputStream} is too long.
     *
     * @param inputStream
     *            the input stream. Read to its end, but not closed -- the caller retains ownership of it.
     * @param inputStreamLengthHint
     *            The number of bytes to read in inputStream, or -1 if unknown.
     * @param pathStr
     *            the source URL the InputStream was opened from, or the zip entry path of this entry in the parent
     *            zipfile
     * @param session
     *            the session that owns what is opened
     * @param log
     *            the log node, or null to skip logging
     * @throws IOException
     *             if an I/O exception occurs.
     */
    PhysicalZipFile(final InputStream inputStream, final long inputStreamLengthHint, final String pathStr,
            final VfsSession session, final @Nullable LogNode log) throws IOException {
        this.pathStr = pathStr;
        this.session = session;
        // Try downloading the InputStream to a byte array. If this succeeds, this will result in an ArraySlice. If
        // it fails, the InputStream will be spilled to disk, resulting in a PathSlice over the temporary file.
        this.slice = Slice.fromInputStream(inputStream, /* tempFileBaseName = */ pathStr, inputStreamLengthHint,
                session, log);
        this.file = this.slice instanceof final PathSlice pathSlice ? pathSlice.getFile() : null;
    }

    /**
     * Get the {@link LogicalZipFile} spanning the whole of this physical zipfile, reading its central directory if
     * this is the first call. Every caller that reaches the same physical zipfile is handed the same instance, so
     * the central directory is only read once, and a caller that arrives while another thread is still reading it
     * blocks until that read finishes. A failed read is recorded and rethrown rather than tried again, since a
     * zipfile whose central directory could not be read once will not read any better on a second attempt.
     *
     * @param log
     *            the log node, or null to skip logging
     * @return the {@link LogicalZipFile} spanning the whole of this physical zipfile.
     * @throws IOException
     *             if the central directory could not be read, or the session has been closed.
     * @throws InterruptedException
     *             if the thread was interrupted.
     */
    LogicalZipFile getLogicalZipFile(final @Nullable LogNode log) throws IOException, InterruptedException {
        // The zipfile is only valid while the session is open, so a lookup is turned away once it has been closed,
        // rather than reading a central directory that nothing would ever release again
        if (session.isClosed()) {
            throw new IOException("Already closed");
        }
        synchronized (logicalZipFileLock) {
            if (logicalZipFileFailure != null) {
                throw new IOException(
                        "Could not read the central directory of " + pathStr + " : " + logicalZipFileFailure,
                        logicalZipFileFailure);
            }
            var zipFile = logicalZipFile;
            if (zipFile == null) {
                try {
                    zipFile = new LogicalZipFile(new ZipFileSlice(this), session, log,
                            session.vfsSpec.isMultiReleaseVersionsEnabled());
                } catch (final IOException | RuntimeException | Error e) {
                    logicalZipFileFailure = e;
                    throw e;
                }
                logicalZipFile = zipFile;
            }
            return zipFile;
        }
    }

    /**
     * Release this zipfile, because nothing will ever be able to reach it: the operation that opened it failed.
     * Without this, the file handle and memory mapping behind it would be held until the session is closed, even
     * though nothing can read through them.
     *
     * @param failure
     *            the failure that stopped this zipfile from being handed over, for any failure to release it to be
     *            recorded within.
     */
    void releaseUnreachable(final Throwable failure) {
        try {
            slice.close();
        } catch (final IOException | RuntimeException | Error e) {
            failure.addSuppressed(e);
        }
    }

    /**
     * Get the {@link Path} for the outermost jar file of this PhysicalZipFile.
     *
     * @return the {@link Path} for the outermost jar file of this PhysicalZipFile, or null if this file was
     *         downloaded from a URL directly to RAM, or is backed by a {@link File}.
     */
    public @Nullable Path getPath() {
        return path;
    }

    /**
     * Get the {@link File} for the outermost jar file of this PhysicalZipFile.
     *
     * @return the {@link File} for the outermost jar file of this PhysicalZipFile, or null if this file was
     *         downloaded from a URL directly to RAM, or is backed by a {@link Path}.
     */
    public @Nullable File getFile() {
        return file;
    }

    /**
     * Get the path for this PhysicalZipFile, which is the file path, if it is file-backed, or a compound nested jar
     * path, if it is memory-backed.
     *
     * @return the path for this PhysicalZipFile, which is the file path, if it is file-backed, or a compound nested
     *         jar path, if it is memory-backed.
     */
    public String getPathString() {
        return pathStr;
    }

    /**
     * Get the length of the mapped file, or the initial remaining bytes in the wrapped ByteBuffer if a buffer was
     * wrapped.
     *
     * @return the length of the mapped file
     */
    public long length() {
        return slice.sliceLength;
    }

    @Override
    public int hashCode() {
        // (Use pathStr for identity, not file -- file is null for Path-backed zipfiles, and for nested jars that
        // were extracted to RAM rather than spilled to disk, so it does not identify a zipfile on its own)
        return Objects.hashCode(pathStr);
    }

    @Override
    public boolean equals(final @Nullable Object o) {
        if (o == this) {
            return true;
        }
        if (!(o instanceof final PhysicalZipFile other)) {
            return false;
        }
        return Objects.equals(pathStr, other.pathStr);
    }

    @Override
    public String toString() {
        return pathStr;
    }
}
