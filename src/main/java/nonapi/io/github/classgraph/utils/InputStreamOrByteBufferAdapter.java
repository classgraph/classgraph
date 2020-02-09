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
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.util.Arrays;

import nonapi.io.github.classgraph.fastzipfilereader.ByteBufferWrapper;

/** Buffer class that can wrap either an InputStream or a ByteBuffer, depending on which is available. */
public class InputStreamOrByteBufferAdapter implements AutoCloseable {
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

    /** The InputStream, if applicable. */
    private InputStream inputStream;

    /** The {@link ByteBufferWrapper}, if applicable. */
    private ByteBufferWrapper byteBufferWrapper;

    /**
     * Bytes read from the beginning of the classfile. Only access an index in this array directly if at least that
     * many bytes have already been read from the classfile (i.e. can be used to read backwards but not forwards in
     * the classfile).
     */
    public byte[] buf;

    /**
     * 
     * /** The current position in the buffer.
     */
    public int curr;

    /** Bytes used in the buffer. */
    private int bufBytesFilled;

    /**
     * Create an {@link InputStreamOrByteBufferAdapter} from an {@link InputStream}.
     *
     * @param inputStream
     *            the input stream
     */
    public InputStreamOrByteBufferAdapter(final InputStream inputStream) {
        this.inputStream = inputStream;
        this.buf = new byte[INITIAL_BUFFER_CHUNK_SIZE];
    }

    /**
     * Create an {@link InputStreamOrByteBufferAdapter} from an {@link InputStream}.
     *
     * @param byteBufferWrapper
     *            the byte buffer wrapper
     */
    public InputStreamOrByteBufferAdapter(final ByteBufferWrapper byteBufferWrapper) {
        final ByteBuffer byteBuffer = byteBufferWrapper.getByteBuffer();
        if (byteBuffer != null && byteBuffer.hasArray()) {
            // Just use the array behind the buffer as the input buffer
            this.buf = byteBuffer.array();
            this.bufBytesFilled = this.buf.length;
        } else {
            this.byteBufferWrapper = byteBufferWrapper;
            this.buf = new byte[INITIAL_BUFFER_CHUNK_SIZE];
        }
    }

    /**
     * Copy up to len bytes into buf, starting at the given offset.
     * 
     * @param off
     *            The start index for the copy.
     * @param len
     *            The maximum number of bytes to copy.
     * @return The number of bytes actually copied.
     * @throws IOException
     *             If the file content could not be read.
     */
    private int read(final int off, final int len) throws IOException {
        if (len == 0) {
            return 0;
        }
        if (inputStream != null) {
            // Wrapped InputStream
            return inputStream.read(buf, off, len);
        } else {
            // Wrapped ByteBuffer
            final int bytesRemainingInBuf = byteBufferWrapper != null ? byteBufferWrapper.remaining()
                    : buf.length - off;
            final int bytesRead = Math.max(0, Math.min(len, bytesRemainingInBuf));
            if (bytesRead == 0) {
                // Return -1, as per InputStream#read() contract
                return -1;
            }
            if (byteBufferWrapper != null) {
                // Copy from the ByteBuffer into the byte array
                final int byteBufPositionBefore = byteBufferWrapper.position();
                try {
                    byteBufferWrapper.get(buf, off, bytesRead);
                } catch (final BufferUnderflowException e) {
                    // Should not happen
                    throw new IOException("Buffer underflow", e);
                }
                return byteBufferWrapper.position() - byteBufPositionBefore;
            } else {
                // Nothing to read, since ByteBuffer is backed with an array
                return bytesRead;
            }
        }
    }

