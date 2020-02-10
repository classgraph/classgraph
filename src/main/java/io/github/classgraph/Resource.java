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
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;
import java.util.zip.ZipEntry;

import nonapi.io.github.classgraph.utils.FileUtils;
import nonapi.io.github.classgraph.utils.InputStreamOrByteBufferAdapter;
import nonapi.io.github.classgraph.utils.LogNode;
import nonapi.io.github.classgraph.utils.URLPathEncoder;

/**
 * A classpath or module path resource (i.e. file) that was found in a whitelisted/non-blacklisted package inside a
 * classpath element or module.
 */
public abstract class Resource implements Closeable, Comparable<Resource> {
    /** The classpath element this resource was obtained from. */
    private final ClasspathElement classpathElement;

    /** The input stream, or null. */
    protected InputStream inputStream;

    /** The byte buffer, or null. */
    protected ByteBuffer byteBuffer;

    /** The length, or -1L for unknown. */
    protected long length;

    /** True if the resource is open. */
    private boolean isOpen;

    /** The cached result of toString(). */
    private String toString;

    /**
     * The {@link LogNode} used to log that the resource was found when classpath element paths are scanned. In the
     * case of whitelisted classfile resources, sublog entries are added when the classfile's contents are scanned.
     */
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
    public Resource(final ClasspathElement classpathElement, final long length) {
        this.classpathElement = classpathElement;
        this.length = length;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Create an {@link InputStream} from a {@link ByteBuffer}.
     *
     * @return the input stream
     */
    protected InputStream byteBufferToInputStream() {
        return inputStream == null ? inputStream = FileUtils.byteBufferToInputStream(byteBuffer) : inputStream;
    }

    /**
     * Create a {@link ByteBuffer} from an {@link InputStream}.
     *
     * @return the byte buffer
     * @throws IOException
     *             if an I/O exception occurs.
     */
    protected ByteBuffer inputStreamToByteBuffer() throws IOException {
        return byteBuffer == null ? byteBuffer = ByteBuffer.wrap(inputStreamToByteArray()) : byteBuffer;
    }

    /**
     * Read all bytes from an {@link InputStream} and return as a byte array.
     *
     * @return the contents of the {@link InputStream}.
     * @throws IOException
     *             if an I/O exception occurs.
     */
    protected byte[] inputStreamToByteArray() throws IOException {
        return FileUtils.readAllBytesAsArray(inputStream, length);
    }

    /**
     * Read/copy contents of a {@link ByteBuffer} as a byte array.
     *
     * @return the contents of the {@link ByteBuffer} as a byte array.
     */
    protected byte[] byteBufferToByteArray() {
        if (byteBuffer.hasArray()) {
            return byteBuffer.array();
        } else {
            final byte[] byteArray = new byte[byteBuffer.remaining()];
            byteBuffer.get(byteArray);
            return byteArray;
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Class for closing the parent {@link Resource} when an {@link InputStream} opened on the resource is closed.
     */
    protected class InputStreamResourceCloser extends InputStream {

        /** The input stream. */
        private InputStream inputStream;

        /** The parent resource. */
        private Resource parentResource;

        /**
         * Constructor.
         *
         * @param parentResource
         *            the parent resource
         * @param inputStream
         *            the input stream
         * @throws IOException
         *             if an I/O exception occurs.
         */
        protected InputStreamResourceCloser(final Resource parentResource, final InputStream inputStream)
                throws IOException {
            super();
            if (inputStream == null) {
                throw new IOException("InputStream cannot be null");
            }
            this.inputStream = inputStream;
            this.parentResource = parentResource;
        }

        /* (non-Javadoc)
         * @see java.io.InputStream#read()
         */
        @Override
        public int read() throws IOException {
            if (inputStream == null) {
                throw new IOException("InputStream is not open");
            }
            return inputStream.read();
        }

        /* (non-Javadoc)
         * @see java.io.InputStream#read(byte[], int, int)
         */
        @Override
        public int read(final byte[] b, final int off, final int len) throws IOException {
            if (inputStream == null) {
                throw new IOException("InputStream is not open");
            }
            return inputStream.read(b, off, len);
        }

        /* (non-Javadoc)
         * @see java.io.InputStream#read(byte[])
         */
        @Override
        public int read(final byte[] b) throws IOException {
            if (inputStream == null) {
                throw new IOException("InputStream is not open");
            }
            return inputStream.read(b);
        }

        /* (non-Javadoc)
         * @see java.io.InputStream#available()
         */
        @Override
        public int available() throws IOException {
            if (inputStream == null) {
                throw new IOException("InputStream is not open");
            }
            return inputStream.available();
        }

        /* (non-Javadoc)
         * @see java.io.InputStream#skip(long)
         */
        @Override
        public long skip(final long n) throws IOException {
            if (inputStream == null) {
                throw new IOException("InputStream is not open");
            }
            return inputStream.skip(n);
        }

        /* (non-Javadoc)
         * @see java.io.InputStream#markSupported()
         */
        @Override
        public boolean markSupported() {
            return inputStream.markSupported();
        }

        /* (non-Javadoc)
         * @see java.io.InputStream#mark(int)
         */
        @Override
        public synchronized void mark(final int readlimit) {
            inputStream.mark(readlimit);
        }

        /* (non-Javadoc)
         * @see java.io.InputStream#reset()
         */
        @Override
        public synchronized void reset() throws IOException {
            if (inputStream == null) {
                throw new IOException("InputStream is not open");
            }
            inputStream.reset();
        }

        /**
         * Close the wrapped InputStream, but don't close parent resource.
         *
         * @throws IOException
         *             if an I/O exception occurs.
         */
        void closeInputStream() throws IOException {
            if (inputStream != null) {
                try {
                    inputStream.close();
                } catch (final IOException e) {
                    // Ignore
                }
                inputStream = null;
            }
        }

        /**
         * Close the parent resource by calling {@link Resource#close()}, which will call
         * {@link #closeInputStream()}.
         *
         * @throws IOException
         *             if an I/O exception occurs.
         */
        @Override
        public void close() throws IOException {
            if (parentResource != null) {
                parentResource.close();
                parentResource = null;
            }
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Mark the resource as open.
     *
     * @throws IOException
     *             If the resource is already open.
     */
    protected void markAsOpen() throws IOException {
        if (isOpen) {
            throw new IOException("Resource is already open -- cannot open it again without first calling close()");
        }
        isOpen = true;
    }

    /** Mark the resource as closed. */
    protected void markAsClosed() {
        isOpen = false;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Convert a URI to URL, catching "jrt:" URIs as invalid.
     *
     * @param uri
     *            the uri
     * @return the URL.
     * @throws IllegalArgumentException
     *             if the URI could not be converted to a URL, or the URI had "jrt:" scheme.
     */
    private static URL uriToURL(final URI uri) {
        if (uri.getScheme().equals("jrt")) {
            // Currently URL cannot handle the "jrt:" scheme, used by system modules.
            throw new IllegalArgumentException("Could not create URL from URI with \"jrt:\" scheme: " + uri);
        }
        try {
            return uri.toURL();
        } catch (final MalformedURLException e) {
            throw new IllegalArgumentException("Could not create URL from URI: " + uri + " -- " + e);
        }
    }

    /**
     * Get the {@link URI} representing the resource's location.
     *
     * @return A {@link URI} representing the resource's location.
     * @throws IllegalArgumentException
     *             the resource was obtained from a module and the module's location URI is null.
     */
    public URI getURI() {
        final URI locationURI = getClasspathElementURI();
        final String locationURIStr = locationURI.toString();
        final String resourcePath = getPathRelativeToClasspathElement();
        // Check if this is a directory-based module (location URI will end in "/")
        final boolean isDir = locationURIStr.endsWith("/");
        try {
            return new URI((isDir || locationURIStr.startsWith("jar:") ? "" : "jar:") + locationURIStr
                    + (isDir ? "" : "!/") + URLPathEncoder.encodePath(resourcePath));
        } catch (final URISyntaxException e) {
            throw new IllegalArgumentException("Could not form URL for classpath element: " + locationURIStr
                    + " ; path: " + resourcePath + " : " + e);
        }
    }

    /**
     * Get the {@link URL} representing the resource's location. Use {@link #getURI()} instead if the resource may
     * have come from a system module, or if this is a jlink'd runtime image, since "jrt:" URI schemes used by
     * system modules and jlink'd runtime images are not supported by {@link URL}, and this will cause
     * {@link IllegalArgumentException} to be thrown.
     *
     * @return A {@link URL} representing the resource's location.
     * @throws IllegalArgumentException
     *             if the resource was obtained from a system module or jlink'd runtime image with a "jrt:" location
     *             URI, or the resource was obtained from a module and the module's location URI is null
     */
    public URL getURL() {
        return uriToURL(getURI());
    }

    /**
     * Get the {@link URI} of the classpath element or module that this resource was obtained from.
     *
     * @return The {@link URL} of the classpath element or module that this resource was found within.
     * @throws IllegalArgumentException
     *             if the classpath element does not have a valid URI (e.g. for modules whose location URI is null).
     */
    public URI getClasspathElementURI() {
        return classpathElement.getURI();
    }

    /**
     * Get the {@link URL} of the classpath element or module that this resource was obtained from. Use
     * {@link #getClasspathElementURI()} instead if the resource may have come from a system module, or if this is a
     * jlink'd runtime image, since "jrt:" URI schemes used by system modules and jlink'd runtime images are not
     * supported by {@link URL}, and this will cause {@link IllegalArgumentException} to be thrown.
     *
     * @return The {@link URL} of the classpath element or module that this resource was found within.
     * @throws IllegalArgumentException
     *             if the resource was obtained from a system module or jlink'd runtime image with a "jrt:" location
     *             URI, or the resource was obtained from a module and the module's location URI is null.
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
    public File getClasspathElementFile() {
        return classpathElement.getFile();
    }

    /**
     * Get the The {@link ModuleRef} for the module that this {@link Resource} was found within.
     *
     * @return The {@link ModuleRef} for the module that this {@link Resource} was found within, as a
     *         {@link ModuleRef}, or null if this {@link Resource} was found in a directory or jar in the classpath.
     */
    public ModuleRef getModuleRef() {
        return classpathElement instanceof ClasspathElementModule
                ? ((ClasspathElementModule) classpathElement).moduleRef
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
        try {
            return new String(load(), StandardCharsets.UTF_8);
        } finally {
            close();
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Get the path of this classpath resource relative to the package root.
     *
     * @return the path of this classpath resource relative to the package root. For example, for a resource path of
     *         {@code "BOOT-INF/classes/com/xyz/resource.xml"} and a package root of {@code "BOOT-INF/classes/"},
     *         returns {@code "com/xyz/resource.xml"}. Also drops version prefixes for multi-version jars, for
     *         example for a resource path of {@code "META-INF/versions/11/com/xyz/resource.xml"} while running on
     *         JDK 9+, returns {@code "com/xyz/resource.xml"}.
     */
    public abstract String getPath();

    /**
     * Get the full path of this classpath resource relative to the root of the classpath element.
     *
     * @return the full path of this classpath resource within the classpath element. For example, will return the
     *         full path of {@code "BOOT-INF/classes/com/xyz/resource.xml"} or
     *         {@code "META-INF/versions/11/com/xyz/resource.xml"}, not {@code "com/xyz/resource.xml"}.
     */
    public abstract String getPathRelativeToClasspathElement();

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
     * finished with the {@link ByteBuffer}, so that the {@link ByteBuffer} is released or unmapped.
     *
     * @return The allocated or mapped {@link ByteBuffer} for the resource file content.
     * @throws IOException
     *             If the resource could not be opened.
     */
    public abstract ByteBuffer read() throws IOException;

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
     * Open a {@link ByteBuffer}, if there is an efficient underlying mechanism for opening one, otherwise open an
     * {@link InputStream}.
     *
     * @return the {@link InputStreamOrByteBufferAdapter}
     * @throws IOException
     *             if an I/O exception occurs.
     */
    abstract InputStreamOrByteBufferAdapter openOrRead() throws IOException;

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
    public abstract long getLastModified();

    /**
     * Get the POSIX file permissions for the resource. POSIX file permissions are obtained from the directory
     * entry, if this resource is a file on disk, or from the zipfile central directory, if this resource is a
     * zipfile entry. POSIX file permissions are not available for resources obtained from system modules or jlink'd
     * modules, and may not be available on non-POSIX-compliant operating systems or non-POSIX filesystems.
     *
     * @return The set of {@link PosixFilePermission} permission flags for the resource, or null if unknown.
     */
    public abstract Set<PosixFilePermission> getPosixFilePermissions();

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

    /* (non-Javadoc)
     * @see java.lang.Object#hashCode()
     */
    @Override
    public int hashCode() {
        return toString().hashCode();
    }

    /* (non-Javadoc)
     * @see java.lang.Object#equals(java.lang.Object)
     */
    @Override
    public boolean equals(final Object obj) {
        if (obj == this) {
            return true;
        } else if (!(obj instanceof Resource)) {
            return false;
        }
        return this.toString().equals(obj.toString());
    }

    /* (non-Javadoc)
     * @see java.lang.Comparable#compareTo(java.lang.Object)
     */
    @Override
    public int compareTo(final Resource o) {
        return toString().compareTo(o.toString());
    }

    // -------------------------------------------------------------------------------------------------------------

    /** Close the underlying InputStream, or release/unmap the underlying ByteBuffer. */
    @Override
    public void close() {
        // Override in subclasses, and call super.close(), then at end, markAsClosed()
        if (inputStream != null) {
            try {
                if (inputStream instanceof InputStreamResourceCloser) {
                    ((InputStreamResourceCloser) inputStream).closeInputStream();
                } else {
                    inputStream.close();
                }
            } catch (final IOException e) {
                // Ignore
            }
            inputStream = null;
        }
    }
}
