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
 * Copyright (c) 2020 Luke Hutchison
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
package nonapi.io.github.classgraph.fastzipfilereader;

import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.Buffer;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.NonReadableChannelException;

import nonapi.io.github.classgraph.utils.FileUtils;
import nonapi.io.github.classgraph.utils.LogNode;

/**
 * A wrapper for either {@link ByteBuffer} (backed by an array in RAM or mapped to a file on disk as a
 * {@link MappedByteBuffer}), or a {@link RandomAccessFile} (which is slower, but doesn't consume either resident
 * RAM or virtual memory address space).
 */
/**
 * @author luke
 *
 */
/**
 * @author luke
 *
 */
public final class ByteBufferWrapper {

    /** The {@link ByteBuffer} to use, or if null, use a {@link RandomAccessFile} instead. */
    private ByteBuffer byteBuffer;

    /** True if byteBuffer is a {@link MemoryMappedByteBuffer}. */
    private boolean isMemoryMappedByteBuffer;

    /** The {@link RandomAccessFile} to use, if byteBuffer is null. */
    private RandomAccessFile raf;

    /** The {@link File} to use, if memory mapping is disabled. */
    private File file;

    /** The start position of the {@link RandomAccessFile} slice. */
    private long rafSliceStart;

    /** The limit of the {@link RandomAccessFile} slice. */
    private int rafSliceLength;

    /** The length of the {@link RandomAccessFile}. */
    private long rafLength;

    /**
     * Wrap a range of a {@link RandomAccessFile}.
     * 
     * @param file
     *            the {@link File} to map.
     * @param sliceStart
     *            The starting position of the slice within the file.
     * @param sliceLength
     *            The length of the slice (must be smaller than (2GB - 8B)).
     */
    public ByteBufferWrapper(final File file, final long sliceStart, final long sliceLength) throws IOException {
        final long fileLen = file.length();
        if (sliceStart < 0 || sliceStart >= fileLen) {
            throw new IllegalArgumentException("start out of range: " + sliceLength);
        }
        if (sliceLength > FileUtils.MAX_BUFFER_SIZE) {
            throw new IllegalArgumentException("len > max (" + FileUtils.MAX_BUFFER_SIZE + ")");
        }
        if (sliceLength > fileLen - sliceStart) {
            throw new IllegalArgumentException("len > " + (fileLen - sliceStart));
        }
        this.file = file;
        // RandomAccessFile#length() is not threadsafe, so use File.length():
        // https://bugs.java.com/bugdatabase/view_bug.do?bug_id=4823133
        rafLength = file.length();
        raf = new RandomAccessFile(file, "r");
        raf.seek(sliceStart);
        rafSliceStart = sliceStart;
        rafSliceLength = (int) sliceLength;
    }

    /**
     * Memory-map a chunk of a file.
     * 
     * @param fileChannel
     *            a {@link FileChannel} to map.
     * @param start
     *            The starting position to map.
     * @param len
     *            The number of bytes to map (must be smaller than (2GB - 8B)).
     * @throws IOException
     *             if file cannot be mapped.
     * @throws OutOfMemoryError
     *             if virtual memory space runs out.
     */
    public ByteBufferWrapper(final FileChannel fileChannel, final long start, final long len) throws IOException {
        if (len > FileUtils.MAX_BUFFER_SIZE) {
            throw new IllegalArgumentException("len > " + FileUtils.MAX_BUFFER_SIZE);
        }
        try {
            // Try mapping the FileChannel
            byteBuffer = fileChannel.map(FileChannel.MapMode.READ_ONLY, start, len);
            isMemoryMappedByteBuffer = true;
        } catch (final OutOfMemoryError e) {
            // If map failed, try calling System.gc() to free some allocated MappedByteBuffers
            // (there is a limit to the number of mapped files -- 64k on Linux)
            // See: http://www.mapdb.org/blog/mmap_files_alloc_and_jvm_crash/
            System.gc();
            System.runFinalization();
            // Then try calling map again
            byteBuffer = fileChannel.map(FileChannel.MapMode.READ_ONLY, start, len);
            // May throw OutOfMemoryError again; caller should catch this
            isMemoryMappedByteBuffer = true;
        } catch (NonReadableChannelException | IllegalArgumentException | UnsupportedOperationException e) {
            // None of these exceptions should be thrown, but wrap in IOException for simplicity
            throw new IOException(e);
        }
    }

