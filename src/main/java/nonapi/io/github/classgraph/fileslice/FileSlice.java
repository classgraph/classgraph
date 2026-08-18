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
package nonapi.io.github.classgraph.fileslice;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.classgraph.ClassGraph;
import nonapi.io.github.classgraph.fastzipfilereader.NestedJarHandler;
import nonapi.io.github.classgraph.fileslice.reader.RandomAccessByteBufferReader;
import nonapi.io.github.classgraph.fileslice.reader.RandomAccessFileChannelReader;
import nonapi.io.github.classgraph.fileslice.reader.RandomAccessReader;
import nonapi.io.github.classgraph.utils.FileUtils;
import nonapi.io.github.classgraph.utils.LogNode;
import nonapi.io.github.classgraph.utils.VersionFinder;

/** A {@link File} slice. */
public class FileSlice extends Slice {
    /** The {@link File}. */
    public final File file;

    /** The {@link RandomAccessFile} opened on the {@link File}. */
    public RandomAccessFile raf;

    /** The file length. */
    private final long fileLength;

    /** The file channel. */
    private FileChannel fileChannel;

    /**
     * The mapped byte buffer, if the file was memory-mapped, or null once closed. Only set on the toplevel file
     * slice, which owns the mapping. Volatile, since every slice of the file reads it, but only the toplevel slice
     * writes it.
     */
    private volatile ByteBuffer backingByteBuffer;

    /**
     * The {@code java.lang.foreign.Arena} (JDK 22+) used to memory-map the file, if any. Typed as {@link Object},
     * since ClassGraph needs to compile and run on JDK 8+. Closing the arena unmaps {@link #backingByteBuffer},
     * without needing to call the terminally-deprecated {@code Unsafe::invokeCleaner} method (#939). Only set on
     * the toplevel file slice, which owns the mapping, and only on JDK 22 or later, where arenas exist.
     */
    private Object arena;

    /**
     * The toplevel file slice, which owns the memory mapping, or {@code this} if this is the toplevel slice.
     */
    private final FileSlice topLevelFileSlice;

    /** True if {@link #close} has been called. */
    private final AtomicBoolean isClosed = new AtomicBoolean();

    /**
     * Needed because the JDK team changed several API methods to return ByteBuffer rather than Buffer
     * in JDK 9, which caused some methods to throw NoSuchMethodError in a way that cannot be statically
     * detected (see #284). This is implemented as a method rather than as an in-place cast so that IDEs
     * are unlikely to remove the cast operations as (as they assume) statically superfluous, which
     * re-introduces the same runtime crash every time it happens.
     *
     * @param buf
     *            the {@link ByteBuffer} to widen to {@link Buffer}
     * @return the same buffer, typed as {@link Buffer}
     */
    public static Buffer toBuffer(final ByteBuffer buf) {
        return buf;
    }

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
     * @param nestedJarHandler
     *            the nested jar handler
     */
    private FileSlice(final FileSlice parentSlice, final long offset, final long length,
            final boolean isDeflatedZipEntry, final long inflatedLengthHint,
            final NestedJarHandler nestedJarHandler) {
        super(parentSlice, offset, length, isDeflatedZipEntry, inflatedLengthHint, nestedJarHandler);
        this.file = parentSlice.file;
        this.raf = parentSlice.raf;
        this.fileChannel = parentSlice.fileChannel;
        this.fileLength = parentSlice.fileLength;
        this.topLevelFileSlice = parentSlice.topLevelFileSlice;

        // A sub slice reads the memory mapping through the toplevel slice rather than keeping a duplicate of
        // its own: any view of a mapping keeps it alive, and below JDK 22 a mapping is released only once the
        // garbage collector finds it unreachable, so a sub slice holding a duplicate would keep the file mapped
        // -- and, on Windows, locked open -- however long ago the file was closed. The mapping always covers the
        // whole file, and is addressed in whole-file coordinates by way of sliceStartPos, in a sub slice as much
        // as in the toplevel slice.
        //
        // Only mark toplevel file slices as open (sub slices don't need to be marked as open since
        // they don't need to be closed, they just copy the resource references of the toplevel slice) 
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
     * @param nestedJarHandler
     *            the nested jar handler
     * @param log
     *            the log
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
        this.topLevelFileSlice = this;

        // (Files larger than MAX_BUFFER_SIZE cannot be memory-mapped to a single ByteBuffer -- for those,
        // fall through and use the RandomAccessFile API instead)
        if (nestedJarHandler.scanSpec.enableMemoryMapping && fileLength <= FileUtils.MAX_BUFFER_SIZE) {
            // On JDK 22+, memory-map the file using the java.lang.foreign.Arena API, so that the mapped
            // ByteBuffer is unmapped as soon as this slice is closed, by closing the arena. Below JDK 22 there
            // are no arenas, so the file is mapped with FileChannel#map and left to the JDK's own cleaner to
            // unmap (see mapFile()).
            boolean canMapFile = true;
            if (VersionFinder.JAVA_MAJOR_VERSION >= 22) {
                arena = FileUtils.openArena(nestedJarHandler.reflectionUtils);
                canMapFile = arena != null;
            }
            if (canMapFile) {
                try {
                    // Try mapping file (some operating systems throw OutOfMemoryError if file
                    // can't be mapped, some throw IOException)
                    backingByteBuffer = mapFile();
                } catch (IOException | OutOfMemoryError e) {
                    // Try running garbage collection then try mapping the file again
                    System.gc();
                    nestedJarHandler.runFinalizationMethod();
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
                    FileUtils.closeArena(arena, nestedJarHandler.reflectionUtils, log);
                    arena = null;
                }
            }
        }

        // Mark toplevel slice as open
        nestedJarHandler.markSliceAsOpen(this);
    }

