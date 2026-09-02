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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

import io.github.classgraph.vfs.internal.slice.PathSlice;
import org.jspecify.annotations.Nullable;

/** One file in a directory tree. */
final class DirEntry extends VfsEntry {
    /** The file. */
    private final Path path;

    /** The name of this entry, relative to the root directory. */
    private final String name;

    /**
     * The attributes of the file, if they were read while listing the directory that contains it, otherwise null.
     */
    private final @Nullable BasicFileAttributes attributes;

    /**
     * The length of the file, or -1 if it has not been read yet. Volatile, so that a thread that asks a second time
     * sees the length another thread read, rather than reading the file's attributes again -- or, worse, seeing a
     * half-written 64-bit value on a 32-bit JVM.
     */
    private volatile long length = -1L;

    /**
     * Constructor.
     *
     * @param root
     *            the directory this entry was found in.
     * @param path
     *            the file.
     * @param name
     *            the name of this entry, relative to the root directory.
     * @param attributes
     *            the attributes of the file, if they have already been read, otherwise null. Listing a directory
     *            has to read the attributes of each of its children anyway, in order to tell the files from the
     *            subdirectories, so handing them to the entry saves reading them a second time.
     */
    DirEntry(final DirRoot root, final Path path, final String name,
            final @Nullable BasicFileAttributes attributes) {
        super(root);
        this.path = path;
        this.name = name;
        this.attributes = attributes;
    }

    // -------------------------------------------------------------------------------------------------------------

    @Override
    public String getPathFromRoot() {
        return name;
    }

    @Override
    public String getPath() {
        return getRoot().getPath() + "/" + name;
    }

    @Override
    public Path getNioPath() {
        return path;
    }

    @Override
    public long getLength() {
        final var attributesCurr = attributes;
        if (attributesCurr != null) {
            try {
                return attributesCurr.size();
            } catch (final UnsupportedOperationException e) {
                return -1L;
            }
        }
        // Reading the size of a file costs a system call, so it is only read if it is asked for, and is then kept
        var lengthCurr = length;
        if (lengthCurr < 0L) {
            try {
                lengthCurr = Files.size(path);
            } catch (final IOException | SecurityException e) {
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
            } catch (final UnsupportedOperationException e) {
                return 0L;
            }
        }
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (final IOException | SecurityException e) {
            return 0L;
        }
    }

    @Override
    public @Nullable Set<PosixFilePermission> getPosixFilePermissions() {
        // On a POSIX filesystem, the attributes read while listing the directory are already POSIX attributes, so
        // the permissions come for free
        if (attributes instanceof final PosixFileAttributes posixAttributes) {
            return unmodifiableEnumSet(posixAttributes.permissions());
        }
        try {
            return unmodifiableEnumSet(Files.readAttributes(path, PosixFileAttributes.class).permissions());
        } catch (final IOException | UnsupportedOperationException | SecurityException e) {
            // The filesystem does not record POSIX permissions (e.g. on Windows)
            return null;
        }
    }

    /**
     * Copies the permissions the filesystem provider returned into an unmodifiable {@link EnumSet}, so that the
     * caller cannot modify them, and so that they iterate in {@link PosixFilePermission} declaration order rather
     * than in whatever order the provider's own set happens to use.
     *
     * @param permissions
     *            the permissions returned by the filesystem provider.
     * @return the permissions, as an unmodifiable {@link EnumSet}.
     */
    private static Set<PosixFilePermission> unmodifiableEnumSet(final Set<PosixFilePermission> permissions) {
        final Set<PosixFilePermission> enumSet = EnumSet.noneOf(PosixFilePermission.class);
        enumSet.addAll(permissions);
        return Collections.unmodifiableSet(enumSet);
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Open a {@link PathSlice} over the file.
     *
     * @return the slice, which the caller owns and must close.
     * @throws IOException
     *             if the file could not be opened, or if the root, or the {@link Vfs} that opened it, has been
     *             closed.
     */
    private PathSlice openSlice() throws IOException {
        final var root = getRoot();
        root.checkNotClosed(getPath());
        return new PathSlice(path, root.getVfs(), /* checkAccess = */ false, /* memoryMapWholeFile = */ false,
                /* log = */ null);
    }

    @Override
    public InputStream open() throws IOException {
        final var slice = openSlice();
        // The slice holds the open file channel, so closing the stream has to close the slice too
        return slice.open(slice);
    }

    @Override
    public CloseableByteBuffer read() throws IOException {
        final var root = getRoot();
        final var slice = openSlice();
        final CloseableByteBuffer buffer;
        try {
            buffer = new CloseableByteBuffer(slice.read(), slice::close, root);
        } catch (final IOException | RuntimeException | Error e) {
            // The caller never sees the slice if this throws, so nothing else can close its file channel
            slice.close();
            throw e;
        }
        // From here the wrapper owns the slice, so a failure to track it is reported by closing the wrapper rather
        // than by closing the slice directly
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
        try (var slice = openSlice()) {
            return slice.load();
        }
    }

}
