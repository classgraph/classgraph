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

import io.github.classgraph.base.internal.path.PathSyntax;
import io.github.classgraph.base.internal.utils.Assert;
import org.jspecify.annotations.Nullable;

/**
 * One file within a {@link VfsRoot}: a file in a directory tree, an entry of a jarfile, or a resource in a module.
 *
 * <p>
 * An entry is stateless, and can be read any number of times, from any number of threads at once. The read methods
 * differ only in what they hand back: {@link #open()} and {@link #openChannel()} stream the content,
 * {@link #read()} maps or wraps it as a {@link java.nio.ByteBuffer}, {@link #load()} copies it into a byte array,
 * and {@link #loadAsString()} decodes that array as UTF-8, or in the charset given to
 * {@link #loadAsString(Charset)}. The first three return something the caller owns and must close; the rest are
 * self-contained.
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
     * Returns the path of this entry relative to its root, with {@code '/'} as the separator and no leading
     * {@code '/'}, e.g. {@code "com/xyz/Widget.class"}. This is the same path whichever kind of root the entry came
     * from, so it is the path to match against, and the path to key a map of entries by.
     *
     * <p>
     * For an entry of a multi-release jarfile that is only present for some JDK versions, this is the path without
     * the {@code "META-INF/versions/<version>/"} prefix, so that the same entry has the same path whichever version
     * of it was selected. Use {@link #getRawPathFromRoot()} to get the unprocessed path the entry is physically
     * stored under.
     *
     * <p>
     * This is a whole path and not a leafname, which is why it is not called {@code getName()}: {@code
     * File#getName()} and {@code Path#getFileName()} both return only the last segment of a path, and this returns
     * all of it. {@link #getLastSegment()} is the method that corresponds to those.
     *
     * @return the path of the entry relative to its root.
     */
    public abstract String getPathFromRoot();

    /**
     * Returns the last segment of {@link #getPathFromRoot()}: the part after the last {@code '/'}, e.g.
     * {@code "Widget.class"} for an entry whose path from the root is {@code "com/xyz/Widget.class"}. This is what
     * {@code File#getName()} would return for the same path. An entry whose path ends in {@code '/'} has an empty
     * last segment.
     *
     * <p>
     * This is the entry-level counterpart of {@link VfsRoot#getLastSegment()}, and is defined the same way: the
     * text after the last separator. An entry path has no nested jar separators in it -- it is always a plain path
     * within one root -- so unlike a root path, there is only one way to read its last segment.
     *
     * @return the last segment of the path of the entry.
     */
    public final String getLastSegment() {
        return PathSyntax.simpleName(getPathFromRoot());
    }

    /**
     * Returns the raw, unprocessed path this entry is stored under within its root: the path as it physically
     * appears in the root, before the root's package root prefix was stripped from it and before any multi-release
     * version prefix was resolved. Use {@link #getPathFromRoot()} to look an entry up or to match it against a
     * path; use this only to report where the entry physically lies within its root.
     *
     * <p>
     * This differs from {@link #getPathFromRoot()} only for an entry of a jarfile: in a root opened at a package
     * root, this path still has the package root prefix on it, and for an entry of a multi-release jarfile that is
     * only present for some JDK versions, this is the versioned path.
     *
     * @return the raw path the entry is stored under, relative to its root.
     */
    public String getRawPathFromRoot() {
        return getPathFromRoot();
    }

    /**
     * Returns the full path of this entry, which locates it on the machine rather than within its root: a
     * filesystem path for a file in a directory, the path of the jarfile followed by {@code "!/"} and the entry's
     * name for a jarfile entry, and the module name followed by {@code "/"} and the entry's name for a module
     * resource, which is how the JDK itself names something within a module.
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
     * @throws java.nio.file.ClosedFileSystemException
     *             if the {@link Vfs} that opened the root has been closed.
     */
    public Path asPath() {
        return getRoot().asFileSystem().getPath("/" + getPathFromRoot());
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
     * @return the length in bytes, or -1 if the length is not known: a module resource does not know its length
     *         without being read, and a file whose size could not be read from the filesystem has none to report.
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
    public abstract long getLastModifiedMillis();

    /**
     * Returns the POSIX file permissions of this entry.
     *
     * @return the permissions, as an unmodifiable set that iterates in {@link PosixFilePermission} declaration
     *         order, or null if the root does not record them -- which is the case for a module resource, for a
     *         jarfile written without them, and for a file in a filesystem that does not support them.
     */
    public @Nullable Set<PosixFilePermission> getPosixFilePermissions() {
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
     * produced it, in which case it is only valid until it is closed. It is also only valid until the {@link Vfs}
     * is closed, which unmaps any memory mapping behind it -- see {@link CloseableByteBuffer}.
     *
     * @return the content of the entry, as a closeable buffer.
     * @throws IOException
     *             if the entry could not be read, or if the {@link Vfs} has been closed, or if the entry is larger
     *             than 2GB, the largest a {@link java.nio.ByteBuffer} can be.
     */
    public abstract CloseableByteBuffer read() throws IOException;

    /**
     * Read this entry's whole content into a byte array, decompressing it if it is stored compressed. Unlike
     * {@link #read()}, the returned array is the caller's own copy, and stays valid after the {@link Vfs} is
     * closed.
     *
     * @return the content of the entry.
     * @throws IOException
     *             if the entry could not be read, or if the {@link Vfs} has been closed, or if the entry is larger
     *             than the largest possible array.
     */
    public abstract byte[] load() throws IOException;

    /**
     * Read this entry's whole content and decode it as UTF-8.
     *
     * @return the content of the entry, as a string.
     * @throws IOException
     *             if the entry could not be read, or if the {@link Vfs} has been closed, or if the entry is larger
     *             than the largest possible array.
     */
    public String loadAsString() throws IOException {
        return loadAsString(StandardCharsets.UTF_8);
    }

    /**
     * Read this entry's whole content and decode it in the given charset. Bytes that the charset cannot decode are
     * replaced rather than throwing, as {@link String#String(byte[], Charset)} specifies.
     *
     * @param charset
     *            the charset to decode the content in.
     * @return the content of the entry, as a string.
     * @throws IOException
     *             if the entry could not be read, or if the {@link Vfs} has been closed, or if the entry is larger
     *             than the largest possible array.
     */
    public String loadAsString(final Charset charset) throws IOException {
        Assert.notNull(charset, "charset");
        return new String(load(), charset);
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
                && getPath().equals(other.getPath());
    }
}
