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
package nonapi.io.github.classgraph.fileslice;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileChannel.MapMode;
import java.util.concurrent.atomic.AtomicBoolean;

import nonapi.io.github.classgraph.fastzipfilereader.NestedJarHandler;
import nonapi.io.github.classgraph.fileslice.reader.RandomAccessByteBufferReader;
import nonapi.io.github.classgraph.fileslice.reader.RandomAccessFileReader;
import nonapi.io.github.classgraph.fileslice.reader.RandomAccessReader;
import nonapi.io.github.classgraph.utils.FileUtils;
import nonapi.io.github.classgraph.utils.LogNode;

/** A {@link File} slice. */
public class FileSlice extends Slice implements Closeable {
    /** The {@link File}. */
    public final File file;

    /** The {@link RandomAccessFile} opened on the {@link File}. */
    public RandomAccessFile raf;

    private final long fileLength;

    private FileChannel fileChannel;

    private ByteBuffer backingByteBuffer;

    private final boolean isTopLevelFileSlice;

    private final NestedJarHandler nestedJarHandler;

    private final AtomicBoolean isClosed = new AtomicBoolean();

    /** Constructor for sub-slice of file. */
    private FileSlice(final FileSlice parentSlice, final long offset, final long length,
            final boolean isDeflatedZipEntry, final long inflatedLengthHint,
            final NestedJarHandler nestedJarHandler) {
        super(parentSlice, offset, length, isDeflatedZipEntry, inflatedLengthHint, nestedJarHandler);
        this.file = parentSlice.file;
        this.raf = parentSlice.raf;
        this.fileChannel = parentSlice.fileChannel;
        this.fileLength = parentSlice.fileLength;
        this.nestedJarHandler = nestedJarHandler;
        this.isTopLevelFileSlice = false;

        if (parentSlice.backingByteBuffer != null) {
            // Duplicate and slice the backing byte buffer, if there is one
            this.backingByteBuffer = parentSlice.backingByteBuffer.duplicate();
            this.backingByteBuffer.position((int) sliceStartPos);
            this.backingByteBuffer.limit((int) (sliceStartPos + sliceLength));
        }

        // Only mark toplevel file slices as open (sub slices don't need to be marked as open since
        // they don't need to be closed, they just copy the resource references of the toplevel slice) 
    }

    /**
     * Constructor for toplevel file slice.
     * 
     * @throws IOException
     *             if the file cannot be opened.
     */
    public FileSlice(final File file, final boolean isDeflatedZipEntry, final long inflatedLengthHint,
            final NestedJarHandler nestedJarHandler, final LogNode log) throws IOException {
        super(file.length(), isDeflatedZipEntry, inflatedLengthHint, nestedJarHandler);
        // Make sure the File is readable and is a regular file
        FileUtils.checkCanReadAndIsFile(file);
        this.file = file;
        this.raf = new RandomAccessFile(file, "r");
        this.fileChannel = raf.getChannel();
        this.fileLength = file.length();
        this.isTopLevelFileSlice = true;
        this.nestedJarHandler = nestedJarHandler;

        if (nestedJarHandler.scanSpec.enableMemoryMapping) {
            try {
                // Try mapping file (some operating systems throw OutOfMemoryError if file
                // can't be mapped, some throw IOException)
                backingByteBuffer = fileChannel.map(MapMode.READ_ONLY, 0L, fileLength);
            } catch (IOException | OutOfMemoryError e) {
                // Try running garbage collection then try mapping the file again
                System.gc();
                System.runFinalization();
                try {
                    backingByteBuffer = fileChannel.map(MapMode.READ_ONLY, 0L, fileLength);
                } catch (IOException | OutOfMemoryError e2) {
                    if (log != null) {
                        log.log("File " + file + " cannot be memory mapped: " + e2
                                + " (using RandomAccessFile API instead)");
                    }
                    // Fall through -- RandomAccessFile API will be used instead
                }
            }
        }

        // Mark toplevel slice as open
        nestedJarHandler.markFileSliceAsOpen(this);
    }

