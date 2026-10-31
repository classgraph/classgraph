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
package io.github.classgraph.vfs;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

import org.jetbrains.annotations.Unmodifiable;
import org.jspecify.annotations.Nullable;

import io.github.classgraph.base.internal.path.FileUtils;
import io.github.classgraph.vfs.internal.slice.PathSlice;
import io.github.classgraph.vfs.internal.slice.Slice;

/** A file entry in a directory root. */
final class DirEntry extends VfsEntry {
    /**
     * The filesystem path of the file, or {@code null} until {@link #getNioPath()} builds it, for an entry that was
     * looked up without building it. A file is read without it, through {@link #dirHandle}. (Threads that build it
     * at once build equal paths, so it needs no lock.)
     */
    private @Nullable Path path;

    /** The entry path relative to the root directory. */
    private final String name;

    /** Pre-fetched file attributes from directory listing, or {@code null} if not yet read. */
    private final @Nullable BasicFileAttributes attributes;

    /**
     * The handle on the file's directory that its attributes were read through, or {@code null} if they were not
     * read through a handle. The file is opened through it, saving a second lookup of the handle.
     */
    private final DirRoot.@Nullable DirHandle dirHandle;

    /** The name of the file within its directory, if {@link #dirHandle} is not {@code null}. */
    private final @Nullable Path fileName;

    /** Cached file length in bytes, or {@code -1} if not yet read. */
    private volatile long length = -1L;

    /**
     * Constructor.
     *
     * @param root
     *            the directory root containing this entry.
     * @param path
     *            the file path.
     * @param name
     *            the entry path relative to the root directory.
     * @param attributes
     *            the file's attributes if already read, or {@code null}.
     */
    DirEntry(final DirRoot root, final Path path, final String name,
            final @Nullable BasicFileAttributes attributes) {
        this(root, path, name, attributes, null, null);
    }

    /**
     * Constructor.
     *
     * @param root
     *            the directory root containing this entry.
     * @param path
     *            the file path, or {@code null} to build it from the name if it is needed, which
     *            {@link DirRoot#resolveCanonicalName(String)} must be able to do.
     * @param name
     *            the entry path relative to the root directory.
     * @param attributes
     *            the file's attributes if already read, or {@code null}.
     * @param dirHandle
     *            the handle on the file's directory that the attributes were read through, or {@code null}.
     * @param fileName
     *            the name of the file within its directory, if {@code dirHandle} is not {@code null}.
     */
    DirEntry(final DirRoot root, final @Nullable Path path, final String name,
            final @Nullable BasicFileAttributes attributes, final DirRoot.@Nullable DirHandle dirHandle,
            final @Nullable Path fileName) {
        super(root);
        this.path = path;
        this.name = name;
        this.attributes = attributes;
        this.dirHandle = dirHandle;
        this.fileName = fileName;
    }

    // -------------------------------------------------------------------------------------------------------------

    @Override
    public String getPathFromRoot() {
        return name;
    }

    @Override
    public String getPath() {
        // The path of a root at the top of the filesystem, "/" or a drive root such as "C:/", already ends in '/'
        final var rootPath = getRoot().getPath();
        return rootPath.endsWith("/") ? rootPath + name : rootPath + "/" + name;
    }

    @Override
    public Path getNioPath() {
        var pathCurr = path;
        if (pathCurr == null) {
            pathCurr = ((DirRoot) getRoot()).resolveCanonicalName(name);
            path = pathCurr;
        }
        return pathCurr;
    }

    @Override
    public long getLength() {
        final var attributesCurr = attributes;
        if (attributesCurr != null) {
            try {
                return attributesCurr.size();
            } catch (final UnsupportedOperationException _) {
                // The fallback attributes of FileUtils#readAttributes do not throw this, but the attributes of a
                // directory on another filesystem come from that filesystem's own provider, which may
                return -1L;
            }
        }
        // Reading the size of a file costs a system call, so it is only read if it is asked for, and is then kept
        var lengthCurr = length;
        if (lengthCurr < 0L) {
            try {
                lengthCurr = Files.size(getNioPath());
            } catch (final IOException _) {
                return -1L;
            }
            length = lengthCurr;
        }
        return lengthCurr;
    }

    @Override
    public long getLastModifiedMillis() {
        final var attributesCurr = attributes;
        if (attributesCurr != null) {
            try {
                return attributesCurr.lastModifiedTime().toMillis();
            } catch (final UnsupportedOperationException _) {
                // As in getLength(), another filesystem's provider may throw this
                return 0L;
            }
        }
        try {
            return Files.getLastModifiedTime(getNioPath()).toMillis();
        } catch (final IOException _) {
            return 0L;
        }
    }

    @Override
    boolean isReadable() {
        // A walk tells the files from the subdirectories with the metadata it already reads, and does not check
        // whether each file can be read, so a file that was listed may still be unreadable
        return FileUtils.canReadAndIsFile(getNioPath());
    }

    @Override
    public @Nullable @Unmodifiable Set<PosixFilePermission> getPosixFilePermissions() {
        // On a POSIX filesystem, the attributes read while listing the directory are already POSIX attributes, so
        // the permissions come for free
        if (attributes instanceof final PosixFileAttributes posixAttributes) {
            return unmodifiableEnumSet(posixAttributes.permissions());
        }
        try {
            return unmodifiableEnumSet(Files.readAttributes(getNioPath(), PosixFileAttributes.class).permissions());
        } catch (final IOException | UnsupportedOperationException _) {
            // The filesystem does not record POSIX permissions (e.g. on Windows)
            return null;
        }
    }

