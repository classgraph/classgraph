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

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.classgraph.base.internal.log.LogNode;
import io.github.classgraph.base.internal.path.FileUtils;
import io.github.classgraph.vfs.internal.VfsSession;
import io.github.classgraph.vfs.internal.slice.reader.RandomAccessByteBufferReader;
import io.github.classgraph.vfs.internal.slice.reader.RandomAccessFileChannelReader;
import io.github.classgraph.vfs.internal.slice.reader.RandomAccessReader;
import org.jspecify.annotations.Nullable;

/** A {@link Path} slice. */
public final class PathSlice extends Slice {
    /** The {@link Path}. */
    public final Path path;

    /** The file length. */
    private final long fileLength;

    /**
     * The {@link FileChannel} opened on the {@link Path}. Set to null by {@link #close()}.
     */
    private @Nullable FileChannel fileChannel;

    /**
     * Get the {@link FileChannel} opened on the {@link Path}.
     *
     * @return the {@link FileChannel}
     * @throws NullPointerException
     *             if {@link #close()} has been called
     */
    private FileChannel fileChannel() {
        return Objects.requireNonNull(fileChannel);
    }

    /** True if this is a top level file slice. */
    private final boolean isTopLevelFileSlice;

    /**
     * The memory mapping of the file, if it was memory-mapped. Only set for toplevel file slices, which own the
     * mapping (sub slices just duplicate the backing byte buffer).
     */
    private @Nullable FileMapping fileMapping;

    /** The backing byte buffer, if the file was memory-mapped. */
    private @Nullable ByteBuffer backingByteBuffer;

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
     * @param session
     *            the session that owns what is opened
     */
    private PathSlice(final PathSlice parentSlice, final long offset, final long length,
            final boolean isDeflatedZipEntry, final long inflatedLengthHint, final VfsSession session) {
        super(parentSlice, offset, length, isDeflatedZipEntry, inflatedLengthHint, session);

        this.path = parentSlice.path;
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
     * @param path
     *            the path
     * @param session
     *            the session that owns what is opened
     * @param checkAccess
     *            whether it is needed to check read access and if it is a file
     * @param memoryMapWholeFile
     *            if true, and files are memory-mapped on this platform, memory-map the whole file. Only pass true
     *            for a file that is read many times at random offsets, such as a zipfile -- for a file that is read
     *            once and then closed, mapping and unmapping the file costs more than reading it.
     * @param log
     *            the log node, or null to skip logging
     * @throws IOException
     *             if the file cannot be opened.
     */
    public PathSlice(final Path path, final VfsSession session, final boolean checkAccess,
            final boolean memoryMapWholeFile, final @Nullable LogNode log) throws IOException {
        super(0L, /* isDeflatedZipEntry = */ false, /* inflatedLengthHint = */ 0L, session);

        if (checkAccess) {
            // Make sure the File is readable and is a regular file
            FileUtils.checkCanReadAndIsFile(path);
        }

        this.path = path;
        final var fileChannelOpened = FileChannel.open(path, StandardOpenOption.READ);
        this.fileChannel = fileChannelOpened;
        this.fileLength = fileChannelOpened.size();
        this.isTopLevelFileSlice = true;

        // Had to use 0L for sliceLength in call to super, since FileChannel wasn't open yet => update sliceLength
        this.sliceLength = fileLength;

        if (memoryMapWholeFile && session.vfsSpec.memoryMapFiles) {
            // Memory-map the whole file, if it can be mapped -- otherwise fall through and read through the
            // FileChannel API instead
            final var mapping = FileMapping.map(fileChannelOpened, fileLength, path, log);
            fileMapping = mapping;
            backingByteBuffer = mapping == null ? null : mapping.byteBuffer;
        }

        // Mark toplevel slice as open
        session.markSliceAsOpen(this);
    }

    /**
     * Constructor for a toplevel slice of a whole zipfile.
     *
     * @param path
     *            the path
     * @param session
     *            the session that owns what is opened
     * @param log
     *            the log node, or null to skip logging
     * @throws IOException
     *             if the file cannot be opened.
     */
    public PathSlice(final Path path, final VfsSession session, final @Nullable LogNode log) throws IOException {
        this(path, session, /* checkAccess = */ true, /* memoryMapWholeFile = */ true, log);
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
        return new PathSlice(this, offset, length, isDeflatedZipEntry, inflatedLengthHint, session);
    }

    /**
     * Read directly from FileChannel (slow path, but handles &gt;2GB).
     *
     * @return the random access reader
     */
    @Override
    public RandomAccessReader randomAccessReader() {
        final var mappedByteBuffer = backingByteBuffer;
        if (mappedByteBuffer == null) {
            // If file was not mmap'd, return a RandomAccessReader that uses the FileChannel
            return new RandomAccessFileChannelReader(fileChannel(), sliceStartPos, sliceLength);
        } else {
            // If file was mmap'd, return a RandomAccessReader that uses the ByteBuffer
            return new RandomAccessByteBufferReader(mappedByteBuffer, sliceStartPos, sliceLength);
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
            // Copy from FileChannel to byte array
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
     * platform where files are memory-mapped, if this slice is part of a zipfile).
     *
     * @return the byte buffer
     * @throws IOException
     *             Signals that an I/O exception has occurred.
     */
    @Override
    public ByteBuffer read() throws IOException {
        final var mappedByteBuffer = backingByteBuffer;
        if (isDeflatedZipEntry) {
            // Inflate to RAM if deflated (unfortunately there is no lazy-loading ByteBuffer that will decompress
            // partial streams on demand, so we have to decompress the whole zip entry)
            if (inflatedLengthHint > Slice.MAX_BUFFER_SIZE) {
                throw new IOException("Uncompressed size is larger than 2GB");
            }
            return ByteBuffer.wrap(load()).asReadOnlyBuffer();
        } else if (mappedByteBuffer == null) {
            // Copy from FileChannel to byte array, then wrap in a ByteBuffer
            if (sliceLength > Slice.MAX_BUFFER_SIZE) {
                throw new IOException("File is larger than 2GB");
            }
            return ByteBuffer.wrap(load()).asReadOnlyBuffer();
        } else {
            // PathSlice is backed with the memory mapping of the whole file, which covers the whole file even for a
            // sub-slice, so narrow the mapping to this slice (a low-cost operation). Slicing, rather than merely
            // setting the position and limit of a duplicate, is what makes the returned buffer start at position
            // zero and stops it from being widened again (by ByteBuffer#clear, say) to reach the rest of the file.
            return mappedByteBuffer.slice((int) sliceStartPos, (int) sliceLength).asReadOnlyBuffer();
        }
    }

    /**
     * Close the slice, unmapping any backing {@link MappedByteBuffer} and closing the {@link FileChannel} if this
     * is the toplevel slice.
     */
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
            final var fileChannelCurr = fileChannel;
            if (isTopLevelFileSlice && fileChannelCurr != null) {
                // Only close the FileChannel in the toplevel file slice, so that it is only closed once (sub slices
                // just copy the reference to the toplevel slice's FileChannel)
                try {
                    fileChannelCurr.close();
                } catch (final IOException e) {
                    // Ignore
                }
            }
            fileChannel = null;
            session.markSliceAsClosed(this);
        }
    }
}