    /**
     * Wrap an array in a {@link ByteBuffer}.
     * 
     * @param arr
     *            The array to wrap.
     * @throws OutOfMemoryError
     *             if virtual memory space runs out.
     */
    public ByteBufferWrapper(final byte[] arr) {
        this.byteBuffer = ByteBuffer.wrap(arr, 0, arr.length);
    }

    /**
     * Wrap a {@link ByteBuffer}.
     * 
     * @param byteBuffer
     *            The byte buffer to wrap.
     * @throws OutOfMemoryError
     *             if virtual memory space runs out.
     */
    public ByteBufferWrapper(final ByteBuffer byteBuffer) {
        this.byteBuffer = byteBuffer;
    }

    /**
     * Duplicate this {@link ByteBufferWrapper} by duplicating the underlying {@link ByteBuffer}, or opening another
     * {@link RandomAccessFile} on the same file.
     * 
     * @throws IOException
     *             if file can no longer be opened.
     */
    public ByteBufferWrapper duplicate() throws IOException {
        return byteBuffer != null ? new ByteBufferWrapper(byteBuffer.duplicate())
                : new ByteBufferWrapper(file, rafSliceStart, rafLength);
    }

    /**
     * Slice this {@link ByteBufferWrapper} by duplicating the underlying {@link ByteBuffer}, or opening another
     * {@link RandomAccessFile} on the same file, then slicing it.
     * 
     * @throws IOException
     *             if file can no longer be opened.
     */
    public ByteBufferWrapper slice(final int childSliceStart, final int childSliceLength) throws IOException {
        if (byteBuffer != null) {
            // Slice a ByteBuffer

            // Duplicate the ByteBuffer so that the position and limit are not changed on the original
            // (In JDK 13, there is a slice(start, len), which eliminates this need.)
            final ByteBuffer dup = byteBuffer.duplicate();

            // Create and return a slice on the chunk ByteBuffer that contains only this zip entry
            // N.B. the cast to Buffer is necessary, see:
            // https://github.com/plasma-umass/doppio/issues/497#issuecomment-334740243
            // https://github.com/classgraph/classgraph/issues/284#issuecomment-443612800
            ((Buffer) byteBuffer).mark();
            try {
                ((Buffer) dup).position(childSliceStart);
                ((Buffer) dup).limit(childSliceStart + childSliceLength);
                final ByteBufferWrapper slice = new ByteBufferWrapper(dup.slice());
                return slice;
            } finally {
                ((Buffer) byteBuffer).reset();
                ((Buffer) dup).limit(dup.capacity());
            }

        } else {
            // Memory mapping is disabled -- open another RandomAccessFile on a slice of the current one
            if (childSliceStart + childSliceLength > rafLength) {
                throw new IOException("Child slice extends past end of file");
            }
            if (childSliceLength > rafSliceLength) {
                throw new IOException("Child slice larger than parent slice");
            }
            return new ByteBufferWrapper(file, rafSliceStart + childSliceStart, childSliceLength);
        }
    }

    /** Return true if remaining() returns a number greater than zero. */
    public boolean hasRemaining() {
        return remaining() > 0;
    }

    /** Return the number of bytes remaining in the wrapped file slice. */
    public int remaining() {
        try {
            if (byteBuffer != null) {
                return byteBuffer.remaining();
            } else {
                final long rafSliceEnd = rafSliceStart + rafSliceLength;
                return Math.max(0, (int) Math.max(0, rafSliceEnd - raf.getFilePointer()));
            }
        } catch (final IOException e) {
            return 0;
        }
    }

    /**
     * Read bytes from the current position in the underlying {@link ByteBuffer} or {@link RandomAccessFile} into a
     * byte array.
     * 
     * @param arr
     *            The byte array to read into.
     * @param arrStart
     *            The start position in the array.
     * @param numBytesToRead
     *            The number of bytes to read.
     * @throws IOException
     *             If the file is closed or the requested number of bytes could not be read.
     */
    public void get(final byte[] arr, final int arrStart, final int numBytesToRead) throws IOException {
        if (byteBuffer != null) {
            // Read from the wrapped ByteBuffer
            byteBuffer.get(arr, arrStart, numBytesToRead);

        } else {
            // Read from the wrapped RandomAccessFile
            if (raf.read(arr, arrStart, numBytesToRead) < numBytesToRead) {
                throw new IOException("Unexpected EOF");
            }
        }
    }

