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
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import org.jetbrains.annotations.Unmodifiable;
import org.jspecify.annotations.Nullable;

import io.github.classgraph.base.internal.path.PathSyntax;
import io.github.classgraph.base.internal.utils.Assert;
import io.github.classgraph.vfs.reader.RandomAccessOrSequentialReader;
import io.github.classgraph.vfs.reader.RandomAccessReader;

/**
 * A file or resource entry within a {@link VfsRoot}: a file in a directory tree, an entry in a jarfile, or a
 * resource in a module.
 *
 * <p>
 * Entries are stateless and thread-safe. Content can be read via {@link #open()} ({@link InputStream}),
 * {@link #openChannel()} ({@link ReadableByteChannel}), {@link #read()} ({@link CloseableByteBuffer}),
 * {@link #load()} ({@code byte[]}), or {@link #loadAsString()} / {@link #loadAsString(Charset)} ({@link String}).
 * Callers must close the streams, channels, and buffers returned by {@link #open()}, {@link #openChannel()}, and
 * {@link #read()}; all open handles become invalid when the owning {@link VfsRoot} or {@link Vfs} is closed.
 */
public abstract sealed class VfsEntry permits ArchiveEntry, DirEntry, ModuleEntry {
    /** The root containing this entry. */
    private final VfsRoot root;