    /**
     * Read another chunk of from the InputStream or ByteBuffer.
     *
     * @param bytesRequired
     *            the number of bytes to read
     * @throws IOException
     *             If an I/O exception occurs.
     */
    private void readMore(final int bytesRequired) throws IOException {
        if ((long) bufBytesFilled + (long) bytesRequired > FileUtils.MAX_BUFFER_SIZE) {
            // Since buf is an array, we're limited to reading 2GB per file
            throw new IOException("File is larger than 2GB, cannot read it");
        }
        // Read INITIAL_BUFFER_CHUNK_SIZE for first chunk, or SUBSEQUENT_BUFFER_CHUNK_SIZE for subsequent chunks,
        // but don't try to read past 2GB limit
        final int targetReadSize = Math.max(bytesRequired, // 
                bufBytesFilled == 0 ? INITIAL_BUFFER_CHUNK_SIZE : SUBSEQUENT_BUFFER_CHUNK_SIZE);
        // Calculate number of bytes to read, based on the target read size, handling integer overflow
        final int maxNewUsed = (int) Math.min((long) bufBytesFilled + (long) targetReadSize,
                FileUtils.MAX_BUFFER_SIZE);
        final int bytesToRead = maxNewUsed - bufBytesFilled;
        if (maxNewUsed > buf.length) {
            // Ran out of space, need to increase the size of the buffer
            long newBufLen = buf.length;
            while (newBufLen < maxNewUsed) {
                newBufLen <<= 1;
            }
            buf = Arrays.copyOf(buf, (int) Math.min(newBufLen, FileUtils.MAX_BUFFER_SIZE));
        }
        int extraBytesStillNotRead = bytesToRead;
        int totBytesRead = 0;
        while (extraBytesStillNotRead > 0) {
            final int bytesRead = read(bufBytesFilled, extraBytesStillNotRead);
            if (bytesRead > 0) {
                bufBytesFilled += bytesRead;
                totBytesRead += bytesRead;
                extraBytesStillNotRead -= bytesRead;
            } else {
                // EOF
                break;
            }
        }
        if (totBytesRead < bytesRequired) {
            throw new IOException("Premature EOF while reading classfile");
        }
    }

    /**
     * Read an unsigned byte from the buffer.
     * 
     * @return The next unsigned byte in the buffer.
     * @throws IOException
     *             If there was an exception while reading.
     */
    public int readUnsignedByte() throws IOException {
        final int val = readUnsignedByte(curr);
        curr++;
        return val;
    }

    /**
     * Read an unsigned byte at a specific offset (without changing the current read point).
     *
     * @param offset
     *            The buffer offset to read from.
     * @return The unsigned byte at the buffer offset.
     * @throws IOException
     *             If there was an exception while reading.
     */
    public int readUnsignedByte(final int offset) throws IOException {
        final int bytesToRead = Math.max(0, offset + 1 - bufBytesFilled);
        if (bytesToRead > 0) {
            readMore(bytesToRead);
        }
        return buf[offset] & 0xff;
    }

    /**
     * Read the next unsigned short.
     *
     * @return The next unsigned short in the buffer.
     * @throws IOException
     *             If there was an exception while reading.
     */
    public int readUnsignedShort() throws IOException {
        final int val = readUnsignedShort(curr);
        curr += 2;
        return val;
    }

    /**
     * Read an unsigned short at a specific offset (without changing the current read point).
     *
     * @param offset
     *            The buffer offset to read from.
     * @return The unsigned short at the buffer offset.
     * @throws IOException
     *             If there was an exception while reading.
     */
    public int readUnsignedShort(final int offset) throws IOException {
        final int bytesToRead = Math.max(0, offset + 2 - bufBytesFilled);
        if (bytesToRead > 0) {
            readMore(bytesToRead);
        }
        return ((buf[offset] & 0xff) << 8) //
                | (buf[offset + 1] & 0xff);
    }

    /**
     * Read the next int.
     *
     * @return The next int in the buffer.
     * @throws IOException
     *             If there was an exception while reading.
     */
    public int readInt() throws IOException {
        final int val = readInt(curr);
        curr += 4;
        return val;
    }

    /**
     * Read an int at a specific offset (without changing the current read point).
     *
     * @param offset
     *            The buffer offset to read from.
     * @return The int at the buffer offset.
     * @throws IOException
     *             If there was an exception while reading.
     */
    public int readInt(final int offset) throws IOException {
        final int bytesToRead = Math.max(0, offset + 4 - bufBytesFilled);
        if (bytesToRead > 0) {
            readMore(bytesToRead);
        }
        return ((buf[offset] & 0xff) << 24) //
                | ((buf[offset + 1] & 0xff) << 16) //
                | ((buf[offset + 2] & 0xff) << 8) //
                | (buf[offset + 3] & 0xff);
    }

    /**
     * Read the next long.
     *
     * @return The next long in the buffer.
     * @throws IOException
     *             If there was an exception while reading.
     */
    public long readLong() throws IOException {
        final long val = readLong(curr);
        curr += 8;
        return val;
    }

    /**
     * Read a long at a specific offset (without changing the current read point).
     * 
     * @param offset
     *            The buffer offset to read from.
     * @return The long at the buffer offset.
     * @throws IOException
     *             If there was an exception while reading.
     */
    public long readLong(final int offset) throws IOException {
        final int bytesToRead = Math.max(0, offset + 8 - bufBytesFilled);
        if (bytesToRead > 0) {
            readMore(bytesToRead);
        }
        return ((buf[offset] & 0xffL) << 56) //
                | ((buf[offset + 1] & 0xffL) << 48) //
                | ((buf[offset + 2] & 0xffL) << 40) //
                | ((buf[offset + 3] & 0xffL) << 32) //
                | ((buf[offset + 4] & 0xffL) << 24) //
                | ((buf[offset + 5] & 0xffL) << 16) //
                | ((buf[offset + 6] & 0xffL) << 8) //
                | (buf[offset + 7] & 0xffL);
    }

