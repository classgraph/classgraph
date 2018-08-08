/*
 * This file is part of ClassGraph.
 *
 * Author: Luke Hutchison
 *
 * Hosted at: https://github.com/lukehutch/fast-classpath-scanner
 *
 * --
 *
 * The MIT License (MIT)
 *
 * Copyright (c) 2018 Luke Hutchison
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
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.ByteBuffer;
import java.util.zip.ZipEntry;

import io.github.classgraph.utils.FileUtils;
import io.github.classgraph.utils.InputStreamOrByteBufferAdapter;

/**
 * A classpath or module path resource (i.e. file) that was found in a whitelisted/non-blacklisted package inside a
 * classpath element or module.
 */
public abstract class Resource implements Closeable, Comparable<Resource> {
    protected InputStream inputStream;
    protected ByteBuffer byteBuffer;
    protected long length = -1L;
    private String toString;

    // -------------------------------------------------------------------------------------------------------------

    protected InputStream byteBufferToInputStream() {
        return inputStream == null ? inputStream = FileUtils.byteBufferToInputStream(byteBuffer) : inputStream;
    }

    protected ByteBuffer inputStreamToByteBuffer() throws IOException {
        return byteBuffer == null ? byteBuffer = ByteBuffer.wrap(inputStreamToByteArray()) : byteBuffer;
    }

    protected byte[] inputStreamToByteArray() throws IOException {
        return FileUtils.readAllBytesAsArray(inputStream, length, null);
    }

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
     * @return the path of this classpath resource relative to the package root within the classpath element. For
     *         example, for a resource path of "BOOT-INF/classes/com/xyz/resource.xml" and a package root of
     *         "BOOT-INF/classes/", returns "com/xyz/resource.xml".
     */
    public abstract String getPath();

    /**
     * @return the path of this classpath resource within the classpath element. For example, for a resource path of
     *         "BOOT-INF/classes/com/xyz/resource.xml", returns "BOOT-INF/classes/com/xyz/resource.xml", even if the
     *         package root is "BOOT-INF/classes/".
     */
    public abstract String getPathRelativeToClasspathElement();

    /**
     * @return A URL representing the resource's location. May point to a temporary file that ClassGraph extracted
     *         an inner jar or directory to, or downloaded a remote jar to. You may or may not be able to fetch
     *         content from the URL.
     * @throws IllegalArgumentException
     *             if a {@link MalformedURLException} occurred while trying to construct the URL.
     */
    public abstract URL getURL();

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
     */
    abstract InputStreamOrByteBufferAdapter openOrRead() throws IOException;

    /**
     * @return The length of the resource. This only reliably returns a valid value after calling {@link #open()},
     *         {@link #read()}, or {@link #load()}, and for {@link #open()}, only if the underlying jarfile has
     *         length information for corresponding {@link ZipEntry} (some jarfiles may not have length information
     *         in their zip entries). Returns -1L if the length is unknown.
     */
    public long getLength() {
        return length;
    }

    // -------------------------------------------------------------------------------------------------------------

    protected abstract String toStringImpl();

    /** Return a string representation of the resource's location. */
    @Override
    public String toString() {
        if (toString != null) {
            return toString;
        } else {
            return toString = toStringImpl();
        }
    }

    @Override
    public int hashCode() {
        return toString().hashCode();
    }

    @Override
    public boolean equals(final Object obj) {
        if (!(obj instanceof Resource)) {
            return false;
        }
        return this.toString().equals(obj.toString());
    }

    @Override
    public int compareTo(final Resource o) {
        return toString().compareTo(o.toString());
    }

    // -------------------------------------------------------------------------------------------------------------

    /** Close the underlying InputStream, or release/unmap the underlying ByteBuffer. */
    @Override
    public abstract void close();
}
