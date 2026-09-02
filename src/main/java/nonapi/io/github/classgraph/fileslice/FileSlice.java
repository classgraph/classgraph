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
import java.util.concurrent.atomic.AtomicInteger;

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
     * The number of views of the memory mapping that the caller could still read, and that therefore have to be
     * released before the file can be unmapped. Only views that outlive the call that produced them are counted: a
     * buffer handed to the caller of {@link io.github.classgraph.Resource#read()} is one, whereas a read that
     * copies bytes out of the mapping and returns is over before it returns. Only used on the toplevel file slice,
     * which owns the mapping.
     */
    // #939
    private final AtomicInteger openViews = new AtomicInteger();

    /** True once the toplevel slice has closed, after which no new view of the mapping is handed out. */
    // #939
    private volatile boolean mappingReleased;

    /** True once the file has been unmapped. Read and written only while holding the lock on this object. */
    // #939
    private boolean unmapped;

    /**
     * An action to run once the file has been unmapped, or null if there is none. Read and written only while
     * holding the lock on this object.
     */
    // #939
    private Runnable onUnmapped;

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
     *            the uncompressed size of a deflated zip entry, or -1 if unknown, or 0 if this is not a deflated
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
        // its own: below JDK 22 the toplevel slice unmaps the file by freeing its address range, so a sub slice
        // that kept reading through a duplicate of the mapping would be reading memory that is no longer there.
        // The mapping always covers the whole file, and is addressed in whole-file coordinates by way of
        // sliceStartPos, in a sub slice as much as in the toplevel slice.
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
     *            the uncompressed size of a deflated zip entry, or -1 if unknown, or 0 if this is not a deflated
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
            // are no arenas, so the file is mapped with FileChannel#map, and unmapped by Unsafe::invokeCleaner
            // once nothing that the caller could still read is a view of it (see mapFile()).
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
                    // Try running garbage collection then try mapping the file again. (The collection has to
                    // be waited for, since a mapping is released after the collection itself is over.)
                    FileUtils.freeUnreachableBuffers();
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
     * be being read when the {@link io.github.classgraph.ScanResult} that owns it is closed. On JDK 22 and later
     * it is released by closing the arena the file was mapped in, which makes a thread that is still reading it
     * throw a recoverable {@link IllegalStateException}. Below JDK 22 there are no arenas, and the only method
     * that can unmap a file, {@code Unsafe::invokeCleaner}, frees the address range whether or not anything is
     * still reading it, so the file is unmapped there only once every view of the mapping that the caller could
     * still read has been released -- see {@link #close()}.
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
     *            the uncompressed size of a deflated zip entry, or -1 if unknown, or 0 if this is not a deflated
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
            // duplicate of the mapping for as long as it is alive, and readers are not closed, so it is given
            // the toplevel slice's closed flag to check before each read: reading a file that has been unmapped
            // is not merely wrong, it reads memory that is no longer there
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
     * Take a view of the memory mapping of this file, so that the file is not unmapped while the caller can still
     * read the buffer it was handed by {@link #read()}.
     *
     * @return the action that releases the view, which the caller must run once it can no longer read the buffer.
     * @throws IOException
     *             if the file has already been unmapped, so that there is nothing left to read.
     */
    // #939
    @Override
    public Runnable acquireMappingView() throws IOException {
        final FileSlice topLevelSlice = topLevelFileSlice;
        if (topLevelSlice.backingByteBuffer == null) {
            // The file is not memory-mapped, so there is no mapping that a view could hold open
            return super.acquireMappingView();
        }
        if (!topLevelSlice.acquireView()) {
            throw new IOException("Cannot read " + file + " after the ScanResult has been closed");
        }
        return new Runnable() {
            @Override
            public void run() {
                topLevelSlice.releaseView();
            }
        };
    }

    /**
     * Take a view of the memory mapping of this file, so that it is not unmapped while the view is open. Called
     * only on the toplevel file slice, which owns the mapping.
     *
     * @return true if a view was taken, or false if the mapping has already been released, in which case there is
     *         nothing left to read and nothing to release.
     */
    // #939
    private boolean acquireView() {
        // Increment first and read mappingReleased afterwards, while close() sets that flag first and reads the
        // count afterwards, so that of two threads racing here at least one sees what the other did: either this
        // one sees the mapping released and takes no view, or the close sees the view and leaves the file
        // mapped. What cannot happen is the unsafe outcome, where the file is unmapped while this caller can
        // still read it.
        openViews.incrementAndGet();
        if (mappingReleased) {
            releaseView();
            return false;
        }
        return true;
    }

    /** Release a view taken by {@link #acquireView()}, unmapping the file if it was the last one. */
    // #939
    private void releaseView() {
        if (openViews.decrementAndGet() == 0 && mappingReleased && unmapIfNoViewIsOpen()) {
            // The toplevel slice closed while this view was open, so releasing it is the last chance to unmap
            // the file -- nothing else will look at this mapping again
            runOnUnmapped();
        }
    }

    /**
     * Run an action once this file has been unmapped, or immediately if it has been unmapped already. Used to
     * delete a temporary file whose delete had to wait for the file to be unmapped: Windows refuses to delete a
     * file that is still mapped, and a file that a view of its mapping holds open is not unmapped until that view
     * is released, which can be after the scan has closed.
     *
     * @param action
     *            the action to run.
     */
    // #939
    public void runWhenUnmapped(final Runnable action) {
        final boolean fileIsUnmapped;
        synchronized (this) {
            fileIsUnmapped = unmapped;
            if (!fileIsUnmapped) {
                onUnmapped = action;
            }
        }
        // Run the action outside the lock, since it touches the filesystem
        if (fileIsUnmapped) {
            action.run();
        }
    }

    /** Run the action registered by {@link #runWhenUnmapped(Runnable)}, if there is one. */
    // #939
    private void runOnUnmapped() {
        final Runnable action;
        synchronized (this) {
            action = onUnmapped;
            onUnmapped = null;
        }
        if (action != null) {
            action.run();
        }
    }

    /**
     * Unmap the file, if the toplevel slice has closed and no view of the mapping is open, and drop the reference
     * to the mapped buffer either way.
     *
     * @return true if the file has been unmapped by the time this returns.
     */
    // #939
    private synchronized boolean unmapIfNoViewIsOpen() {
        if (openViews.get() != 0) {
            // A view of the mapping is still open, so releasing it is what will unmap the file
            return false;
        }
        final ByteBuffer mappedByteBuffer = backingByteBuffer;
        if (!unmapped && mappedByteBuffer != null) {
            // Synchronized, so that two threads racing here cannot both unmap the file
            unmapped = FileUtils.closeDirectByteBuffer(mappedByteBuffer, nestedJarHandler.reflectionUtils,
                    /* log = */ null);
        }
        // Drop the reference to the mapped buffer: if the file could not be unmapped here, that is what lets the
        // garbage collector find the mapping unreachable and unmap it instead
        backingByteBuffer = null;
        return unmapped;
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

    /**
     * Close the slice, releasing any memory mapping of the file.
     *
     * <p>
     * On JDK 22 and later this unmaps the file by closing the arena that mapped it, and does so even if another
     * thread is still reading it: that read throws {@link IllegalStateException}, which the readers translate into
     * {@link IOException}. An arena that will not close leaves the file mapped for the rest of the life of the
     * JVM, so that too is left to the garbage collector to do what it can with.
     *
     * <p>
     * Below JDK 22 there is no arena, and the only method that can unmap a file, {@code Unsafe::invokeCleaner},
     * frees the address range whether or not anything is still reading it -- a thread that reads one byte
     * afterwards takes a SIGSEGV that kills the JVM. So the file is unmapped here only if no view of the mapping
     * is open; if one is, the last {@link #releaseView()} unmaps it instead, and if it cannot be unmapped at all,
     * it is left to the garbage collector. A read that is already in flight on another thread when the slice is
     * closed is not covered by either: every reader checks that the file is still mapped before it reads, but a
     * close that lands between that check and the read itself can still pull the memory out from under it.
     * Closing a {@link io.github.classgraph.ScanResult} while another thread is reading through it is a
     * use-after-close either way, and is documented as one.
     */
    @Override
    public void close() {
        if (!isClosed.getAndSet(true)) {
            // Only the toplevel file slice owns the mapping and the file handle, so that they are only released
            // once (a sub slice reads the mapping through the toplevel slice, and just copies the reference to
            // the toplevel slice's RandomAccessFile)
            if (topLevelFileSlice == this) {
                // Set this before reading the view count, so that acquireView() cannot take a view of a mapping
                // that is being released
                // #939
                mappingReleased = true;
                if (arena != null) {
                    // On JDK 22+ the file is unmapped by closing the arena it was mapped in, which unmaps it
                    // the moment this slice is closed (see mapFile())
                    synchronized (this) {
                        unmapped = true;
                    }
                    if (!FileUtils.closeArena(arena, nestedJarHandler.reflectionUtils, /* log = */ null)) {
                        // The arena would not close, so the file is still mapped -- ask for a collection as the
                        // scan closes, in case something the collector can reach is what is holding it
                        // #939
                        nestedJarHandler.markFileAsAwaitingUnmapping(this);
                    }
                    arena = null;
                    backingByteBuffer = null;
                } else if (backingByteBuffer != null && !unmapIfNoViewIsOpen()) {
                    // The file could not be unmapped here, so it is left to the garbage collector, which closing
                    // the scan asks for
                    // #939
                    nestedJarHandler.markFileAsAwaitingUnmapping(this);
                }
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
