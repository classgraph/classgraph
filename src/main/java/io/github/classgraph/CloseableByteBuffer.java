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
 * Copyright (c) 2021 Luke Hutchison
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
import java.nio.ByteBuffer;

/**
 * A wrapper for {@link ByteBuffer} that implements the {@link Closeable} interface, releasing the
 * {@link ByteBuffer} when it is no longer needed.
 */
public class CloseableByteBuffer implements Closeable {
    private ByteBuffer byteBuffer;
    private Runnable onClose;

    /**
     * A wrapper for {@link ByteBuffer} that implements the {@link Closeable} interface, releasing the
     * {@link ByteBuffer} when it is no longer needed.
     * 
     * @param byteBuffer
     *            The {@link ByteBuffer} to wrap
     * @param onClose
     *            The method to run when {@link #close()} is called.
     */
    CloseableByteBuffer(final ByteBuffer byteBuffer, final Runnable onClose) {
        this.byteBuffer = byteBuffer;
        this.onClose = onClose;
    }

    /**
     * @return The wrapped {@link ByteBuffer}.
     */
    public ByteBuffer getByteBuffer() {
        return byteBuffer;
    }

    /** Release the wrapped {@link ByteBuffer}. */
    @Override
    public void close() throws IOException {
        if (onClose != null) {
            try {
                onClose.run();
            } catch (final Exception e) {
                // Ignore
            }
            onClose = null;
        }
        byteBuffer = null;
    }
}
