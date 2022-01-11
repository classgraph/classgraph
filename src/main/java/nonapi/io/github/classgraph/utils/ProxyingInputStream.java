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
package nonapi.io.github.classgraph.utils;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;

/**
 * A proxying {@link InputStream} implementation that compiles for JDK 7 but can support the methods added in JDK 8
 * by reflection.
 */
public class ProxyingInputStream extends InputStream {
    private InputStream inputStream;

    private static Method readAllBytes;
    private static Method readNBytes1;
    private static Method readNBytes3;
    private static Method skipNBytes;
    private static Method transferTo;

    static {
        // Use reflection for InputStream methods not present in JDK 7.
        // TODO Switch to direct method calls once JDK 8 is required, and add back missing @Override annotations
        try {
            readAllBytes = InputStream.class.getDeclaredMethod("readAllBytes");
        } catch (NoSuchMethodException | SecurityException e1) {
            // Ignore
        }
        try {
            readNBytes1 = InputStream.class.getDeclaredMethod("readNBytes", int.class);
        } catch (NoSuchMethodException | SecurityException e1) {
            // Ignore
        }
        try {
            readNBytes3 = InputStream.class.getDeclaredMethod("readNBytes", byte[].class, int.class, int.class);
        } catch (NoSuchMethodException | SecurityException e1) {
            // Ignore
        }
        try {
            skipNBytes = InputStream.class.getDeclaredMethod("skipNBytes", long.class);
        } catch (NoSuchMethodException | SecurityException e1) {
            // Ignore
        }
        try {
            transferTo = InputStream.class.getDeclaredMethod("transferTo", OutputStream.class);
        } catch (NoSuchMethodException | SecurityException e1) {
            // Ignore
        }
    }

    /**
     * A proxying {@link InputStream} implementation that compiles for JDK 7 but can support the methods added in
     * JDK 8 by reflection.
     *
     * @param inputStream
     *            the {@link InputStream} to wrap.
     */
    public ProxyingInputStream(final InputStream inputStream) {
        this.inputStream = inputStream;
    }

    @Override
    public int read() throws IOException {
        return inputStream.read();
    }

    @Override
    public int read(final byte[] b) throws IOException {
        return inputStream.read(b);
    }

    @Override
    public int read(final byte[] b, final int off, final int len) throws IOException {
        return inputStream.read(b, off, len);
    }

    // No @Override, since this method is not present in JDK 7
    public byte[] readAllBytes() throws IOException {
        if (readAllBytes == null) {
            throw new UnsupportedOperationException();
        }
        try {
            return (byte[]) readAllBytes.invoke(inputStream);
        } catch (final Exception e) {
            throw new IOException(e);
        }
    }

    // No @Override, since this method is not present in JDK 7
    public byte[] readNBytes(final int len) throws IOException {
        if (readNBytes1 == null) {
            throw new UnsupportedOperationException();
        }
        try {
            return (byte[]) readNBytes1.invoke(inputStream, len);
        } catch (final Exception e) {
            throw new IOException(e);
        }
    }

    // No @Override, since this method is not present in JDK 7
    public int readNBytes(final byte[] b, final int off, final int len) throws IOException {
        if (readNBytes3 == null) {
            throw new UnsupportedOperationException();
        }
        try {
            return (int) readNBytes3.invoke(inputStream, b, off, len);
        } catch (final Exception e) {
            throw new IOException(e);
        }
    }

    @Override
    public int available() throws IOException {
        return inputStream.available();
    }

    @Override
    public boolean markSupported() {
        return inputStream.markSupported();
    }

    @Override
    public synchronized void mark(final int readlimit) {
        inputStream.mark(readlimit);
    }

    @Override
    public synchronized void reset() throws IOException {
        inputStream.reset();
    }

    @Override
    public long skip(final long n) throws IOException {
        return inputStream.skip(n);
    }

    // No @Override, since this method is not present in JDK 7
    public void skipNBytes(final long n) throws IOException {
        if (skipNBytes == null) {
            throw new UnsupportedOperationException();
        }
        try {
            skipNBytes.invoke(inputStream, n);
        } catch (final Exception e) {
            throw new IOException(e);
        }
    }

    // No @Override, since this method is not present in JDK 7
    public long transferTo(final OutputStream out) throws IOException {
        if (transferTo == null) {
            throw new UnsupportedOperationException();
        }
        try {
            return (long) transferTo.invoke(inputStream, out);
        } catch (final Exception e) {
            throw new IOException(e);
        }
    }

    @Override
    public String toString() {
        return inputStream.toString();
    }

    @Override
    public void close() throws IOException {
        if (inputStream != null) {
            try {
                inputStream.close();
            } finally {
                inputStream = null;
            }
        }
    }
}
