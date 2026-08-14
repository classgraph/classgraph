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
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Objects;
import java.util.Set;

import io.github.classgraph.vfs.internal.slice.reader.ClassfileReader;
import io.github.classgraph.vfs.internal.zip.FastZipEntry;
import org.jspecify.annotations.Nullable;

/**
 * One file within a {@link VfsRoot}: a file in a directory tree, an entry of a jarfile, or a resource in a module.
 *
 * <p>
 * An entry is stateless, and can be read any number of times, from any number of threads at once. The five read
 * methods differ only in what they hand back: {@link #open()} and {@link #openChannel()} stream the content,
 * {@link #read()} maps or wraps it as a {@link java.nio.ByteBuffer}, {@link #load()} copies it into a byte array,
 * and {@link #loadAsString()} decodes that array as UTF-8. The first three return something the caller owns and
 * must close; the last two are self-contained.
 *
 * <p>
 * Everything an entry hands out stops working once the {@link Vfs} that produced it is closed.
 */
public abstract class VfsEntry {
    /** The root this entry belongs to. */
    private final VfsRoot root;

    /**
     * Constructor.
     *
     * @param root
     *            the root this entry belongs to.
     */
    VfsEntry(final VfsRoot root) {
        this.root = root;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Returns the root this entry belongs to.
     *
     * @return the root.
     */
    public VfsRoot getRoot() {
        return root;
    }

    /**
     * Returns the name of this entry relative to its root, with {@code '/'} as the separator and no leading
     * {@code '/'}, e.g. {@code "com/xyz/Widget.class"}. This is the same name whichever kind of root the entry came
     * from, so it is the name to match against, and the name to key a map of entries by.
     *
     * <p>
     * For an entry of a multi-release jarfile that is only present for some JDK versions, this is the name without
     * the {@code "META-INF/versions/<version>/"} prefix, so that the same entry has the same name whichever version
     * of it was selected.
     *
     * @return the name of the entry.
     */
    public abstract String getName();

    /**
     * Returns the full path of this entry, which locates it on the machine rather than within its root: a
     * filesystem path for a file in a directory, the path of the jarfile followed by {@code "!/"} and the entry's
     * name for a jarfile entry, and the module name followed by {@code ":"} and the entry's name for a module
     * resource.
     *
     * @return the path of the entry.
     */
    public abstract String getPath();

    /**
     * Returns the {@link URI} of this entry: a {@code file:} URI for a file in a directory, a {@code jar:} URI for
     * a jarfile entry, and whatever the module names it as for a module resource, which is a {@code jrt:} URI for a
     * module of the running JDK.
     *
     * @return the {@link URI} of the entry.
     * @throws IllegalStateException
     *             if the {@link URI} could not be formed.
     */
    public abstract URI getURI();

    /**
     * Returns the {@link URL} of this entry.
     *
     * @return the {@link URL} of the entry.
     * @throws IllegalStateException
     *             if the {@link URL} could not be formed, which includes the case of a {@link URI} whose scheme has
     *             no protocol handler installed.
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
     * Returns this entry as a {@link Path} within the {@link VfsRoot#asFileSystem()} view of the root it came from,
     * so that it can be handed to code that reads through {@link java.nio.file.Files}. Unlike
     * {@link #getNioPath()}, this never returns null, whatever kind of storage the entry is held in.
     *
     * @return this entry, as a {@link Path} of a read-only virtual filesystem.
     */
    public Path asPath() {
        return getRoot().asFileSystem().getPath("/" + getName());
    }

    /**
     * Returns the {@link Path} of this entry in the filesystem, if it has one.
     *
     * @return the {@link Path} of the entry, or null if the entry is not a file in a filesystem this JVM has
     *         mounted -- which is the case for a jarfile entry and for a module resource.
     */
    public @Nullable Path getNioPath() {
        return null;
    }

    /**
     * Returns the number of bytes of content this entry has, once decompressed.
     *
     * @return the length in bytes, or -1 if the length is not known without reading the entry, which is the case
     *         for a module resource.
     */
    public abstract long getLength();

    /**
     * Returns the number of bytes this entry occupies in its root, i.e. its size after compression. For an entry
     * that is not stored compressed, this is the same as {@link #getLength()}.
     *
     * @return the stored size in bytes, or -1 if it is not known.
     */
    public long getCompressedSize() {
        return getLength();
    }

    /**
     * Returns the time this entry was last modified, in milliseconds since the epoch.
     *
     * @return the last modified time in milliseconds since the epoch, or 0 if the root does not record it, which is
     *         the case for a module resource.
     */
    public abstract long getLastModifiedTimeMillis();

    /**
     * Returns the POSIX file permissions of this entry.
     *
     * @return the permissions, or null if the root does not record them -- which is the case for a module resource,
     *         for a jarfile written without them, and for a file in a filesystem that does not support them.
     */
    public @Nullable Set<PosixFilePermission> getPosixFilePermissions() {
        return null;
    }

    /**
     * Returns the central directory entry that this entry reads from. This is for the other ClassGraph modules,
     * which need the entry's compression method and its position within the jarfile, and is not part of the API.
     *
     * @return the zip entry, or null if this entry is not in an archive.
     * @hidden
     */
    public @Nullable FastZipEntry getZipEntry() {
        return null;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Open this entry's content as an {@link InputStream}, decompressing it if it is stored compressed. The caller
     * owns the returned stream and must close it.
     *
     * @return the content of the entry, as a stream.
     * @throws IOException
     *             if the entry could not be read, or if the {@link Vfs} has been closed.
     */
    public abstract InputStream open() throws IOException;

    /**
     * Open this entry's content as a {@link ReadableByteChannel}, decompressing it if it is stored compressed. The
     * caller owns the returned channel and must close it.
     *
     * @return the content of the entry, as a channel.
     * @throws IOException
     *             if the entry could not be read, or if the {@link Vfs} has been closed.
     */
    public ReadableByteChannel openChannel() throws IOException {
        return Channels.newChannel(open());
    }

    /**
     * Read this entry's whole content as a read-only {@link java.nio.ByteBuffer}. The caller owns the returned
     * buffer and must close it: the buffer may be a memory mapping, or may belong to the module reader that
     * produced it, in which case it is only valid until it is closed.
     *
     * @return the content of the entry, as a closeable buffer.
     * @throws IOException
     *             if the entry could not be read, or if the {@link Vfs} has been closed.
     * @throws OutOfMemoryError
     *             if the entry is larger than 2GB, the largest a {@link java.nio.ByteBuffer} can be.
     */
    public abstract CloseableByteBuffer read() throws IOException;

    /**
     * Read this entry's whole content into a byte array, decompressing it if it is stored compressed. Unlike
     * {@link #read()}, the returned array is the caller's own copy, and stays valid after the {@link Vfs} is
     * closed.
     *
     * @return the content of the entry.
     * @throws IOException
     *             if the entry could not be read, or if the {@link Vfs} has been closed.
     * @throws OutOfMemoryError
     *             if the entry is larger than the largest possible array.
     */
    public abstract byte[] load() throws IOException;

    /**
     * Read this entry's whole content and decode it as UTF-8.
     *
     * @return the content of the entry, as a string.
     * @throws IOException
     *             if the entry could not be read, or if the {@link Vfs} has been closed.
     * @throws OutOfMemoryError
     *             if the entry is larger than the largest possible array.
     */
    public String loadAsString() throws IOException {
        return new String(load(), StandardCharsets.UTF_8);
    }

    /**
     * Open a reader on this entry's content, for parsing the classfile it holds. Each kind of root reads a
     * classfile in whichever way is cheapest for it. This is for the other ClassGraph modules, which read
     * classfiles from every kind of root, and is not part of the API.
     *
     * @param resourceToClose
     *            a resource to close once the reader is closed, or null if there is none.
     * @return the reader, which the caller owns and must close.
     * @throws IOException
     *             if the entry could not be read, or if the {@link Vfs} has been closed.
     * @hidden
     */
    public ClassfileReader openClassfileReader(final @Nullable AutoCloseable resourceToClose) throws IOException {
        return new ClassfileReader(open(), resourceToClose);
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Returns the full path of this entry.
     *
     * @return the entry, as a string.
     */
    @Override
    public String toString() {
        return getPath();
    }

    /**
     * Returns a hash code based on the full path of this entry.
     *
     * @return the hash code.
     */
    @Override
    public int hashCode() {
        return getPath().hashCode();
    }

    /**
     * Two entries are equal if they have the same full path and came from the same {@link Vfs}.
     *
     * @param obj
     *            the object to compare with.
     * @return true if the two entries are equal.
     */
    @Override
    public boolean equals(final @Nullable Object obj) {
        if (obj == this) {
            return true;
        }
        return obj instanceof final VfsEntry other && root.getVfs() == other.root.getVfs()
                && Objects.equals(getPath(), other.getPath());
    }
}