    /**
     * Copy a set of {@link PosixFilePermission} values into an unmodifiable {@link EnumSet} in declaration order.
     *
     * @param permissions
     *            the permissions to copy.
     * @return an unmodifiable {@link Set} of permissions in declaration order.
     */
    private static Set<PosixFilePermission> unmodifiableEnumSet(final Set<PosixFilePermission> permissions) {
        final Set<PosixFilePermission> enumSet = EnumSet.noneOf(PosixFilePermission.class);
        enumSet.addAll(permissions);
        return Collections.unmodifiableSet(enumSet);
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Open a {@link PathSlice} for this file.
     *
     * @return the opened {@link PathSlice}.
     * @throws IOException
     *             if the file cannot be opened, or if the root or {@link Vfs} has been closed.
     */
    private PathSlice openSlice() throws IOException {
        final var root = checkRootNotClosed();
        return PathSlice.forDirEntry(getNioPath(), root.openFile(name, path, dirHandle, fileName), root.getVfs());
    }

    /**
     * Throw {@link IOException} if the root or its {@link Vfs} has been closed.
     *
     * @return the root.
     * @throws IOException
     *             if the root or its {@link Vfs} has been closed.
     */
    private DirRoot checkRootNotClosed() throws IOException {
        final var root = (DirRoot) getRoot();
        if (root.isClosed()) {
            // The path is only needed for the message, so it is only built when the check fails
            root.checkNotClosed(getPath());
        }
        return root;
    }

    @Override
    InputStream openUnchecked() throws IOException {
        final var slice = openSlice();
        // The slice holds the open file channel, so closing the stream has to close the slice too
        return slice.open(slice);
    }

    @Override
    public CloseableByteBuffer read() throws IOException {
        final var root = getRoot();
        // A file in a directory is not memory-mapped, so its content is copied into an array whichever way it is
        // read, and the file can be closed as soon as it has been copied
        final var buffer = new CloseableByteBuffer(ByteBuffer.wrap(load()).asReadOnlyBuffer(), () -> {
        }, root);
        if (!root.trackOpenHandle(buffer)) {
            buffer.close();
            throw new IOException("Cannot read " + getPath() + " after the root has been closed");
        }
        return buffer;
    }

    @Override
    RandomAccessContent openRandomAccess() throws IOException {
        final var slice = openSlice();
        try {
            // The slice holds the open file channel that the reader reads, so it is closed when the caller has
            // finished with the reader
            return new RandomAccessContent(slice.randomAccessReader(), slice::close);
        } catch (final IOException | RuntimeException | Error e) {
            // The caller never sees the reader if this throws, so nothing else can close the file channel
            slice.close();
            throw e;
        }
    }

    @Override
    public byte[] load() throws IOException {
        final var root = checkRootNotClosed();
        final var lengthAtLookup = attributes == null ? -1L : getLength();
        if (lengthAtLookup >= 0L && lengthAtLookup <= Slice.MAX_BUFFER_SIZE) {
            // The length read when the file was looked up is the length of the array, which saves reading the length
            // again after opening the file. One more byte is read into a buffer of its own, so that a file that has
            // grown since it was looked up is noticed in the same read: then, and if it has shrunk, the file is read
            // again below, at the length it has now
            final byte @Nullable [] content;
            try (var channel = root.openFile(name, path, dirHandle, fileName)) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new InterruptedIOException("Interrupted while reading " + getNioPath());
                }
                content = read(channel, (int) lengthAtLookup);
            }
            if (content != null) {
                return content;
            }
        }
        try (var slice = openSlice()) {
            return slice.load();
        }
    }

    /**
     * Read a file that is expected to have a given length into a new array, from the start of the file. One more
     * byte is read into a buffer of its own, so that a file longer than expected is noticed in the same read.
     *
     * @param channel
     *            the file, positioned at its start.
     * @param length
     *            the expected length of the file.
     * @return the content of the file, or null if the file is not {@code length} bytes long.
     * @throws IOException
     *             if the file could not be read.
     */
    static byte @Nullable [] read(final FileChannel channel, final int length) throws IOException {
        final var content = new byte[length];
        final var contentBuffer = ByteBuffer.wrap(content);
        final var buffers = new ByteBuffer[] { contentBuffer, ByteBuffer.allocate(1) };
        var numBytesRead = 0L;
        // A read from a network filesystem can be short, so reading goes on until the array is full or the end of
        // the file is reached. When a read that asked for the rest of the array and the extra byte fills only the
        // array, the end of the file was reached (POSIX lets a read of a regular file be short only at the end of
        // the file), so the file has the expected length. A large file is read in chunks (see
        // PathSlice#MAX_ARRAY_READ_SIZE), and the extra byte is read with the last chunk.
        do {
            final long numBytesReadThisCall;
            if (length - numBytesRead > PathSlice.MAX_ARRAY_READ_SIZE) {
                contentBuffer.limit((int) numBytesRead + PathSlice.MAX_ARRAY_READ_SIZE);
                numBytesReadThisCall = channel.read(contentBuffer);
            } else {
                contentBuffer.limit(length);
                numBytesReadThisCall = channel.read(buffers);
            }
            if (numBytesReadThisCall < 0L) {
                break;
            }
            numBytesRead += numBytesReadThisCall;
        } while (numBytesRead < length);
        return numBytesRead == length ? content : null;
    }
}
