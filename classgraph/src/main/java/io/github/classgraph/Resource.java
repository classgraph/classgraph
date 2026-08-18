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
package io.github.classgraph;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.module.ModuleReference;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;

import io.github.classgraph.base.LogNode;
import io.github.classgraph.base.internal.path.URLPaths;
import io.github.classgraph.base.internal.utils.ProxyingInputStream;
import io.github.classgraph.vfs.CloseableByteBuffer;
import io.github.classgraph.vfs.Vfs;
import io.github.classgraph.vfs.VfsEntry;
import org.jspecify.annotations.Nullable;

/**
 * A classpath or module path resource (i.e. file) that was found in an accepted/non-rejected package inside a
 * classpath element or module.
 *
 * <p>
 * A resource is read through the virtual filesystem, so that a file in a directory, an entry of a jarfile and a
 * resource in a module are all read the same way. Call {@link #getVfsEntry()} to reach the {@link VfsEntry} behind
 * the resource, which offers the rest of the {@link Vfs} API. Each kind of classpath element subclasses this only
 * to say how the resource is named or located within it, since that is all that differs between them.
 */
public abstract class Resource implements AutoCloseable, Comparable<Resource> {
    /** The classpath element this resource was obtained from. */
    private final ClasspathElement classpathElement;

    /** The entry in the virtual filesystem that this resource is read from. */
    private final VfsEntry entry;

    /** The path of the resource relative to the package root. */
    private final String path;

    /** True if this resource is currently open. */
    private final AtomicBoolean isOpen = new AtomicBoolean();

    /** The stream this resource was opened as, or null if it has not been opened as a stream. */
    private @Nullable InputStream inputStream;

    /** The buffer this resource was read into, or null if it has not been read into a buffer. */
    private @Nullable CloseableByteBuffer closeableByteBuffer;

    /** The length, or -1L for unknown. */
    private long length;

    /** The cached result of toString(), or null if not yet computed. */
    private @Nullable String toString;

