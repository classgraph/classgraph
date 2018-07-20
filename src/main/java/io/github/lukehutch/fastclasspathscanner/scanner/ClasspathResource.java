/*
 * This file is part of FastClasspathScanner.
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
package io.github.lukehutch.fastclasspathscanner.scanner;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;

import io.github.lukehutch.fastclasspathscanner.utils.FileUtils;

/**
 * A classpath resource (or file) that was found in a whitelisted/non-blacklisted package inside a classpath
 * element.
 */
public abstract class ClasspathResource implements AutoCloseable {
    protected InputStream inputStream;
    protected ByteBuffer byteBuffer;
    protected long length = -1L;

    protected InputStream byteBufferToInputStream() {
        return inputStream == null ? inputStream = FileUtils.byteBufferToInputStream(byteBuffer) : inputStream;
    }

    protected ByteBuffer inputStreamToByteBuffer() throws IOException {
        return byteBuffer == null ? byteBuffer = ByteBuffer.wrap(inputStreamToByteArray()) : byteBuffer;
    }

    protected byte[] inputStreamToByteArray() throws IOException {
        return FileUtils.readAllBytes(inputStream, length, null);
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

    /**
     * Returns the path of this classpath resource relative to the package root within the classpath element.
     * 
     * @returns the path of this classpath resource relative to the package root within the classpath element. For
     *          example, for a resource path of "BOOT-INF/classes/com/xyz/resource.xml" and a package root of
     *          "BOOT-INF/classes/", returns "com/xyz/resource.xml".
     */
    public abstract String getPathRelativeToPackageRoot();

    /**
     * Returns the path of this classpath resource within the classpath element.
     * 
     * @returns the path of this classpath resource within the classpath element. For example, for a resource path
     *          of "BOOT-INF/classes/com/xyz/resource.xml", returns "BOOT-INF/classes/com/xyz/resource.xml" (even if
     *          the package root is "BOOT-INF/classes/").
     */
    public abstract String getPathRelativeToClasspathElement();

    /**
     * Open an InputStream for a classpath resource. Make sure you call {@link ClasspathResource#close()} when you
     * are finished with the InputStream, so that the InputStream is closed.
     */
    public abstract InputStream open() throws IOException;

    /**
     * Open a ByteBuffer for a classpath resource. Make sure you call {@link ClasspathResource#close()} when you are
     * finished with the ByteBuffer, so that the ByteBuffer is released or unmapped.
     */
    public abstract ByteBuffer read() throws IOException;

    /**
     * Load a classpath resource and return its content as a byte array. Automatically calls
     * {@link ClasspathResource#close()} after loading the byte array and before returning it, so that the
     * underlying InputStream is closed or the underlying ByteBuffer is released or unmapped.
     */
    public abstract byte[] load() throws IOException;

    /**
     * Get length of InputStream or ByteBuffer -- only set after calling {@link #open()} or {@link #read()}. (For
     * some resources in jarfiles, length will be unknown even after calling {@link #open()} or {@link #read()}.)
     * Returns -1 if length is unknown.
     */
    public long getLength() {
        return length;
    }

    /** Close the underlying InputStream, or release/unmap the underlying ByteBuffer. */
    @Override
    public void close() {
        if (inputStream != null) {
            try {
                inputStream.close();
                inputStream = null;
            } catch (final IOException e) {
            }
        }
        if (byteBuffer != null) {
            byteBuffer = null;
        }
    }
}