    /**
     * Constructor.
     *
     * @param root
     *            the root containing this entry.
     */
    VfsEntry(final VfsRoot root) {
        this.root = root;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Return the {@link VfsRoot} containing this entry.
     *
     * @return the owning {@link VfsRoot}.
     */
    public VfsRoot getRoot() {
        return root;
    }

    /**
     * Return the path of this entry relative to its root, using {@code '/'} separators and no leading {@code '/'}
     * (e.g. {@code "com/xyz/Widget.class"}).
     *
     * <p>
     * For versioned entries in a multi-release jarfile, the {@code "META-INF/versions/<version>/"} prefix is
     * stripped; use {@link #getRawPathFromRoot()} to obtain the physical path in the archive.
     *
     * @return the root-relative path of this entry.
     */
    public abstract String getPathFromRoot();

    /**
     * Return the filename segment of {@link #getPathFromRoot()} after the last {@code '/'} (e.g.
     * {@code "Widget.class"} for {@code "com/xyz/Widget.class"}).
     *
     * @return the last path segment.
     */
    public final String getLastSegment() {
        return PathSyntax.simpleName(getPathFromRoot());
    }

    /**
     * Return the physical path of this entry within its root before stripping any package-root or multi-release
     * {@code "META-INF/versions/<version>/"} prefix.
     *
     * @return the raw root-relative path of this entry.
     */
    public String getRawPathFromRoot() {
        return getPathFromRoot();
    }

    /**
     * Return the full path of this entry: a filesystem path for a directory entry, {@code "<jar-path>!/<entry>"}
     * for an archive entry, or {@code "<module-name>/<entry>"} for a module resource.
     *
     * @return the full path of this entry.
     */
    public abstract String getPath();

    /**
     * Return the {@link URI} of this entry ({@code file:}, {@code jar:}, or module resource URI such as
     * {@code jrt:}).
     *
     * @return the {@link URI} of this entry.
     * @throws IllegalStateException
     *             if the {@link URI} cannot be constructed.
     */
    public URI getURI() {
        // How a URI is spelled depends on the kind of storage the entry is held in, not on the entry, so the root
        // is asked -- and it can name a path that no entry was looked up for, which is why this is its job
        return getRoot().resolveURI(getRawPathFromRoot());
    }

    /**
     * Return the {@link URL} of this entry.
     *
     * @return the {@link URL} of this entry.
     * @throws IllegalStateException
     *             if the {@link URI} cannot be converted to a {@link URL} (for example, if no URL stream handler is
     *             installed for its scheme).
     */
    public URL getURL() {
        final var uri = getURI();
        try {
            return uri.toURL();
        } catch (final IllegalArgumentException | MalformedURLException e) {
            throw new IllegalStateException("Could not create URL from URI: " + uri + " : " + e, e);
        }
    }

    /**
     * Return this entry as a {@link Path} within its root's {@link VfsRoot#asFileSystem()} view.
     *
     * @return a {@link Path} representing this entry in the virtual filesystem view.
     * @throws java.nio.file.ClosedFileSystemException
     *             if the root or its {@link Vfs} has been closed.
     */
    public Path asPath() {
        return getRoot().asFileSystem().getPath("/" + getPathFromRoot());
    }

    /**
     * Return the native filesystem {@link Path} for this entry, if backed directly by a mounted filesystem.
     *
     * @return the filesystem {@link Path}, or {@code null} for archive entries and module resources.
     */
    public @Nullable Path getNioPath() {
        return null;
    }

    /**
     * Return the uncompressed size of this entry in bytes.
     *
     * @return the uncompressed size in bytes, or {@code -1} if unknown (e.g. for module resources).
     */
    public abstract long getLength();

    /**
     * Return the stored (compressed) size of this entry in bytes, or {@link #getLength()} if uncompressed.
     *
     * @return the stored size in bytes, or {@code -1} if unknown.
     */
    public long getCompressedSize() {
        return getLength();
    }

    /**
     * Return the last modified timestamp of this entry in milliseconds since the Unix epoch.
     *
     * <p>
     * Standard MS-DOS zip timestamps have 2-second resolution and no time zone (interpreted here as UTC unless an
     * extended UTC timestamp extra field is present).
     *
     * @return the last modified time in milliseconds since the epoch, or {@code 0} if unknown.
     */
    public abstract long getLastModifiedMillis();

    /**
     * Return whether this entry is readable.
     *
     * @return {@code true} if readable.
     */
    boolean isReadable() {
        return true;
    }

    /**
     * Return the POSIX file permissions for this entry, if available.
     *
     * @return an unmodifiable set of {@link PosixFilePermission} in declaration order, or {@code null} if the root
     *         does not record POSIX permissions.
     */
    public @Nullable @Unmodifiable Set<PosixFilePermission> getPosixFilePermissions() {
        return null;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Open an {@link InputStream} over the uncompressed content of this entry. The caller must close the returned
     * stream; closing the owning {@link VfsRoot} or {@link Vfs} also closes the stream and causes subsequent reads
     * to throw {@link IOException}.
     *
     * @return an {@link InputStream} for reading this entry's content.
     * @throws IOException
     *             if the entry cannot be opened, or if the root or {@link Vfs} has been closed.
     */
    public final InputStream open() throws IOException {
        final var root = getRoot();
        root.checkNotClosed(getPath());
        final var inputStream = openUnchecked();
        final RootCheckedInputStream rootCheckedInputStream;
        try {
            rootCheckedInputStream = new RootCheckedInputStream(this, inputStream);
        } catch (final RuntimeException | Error e) {
            // The caller never sees the stream if this throws, so nothing else would close it
            try {
                inputStream.close();
            } catch (final IOException e2) {
                e.addSuppressed(e2);
            }
            throw e;
        }
        // From here the wrapper owns the stream, so a failure to track it is reported by closing the wrapper. A
        // stream on a file in a directory, or on a resource of a module that is a directory, holds a file handle of
        // its own that nothing else the root closes would release
        if (!root.trackOpenHandle(rootCheckedInputStream)) {
            rootCheckedInputStream.close();
            throw new IOException("Cannot read " + getPath() + " after the root has been closed");
        }
        return rootCheckedInputStream;
    }

    /**
     * Open the raw {@link InputStream} for this entry prior to wrapping in {@link RootCheckedInputStream}.
     *
     * @return the underlying {@link InputStream}.
     * @throws IOException
     *             if the entry cannot be opened.
     */
    abstract InputStream openUnchecked() throws IOException;

    /**
     * An {@link InputStream} wrapper that verifies the owning {@link VfsRoot} remains open on each read and
     * unregisters itself on close.
     */
    private static final class RootCheckedInputStream extends InputStream {
        /** The entry being read. */
        private final VfsEntry entry;

        /** The underlying stream. */
        private final InputStream inputStream;

        /** True once {@link #close()} has been called. */
        private final AtomicBoolean closed = new AtomicBoolean();

        /**
         * Constructor.
         *
         * @param entry
         *            the entry being read.
         * @param inputStream
         *            the underlying stream.
         */
        RootCheckedInputStream(final VfsEntry entry, final InputStream inputStream) {
            this.entry = entry;
            this.inputStream = inputStream;
        }

        /**
         * Throw {@link IOException} if the entry's root or {@link Vfs} has been closed.
         *
         * @throws IOException
         *             if the root or {@link Vfs} has been closed.
         */
        private void checkRootNotClosed() throws IOException {
            final var root = entry.getRoot();
            // Only build the path of the entry for the error message once the root is found closed, since the path
            // of some entries is built anew by each call, and this check runs on every read
            if (root.isClosed()) {
                root.checkNotClosed(entry.getPath());
            }
        }

        @Override
        public int read() throws IOException {
            checkRootNotClosed();
            return inputStream.read();
        }

        @Override
        public int read(final byte[] buf, final int off, final int len) throws IOException {
            checkRootNotClosed();
            return inputStream.read(buf, off, len);
        }

        @Override
        public long skip(final long n) throws IOException {
            checkRootNotClosed();
            return inputStream.skip(n);
        }

        @Override
        public int available() throws IOException {
            checkRootNotClosed();
            return inputStream.available();
        }

        @Override
        public synchronized void mark(final int readlimit) {
            inputStream.mark(readlimit);
        }

        @Override
        public synchronized void reset() throws IOException {
            checkRootNotClosed();
            inputStream.reset();
        }

        @Override
        public boolean markSupported() {
            return inputStream.markSupported();
        }

        @Override
        public void close() throws IOException {
            if (closed.getAndSet(true)) {
                return;
            }
            try {
                inputStream.close();
            } finally {
                entry.getRoot().untrackOpenHandle(this);
            }
        }
    }

    /**
     * Open a {@link ReadableByteChannel} over the uncompressed content of this entry. The caller must close the
     * returned channel; closing the owning {@link VfsRoot} or {@link Vfs} closes the underlying stream and causes
     * subsequent reads to throw {@link IOException}.
     *
     * @return a {@link ReadableByteChannel} for reading this entry's content.
     * @throws IOException
     *             if the entry cannot be opened, or if the root or {@link Vfs} has been closed.
     */
    public ReadableByteChannel openChannel() throws IOException {
        return Channels.newChannel(open());
    }

    /**
     * Read this entry's content as a read-only {@link java.nio.ByteBuffer} wrapped in a
     * {@link CloseableByteBuffer}. The caller must close the returned wrapper, and must not access the buffer after
     * the wrapper, its {@link VfsRoot}, or its {@link Vfs} is closed.
     *
     * @return a {@link CloseableByteBuffer} wrapping this entry's content.
     * @throws IOException
     *             if the entry cannot be read, exceeds 2 GiB, or if the root or {@link Vfs} has been closed.
     */
    public abstract CloseableByteBuffer read() throws IOException;

    /**
     * Random-access view of an entry's content and its cleanup callback.
     *
     * @param reader
     *            the reader over the entry's content (not thread-safe).
     * @param closeAction
     *            the cleanup action to release resources held by {@code reader}.
     */
    record RandomAccessContent(RandomAccessReader reader, Runnable closeAction) {
    }

    /**
     * Open a random-access view of this entry's content. Uncompressed archive entries and directory files are read
     * directly in place; deflated archive entries and module resources are streamed and buffered on demand up to
     * the furthest offset read.
     *
     * @return the random-access content view, which the caller must close via its {@code closeAction}.
     * @throws IOException
     *             if the entry cannot be opened, or if the root or {@link Vfs} has been closed.
     */
    RandomAccessContent openRandomAccess() throws IOException {
        // This entry cannot be read at an offset, so read it as a stream, buffering what has been read so far. The
        // reader closes the stream that it opened on this entry when its close action is run.
        final var reader = new RandomAccessOrSequentialReader(this);
        return new RandomAccessContent(reader, reader::close);
    }

    /**
     * Read this entry's entire uncompressed content into a new byte array.
     *
     * <p>
     * To read a file whose name you know, without needing its entry, {@link VfsRoot#load(String) root.load(name)}
     * gives the same result as {@link VfsRoot#getEntry(String) getEntry(name)} followed by {@code load()}, and
     * returns {@code null} where {@code getEntry} would. It is faster for a directory root, which opens the file
     * without first reading its metadata. For the same reason, it opens a named pipe, and so waits for a writer, as
     * {@link java.nio.file.Files#readAllBytes(java.nio.file.Path)} does, rather than returning {@code null}.
     *
     * @return a byte array containing the entry's content.
     * @throws IOException
     *             if the entry cannot be read, exceeds the maximum array size, or if the root or {@link Vfs} has
     *             been closed.
     */
    public abstract byte[] load() throws IOException;

    /**
     * Read this entry's entire content and decode it as a UTF-8 string.
     *
     * @return the entry's content decoded as UTF-8.
     * @throws IOException
     *             if the entry cannot be read, exceeds the maximum array size, or if the root or {@link Vfs} has
     *             been closed.
     */
    public String loadAsString() throws IOException {
        return loadAsString(StandardCharsets.UTF_8);
    }

    /**
     * Read this entry's entire content and decode it using the specified {@link Charset}.
     *
     * @param charset
     *            the charset to use for decoding.
     * @return the decoded entry content.
     * @throws IOException
     *             if the entry cannot be read, exceeds the maximum array size, or if the root or {@link Vfs} has
     *             been closed.
     */
    public String loadAsString(final Charset charset) throws IOException {
        Assert.notNull(charset, "charset");
        return new String(load(), charset);
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Return the full path of this entry ({@link #getPath()}).
     *
     * @return the full path string.
     */
    @Override
    public String toString() {
        return getPath();
    }

    /**
     * Return a hash code based on the owning {@link VfsRoot} and {@link #getPathFromRoot()}.
     *
     * @return the hash code.
     */
    @Override
    public int hashCode() {
        return root.hashCode() * 31 + getPathFromRoot().hashCode();
    }

    /**
     * Return whether {@code obj} is a {@link VfsEntry} belonging to the same {@link VfsRoot} instance with the same
     * {@link #getPathFromRoot()}.
     *
     * @param obj
     *            the object to compare with.
     * @return {@code true} if equal.
     */
    @Override
    public boolean equals(final @Nullable Object obj) {
        return obj == this || obj instanceof final VfsEntry other && root == other.root
                && getPathFromRoot().equals(other.getPathFromRoot());
    }
}
