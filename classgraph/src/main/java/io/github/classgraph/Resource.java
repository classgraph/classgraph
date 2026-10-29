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
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;

import org.jetbrains.annotations.Unmodifiable;
import org.jspecify.annotations.Nullable;

import io.github.classgraph.base.LogNode;
import io.github.classgraph.base.internal.utils.ProxyingInputStream;
import io.github.classgraph.vfs.CloseableByteBuffer;
import io.github.classgraph.vfs.Vfs;
import io.github.classgraph.vfs.VfsEntry;

/**
 * A classpath or module path resource (file) found in an accepted, non-rejected package within a classpath element
 * or module.
 *
 * <p>
 * Call {@link #getVfsEntry()} to access the underlying {@link VfsEntry} in the virtual filesystem ({@link Vfs}).
 * See {@link #getPath()} and {@link #getPathRelativeToContainer()} for how resource paths are represented relative
 * to the package root and the containing jarfile or directory.
 */
public final class Resource implements AutoCloseable, Comparable<Resource> {
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
     * Check that this resource can be opened, and mark it as open. Called by each of the methods that open the
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
     * Mark this resource as closed. Called by {@link #close()}, to guard against releasing the same resources
     * twice.
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
            throw new IllegalStateException("Could not create URL from URI: " + uri + " : " + e, e);
        }
    }

    /**
     * Get the {@link URI} representing the resource's location.
     *
     * @return A {@link URI} representing the resource's location.
     * @throws IllegalStateException
     *             if the {@link URI} could not be formed, which includes the case of a resource obtained from a
     *             module that does not report where its resources are.
     */
    public URI getURI() {
        // The entry knows where it is, and knows it as a URI rather than as a string, so it is asked rather than
        // rebuilt from the classpath element's URI and this resource's path -- taking a URI apart into strings and
        // putting it back together again is how a path ends up encoded twice, or not at all
        return entry.getURI();
    }

    /**
     * Get the {@link URL} representing the resource's location.
     *
     * @return A {@link URL} representing the resource's location.
     * @throws IllegalStateException
     *             if the {@link URL} could not be formed, which includes the case of a resource obtained from a
     *             module that does not report where its resources are, and the case of a {@link URI} whose scheme
     *             has no protocol handler installed.
     */
    public URL getURL() {
        return entry.getURL();
    }

    /**
     * Get the {@link URI} of the classpath element or module that this resource was obtained from.
     *
     * @return The {@link URI} of the classpath element or module that this resource was found within.
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
     * Get the classpath element {@link File}. For a jarfile nested within another jarfile, this is the outermost
     * jarfile.
     *
     * @return The {@link File} for the classpath element package root dir or jar that this {@link Resource} was
     *         found within, or null if this {@link Resource} was found in a module backed by a "jrt:" URI, or a
     *         module whose location is unknown or is not an existing file, or in a directory or jar in a filesystem
     *         that supports the {@link java.nio.file.Path} API but not the {@link File} API. A jar downloaded from
     *         a URL, such as an http or https URL, is held in RAM, and this returns null for it, unless it is
     *         larger than {@link ClassGraph#setMaxBufferedJarRAMSize(int)} allows: it is then written to a
     *         temporary file, and this returns that file, which is deleted when the {@link ScanResult} is closed.
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
     * Get the underlying {@link VfsEntry} for this {@link Resource}, which provides additional {@link Vfs}
     * operations such as opening a {@link java.nio.channels.ReadableByteChannel}, obtaining a
     * {@link java.nio.file.Path} in the read-only virtual filesystem, or querying the compressed size. The returned
     * entry cannot be used after the enclosing {@link ScanResult} is closed.
     *
     * @return the {@link VfsEntry} for this {@link Resource}.
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
     * Get the logical path of this resource relative to the package root, with any package root prefix and
     * multi-release version prefix stripped. This path corresponds to the resource's package and can be passed to
     * {@link ClassLoader#getResource(String)}.
     *
     * <p>
     * Two prefixes are stripped when present:
     *
     * <ul>
     * <li>the <b>package root prefix</b> (e.g. {@code "BOOT-INF/classes/"} in a Spring Boot jar or
     * {@code "WEB-INF/classes/"} in a WAR), which is part of {@link #getClasspathElementURI()};
     * <li>the <b>multi-release version prefix</b> (e.g. {@code "META-INF/versions/11/"} in a multi-release jar).
     * </ul>
     *
     * <p>
     * By contrast, {@link #getPathRelativeToContainer()} returns the full path within the containing jarfile or
     * directory, including both prefixes:
     *
     * <table>
     * <caption>Resource paths for each combination of package root prefix and multi-release version
     * prefix</caption>
     * <tr>
     * <th>Stored path</th>
     * <th>{@link #getPackageRootPrefix()}</th>
     * <th>{@link #getPath()}</th>
     * <th>{@link #getPathRelativeToContainer()}</th>
     * </tr>
     * <tr>
     * <td>{@code "com/xyz/Widget.class"}</td>
     * <td>{@code ""}</td>
     * <td>{@code "com/xyz/Widget.class"}</td>
     * <td>{@code "com/xyz/Widget.class"}</td>
     * </tr>
     * <tr>
     * <td>{@code "META-INF/versions/11/com/xyz/Widget.class"}</td>
     * <td>{@code ""}</td>
     * <td>{@code "com/xyz/Widget.class"}</td>
     * <td>{@code "META-INF/versions/11/com/xyz/Widget.class"}</td>
     * </tr>
     * <tr>
     * <td>{@code "BOOT-INF/classes/com/xyz/Widget.class"}</td>
     * <td>{@code "BOOT-INF/classes/"}</td>
     * <td>{@code "com/xyz/Widget.class"}</td>
     * <td>{@code "BOOT-INF/classes/com/xyz/Widget.class"}</td>
     * </tr>
     * <tr>
     * <td>{@code "META-INF/versions/11/BOOT-INF/classes/com/xyz/Widget.class"}</td>
     * <td>{@code "BOOT-INF/classes/"}</td>
     * <td>{@code "com/xyz/Widget.class"}</td>
     * <td>{@code "META-INF/versions/11/BOOT-INF/classes/com/xyz/Widget.class"}</td>
     * </tr>
     * </table>
     *
     * @return the path of this resource relative to the package root, with any multi-release version prefix
     *         stripped.
     */
    public String getPath() {
        return path;
    }

    /**
     * Get the physical path of this resource within the containing jarfile or directory, including any
     * multi-release version prefix and package root prefix (see {@link #getPath()}).
     *
     * @return the path of this resource within the jarfile or directory that contains it.
     */
    public String getPathRelativeToContainer() {
        return multiReleaseVersionPrefix() + classpathElement.packageRootPrefix + path;
    }

    /**
     * Get the package root prefix of this resource's classpath element (the directory within the containing jarfile
     * or directory where the classpath element starts, ending in {@code '/'}, e.g. {@code "BOOT-INF/classes/"} or
     * {@code "WEB-INF/classes/"}), or {@code ""} if none.
     *
     * @return the package root prefix, ending in {@code '/'}, or {@code ""} if none.
     */
    public String getPackageRootPrefix() {
        return classpathElement.packageRootPrefix;
    }

    /**
     * Get the multi-release version prefix stripped by {@link #getPath()} (e.g. {@code "META-INF/versions/11/"}),
     * or {@code ""} if this resource is not a versioned entry of a multi-release jarfile.
     *
     * @return the multi-release version prefix, ending in {@code '/'}, or {@code ""} if none.
     */
    private String multiReleaseVersionPrefix() {
        final var rawPath = entry.getRawPathFromRoot();
        final var unversionedPath = classpathElement.packageRootPrefix + path;
        return rawPath.length() > unversionedPath.length() && rawPath.endsWith(unversionedPath)
                ? rawPath.substring(0, rawPath.length() - unversionedPath.length())
                : "";
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
     * Read a classpath resource as a {@link ByteBuffer} (on Windows, a view of the memory-mapped jarfile when the
     * resource is stored uncompressed in a jarfile on disk, and otherwise a copy). Call
     * {@link CloseableByteBuffer#getByteBuffer()} on the returned instance to obtain the {@link ByteBuffer}.
     *
     * <p>
     * Close the returned {@link CloseableByteBuffer} or this {@link Resource} when finished to release or unmap the
     * buffer (closing both is safe, and closing the {@link ScanResult} also closes the buffer). See
     * {@link CloseableByteBuffer#getByteBuffer()} for the lifecycle of the underlying {@link ByteBuffer}.
     *
     * @return The allocated or mapped {@link ByteBuffer} for the resource file content, wrapped in a
     *         {@link CloseableByteBuffer}.
     * @throws IOException
     *             If the resource could not be read.
     */
    public CloseableByteBuffer read() throws IOException {
        checkCanOpen();
        try {
            final var entryBuffer = entry.read();
            // Point the field at the entry's buffer before wrapping it, so that close() releases the buffer even if
            // the wrapper cannot be allocated
            closeableByteBuffer = entryBuffer;
            // The buffer may belong to whatever produced it, and is only valid until this resource is closed
            length = Objects.requireNonNull(entryBuffer.getByteBuffer()).remaining();
            // Closing the returned wrapper releases the entry's buffer and closes this resource. Closing this
            // resource closes the returned wrapper, so the wrapper's close action has already run if the caller
            // closes it afterward, and closing it then cannot close a later open() or read() of this resource. The
            // wrapper is built on the entry's buffer, so it reports that it is closed when the root the entry was
            // read from closes the entry's buffer, as closing the ScanResult does.
            final var callerBuffer = new CloseableByteBuffer(entryBuffer, () -> {
                try {
                    entryBuffer.close();
                } finally {
                    close();
                }
            });
            closeableByteBuffer = callerBuffer;
            return callerBuffer;

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
     *         length information for the corresponding {@link ZipEntry}). Returns -1L if the length is unknown.
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
     * Note: The MS-DOS timestamp of a zip entry is a local date and time, and does not record which time zone it is
     * in. We arbitrarily assume that it is in UTC, so if the zipfile was written in another time zone, you will
     * need to correct for the offset yourself. (java.util.zip.ZipFile assumes instead that it is in the default
     * time zone of the JVM reading it.) It also counts seconds in steps of two, so it can be up to two seconds
     * earlier than the time the file was written. An entry that also has an extended timestamp extra field or an
     * Info-ZIP Unix extra field, which are in UTC to the second, gets its time from that field, and needs no
     * correction.
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
     * @return The set of {@link PosixFilePermission} permission flags for the resource, as an unmodifiable set that
     *         iterates in {@link PosixFilePermission} declaration order, or null if unknown.
     */
    public @Nullable @Unmodifiable Set<PosixFilePermission> getPosixFilePermissions() {
        return entry.getPosixFilePermissions();
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Get a string representation of the resource's location: its URI, or, for a resource of a module that cannot
     * name a URI for it (see {@link #getURI()}), the module name and the path within the module, separated by
     * {@code "/"}, which is the notation the JDK uses for a resource of a module.
     *
     * @return the resource's URI as a string, or the module name and the path within the module if the resource has
     *         no URI.
     */
    @Override
    public String toString() {
        // The field is read only once, into a local, since this may be called from several threads, and without
        // synchronization a second read of the field could see null even after the first read saw a value
        var str = toString;
        if (str == null) {
            try {
                str = getURI().toString();
            } catch (final IllegalStateException e) {
                // A module's ModuleReader may be unable to name a URI for a resource that it can list and open.
                // The entry's path is then used instead, which for a module resource is "<module>/<path>". This
                // has no URI scheme unless the module name contains ':', which no module declared in Java source
                // can, so it does not equal the URI string of another resource. It is cached like a URI string, so
                // that hashCode() and equals() stay consistent, and do not ask the ModuleReader for the URI again
                // on every call
                str = entry.getPath();
            }
            toString = str;
        }
        return str;
    }

    /**
     * Get the hash code of the resource's URI, or of its {@link #toString()} form if it has no URI.
     *
     * @return the hash code.
     */
    @Override
    public int hashCode() {
        return toString().hashCode();
    }

    /**
     * Compare this resource with another resource for equality, by URI, or by {@link #toString()} form for a
     * resource that has no URI.
     *
     * @param obj
     *            the object to compare with.
     * @return true if the other object is a {@link Resource} with the same {@link #toString()} form, which is the
     *         same URI for two resources that have one.
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
     * Compare this resource with another resource, by URI, or by {@link #toString()} form for a resource that has
     * no URI.
     *
     * @param other
     *            the resource to compare with.
     * @return the ordering of this resource's {@link #toString()} form relative to the other resource's.
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
