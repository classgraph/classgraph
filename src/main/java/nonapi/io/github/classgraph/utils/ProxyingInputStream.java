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

/**
 * An {@link InputStream} that delegates every method to a wrapped
 * {@link InputStream}.
 */
public class ProxyingInputStream extends InputStream {
    /** The wrapped {@link InputStream}. */
    private final InputStream inputStream;

    /**
     * True once {@link #close()} has been called. Guards against closing the
     * wrapped {@link InputStream} more than once, including re-entrantly, if a
     * subclass' {@link #close()} method closes a resource that in turn closes this
     * stream.
     */
    private boolean closed;

    /**
     * Constructor.
     *
     * @param inputStream the {@link InputStream} to wrap.
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

    @Override
    public byte[] readAllBytes() throws IOException {
        return inputStream.readAllBytes();
    }

    @Override
    public byte[] readNBytes(final int len) throws IOException {
        return inputStream.readNBytes(len);
    }

    @Override
    public int readNBytes(final byte[] b, final int off, final int len) throws IOException {
        return inputStream.readNBytes(b, off, len);
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

    @Override
    public void skipNBytes(final long n) throws IOException {
        inputStream.skipNBytes(n);
    }

    @Override
    public long transferTo(final OutputStream out) throws IOException {
        return inputStream.transferTo(out);
    }

    @Override
    public String toString() {
        return inputStream.toString();
    }

    @Override
    public void close() throws IOException {
        if (!closed) {
            closed = true;
            inputStream.close();
        }
    }
}
