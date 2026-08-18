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
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.classgraph.base.LogNode;
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
     * The {@link FileChannel} opened on the {@link Path}, or null once closed. Only set on the toplevel file slice,
     * which owns the file channel.
     */
    private @Nullable FileChannel fileChannel;

    /**
     * Get the {@link FileChannel} opened on the {@link Path}.
     *
     * @return the {@link FileChannel}
     * @throws IOException
     *             if {@link #close()} has been called
     */
    private FileChannel fileChannel() throws IOException {
        // Read the field into a local, so that a close running concurrently cannot null it between the check and
        // the use
        final var channel = topLevelPathSlice.fileChannel;
        if (channel == null) {
            throw new IOException("Cannot read " + path + " after the Vfs has been closed");
        }
        return channel;
    }

    /**
     * The toplevel file slice, which owns the file channel and the memory mapping, or {@code this} if this is the
     * toplevel slice.
     */
    private final PathSlice topLevelPathSlice;

    /**
     * The memory mapping of the file, if it was memory-mapped. Only set on the toplevel file slice, which owns the
     * mapping.
     */
    private @Nullable FileMapping fileMapping;

    /**
     * The mapped byte buffer, if the file was memory-mapped, or null once closed. Only set on the toplevel file
     * slice, which owns the mapping. Volatile, since every slice of the file reads it, but only the toplevel slice
     * writes it.
     */
    private volatile @Nullable ByteBuffer backingByteBuffer;

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
        this.fileLength = parentSlice.fileLength;
        this.topLevelPathSlice = parentSlice.topLevelPathSlice;

        // A sub slice reads through the toplevel slice's file channel and memory mapping rather than keeping
        // copies of its own, so that closing the toplevel slice releases both of them for every slice of the file
        // at once. A copy of the mapped buffer would matter most: below JDK 22 a mapping is released only once
        // the garbage collector finds it unreachable, so a sub slice holding a view of it would keep the file
        // mapped -- and, on Windows, locked open -- however long ago the file was closed. The mapping always
        // covers the whole file, and is addressed in whole-file coordinates by way of sliceStartPos, in a sub
        // slice as much as in the toplevel slice.
        //
        // Only mark toplevel file slices as open (sub slices don't need to be marked as open since they don't need
        // to be closed, they read through the toplevel slice's file channel and mapping)
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
        // Set before the file is opened, since it is what tells close() that this slice owns the file channel
        this.topLevelPathSlice = this;

        final var fileChannelOpened = FileChannel.open(path, StandardOpenOption.READ);
        this.fileChannel = fileChannelOpened;
        // Nothing but this constructor knows about the file channel until the slice is registered as open, so if
        // anything below throws, this is the only place the channel can be closed
        try {
            this.fileLength = fileChannelOpened.size();

            // Had to use 0L for sliceLength in call to super, since FileChannel wasn't open yet => update
            // sliceLength
            this.sliceLength = fileLength;

            if (memoryMapWholeFile && session.vfsSpec.isMemoryMappingFiles()) {
                // Memory-map the whole file, if it can be mapped -- otherwise fall through and read through the
                // FileChannel API instead
                final var mapping = FileMapping.map(fileChannelOpened, fileLength, path, log);
                fileMapping = mapping;
                backingByteBuffer = mapping == null ? null : mapping.byteBuffer;
            }

            // Mark toplevel slice as open
            registerAsOpen();
        } catch (final IOException | RuntimeException | Error e) {
            close();
            throw e;
        }
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
     * @throws IOException
     *             if this slice has been closed, so that there is neither a mapping nor a file handle left to read
     *             through.
     */
    @Override
    public RandomAccessReader randomAccessReader() throws IOException {
        // Read the field into a local, so that a close running concurrently cannot null it between the check and
        // the use
        final var mappedByteBuffer = topLevelPathSlice.backingByteBuffer;
        if (mappedByteBuffer == null) {
            // If file was not mmap'd, return a RandomAccessReader that uses the FileChannel
            return new RandomAccessFileChannelReader(fileChannel(), sliceStartPos, sliceLength);
        } else {
            // If file was mmap'd, return a RandomAccessReader that uses the ByteBuffer. The reader keeps a view
            // of the mapping for as long as it is alive, so it is also given the toplevel slice's closed flag to
            // check before each read -- below JDK 22 closing the file does not unmap it, so without the flag a
            // reader that outlived the close would keep returning content
            return new RandomAccessByteBufferReader(mappedByteBuffer, sliceStartPos, sliceLength,
                    topLevelPathSlice.isClosed::get);
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
        // Read the field into a local, so that a close running concurrently cannot null it between the check and
        // the use
        final var mappedByteBuffer = topLevelPathSlice.backingByteBuffer;
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
            // Unregister this slice before releasing anything of it, so that the session cannot hand out a slice
            // that has already started closing. This cannot fail, so the release below is still reached
            session.markSliceAsClosed(this);
            if (topLevelPathSlice != this) {
                // Only the toplevel file slice owns the file channel and the memory mapping -- a sub slice reads
                // through the toplevel slice's, and has nothing of its own to release
                return;
            }
            // Take what has to be released, and drop the references to it, before releasing any of it: this slice
            // is already marked as closed, so a second call must not release the same resource twice. Below JDK 22
            // dropping the reference to the mapped buffer is not merely tidiness: there is no arena to close, so
            // it is what lets the garbage collector find the mapping unreachable and unmap the file
            final var mapping = fileMapping;
            final var fileChannelToClose = fileChannel;
            fileMapping = null;
            backingByteBuffer = null;
            fileChannel = null;
            try {
                if (mapping != null) {
                    mapping.unmap();
                    if (mapping.unmappedByGarbageCollector) {
                        session.markFileAsAwaitingUnmapping();
                    }
                }
            } finally {
                // The file channel is closed even if the file could not be unmapped -- this slice is already
                // marked as closed and unregistered, so nothing else would release it
                try {
                    if (fileChannelToClose != null) {
                        fileChannelToClose.close();
                    }
                } catch (final IOException e) {
                    // Ignore
                }
            }
        }
    }
}
