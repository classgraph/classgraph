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

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileChannel.MapMode;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.classgraph.ClassGraph;
import nonapi.io.github.classgraph.fileslice.reader.RandomAccessByteBufferReader;
import nonapi.io.github.classgraph.fileslice.reader.RandomAccessFileChannelReader;
import nonapi.io.github.classgraph.fileslice.reader.RandomAccessReader;
import nonapi.io.github.classgraph.utils.FileUtils;
import nonapi.io.github.classgraph.utils.LogNode;
import nonapi.io.github.classgraph.utils.VersionFinder;
import org.jspecify.annotations.Nullable;

/** A {@link File} slice. */
public class FileSlice extends Slice {
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
     * The {@code java.lang.foreign.Arena} (JDK 22+) used to memory-map the file, if any. Typed as {@link Object},
     * since ClassGraph needs to compile and run on JDK 17+. Closing the arena unmaps {@link #backingByteBuffer},
     * without needing to call the terminally-deprecated {@code Unsafe::invokeCleaner} method. Only set for toplevel
     * file slices, which own the mapping (sub slices just duplicate the backing byte buffer).
     */
    // #939
    private @Nullable Object arena;

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

        if (parentSlice.backingByteBuffer != null) {
            // Duplicate and slice the backing byte buffer, if there is one
            this.backingByteBuffer = parentSlice.backingByteBuffer.duplicate();
            this.backingByteBuffer.position((int) sliceStartPos);
            this.backingByteBuffer.limit((int) (sliceStartPos + sliceLength));
        }

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
        this.fileLength = file.length();
        this.isTopLevelFileSlice = true;

        // (Files larger than MAX_BUFFER_SIZE cannot be memory-mapped to a single ByteBuffer -- for those, fall
        // through and use the RandomAccessFile API instead)
        if (scanResources.scanSpec.enableMemoryMapping && fileLength <= FileUtils.MAX_BUFFER_SIZE) {
            // On JDK 22+, memory-map the file using the java.lang.foreign.Arena API, so that the mapped ByteBuffer
            // can be unmapped by closing the arena when this slice is closed, rather than by calling the
            // terminally-deprecated method Unsafe::invokeCleaner (#939). (openArena returns null on JDK older than
            // 22.)
            arena = FileUtils.openArena(scanResources.reflectionUtils);
            try {
                // Try mapping file (some operating systems throw OutOfMemoryError if file can't be mapped, some
                // throw IOException)
                backingByteBuffer = mapFile();
            } catch (IOException | OutOfMemoryError e) {
                // Try running garbage collection then try mapping the file again
                System.gc();
                FileUtils.runFinalization(scanResources.reflectionUtils);
                try {
                    backingByteBuffer = mapFile();
                } catch (IOException | OutOfMemoryError e2) {
                    if (log != null) {
                        log.log("File " + file + " cannot be memory mapped: " + e2
                                + " (using RandomAccessFile API instead)");
                    }
                    // Fall through -- RandomAccessFile API will be used instead
                }
            }
            if (backingByteBuffer == null && arena != null) {
                // The arena ended up not being used to map the file -- close it again
                FileUtils.closeArena(arena, scanResources.reflectionUtils, log);
                arena = null;
            }
        }

        // Mark toplevel slice as open
        scanResources.markSliceAsOpen(this);
    }

    /**
     * Memory-map the file to a {@link ByteBuffer}, using an {@code Arena} to perform the mapping on JDK 22+, or
     * {@link FileChannel#map(MapMode, long, long)} on older JDK versions.
     *
     * @return the mapped byte buffer, or null if the arena-based mapping API could not be invoked reflectively.
     * @throws IOException
     *             if an I/O exception occurred while mapping the file (mapping may succeed if retried after garbage
     *             collection).
     */
    private @Nullable ByteBuffer mapFile() throws IOException {
        final var openFileChannel = Objects.requireNonNull(fileChannel);
        if (arena != null) {
            return FileUtils.mapFileUsingArena(arena, openFileChannel, 0L, fileLength,
                    scanResources.reflectionUtils);
        }
        if (VersionFinder.JAVA_MAJOR_VERSION >= 22) {
            // An arena could not be opened, even though the arena API should be available -- don't fall back to
            // FileChannel#map, since the resulting MappedByteBuffer could only be unmapped by the garbage collector
            // (Unsafe::invokeCleaner is not used on JDK 22+, see #939) -- use the RandomAccessFile API instead
            return null;
        }
        return openFileChannel.map(MapMode.READ_ONLY, 0L, fileLength);
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
            if (inflatedLengthHint > FileUtils.MAX_BUFFER_SIZE) {
                throw new IOException("Uncompressed size is larger than 2GB");
            }
            try (var inputStream = open()) {
                return FileUtils.readAllBytesAsArray(inputStream, inflatedLengthHint);
            }
        } else {
            // Copy from either RandomAccessFile or MappedByteBuffer to byte array
            if (sliceLength > FileUtils.MAX_BUFFER_SIZE) {
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
     * Read the slice into a {@link ByteBuffer} (or memory-map the slice to a {@link MappedByteBuffer}, if
     * {@link ClassGraph#enableMemoryMapping()} was called.)
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

    /** Close the slice. Unmaps any backing {@link MappedByteBuffer}. */
    @Override
    public void close() {
        if (!isClosed.getAndSet(true)) {
            if (isTopLevelFileSlice && backingByteBuffer != null) {
                // Only unmap the backing ByteBuffer in the toplevel file slice, so that it is only closed once
                // (also duplicates of mapped ByteBuffers cannot be closed by the cleaner API)
                if (arena != null) {
                    // JDK 22+: unmap the ByteBuffer by closing the arena that was used to map it (#939)
                    FileUtils.closeArena(arena, scanResources.reflectionUtils, /* log = */ null);
                    arena = null;
                } else {
                    FileUtils.closeDirectByteBuffer(backingByteBuffer, scanResources.reflectionUtils,
                            /* log = */ null);
                }
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
