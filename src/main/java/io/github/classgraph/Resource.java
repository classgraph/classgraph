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
 * Copyright (c) 2019 Luke Hutchison
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

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.module.ModuleReference;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;

import nonapi.io.github.classgraph.fileslice.reader.ClassfileReader;
import nonapi.io.github.classgraph.utils.LogNode;
import nonapi.io.github.classgraph.utils.URLPathEncoder;
import org.jspecify.annotations.Nullable;

/**
 * A classpath or module path resource (i.e. file) that was found in an accepted/non-rejected package inside a
 * classpath element or module.
 */
public abstract class Resource implements Closeable, Comparable<Resource> {
    /** The classpath element this resource was obtained from. */
    private final ClasspathElement classpathElement;

    /** True if this resource is currently open. */
    private final AtomicBoolean isOpen = new AtomicBoolean();

    /** The input stream, or null. */
    @Nullable
    InputStream inputStream;

    /** The byte buffer, or null. */
    @Nullable
    ByteBuffer byteBuffer;

    /** The length, or -1L for unknown. */
    long length;

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
     * @param length
     *            the length the length of the resource.
     */
    Resource(final ClasspathElement classpathElement, final long length) {
        this.classpathElement = classpathElement;
        this.length = length;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Check that this resource can be opened, and mark it as open. Called by the subclass methods that open the
     * resource.
     *
     * @throws IllegalStateException
     *             if the classpath element could not be opened, or this resource is already open, or the
     *             {@link ScanResult} that this resource came from has been closed.
     */
    void checkCanOpen() {
        if (classpathElement.skipClasspathElement) {
            // Shouldn't happen
            throw new IllegalStateException("Classpath element could not be opened");
        }
        if (isOpen.getAndSet(true)) {
            throw new IllegalStateException(
                    "Resource is already open -- cannot open it again without first calling close()");
        }
        final var scanResult = classpathElement.scanResult;
        if (scanResult != null && scanResult.isClosed()) {
            throw new IllegalStateException("Cannot open a resource after the ScanResult is closed");
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
                            + URLPathEncoder.encodePath(resourcePath));
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
     * Convenience method to get the content of this {@link Resource} as a String. Assumes UTF8 encoding. (Calls
     * {@link #close()} after completion.)
     *
     * @return the content of this {@link Resource} as a String.
     * @throws IOException
     *             If an I/O exception occurred.
     */
    public String getContentAsString() throws IOException {
        final String content = new String(load(), StandardCharsets.UTF_8);
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
    public abstract String getPath();

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
    public abstract InputStream open() throws IOException;

    /**
     * Open a {@link ByteBuffer} for a classpath resource. Make sure you call {@link Resource#close()} when you are
     * finished with the {@link ByteBuffer}, so that the {@link ByteBuffer} is released or unmapped. See also
     * {@link #readCloseable()}.
     *
     * @return The allocated or mapped {@link ByteBuffer} for the resource file content.
     * @throws IOException
     *             If the resource could not be read.
     */
    public abstract ByteBuffer read() throws IOException;

    /**
     * Open a {@link ByteBuffer} for a classpath resource, and wrap it in a {@link CloseableByteBuffer} instance,
     * which implements the {@link Closeable#close()} method to free the underlying {@link ByteBuffer} when
     * {@link CloseableByteBuffer#close()} is called, by automatically calling {@link Resource#close()}.
     *
     * <p>
     * Call {@link CloseableByteBuffer#getByteBuffer()} on the returned instance to access the underlying
     * {@link ByteBuffer}.
     *
     * @return The allocated or mapped {@link ByteBuffer} for the resource file content.
     * @throws IOException
     *             If the resource could not be read.
     */
    public CloseableByteBuffer readCloseable() throws IOException {
        return new CloseableByteBuffer(read(), this::close);
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
    public abstract byte[] load() throws IOException;

    /**
     * Open a {@link ClassfileReader} on the resource (for reading classfiles).
     *
     * @return the {@link ClassfileReader}.
     * @throws IOException
     *             if an I/O exception occurs.
     */
    abstract ClassfileReader openClassfile() throws IOException;

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
    public abstract long getLastModifiedMillis();

    /**
     * Get the POSIX file permissions for the resource. POSIX file permissions are obtained from the directory
     * entry, if this resource is a file on disk, or from the zipfile central directory, if this resource is a
     * zipfile entry. POSIX file permissions are not available for resources obtained from system modules or jlink'd
     * modules, and may not be available on non-POSIX-compliant operating systems or non-POSIX filesystems.
     *
     * @return The set of {@link PosixFilePermission} permission flags for the resource, or null if unknown.
     */
    public abstract @Nullable Set<PosixFilePermission> getPosixFilePermissions();

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
     * Close the underlying InputStream, or release/unmap the underlying ByteBuffer.
     */
    @Override
    public void close() {
        // Subclasses override this, guarding the body with markClosed() and calling super.close() at the end
        final var in = inputStream;
        if (in != null) {
            try {
                in.close();
            } catch (final IOException e) {
                // Ignore
            }
            inputStream = null;
        }
    }
}
