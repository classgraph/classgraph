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

import java.io.IOError;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

import io.github.classgraph.base.internal.path.URLPaths;
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
    public String getName() {
        return name;
    }

    @Override
    public String getPath() {
        return getRoot().getPath() + "/" + name;
    }

    @Override
    public URI getURI() {
        try {
            // On Windows, Path#toUri() puts the server of a UNC path in the URI authority, where java.net.URL does
            // not find it again
            return URLPaths.moveUNCServerIntoPath(path.toUri());
        } catch (final IOError | SecurityException e) {
            throw new IllegalStateException("Could not form URI for " + path + " : " + e, e);
        }
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
            return posixAttributes.permissions();
        }
        try {
            return Files.readAttributes(path, PosixFileAttributes.class).permissions();
        } catch (final IOException | UnsupportedOperationException | SecurityException e) {
            // The filesystem does not record POSIX permissions (e.g. on Windows)
            return null;
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Open a {@link PathSlice} over the file.
     *
     * @return the slice, which the caller owns and must close.
     * @throws IOException
     *             if the file could not be opened, or if the {@link Vfs} has been closed.
     */
    private PathSlice openSlice() throws IOException {
        final var root = getRoot();
        root.checkNotClosed(getPath());
        final var vfs = root.getVfs();
        return new PathSlice(path, vfs.session(), /* checkAccess = */ false, /* memoryMapWholeFile = */ false,
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
        final var slice = openSlice();
        try {
            return new CloseableByteBuffer(slice.read(), slice::close);
        } catch (final IOException | RuntimeException | Error e) {
            // The caller never sees the slice if this throws, so nothing else can close its file channel
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
