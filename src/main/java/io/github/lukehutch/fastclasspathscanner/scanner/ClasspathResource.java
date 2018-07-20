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
 * Copyright (c) 2016 Luke Hutchison
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

/** The combination of a classpath element and a relative path within this classpath element. */
public abstract class ClasspathResource implements AutoCloseable {
    protected InputStream inputStream;
    protected ByteBuffer byteBuffer;
    protected byte[] byteArray;
    protected long length = -1L;

    // https://stackoverflow.com/questions/4332264/wrapping-a-bytebuffer-with-an-inputstream/6603018#6603018
    protected InputStream byteBufferToInputStream() {
        return inputStream == null ? inputStream = FileUtils.byteBufferToInputStream(byteBuffer) : inputStream;
    }

    protected ByteBuffer inputStreamToByteBuffer() throws IOException {
        return byteBuffer == null ? byteBuffer = ByteBuffer.wrap(inputStreamToByteArray()) : byteBuffer;
    }

    protected byte[] inputStreamToByteArray() throws IOException {
        return byteArray == null ? byteArray = FileUtils.readAllBytes(inputStream, length, null) : byteArray;
    }

    protected byte[] byteBufferToByteArray() {
        return byteArray == null ? byteArray = byteBuffer.array() : byteArray;
    }

    /**
     * The path of this classpath resource relative to the package root of the classpath element. For example, for a
     * package root of "BOOT-INF/classes/" and a resource path of "BOOT-INF/classes/com/xyz/resource.xml", returns
     * "com/xyz/resource.xml".
     */
    public abstract String getPathRelativeToPackageRoot();

    /**
     * The path of this classpath resource relative to the package root of the classpath element. For example, for a
     * resource path of "BOOT-INF/classes/com/xyz/resource.xml", returns the whole resource path, even if the
     * package root is "BOOT-INF/classes/".
     */
    public abstract String getPathRelativeToClasspathElement();

    /** Open an InputStream for a classpath resource. */
    public abstract InputStream open() throws IOException;

    /** Open a ByteBuffer for a classpath resource. */
    public abstract ByteBuffer read() throws IOException;

    /** Load a classpath resource and return its content as a byte array. */
    public abstract byte[] load() throws IOException;

    /**
     * Get length of InputStream or ByteBuffer -- may only be set after calling {@link #open()} or {@link #read()}.
     * (For some resources, length can never be determined.) Returns -1 if length is unknown.
     */
    public long getLength() {
        return length >= 0L ? length : byteArray != null ? byteArray.length : -1L;
    }

    @Override
    public void close() {
        byteArray = null;
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
