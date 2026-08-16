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
package io.github.classgraph.vfs.internal.slice;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.classgraph.base.internal.log.LogNode;
import io.github.classgraph.base.internal.path.FileUtils;
import io.github.classgraph.vfs.internal.ScanResources;
import io.github.classgraph.vfs.internal.slice.reader.RandomAccessByteBufferReader;
import io.github.classgraph.vfs.internal.slice.reader.RandomAccessFileChannelReader;
import io.github.classgraph.vfs.internal.slice.reader.RandomAccessReader;
import org.jspecify.annotations.Nullable;

/**
 * A {@link File} slice. Unlike {@link PathSlice}, which is also opened for single classfiles, this always
 * memory-maps the whole file on a platform where files are memory-mapped, so it should only be opened for a file
 * that is read many times at random offsets, such as a zipfile.
 */
public final class FileSlice extends Slice {
    /** The {@link File}. */
    public final File file;

    /** The {@link RandomAccessFile} opened on the {@link File}, or null once closed. */
    private @Nullable RandomAccessFile raf;

    /** The file length. */
    private final long fileLength;

    /** The file channel, or null once closed. */
    private @Nullable FileChannel fileChannel;

    /** The backing byte buffer, if any. */
    private @Nullable ByteBuffer backingByteBuffer;

    /**
     * The memory mapping of the file, if it was memory-mapped. Only set for toplevel file slices, which own the
     * mapping (sub slices just duplicate the backing byte buffer).
     */
    private @Nullable FileMapping fileMapping;

    /** True if this is a top level file slice. */
    private final boolean isTopLevelFileSlice;

    /** True if {@link #close} has been called. */
    private final AtomicBoolean isClosed = new AtomicBoolean();

    /**
     * Constructor for treating a range of a file as a slice.
     *
     * @param parentSlice
     *            the parent slice
     * @param offset
     *            the offset of the sub-slice within the parent slice
     * @param length
     *            the length of the sub-slice
     * @param isDeflatedZipEntry
     *            true if this is a deflated zip entry
     * @param inflatedLengthHint
     *            the uncompressed size of a deflated zip entry, or -1 if unknown, or 0 of this is not a deflated
     *            zip entry.
     * @param scanResources
     *            the resources owned by the scan
     */
    private FileSlice(final FileSlice parentSlice, final long offset, final long length,
            final boolean isDeflatedZipEntry, final long inflatedLengthHint, final ScanResources scanResources) {
        super(parentSlice, offset, length, isDeflatedZipEntry, inflatedLengthHint, scanResources);
        this.file = parentSlice.file;
        this.raf = parentSlice.raf;
        this.fileChannel = parentSlice.fileChannel;
        this.fileLength = parentSlice.fileLength;
        this.isTopLevelFileSlice = false;

        // The backing byte buffer always covers the whole file, and is addressed in whole-file coordinates by way
        // of sliceStartPos, both here and in the toplevel slice. It is duplicated so that this slice has its own
        // position and limit, but its position and limit are not narrowed to the sub-slice, since every use of it
        // sets them for itself.
        this.backingByteBuffer = parentSlice.backingByteBuffer == null ? null
                : parentSlice.backingByteBuffer.duplicate();

        // Only mark toplevel file slices as open (sub slices don't need to be marked as open since they don't need
        // to be closed, they just copy the resource references of the toplevel slice)
    }

    /**
     * Constructor for toplevel file slice.
     *
     * @param file
     *            the file
     * @param isDeflatedZipEntry
     *            true if this is a deflated zip entry
     * @param inflatedLengthHint
     *            the uncompressed size of a deflated zip entry, or -1 if unknown, or 0 of this is not a deflated
     *            zip entry.
     * @param scanResources
     *            the resources owned by the scan
     * @param log
     *            the log node, or null to skip logging
     * @throws IOException
     *             if the file cannot be opened.
     */
    public FileSlice(final File file, final boolean isDeflatedZipEntry, final long inflatedLengthHint,
            final ScanResources scanResources, final @Nullable LogNode log) throws IOException {
        super(file.length(), isDeflatedZipEntry, inflatedLengthHint, scanResources);
        // Make sure the File is readable and is a regular file
        FileUtils.checkCanReadAndIsFile(file);
        this.file = file;
        this.raf = new RandomAccessFile(file, "r");
        this.fileChannel = raf.getChannel();
        // (sliceLength was set to file.length() by the call to super, so don't measure the file a second time --
        // the two values have to agree, since the memory mapping covers fileLength but is read through sliceLength)
        this.fileLength = sliceLength;
        this.isTopLevelFileSlice = true;

        if (scanResources.vfsScanSpec.memoryMapFiles) {
            // Memory-map the whole file, if it can be mapped -- otherwise fall through and use the
            // RandomAccessFile API instead
            final var mapping = FileMapping.map(Objects.requireNonNull(fileChannel), fileLength, file, log);
            fileMapping = mapping;
            backingByteBuffer = mapping == null ? null : mapping.byteBuffer;
        }

        // Mark toplevel slice as open
        scanResources.markSliceAsOpen(this);
    }

    /**
     * Constructor for toplevel file slice.
     *
     * @param file
     *            the file
     * @param scanResources
     *            the resources owned by the scan
     * @param log
     *            the log node, or null to skip logging
     * @throws IOException
     *             if the file cannot be opened.
     */
    public FileSlice(final File file, final ScanResources scanResources, final @Nullable LogNode log)
            throws IOException {
        this(file, /* isDeflatedZipEntry = */ false, /* inflatedSizeHint = */ 0L, scanResources, log);
    }