    /**
     * Memory-map the whole file to a {@link ByteBuffer}.
     *
     * <p>
     * A mapping outlives the call that reads from it -- a {@link RandomAccessByteBufferReader} keeps a duplicate
     * for the life of the reader, and {@link #read()} hands a slice of it to the caller -- so a mapping can still
     * be being read when the {@link io.github.classgraph.ScanResult} that owns it is closed. It therefore has to
     * be released in a way that cannot pull the memory out from under a thread that is still reading it: on JDK
     * 22 and later, by closing the arena the file was mapped in, which makes the reading thread throw a
     * recoverable {@link IllegalStateException}; below JDK 22, where there are no arenas, by leaving the mapping
     * to the JDK's own cleaner, which unmaps the file once no view of it is reachable any more. What is never
     * called is {@code Unsafe::invokeCleaner} (#939), which frees the address range unconditionally, so that a
     * thread that reads one byte afterwards takes a SIGSEGV that kills the JVM.
     *
     * @return the mapped byte buffer.
     * @throws IOException
     *             if an I/O exception occurred while mapping the file (mapping may succeed if retried after
     *             garbage collection).
     */
    private ByteBuffer mapFile() throws IOException {
        return arena == null ? fileChannel.map(FileChannel.MapMode.READ_ONLY, 0L, fileLength)
                : FileUtils.mapFileUsingArena(arena, fileChannel, 0L, fileLength,
                        nestedJarHandler.reflectionUtils);
    }

    /**
     * Constructor for toplevel file slice.
     *
     * @param file
     *            the file
     * @param nestedJarHandler
     *            the nested jar handler
     * @param log
     *            the log
     * @throws IOException
     *             if the file cannot be opened.
     */
    public FileSlice(final File file, final NestedJarHandler nestedJarHandler, final LogNode log)
            throws IOException {
        this(file, /* isDeflatedZipEntry = */ false, /* inflatedSizeHint = */ 0L, nestedJarHandler, log);
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
        return new FileSlice(this, offset, length, isDeflatedZipEntry, inflatedLengthHint, nestedJarHandler);
    }