    /**
     * Constructor for toplevel file slice.
     * 
     * @throws IOException
     *             if the file cannot be opened.
     */
    public FileSlice(final File file, final NestedJarHandler nestedJarHandler, final LogNode log)
            throws IOException {
        this(file, /* isDeflatedZipEntry = */ false, /* inflatedSizeHint = */ 0L, nestedJarHandler, log);
    }

    /** Slice the file. */
    @Override
    public Slice slice(final long offset, final long length, final boolean isDeflatedZipEntry,
            final long inflatedLengthHint) {
        if (this.isDeflatedZipEntry) {
            throw new IllegalArgumentException("Cannot slice a deflated zip entry");
        }
        return new FileSlice(this, offset, length, isDeflatedZipEntry, inflatedLengthHint, nestedJarHandler);
    }

    /** Read directly from FileChannel (slow path, but handles >2GB). */
    @Override
    public RandomAccessReader randomAccessReader() {
        if (backingByteBuffer == null) {
            // If file was not mmap'd, return a RandomAccessReader that uses the FileChannel
            return new RandomAccessFileReader(fileChannel, sliceStartPos, sliceLength);
        } else {
            // If file was mmap'd, return a RandomAccessReader that uses the ByteBuffer
            return new RandomAccessByteBufferReader(backingByteBuffer, sliceStartPos, sliceLength);
        }
    }

    @Override
    public byte[] load() throws IOException {
        if (isDeflatedZipEntry) {
            // Inflate into RAM if deflated
            if (inflatedLengthHint > FileUtils.MAX_BUFFER_SIZE) {
                throw new IOException("Uncompressed size is larger than 2GB");
            }
            try (InputStream inputStream = open()) {
                return NestedJarHandler.readAllBytesAsArray(inputStream, inflatedLengthHint);
            }
        } else {
            // Copy from either RandomAccessFile or MappedByteBuffer to byte array
            if (sliceLength > FileUtils.MAX_BUFFER_SIZE) {
                throw new IOException("File is larger than 2GB");
            }
            final RandomAccessReader reader = randomAccessReader();
            final byte[] content = new byte[(int) sliceLength];
            if (reader.read(0, content, 0, content.length) < content.length) {
                // Should not happen
                throw new IOException("File is truncated");
            }
            return content;
        }
    }

    @Override
    public ByteBuffer read() throws IOException {
        if (isDeflatedZipEntry) {
            // Inflate to RAM if deflated (unfortunately there is no lazy-loading ByteBuffer that will
            // decompress partial streams on demand, so we have to decompress the whole zip entry) 
            if (inflatedLengthHint > FileUtils.MAX_BUFFER_SIZE) {
                throw new IOException("Uncompressed size is larger than 2GB");
            }
            return ByteBuffer.wrap(load());
        } else if (backingByteBuffer == null) {
            // Copy from RandomAccessFile to byte array, then wrap in a ByteBuffer
            if (sliceLength > FileUtils.MAX_BUFFER_SIZE) {
                throw new IOException("File is larger than 2GB");
            }
            return ByteBuffer.wrap(load());
        } else {
            // FileSlice is backed with a MappedByteBuffer -- duplicate it and return it (low-cost operation)
            return backingByteBuffer.duplicate();
        }
    }

    // TOOD: close this in NestedJarHandler and ClasspathElementDir
    @Override
    public void close() {
        if (!isClosed.getAndSet(true)) {
            if (isTopLevelFileSlice && backingByteBuffer != null) {
                // Only close ByteBuffer in toplevel file slice, so that ByteBuffer is only closed once
                // (also duplicates of MappedByteBuffers cannot be closed by the cleaner API)
                FileUtils.closeDirectByteBuffer(backingByteBuffer, /* log = */ null);
            }
            backingByteBuffer = null;
            fileChannel = null;
            try {
                // Closing raf will also close the associated FileChannel
                raf.close();
            } catch (final IOException e) {
                // Ignore
            }
            raf = null;
            nestedJarHandler.markFileSliceAsClosed(this);
        }
    }
}