    /**
     * Slice the file.
     *
     * @param offset
     *            the offset of the sub-slice within the parent slice
     * @param length
     *            the length of the sub-slice
     * @param isDeflatedZipEntry
     *            true if this is a deflated zip entry
     * @param inflatedLengthHint
     *            the uncompressed size of a deflated zip entry, or -1 if unknown, or 0 of this is not a deflated
     *            zip entry.
     * @return the slice
     */
    @Override
    public Slice slice(final long offset, final long length, final boolean isDeflatedZipEntry,
            final long inflatedLengthHint) {
        if (this.isDeflatedZipEntry) {
            throw new IllegalArgumentException("Cannot slice a deflated zip entry");
        }
        return new FileSlice(this, offset, length, isDeflatedZipEntry, inflatedLengthHint, scanResources);
    }

    /**
     * Read directly from FileChannel (slow path, but handles &gt;2GB).
     *
     * @return the random access reader
     */
    @Override
    public RandomAccessReader randomAccessReader() {
        if (backingByteBuffer == null) {
            // If file was not mmap'd, return a RandomAccessReader that uses the FileChannel
            return new RandomAccessFileChannelReader(Objects.requireNonNull(fileChannel), sliceStartPos,
                    sliceLength);
        } else {
            // If file was mmap'd, return a RandomAccessReader that uses the ByteBuffer
            return new RandomAccessByteBufferReader(backingByteBuffer, sliceStartPos, sliceLength);
        }
    }

    /**
     * Load the slice as a byte array.
     *
     * @return the byte[]
     * @throws IOException
     *             Signals that an I/O exception has occurred.
     */
    @Override
    public byte[] load() throws IOException {
        if (isDeflatedZipEntry) {
            // Inflate into RAM if deflated
            if (inflatedLengthHint > Slice.MAX_BUFFER_SIZE) {
                throw new IOException("Uncompressed size is larger than 2GB");
            }
            try (var inputStream = open()) {
                return Slice.readAllBytesAsArray(inputStream, inflatedLengthHint);
            }
        } else {
            // Copy from either RandomAccessFile or MappedByteBuffer to byte array
            if (sliceLength > Slice.MAX_BUFFER_SIZE) {
                throw new IOException("File is larger than 2GB");
            }
            final var reader = randomAccessReader();
            final var content = new byte[(int) sliceLength];
            if (reader.read(0, content, 0, content.length) < content.length) {
                // Should not happen
                throw new IOException("File is truncated");
            }
            return content;
        }
    }

    /**
     * Read the slice into a {@link ByteBuffer} (or memory-map the slice to a {@link MappedByteBuffer}, on a
     * platform where files are memory-mapped).
     *
     * @return the byte buffer
     * @throws IOException
     *             Signals that an I/O exception has occurred.
     */
    @Override
    public ByteBuffer read() throws IOException {
        if (isDeflatedZipEntry) {
            // Inflate to RAM if deflated (unfortunately there is no lazy-loading ByteBuffer that will decompress
            // partial streams on demand, so we have to decompress the whole zip entry)
            if (inflatedLengthHint > Slice.MAX_BUFFER_SIZE) {
                throw new IOException("Uncompressed size is larger than 2GB");
            }
            return ByteBuffer.wrap(load()).asReadOnlyBuffer();
        } else if (backingByteBuffer == null) {
            // Copy from RandomAccessFile to byte array, then wrap in a ByteBuffer
            if (sliceLength > Slice.MAX_BUFFER_SIZE) {
                throw new IOException("File is larger than 2GB");
            }
            return ByteBuffer.wrap(load()).asReadOnlyBuffer();
        } else {
            // FileSlice is backed with the memory mapping of the whole file, which covers the whole file even for a
            // sub-slice, so narrow the mapping to this slice (a low-cost operation). Slicing, rather than merely
            // setting the position and limit of a duplicate, is what makes the returned buffer start at position
            // zero and stops it from being widened again (by ByteBuffer#clear, say) to reach the rest of the file.
            return backingByteBuffer.slice((int) sliceStartPos, (int) sliceLength).asReadOnlyBuffer();
        }
    }

    /** Close the slice. Unmaps any backing {@link MappedByteBuffer}. */
    @Override
    public void close() {
        if (!isClosed.getAndSet(true)) {
            final var mapping = fileMapping;
            if (mapping != null) {
                // Only the toplevel file slice has a FileMapping, so the file is only unmapped once (also
                // duplicates of mapped ByteBuffers cannot be closed by the cleaner API)
                mapping.unmap();
                fileMapping = null;
            }
            backingByteBuffer = null;
            fileChannel = null;
            final var rafCurr = raf;
            if (isTopLevelFileSlice && rafCurr != null) {
                // Only close the RandomAccessFile in the toplevel file slice, so that it is only closed once (sub
                // slices just copy the reference to the toplevel slice's RandomAccessFile)
                try {
                    // Closing raf will also close the associated FileChannel
                    rafCurr.close();
                } catch (final IOException e) {
                    // Ignore
                }
            }
            raf = null;
            scanResources.markSliceAsClosed(this);
        }
    }
}