    /**
     * Read directly from FileChannel (slow path, but handles &gt;2GB).
     *
     * @return the random access reader
     */
    @Override
    public RandomAccessReader randomAccessReader() {
        // Read the field into a local, so that a close running concurrently cannot null it between the check
        // and the use
        final ByteBuffer mappedByteBuffer = topLevelFileSlice.backingByteBuffer;
        if (mappedByteBuffer == null) {
            // If file was not mmap'd, return a RandomAccessReader that uses the FileChannel
            return new RandomAccessFileChannelReader(fileChannel, sliceStartPos, sliceLength);
        } else {
            // If file was mmap'd, return a RandomAccessReader that uses the ByteBuffer. The reader keeps a
            // duplicate of the mapping for as long as it is alive, so it is also given the toplevel slice's
            // closed flag to check before each read -- below JDK 22 closing the file does not unmap it, so
            // without the flag a reader that outlived the close would keep returning file content
            return new RandomAccessByteBufferReader(mappedByteBuffer, sliceStartPos, sliceLength,
                    topLevelFileSlice.isClosed);
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
        // Read the field into a local, so that a close running concurrently cannot null it between the check
        // and the use
        final ByteBuffer mappedByteBuffer = topLevelFileSlice.backingByteBuffer;
        if (isDeflatedZipEntry) {
            // Inflate to RAM if deflated (unfortunately there is no lazy-loading ByteBuffer that will
            // decompress partial streams on demand, so we have to decompress the whole zip entry) 
            if (inflatedLengthHint > FileUtils.MAX_BUFFER_SIZE) {
                throw new IOException("Uncompressed size is larger than 2GB");
            }
            return ByteBuffer.wrap(load());
        } else if (mappedByteBuffer == null) {
            // Copy from RandomAccessFile to byte array, then wrap in a ByteBuffer
            if (sliceLength > FileUtils.MAX_BUFFER_SIZE) {
                throw new IOException("File is larger than 2GB");
            }
            return ByteBuffer.wrap(load());
        } else {
            // FileSlice is backed with the memory mapping of the whole file, which covers the whole file even for
            // a sub-slice, so narrow the mapping to this slice (a low-cost operation). Slicing, rather than merely
            // duplicating, is what makes the returned buffer start at position zero and stops it from being
            // widened again (by ByteBuffer#clear, say) to reach the rest of the file. (ByteBuffer#slice(int, int)
            // would narrow and slice in one call, but it was only added in JDK 13.)
            final ByteBuffer dup = mappedByteBuffer.duplicate();
            final Buffer bb = toBuffer(dup);
            bb.position((int) sliceStartPos);
            bb.limit((int) (sliceStartPos + sliceLength));
            return dup.slice();
        }
    }

    @Override
    public boolean equals(final Object o) {
        return super.equals(o);
    }

    @Override
    public int hashCode() {
        return super.hashCode();
    }

    /** Close the slice. Releases any memory mapping of the file. */
    @Override
    public void close() {
        if (!isClosed.getAndSet(true)) {
            // Only the toplevel file slice owns the mapping and the file handle, so that they are only released
            // once (a sub slice reads the mapping through the toplevel slice, and just copies the reference to
            // the toplevel slice's RandomAccessFile)
            if (topLevelFileSlice == this) {
                if (arena != null) {
                    // On JDK 22+ the file is unmapped by closing the arena it was mapped in, which unmaps it
                    // the moment this slice is closed (see mapFile())
                    FileUtils.closeArena(arena, nestedJarHandler.reflectionUtils, /* log = */ null);
                    arena = null;
                }
                // Below JDK 22 there is no arena, and dropping the reference to the mapped buffer is not merely
                // tidiness: it is what lets the garbage collector find the mapping unreachable and unmap the file
                backingByteBuffer = null;
                if (raf != null) {
                    try {
                        // Closing raf will also close the associated FileChannel
                        raf.close();
                    } catch (final IOException e) {
                        // Ignore
                    }
                }
            }
            fileChannel = null;
            raf = null;
            nestedJarHandler.markSliceAsClosed(this);
        }
    }
}