    /**
     * Skip the given number of bytes.
     * 
     * @param bytesToSkip
     *            The number of bytes to skip.
     * @throws IOException
     *             If there was an exception while reading.
     */
    public void skip(final int bytesToSkip) throws IOException {
        final int bytesToRead = Math.max(0, curr + bytesToSkip - bufBytesFilled);
        if (bytesToRead > 0) {
            readMore(bytesToRead);
        }
        curr += bytesToSkip;
    }

    /**
     * Reads the "modified UTF8" format defined in the Java classfile spec, optionally replacing '/' with '.', and
     * optionally removing the prefix "L" and the suffix ";".
     *
     * @param strStart
     *            The start index of the string.
     * @param replaceSlashWithDot
     *            If true, replace '/' with '.'.
     * @param stripLSemicolon
     *            If true, string final ';' character.
     * @return The string.
     * @throws IOException
     *             If an I/O exception occurs.
     */
    public String readString(final int strStart, final boolean replaceSlashWithDot, final boolean stripLSemicolon)
            throws IOException {
        final int utfLen = readUnsignedShort(strStart);
        final int utfStart = strStart + 2;
        final int bufferUnderrunBytes = Math.max(0, utfStart + utfLen - bufBytesFilled);
        if (bufferUnderrunBytes > 0) {
            readMore(bufferUnderrunBytes);
        }
        final char[] chars = new char[utfLen];
        int byteIdx = 0;
        int charIdx = 0;
        for (; byteIdx < utfLen; byteIdx++) {
            final int c = buf[utfStart + byteIdx] & 0xff;
            if (c > 127) {
                break;
            }
            chars[charIdx++] = (char) (replaceSlashWithDot && c == '/' ? '.' : c);
        }
        while (byteIdx < utfLen) {
            final int c = buf[utfStart + byteIdx] & 0xff;
            switch (c >> 4) {
            case 0:
            case 1:
            case 2:
            case 3:
            case 4:
            case 5:
            case 6:
            case 7: {
                byteIdx++;
                chars[charIdx++] = (char) (replaceSlashWithDot && c == '/' ? '.' : c);
                break;
            }
            case 12:
            case 13: {
                byteIdx += 2;
                if (byteIdx > utfLen) {
                    throw new IOException("Bad modified UTF8");
                }
                final int c2 = buf[utfStart + byteIdx - 1];
                if ((c2 & 0xc0) != 0x80) {
                    throw new IOException("Bad modified UTF8");
                }
                final int c3 = ((c & 0x1f) << 6) | (c2 & 0x3f);
                chars[charIdx++] = (char) (replaceSlashWithDot && c3 == '/' ? '.' : c3);
                break;
            }
            case 14: {
                byteIdx += 3;
                if (byteIdx > utfLen) {
                    throw new IOException("Bad modified UTF8");
                }
                final int c2 = buf[utfStart + byteIdx - 2];
                final int c3 = buf[utfStart + byteIdx - 1];
                if ((c2 & 0xc0) != 0x80 || (c3 & 0xc0) != 0x80) {
                    throw new IOException("Bad modified UTF8");
                }
                final int c4 = ((c & 0x0f) << 12) | ((c2 & 0x3f) << 6) | (c3 & 0x3f);
                chars[charIdx++] = (char) (replaceSlashWithDot && c4 == '/' ? '.' : c4);
                break;
            }
            default:
                throw new IOException("Bad modified UTF8");
            }
        }
        if (charIdx == utfLen && !stripLSemicolon) {
            return new String(chars);
        } else {
            if (stripLSemicolon) {
                if (charIdx < 2 || chars[0] != 'L' || chars[charIdx - 1] != ';') {
                    throw new IOException("Expected string to start with 'L' and end with ';', got \""
                            + new String(chars) + "\"");
                }
                return new String(chars, 1, charIdx - 2);
            } else {
                return new String(chars, 0, charIdx);
            }
        }
    }

    /* (non-Javadoc)
     * @see java.lang.AutoCloseable#close()
     */
    @Override
    public void close() {
        if (this.inputStream != null) {
            try {
                this.inputStream.close();
            } catch (final IOException e) {
                // Ignore
            }
            this.inputStream = null;
        }
        this.byteBufferWrapper = null;
        this.buf = null;
    }
}