    /**
     * The {@link LogNode} used to log that the resource was found when classpath element paths are scanned. In the
     * case of accepted classfile resources, sublog entries are added when the classfile's contents are scanned.
     */
    @Nullable
    LogNode scanLog;

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Constructor.
     *
     * @param classpathElement
     *            the classpath element this resource was obtained from.
     * @param entry
     *            the entry in the virtual filesystem that this resource is read from.
     * @param path
     *            the path of the resource relative to the package root.
     */
    Resource(final ClasspathElement classpathElement, final VfsEntry entry, final String path) {
        this.classpathElement = classpathElement;
        this.entry = entry;
        this.path = path;
        this.length = entry.getLength();
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Check that this resource can be opened, and mark it as open. Called by the subclass methods that open the
     * resource.
     *
     * @throws IllegalStateException
     *             if the classpath element could not be opened, or the {@link ScanResult} that this resource came
     *             from has been closed, or this resource is already open.
     */
    void checkCanOpen() {
        if (classpathElement.skipClasspathElement) {
            // Shouldn't happen
            throw new IllegalStateException("Classpath element could not be opened");
        }
        final var scanResult = classpathElement.scanResult;
        if (scanResult != null && scanResult.isClosed()) {
            throw new IllegalStateException("Cannot open a resource after the ScanResult is closed");
        }
        // Mark the resource as open last, so that a failed check leaves it closed, and the next attempt to open
        // it reports the same reason again rather than reporting that the resource is already open
        if (isOpen.getAndSet(true)) {
            throw new IllegalStateException(
                    "Resource is already open -- cannot open it again without first calling close()");
        }
    }

    /**
     * Mark this resource as closed. Called by the subclass implementations of {@link #close()}, to guard against
     * releasing the same resources twice.
     *
     * @return true if this resource was open, and has now been marked as closed; false if it was already closed.
     */
    boolean markClosed() {
        return isOpen.getAndSet(false);
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Convert a URI to a URL.
     *
     * @param uri
     *            the uri
     * @return the URL.
     * @throws IllegalStateException
     *             if the URI could not be converted to a URL.
     */
    private static URL uriToURL(final URI uri) {
        try {
            return uri.toURL();
        } catch (final IllegalArgumentException | MalformedURLException e) {
            throw new IllegalStateException("Could not create URL from URI: " + uri + " -- " + e);
        }
    }

    /**
     * Get the {@link URI} representing the resource's location.
     *
     * @return A {@link URI} representing the resource's location.
     * @throws IllegalStateException
     *             if the resource was obtained from a module and the module's location URI is null.
     */
    public URI getURI() {
        final var locationURI = getClasspathElementURI();
        final var locationURIStr = locationURI.toString();
        final var resourcePath = getPathRelativeToClasspathElement();
        // Check if this is a directory-based module (location URI will end in "/")
        final var isDir = locationURIStr.endsWith("/");
        try {
            return new URI(
                    (isDir || locationURIStr.startsWith("jar:") || locationURIStr.startsWith("jrt:") ? "" : "jar:")
                            + locationURIStr + (isDir ? "" : locationURIStr.startsWith("jrt:") ? "/" : "!/")
                            + URLPaths.encodePath(resourcePath));
        } catch (final URISyntaxException e) {
            throw new IllegalStateException("Could not form URL for classpath element: " + locationURIStr
                    + " ; path: " + resourcePath + " : " + e);
        }
    }

    /**
     * Get the {@link URL} representing the resource's location.
     *
     * @return A {@link URL} representing the resource's location.
     * @throws IllegalStateException
     *             if the resource was obtained from a module and the module's location URI is null, or if the
     *             location URI could not be converted to a {@link URL}.
     */
    public URL getURL() {
        return uriToURL(getURI());
    }

    /**
     * Get the {@link URI} of the classpath element or module that this resource was obtained from.
     *
     * @return The {@link URL} of the classpath element or module that this resource was found within.
     * @throws IllegalStateException
     *             if the classpath element does not have a valid URI (e.g. for modules whose location URI is null).
     */
    public URI getClasspathElementURI() {
        return classpathElement.getURI();
    }

    /**
     * Get the {@link URL} of the classpath element or module that this resource was obtained from.
     *
     * @return The {@link URL} of the classpath element or module that this resource was found within.
     * @throws IllegalStateException
     *             if the resource was obtained from a module and the module's location URI is null, or if the
     *             location URI could not be converted to a {@link URL}.
     */
    public URL getClasspathElementURL() {
        return uriToURL(getClasspathElementURI());
    }

    /**
     * Get the classpath element {@link File}.
     *
     * @return The {@link File} for the classpath element package root dir or jar that this {@link Resource} was
     *         found within, or null if this {@link Resource} was found in a module backed by a "jrt:" URI, or a
     *         module with an unknown location. May also return null if the classpath element was an http/https URL,
     *         and the jar was downloaded directly to RAM, rather than to a temp file on disk (e.g. if the temp dir
     *         is not writeable).
     */
    public @Nullable File getClasspathElementFile() {
        return classpathElement.getFile();
    }

    /**
     * Get the {@link ModuleReference} for the module that this {@link Resource} was found within.
     *
     * @return The {@link ModuleReference} for the module that this {@link Resource} was found within, or null if
     *         this {@link Resource} was found in a directory or jar in the classpath.
     */
    public @Nullable ModuleReference getModuleReference() {
        return classpathElement instanceof final ClasspathElementModule classpathElementModule
                ? classpathElementModule.moduleReference
                : null;
    }

    /**
     * Get the {@link VfsEntry} that this {@link Resource} is read from, giving access to the rest of the
     * {@link Vfs} API for the resource: reading it as a {@link java.nio.channels.ReadableByteChannel}, addressing
     * it as a {@link java.nio.file.Path} of a read-only virtual filesystem, or asking for its compressed size.
     *
     * <p>
     * The returned entry stops working once the {@link ScanResult} that this {@link Resource} came from is closed,
     * since closing the {@link ScanResult} closes the {@link Vfs} that the entry is read through.
     *
     * @return the {@link VfsEntry} that this {@link Resource} is read from.
     */
    public VfsEntry getVfsEntry() {
        return entry;
    }

    /**
     * Read this resource's whole content and decode it as UTF-8. (Calls {@link #close()} after completion.)
     *
     * @return the content of this {@link Resource}, as a string.
     * @throws IOException
     *             If an I/O exception occurred.
     */
    public String loadAsString() throws IOException {
        return loadAsString(StandardCharsets.UTF_8);
    }

    /**
     * Read this resource's whole content and decode it in the given charset. Bytes that the charset cannot decode
     * are replaced rather than throwing, as {@link String#String(byte[], Charset)} specifies. (Calls
     * {@link #close()} after completion.)
     *
     * @param charset
     *            the charset to decode the content in.
     * @return the content of this {@link Resource}, as a string.
     * @throws IOException
     *             If an I/O exception occurred.
     */
    public String loadAsString(final Charset charset) throws IOException {
        final String content = new String(load(), charset);
        close();
        return content;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Get the path of this classpath resource relative to the package root.
     *
     * @return the path of this classpath resource relative to the package root. For example, for a resource path of
     *         {@code "BOOT-INF/classes/com/xyz/resource.xml"} and a package root of {@code "BOOT-INF/classes/"},
     *         returns {@code "com/xyz/resource.xml"}. Also drops version prefixes for multi-version jars, for
     *         example for a resource path of {@code "META-INF/versions/11/com/xyz/resource.xml"}, returns
     *         {@code "com/xyz/resource.xml"}.
     */
    public String getPath() {
        return path;
    }

    /**
     * Get the full path of this classpath resource relative to the root of the classpath element.
     *
     * @return the full path of this classpath resource within the classpath element. For example, will return the
     *         full path of {@code "BOOT-INF/classes/com/xyz/resource.xml"} or
     *         {@code "META-INF/versions/11/com/xyz/resource.xml"}, not {@code "com/xyz/resource.xml"}.
     */
    public String getPathRelativeToClasspathElement() {
        // Only overridden for jars
        return getPath();
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Open an {@link InputStream} for a classpath resource. Make sure you call {@link Resource#close()} when you
     * are finished with the {@link InputStream}, so that the {@link InputStream} is closed.
     *
     * @return The opened {@link InputStream}.
     * @throws IOException
     *             If the {@link InputStream} could not be opened.
     */
    public InputStream open() throws IOException {
        checkCanOpen();
        try {
            final var entryInputStream = entry.open();
            // Point the field at the stream the entry opened before wrapping it, so that close() releases the
            // stream even if the wrapper cannot be allocated -- nothing else could reach it to close it
            inputStream = entryInputStream;
            inputStream = new ProxyingInputStream(entryInputStream) {
                /** True once the resource has been closed, so that it is only closed once. */
                private boolean closedResource;

                @Override
                public void close() throws IOException {
                    try {
                        super.close();
                    } finally {
                        // Closing the stream closes the resource it was opened on. Closing the stream a second time
                        // must not close the resource again, since by then the resource may have been reopened.
                        if (!closedResource) {
                            closedResource = true;
                            Resource.this.close();
                        }
                    }
                }
            };
            length = entry.getLength();
            return inputStream;

        } catch (final IOException | RuntimeException | Error e) {
            // Leave the resource closed if it could not be opened, so that opening it can be tried again, and so
            // that anything the entry checked out in order to open it is handed back
            close();
            throw e;
        }
    }

    /**
     * Read a classpath resource as a {@link ByteBuffer}, which is a memory mapping of the resource where the
     * resource is stored uncompressed in a file that can be mapped. Call
     * {@link CloseableByteBuffer#getByteBuffer()} on the returned instance to reach the {@link ByteBuffer}.
     *
     * <p>
     * Close the returned {@link CloseableByteBuffer} when you have finished with it, so that the {@link ByteBuffer}
     * is released or unmapped. You can also close the {@link Resource} instead, which closes the buffer -- closing
     * both is safe, since the buffer is only released once.
     *
     * @return The allocated or mapped {@link ByteBuffer} for the resource file content, wrapped so that it can be
     *         closed.
     * @throws IOException
     *             If the resource could not be read.
     */
    public CloseableByteBuffer read() throws IOException {
        checkCanOpen();
        try {
            final var entryBuffer = entry.read();
            closeableByteBuffer = entryBuffer;
            // The buffer may belong to whatever produced it, and is only valid until this resource is closed
            final var buffer = Objects.requireNonNull(entryBuffer.getByteBuffer());
            length = buffer.remaining();
            // Closing the returned wrapper closes this resource, which releases the buffer the entry produced
            return new CloseableByteBuffer(buffer, this::close);

        } catch (final IOException | RuntimeException | Error e) {
            // Leave the resource closed if it could not be read, so that reading it can be tried again, and so that
            // the buffer the entry produced is released
            close();
            throw e;
        }
    }

    /**
     * Load a classpath resource and return its content as a byte array. Automatically calls
     * {@link Resource#close()} after loading the byte array and before returning it, so that the underlying
     * InputStream is closed or the underlying ByteBuffer is released or unmapped.
     *
     * @return The contents of the resource file.
     * @throws IOException
     *             If the file contents could not be loaded in their entirety.
     */
    public byte[] load() throws IOException {
        checkCanOpen();
        try (Resource res = this) { // Close this after use
            final var byteArray = entry.load();
            res.length = byteArray.length;
            return byteArray;
        }
    }

    /**
     * Get the length of the resource.
     *
     * @return The length of the resource. This only reliably returns a valid value after calling {@link #open()},
     *         {@link #read()}, or {@link #load()} (and for {@link #open()}, only if the underlying jarfile has
     *         length information for corresponding {@link ZipEntry} -- some jarfiles may not have length
     *         information in their zip entries). Returns -1L if the length is unknown.
     */
    public long getLength() {
        return length;
    }

    /**
     * Get the last modified time for the resource, in milliseconds since the epoch. This time is obtained from the
     * directory entry, if this resource is a file on disk, or from the zipfile central directory, if this resource
     * is a zipfile entry. Timestamps are not available for resources obtained from system modules or jlink'd
     * modules.
     *
     * <p>
     * Note: The ZIP format has no notion of timezone, so timestamps are only meaningful if it is known what
     * timezone they were created in. We arbitrarily assume that zipfile timestamps are in the UTC timezone. This
     * may be a wrong assumption, so you may need to apply a timezone correction if you know the timezone used by
     * the zipfile creator.
     *
     * @return The millis since the epoch indicating the date / time that this file resource was last modified.
     *         Returns 0L if the last modified date is unknown.
     */
    public long getLastModifiedMillis() {
        return entry.getLastModifiedMillis();
    }

    /**
     * Get the POSIX file permissions for the resource. POSIX file permissions are obtained from the directory
     * entry, if this resource is a file on disk, or from the zipfile central directory, if this resource is a
     * zipfile entry. POSIX file permissions are not available for resources obtained from system modules or jlink'd
     * modules, and may not be available on non-POSIX-compliant operating systems or non-POSIX filesystems.
     *
     * @return The set of {@link PosixFilePermission} permission flags for the resource, or null if unknown.
     */
    public @Nullable Set<PosixFilePermission> getPosixFilePermissions() {
        return entry.getPosixFilePermissions();
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Get a string representation of the resource's location (as a URL string).
     *
     * @return the resource location as a URL String.
     */
    @Override
    public String toString() {
        if (toString != null) {
            return toString;
        } else {
            return toString = getURI().toString();
        }
    }

    /**
     * Get the hash code of the resource's URI.
     *
     * @return the hash code.
     */
    @Override
    public int hashCode() {
        return toString().hashCode();
    }

    /**
     * Compare this resource with another resource for equality, by URI.
     *
     * @param obj
     *            the object to compare with.
     * @return true if the other object is a {@link Resource} with the same URI.
     */
    @Override
    public boolean equals(final @Nullable Object obj) {
        if (obj == this) {
            return true;
        } else if (!(obj instanceof Resource)) {
            return false;
        }
        return this.toString().equals(obj.toString());
    }

    /**
     * Compare this resource with another resource, by URI.
     *
     * @param other
     *            the resource to compare with.
     * @return the ordering of this resource's URI relative to the other resource's URI.
     */
    @Override
    public int compareTo(final Resource other) {
        return toString().compareTo(other.toString());
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Close the underlying InputStream, or release/unmap the underlying ByteBuffer. Closing a resource that is
     * already closed has no effect, so a resource can be closed both directly and through the stream or buffer it
     * was opened as.
     */
    @Override
    public void close() {
        if (markClosed()) {
            try {
                final var closeableBuffer = closeableByteBuffer;
                if (closeableBuffer != null) {
                    closeableByteBuffer = null;
                    // Releases the buffer, and hands back anything the entry checked out in order to read it
                    closeableBuffer.close();
                }
            } finally {
                // The stream is closed even if the buffer could not be released -- this resource is already marked
                // as closed, so nothing else would close it
                final var in = inputStream;
                if (in != null) {
                    inputStream = null;
                    try {
                        in.close();
                    } catch (final IOException e) {
                        // Ignore
                    }
                }
            }
        }
    }
}
