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
 * Tracks the remaining byte budget for inflating deflated jarfiles nested at any depth within a toplevel zipfile,
 * bounded to the greater of {@value #MAX_INFLATION_RATIO} times the toplevel zipfile's length and
 * {@value #MIN_MAX_BYTES} bytes.
 */
final class NestedJarInflationAllowance {
    /** Maximum cumulative inflation ratio for nested jarfiles relative to the toplevel zipfile length. */
    static final long MAX_INFLATION_RATIO = 100L;

    /** Minimum cumulative inflation budget in bytes for nested jarfiles within a toplevel zipfile. */
    static final long MIN_MAX_BYTES = 256L * 1024 * 1024;

    /** The path of the toplevel zipfile, for error messages. */
    private final String toplevelPath;

    /** Total byte budget for inflating nested jarfiles. */
    private final long maxBytes;

    /** Remaining byte budget for inflating nested jarfiles. */
    private final AtomicLong bytesLeft;

    /**
     * Construct the inflation allowance for a toplevel zipfile.
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
     * Return the remaining byte budget available for inflating nested jarfiles.
     *
     * @return the number of bytes remaining.
     */
    long bytesLeft() {
        return bytesLeft.get();
    }

    /**
     * Return bytes to this allowance when an inflated nested jarfile is closed.
     *
     * @param numBytes
     *            the number of bytes to return.
     */
    void giveBack(final long numBytes) {
        bytesLeft.addAndGet(numBytes);
    }

    /**
     * Wrap an inflated nested jarfile stream so that each byte read deducts from this allowance and throws
     * {@link IOException} if the allowance is exceeded.
     *
     * @param inflatedInputStream
     *            the stream inflating the nested jarfile.
     * @param nestedJarPath
     *            the path of the nested jarfile, for error messages.
     * @return the budget-limited stream.
     */
    LimitedInputStream limit(final InputStream inflatedInputStream, final String nestedJarPath) {
        return new LimitedInputStream(inflatedInputStream, nestedJarPath);
    }

    /** A {@link FilterInputStream} that deducts each read or skipped byte from the enclosing allowance. */
    final class LimitedInputStream extends FilterInputStream {
        /** The path of the nested jarfile, for error messages. */
        private final String nestedJarPath;

        /** The number of bytes deducted from the allowance by this stream. */
        private long bytesTaken;

        /**
         * Constructor.
         *
         * @param inflatedInputStream
         *            the stream inflating the nested jarfile.
         * @param nestedJarPath
         *            the path of the nested jarfile, for error messages.
         */
        private LimitedInputStream(final InputStream inflatedInputStream, final String nestedJarPath) {
            super(inflatedInputStream);
            this.nestedJarPath = nestedJarPath;
        }

        /**
         * Return the number of bytes deducted from the allowance by this stream so far.
         *
         * @return the number of bytes taken.
         */
        long getBytesTaken() {
            return bytesTaken;
        }

        /**
         * Deduct {@code numBytes} from the allowance.
         *
         * @param numBytes
         *            the number of bytes read or skipped.
         * @throws IOException
         *             if fewer than {@code numBytes} remain in the allowance.
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
