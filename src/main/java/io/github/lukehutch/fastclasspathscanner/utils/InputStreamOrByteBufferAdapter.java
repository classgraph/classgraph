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
package io.github.lukehutch.fastclasspathscanner.utils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.util.Arrays;

/** Buffer class that can wrap either an InputStream or a ByteBuffer, depending on which is available. */
public abstract class InputStreamOrByteBufferAdapter {
    /**
     * Buffer size for initial read. We can save some time by reading most of the classfile header in a single read
     * at the beginning of the scan.
     *
     * <p>
     * (If chunk sizes are too small, significant overhead is expended in refilling the buffer. If they are too
     * large, significant overhead is expended in decompressing more of the classfile header than is needed. Testing
     * on a large classpath indicates that the defaults are reasonably optimal.)
     */
    private static final int INITIAL_BUFFER_CHUNK_SIZE = 16384;

    /** Buffer size for classfile reader. */
    private static final int SUBSEQUENT_BUFFER_CHUNK_SIZE = 4096;

    /** Bytes read from the beginning of the classfile. This array is reused across calls. */
    public byte[] buf = new byte[INITIAL_BUFFER_CHUNK_SIZE];

    /** The current position in the buffer. */
    public int curr = 0;

    /** Bytes used in the buffer. */
    public int used = 0;

    public void readInitialChunk() throws IOException {
        // Read first bufferful
        for (int bytesRead; used < INITIAL_BUFFER_CHUNK_SIZE
                && (bytesRead = read(buf, used, INITIAL_BUFFER_CHUNK_SIZE - used)) != -1;) {
            used += bytesRead;
        }
    }

