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
package io.github.classgraph.vfs.internal.zip;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The number of bytes that the deflated jarfiles nested within a toplevel zipfile may still inflate to, all
 * together and at any depth of nesting. It starts at {@value #MAX_INFLATION_RATIO} times the length of the toplevel
 * zipfile, or {@value #MIN_MAX_BYTES} bytes if that is more, and is shared by the toplevel zipfile and every
 * zipfile inflated out of it.
 *
 * <p>
 * Without a limit, a small crafted jarfile could fill the heap or the temporary directory, since deflate compresses
 * repeated bytes about a thousand times, and that factor multiplies with each level of nesting. The limit covers
 * all the nested jarfiles together rather than each one separately, since a limit for each one alone would be
 * defeated by splitting the content among many entries, or among many entries that share the same compressed bytes.
 * A real nested jarfile holds content that is already compressed, so it inflates to about 1.0 to 1.5 times its
 * compressed size: of 611 deflated nested archives measured in the jarfiles of a local Maven repository and in
 * ClassGraph's test resources, the highest was 2.8, for a 22-byte jarfile.
 */
final class NestedJarInflationAllowance {
    /**
     * The number of times the length of a toplevel zipfile that the jarfiles nested within it may inflate to, all
     * together.
     */
    static final long MAX_INFLATION_RATIO = 100L;

    /**
     * The number of bytes that the jarfiles nested within a toplevel zipfile may always inflate to, all together,
     * however short the toplevel zipfile is, so that a small jarfile holding a large file that compresses very
     * well, such as a sparse database or disk image used as a test fixture, is not refused.
     */
    static final long MIN_MAX_BYTES = 256L * 1024 * 1024;

    /** The path of the toplevel zipfile, for the error message. */
    private final String toplevelPath;

    /** The number of bytes the nested jarfiles may inflate to in all. */
    private final long maxBytes;

    /** The number of bytes the nested jarfiles may still inflate to. */
    private final AtomicLong bytesLeft;

    /**
     * Construct the allowance of a toplevel zipfile.
     *
     * @param toplevelPath
     *            the path of the toplevel zipfile.
     * @param toplevelLength
     *            the length of the toplevel zipfile, in bytes.
     */
    NestedJarInflationAllowance(final String toplevelPath, final long toplevelLength) {
        this.toplevelPath = toplevelPath;
        this.maxBytes = toplevelLength > Long.MAX_VALUE / MAX_INFLATION_RATIO ? Long.MAX_VALUE
                : Math.max(toplevelLength * MAX_INFLATION_RATIO, MIN_MAX_BYTES);
        this.bytesLeft = new AtomicLong(maxBytes);
    }

    /**
     * Get the number of bytes the nested jarfiles may still inflate to, all together. A stream wrapped by
     * {@link #limit(InputStream, String)} cannot be read further than this without throwing, though other streams
     * may take from the allowance or give back to it at the same time.
     *
     * @return the number of bytes left.
     */
    long bytesLeft() {
        return bytesLeft.get();
    }

    /**
     * Give back bytes that were taken from this allowance, when what they were inflated to is released.
     *
     * @param numBytes
     *            the number of bytes to give back.
     */
    void giveBack(final long numBytes) {
        bytesLeft.addAndGet(numBytes);
    }

    /**
     * Wrap the stream that a nested jarfile is inflated from, so that each byte read from it is taken from this
     * allowance, and a read that would take more than is left throws {@link IOException}.
     *
     * @param inflatedInputStream
     *            the stream that inflates the nested jarfile.
     * @param nestedJarPath
     *            the path of the nested jarfile, for the error message.
     * @return the wrapped stream. Closing it closes {@code inflatedInputStream}, but does not give back what was
     *         taken: whoever keeps what was read gives that back when it is released, with {@link #giveBack(long)}.
     */
    LimitedInputStream limit(final InputStream inflatedInputStream, final String nestedJarPath) {
        return new LimitedInputStream(inflatedInputStream, nestedJarPath);
    }

    /** A stream that takes each byte read from it from the allowance. */
    final class LimitedInputStream extends FilterInputStream {
        /** The path of the nested jarfile, for the error message. */
        private final String nestedJarPath;

        /** The number of bytes taken from the allowance so far. */
        private long bytesTaken;

        /**
         * Constructor.
         *
         * @param inflatedInputStream
         *            the stream that inflates the nested jarfile.
         * @param nestedJarPath
         *            the path of the nested jarfile, for the error message.
         */
        private LimitedInputStream(final InputStream inflatedInputStream, final String nestedJarPath) {
            super(inflatedInputStream);
            this.nestedJarPath = nestedJarPath;
        }

        /**
         * Get the number of bytes taken from the allowance so far, i.e. the number of bytes read or skipped.
         *
         * @return the number of bytes taken from the allowance.
         */
        long getBytesTaken() {
            return bytesTaken;
        }

        /**
         * Take bytes that were read or skipped from the allowance.
         *
         * @param numBytes
         *            the number of bytes read or skipped.
         * @throws IOException
         *             if fewer than that many bytes are left.
         */
        private void take(final long numBytes) throws IOException {
            if (numBytes <= 0) {
                return;
            }
            // Compare and set rather than subtract and then add back on failure, since a subtraction that went
            // below zero would, until added back, make a concurrent read from another nested jarfile fail too
            long left;
            do {
                left = bytesLeft.get();
                if (left < numBytes) {
                    throw new IOException("Nested jar " + nestedJarPath + " was not inflated, since the jarfiles"
                            + " nested within " + toplevelPath + " would inflate to more than " + maxBytes
                            + " bytes in all, which is the larger of " + MAX_INFLATION_RATIO
                            + " times its length and " + MIN_MAX_BYTES + " bytes");
                }
            } while (!bytesLeft.compareAndSet(left, left - numBytes));
            bytesTaken += numBytes;
        }

        @Override
        public int read() throws IOException {
            final var b = super.read();
            if (b >= 0) {
                take(1);
            }
            return b;
        }

        @Override
        public int read(final byte[] b, final int off, final int len) throws IOException {
            final var bytesRead = super.read(b, off, len);
            take(bytesRead);
            return bytesRead;
        }

        @Override
        public long skip(final long n) throws IOException {
            final var bytesSkipped = super.skip(n);
            take(bytesSkipped);
            return bytesSkipped;
        }
    }
}