    /**
     * Read bytes from the specified position in the underlying {@link ByteBuffer} or {@link RandomAccessFile} into
     * a byte array.
     * 
     * @param off
     *            The offset within the slice to start reading from.
     * @param arr
     *            The byte array to read into.
     * @param arrStart
     *            The start position in the array.
     * @param numBytesToRead
     *            The number of bytes to read.
     * @throws IOException
     *             If the file is closed or the requested number of bytes could not be read.
     */
    public void get(final int off, final byte[] arr, final int arrStart, final int numBytesToRead)
            throws IOException {
        if (byteBuffer != null) {
            // Read from the wrapped ByteBuffer
            if (numBytesToRead > byteBuffer.remaining()) {
                throw new IOException("Unexpected EOF");
            }
            // N.B. the cast to Buffer is necessary, see:
            // https://github.com/plasma-umass/doppio/issues/497#issuecomment-334740243
            // https://github.com/classgraph/classgraph/issues/284#issuecomment-443612800
            // Otherwise compiling in JDK<9 compatibility mode using JDK9+ causes runtime breakage. 
            ((Buffer) byteBuffer).mark();
            try {
                ((Buffer) byteBuffer).position(off);
                byteBuffer.get(arr, arrStart, numBytesToRead);
            } catch (final BufferUnderflowException e) {
                // Should not happen
                throw new EOFException("Unexpected EOF");
            } finally {
                ((Buffer) byteBuffer).reset();
            }

        } else {
            // Read from the wrapped RandomAccessFile
            final long currPos = raf.getFilePointer();
            raf.seek(rafSliceStart + off);
            if (raf.read(arr, arrStart, numBytesToRead) < numBytesToRead) {
                throw new IOException("Unexpected EOF");
            }
            raf.seek(currPos);
        }
    }

    /**
     * Skip the given number of bytes from the current position.
     * 
     * @param numBytesToSkip
     *            the number of bytes to skip.
     */
    public void skip(final int numBytesToSkip) throws IOException {
        if (numBytesToSkip == 0) {
            return;
        } else if (numBytesToSkip < 0) {
            throw new IllegalArgumentException("Tried to skip a negative number of bytes");
        }
        if (byteBuffer != null) {
            try {
                // N.B. the cast to Buffer is necessary, see:
                // https://github.com/plasma-umass/doppio/issues/497#issuecomment-334740243
                // https://github.com/classgraph/classgraph/issues/284#issuecomment-443612800
                ((Buffer) byteBuffer).position(byteBuffer.position() + numBytesToSkip);
            } catch (final IllegalArgumentException e) {
                throw new IOException("Unexpected EOF");
            }
        } else {
            if (raf.skipBytes(numBytesToSkip) < numBytesToSkip) {
                throw new IOException("Unexpected EOF");
            }
        }
    }

    /**
     * @return the current position in the file slice.
     */
    public int position() throws IOException {
        if (byteBuffer != null) {
            return byteBuffer.position();
        } else {
            return (int) (raf.getFilePointer() - rafSliceStart);
        }
    }

    /**
     * Get the wrapped {@link ByteBuffer}, or returns null if this file is backed by a {@link RandomAccessFile}
     * instead.
     */
    public ByteBuffer getByteBuffer() {
        return byteBuffer;
    }

    /**
     * Unmap the wrapped {@link ByteBuffer} (if this object wraps a {@link MappedByteBuffer} and not an array-backed
     * {@link ByteBuffer} or {@link RandomAccessFile}).
     * 
     * @param log
     *            the log
     */
    public void close(final LogNode log) {
        if (isMemoryMappedByteBuffer) {
            FileUtils.closeDirectByteBuffer(byteBuffer, log);
        }
        if (raf != null) {
            try {
                raf.close();
            } catch (final IOException e) {
                // Ignore
            }
            raf = null;
        }
    }
}