    /** Read another chunk of from the InputStream or ByteBuffer. */
    private void readMore(final int bytesRequired) throws IOException, InterruptedException {
        final int extraBytesNeeded = bytesRequired - (used - curr);
        int bytesToRequest = extraBytesNeeded + SUBSEQUENT_BUFFER_CHUNK_SIZE;
        final int maxNewUsed = used + bytesToRequest;
        if (maxNewUsed > buf.length) {
            // Ran out of space, need to increase the size of the buffer
            int newBufLen = buf.length;
            while (newBufLen < maxNewUsed) {
                newBufLen <<= 1;
                if (newBufLen <= 0) {
                    // Handle overflow
                    throw new IOException("Classfile is bigger than 2GB, cannot read it");
                }
            }
            buf = Arrays.copyOf(buf, newBufLen);
        }
        int extraBytesStillNotRead = extraBytesNeeded;
        while (extraBytesStillNotRead > 0) {
            final int bytesRead = read(buf, used, bytesToRequest);
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException();
            }
            if (bytesRead > 0) {
                used += bytesRead;
                bytesToRequest -= bytesRead;
                extraBytesStillNotRead -= bytesRead;
            } else {
                // EOF
                if (extraBytesStillNotRead > 0) {
                    throw new IOException("Premature EOF while reading classfile");
                } else {
                    break;
                }
            }
        }
    }

    /** Read an unsigned byte from the buffer. */
    public int readUnsignedByte() throws IOException, InterruptedException {
        if (curr > used - 1) {
            readMore(1);
        }
        return buf[curr++] & 0xff;
    }

    /** Read an unsigned byte from the buffer at a specific offset before the current read point. */
    public int readUnsignedByte(final int offset) {
        return buf[offset] & 0xff;
    }

    /** Read an unsigned short from the buffer. */
    public int readUnsignedShort() throws IOException, InterruptedException {
        if (curr > used - 2) {
            readMore(2);
        }
        final int val = ((buf[curr] & 0xff) << 8) | (buf[curr + 1] & 0xff);
        curr += 2;
        return val;
    }

    /** Read an unsigned short from the buffer at a specific offset before the current read point. */
    public int readUnsignedShort(final int offset) {
        return ((buf[offset] & 0xff) << 8) | (buf[offset + 1] & 0xff);
    }

    /** Read an int from the buffer. */
    public int readInt() throws IOException, InterruptedException {
        if (curr > used - 4) {
            readMore(4);
        }
        final int val = ((buf[curr] & 0xff) << 24) | ((buf[curr + 1] & 0xff) << 16) | ((buf[curr + 2] & 0xff) << 8)
                | (buf[curr + 3] & 0xff);
        curr += 4;
        return val;
    }

    /** Read an int from the buffer at a specific offset before the current read point. */
    public int readInt(final int offset) throws IOException {
        return ((buf[offset] & 0xff) << 24) | ((buf[offset + 1] & 0xff) << 16) | ((buf[offset + 2] & 0xff) << 8)
                | (buf[offset + 3] & 0xff);
    }

    /** Read a long from the buffer. */
    public long readLong() throws IOException, InterruptedException {
        if (curr > used - 8) {
            readMore(8);
        }
        final long val = (((long) (((buf[curr] & 0xff) << 24) | ((buf[curr + 1] & 0xff) << 16)
                | ((buf[curr + 2] & 0xff) << 8) | (buf[curr + 3] & 0xff))) << 32) | ((buf[curr + 4] & 0xff) << 24)
                | ((buf[curr + 5] & 0xff) << 16) | ((buf[curr + 6] & 0xff) << 8) | (buf[curr + 7] & 0xff);
        curr += 8;
        return val;
    }

    /** Read a long from the buffer at a specific offset before the current read point. */
    public long readLong(final int offset) throws IOException {
        return (((long) (((buf[offset] & 0xff) << 24) | ((buf[offset + 1] & 0xff) << 16)
                | ((buf[offset + 2] & 0xff) << 8) | (buf[offset + 3] & 0xff))) << 32)
                | ((buf[offset + 4] & 0xff) << 24) | ((buf[offset + 5] & 0xff) << 16)
                | ((buf[offset + 6] & 0xff) << 8) | (buf[offset + 7] & 0xff);
    }

    /** Skip the given number of bytes in the input stream. */
    public void skip(final int bytesToSkip) throws IOException, InterruptedException {
        if (curr > used - bytesToSkip) {
            readMore(bytesToSkip);
        }
        curr += bytesToSkip;
    }

    /**
     * Reads the "modified UTF8" format defined in the Java classfile spec, optionally replacing '/' with '.', and
     * optionally removing the prefix "L" and the suffix ";".
     */
    public String readString(final int strStart, final boolean replaceSlashWithDot, final boolean stripLSemicolon) {
        final int utfLen = readUnsignedShort(strStart);
        final int utfStart = strStart + 2;
        final char[] chars = new char[utfLen];
        int c, c2, c3, c4;
        int byteIdx = 0;
        int charIdx = 0;
        for (; byteIdx < utfLen; byteIdx++) {
            c = buf[utfStart + byteIdx] & 0xff;
            if (c > 127) {
                break;
            }
            chars[charIdx++] = (char) (replaceSlashWithDot && c == '/' ? '.' : c);
        }
        while (byteIdx < utfLen) {
            c = buf[utfStart + byteIdx] & 0xff;
            switch (c >> 4) {
            case 0:
            case 1:
            case 2:
            case 3:
            case 4:
            case 5:
            case 6:
            case 7:
                byteIdx++;
                chars[charIdx++] = (char) (replaceSlashWithDot && c == '/' ? '.' : c);
                break;
            case 12:
            case 13:
                byteIdx += 2;
                if (byteIdx > utfLen) {
                    throw new RuntimeException("Bad modified UTF8");
                }
                c2 = buf[utfStart + byteIdx - 1];
                if ((c2 & 0xc0) != 0x80) {
                    throw new RuntimeException("Bad modified UTF8");
                }
                c4 = ((c & 0x1f) << 6) | (c2 & 0x3f);
                chars[charIdx++] = (char) (replaceSlashWithDot && c4 == '/' ? '.' : c4);
                break;
            case 14:
                byteIdx += 3;
                if (byteIdx > utfLen) {
                    throw new RuntimeException("Bad modified UTF8");
                }
                c2 = buf[utfStart + byteIdx - 2];
                c3 = buf[utfStart + byteIdx - 1];
                if (((c2 & 0xc0) != 0x80) || ((c3 & 0xc0) != 0x80)) {
                    throw new RuntimeException("Bad modified UTF8");
                }
                c4 = ((c & 0x0f) << 12) | ((c2 & 0x3f) << 6) | ((c3 & 0x3f) << 0);
                chars[charIdx++] = (char) (replaceSlashWithDot && c4 == '/' ? '.' : c4);
                break;
            default:
                throw new RuntimeException("Bad modified UTF8");
            }
        }
        if (charIdx == utfLen && !stripLSemicolon) {
            return new String(chars);
        } else {
            if (stripLSemicolon) {
                if (charIdx < 2 || chars[0] != 'L' || chars[charIdx - 1] != ';') {
                    throw new RuntimeException("Expected string to start with 'L' and end with ';', got \""
                            + new String(chars) + "\"");
                }
                return new String(chars, 1, charIdx - 2);
            } else {
                return new String(chars, 0, charIdx);
            }
        }
    }

    public abstract int read(byte[] array, int off, int len) throws IOException;

    private static class InputStreamAdapter extends InputStreamOrByteBufferAdapter {
        private final InputStream inputStream;

        public InputStreamAdapter(final InputStream inputStream) throws IOException {
            super();
            this.inputStream = inputStream;
        }

        @Override
        public int read(final byte[] array, final int off, final int len) throws IOException {
            return inputStream.read(array, off, len);
        }
    }

    private static class ByteBufferAdapter extends InputStreamOrByteBufferAdapter {
        private final ByteBuffer byteBuffer;

        public ByteBufferAdapter(final ByteBuffer byteBuffer) throws IOException {
            super();
            this.byteBuffer = byteBuffer;
        }

        @Override
        public int read(final byte[] array, final int off, final int len) throws IOException {
            if (len == 0) {
                return 0;
            }
            final int bytesToRead = Math.min(len, byteBuffer.remaining());
            if (bytesToRead == 0) {
                // Return -1, as per InputStream#read() contract
                return -1;
            }
            final int byteBufPositionBefore = byteBuffer.position();
            try {
                byteBuffer.get(array, off, bytesToRead);
            } catch (final BufferUnderflowException e) {
                // Should not happen
                throw new IOException("Buffer underflow", e);
            }
            return byteBuffer.position() - byteBufPositionBefore;
        }
    }

    /** Create a new InputStream adapter. */
    public static InputStreamOrByteBufferAdapter create(final InputStream inputStream) throws IOException {
        return new InputStreamAdapter(inputStream);
    }

    /** Create a new ByteBuffer adapter. */
    public static InputStreamOrByteBufferAdapter create(final ByteBuffer byteBuffer) throws IOException {
        return new ByteBufferAdapter(byteBuffer);
    }
}
