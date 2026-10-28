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
import java.io.InterruptedIOException;
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

    /** The file channel of {@link #raf} on the top-level file slice, or null once closed. */
    private volatile FileChannel fileChannel;

    /** Lock guarding {@link #raf} and {@link #fileChannel} on the top-level slice. */
    private final Object fileChannelLock;

    /** The mapped byte buffer on the top-level file slice if memory-mapped, or null. */
    private volatile ByteBuffer backingByteBuffer;

    /** The {@code java.lang.foreign.Arena} (JDK 22+) used to memory-map the file on the top-level slice, if any. */
    private Object arena;

    /** The top-level file slice that owns the file handle and memory mapping. */
    private final FileSlice topLevelFileSlice;

    /** True if {@link #close} has been called. */
    private final AtomicBoolean isClosed = new AtomicBoolean();

    /** Count of active caller-held views of the memory mapping on the top-level file slice. */
    // #939
    private final AtomicInteger openViews = new AtomicInteger();

    /** True once the top-level slice has been closed. */
    // #939
    private volatile boolean mappingReleased;

    /** True once the file has been unmapped (guarded by {@code this}). */
    // #939
    private boolean unmapped;

    /** Action to run once the file has been unmapped, or null (guarded by {@code this}). */
    // #939
    private Runnable onUnmapped;

    /**
     * Widen a {@link ByteBuffer} reference to {@link Buffer} to avoid {@link NoSuchMethodError} across JDK 8 and
     * JDK 9+ (where buffer position/limit methods received covariant return types).
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
        this.fileLength = parentSlice.fileLength;
        this.topLevelFileSlice = parentSlice.topLevelFileSlice;
        this.fileChannelLock = parentSlice.fileChannelLock;

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
        this.fileChannelLock = new Object();

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
            } else if (!FileUtils.canCloseDirectByteBuffer(nestedJarHandler.reflectionUtils)) {
                // Without the cleaner method, the mapping would stay in place until the ByteBuffer is garbage
                // collected, and on Windows the file stays locked until then -- read the file through the
                // RandomAccessFile API instead
                if (log != null) {
                    log.log("File " + file + " is not memory mapped, since it could not be unmapped again "
                            + "(using RandomAccessFile API instead)");
                }
                canMapFile = false;
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
     * Memory-map the file to a {@link ByteBuffer}.
     *
     * @return the mapped byte buffer.
     * @throws IOException
     *             if an I/O exception occurred while mapping the file.
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
     * Get the {@link FileChannel} for reading the file, reopening it if it was closed by an interrupted thread.
     *
     * @return the {@link FileChannel}
     * @throws InterruptedIOException
     *             if this thread has been interrupted
     * @throws IOException
     *             if {@link #close()} has been called, or if the channel could not be reopened
     */
    private FileChannel fileChannel() throws IOException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException("Interrupted while reading " + file);
        }
        // Read the field into a local, so that a close running concurrently cannot null it between the check and
        // the use
        final FileChannel channel = fileChannel;
        if (channel != null && channel.isOpen()) {
            return channel;
        }
        synchronized (fileChannelLock) {
            final FileChannel currentChannel = fileChannel;
            if (currentChannel == null) {
                throw new IOException("Cannot read " + file + " after it has been closed");
            }
            if (currentChannel.isOpen()) {
                // Another thread reopened the channel first
                return currentChannel;
            }
            final RandomAccessFile reopenedRaf = new RandomAccessFile(file, "r");
            try {
                if (reopenedRaf.length() != fileLength) {
                    // The offsets that were read from the file when it was first opened would not be valid for
                    // the file that is there now
                    throw new IOException(file + " changed after it was opened");
                }
            } catch (final IOException e) {
                try {
                    reopenedRaf.close();
                } catch (final IOException e2) {
                    e.addSuppressed(e2);
                }
                throw e;
            }
            raf = reopenedRaf;
            fileChannel = reopenedRaf.getChannel();
            return fileChannel;
        }
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
            // If file was not mmap'd, return a RandomAccessReader that uses the FileChannel. The reader gets the
            // channel from the toplevel slice before every read, rather than keeping the channel it was made with,
            // since the toplevel slice replaces a channel that was closed by an interrupted thread
            final FileSlice topLevelSlice = topLevelFileSlice;
            return new RandomAccessFileChannelReader(new RandomAccessFileChannelReader.FileChannelSource() {
                @Override
                public FileChannel fileChannel() throws IOException {
                    return topLevelSlice.fileChannel();
                }
            }, sliceStartPos, sliceLength);
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
     * Acquire a view of the memory mapping so the file is not unmapped while the caller reads the buffer returned
     * by {@link #read()}.
     *
     * @return an action that releases the view.
     * @throws IOException
     *             if the file has already been unmapped.
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
     * Acquire a view of the memory mapping on the top-level file slice.
     *
     * @return true if a view was acquired, or false if the mapping has already been released.
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
     * Run an action once this file has been unmapped, or immediately if already unmapped.
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

    /** Close the slice, releasing the file handle and any memory mapping on the top-level slice. */
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
                    // as this slice is closed, once any access to it in progress in another thread has finished
                    // (see mapFile())
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
                // Take the file handle under the lock that a reopen of it holds, so that a reopen cannot replace it
                // after it has been taken
                final RandomAccessFile rafToClose;
                synchronized (fileChannelLock) {
                    rafToClose = raf;
                    raf = null;
                    fileChannel = null;
                }
                if (rafToClose != null) {
                    try {
                        // Closing raf will also close the associated FileChannel
                        rafToClose.close();
                    } catch (final IOException e) {
                        // Ignore
                    }
                }
            }
            raf = null;
            nestedJarHandler.markSliceAsClosed(this);
        }
    }
}
