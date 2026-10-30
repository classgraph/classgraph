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
import java.io.InterruptedIOException;
import java.io.RandomAccessFile;
import java.lang.ref.PhantomReference;
import java.lang.ref.ReferenceQueue;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

import org.jspecify.annotations.Nullable;

import io.github.classgraph.base.LogNode;
import io.github.classgraph.base.internal.path.FileUtils;
import io.github.classgraph.base.internal.utils.VersionFinder;
import io.github.classgraph.base.internal.utils.VersionFinder.OperatingSystem;
import io.github.classgraph.vfs.Vfs;
import io.github.classgraph.vfs.internal.TempFile;
import io.github.classgraph.vfs.reader.RandomAccessByteBufferReader;
import io.github.classgraph.vfs.reader.RandomAccessFileChannelReader;
import io.github.classgraph.vfs.reader.RandomAccessReader;

/** A {@link Path} slice. */
public final class PathSlice extends Slice {
    /**
     * Maximum time in milliseconds that {@link #freeUnreachableBuffers()} waits for reference processing after GC.
     */
    private static final int REFERENCE_PROCESSING_TIMEOUT_MILLIS = 100;

    /**
     * Reads from a memory-mapped file of at least this many bytes go through a {@link FileChannel} instead of the
     * mapping. A read through the mapping costs a page fault for each page it touches the first time, and a read
     * through a file channel costs a system call, so the mapping is faster for small reads and the file channel for
     * large ones. Measured on JDK 26 by mapping a jarfile and reading each of its 32 MB of entries of a single size
     * once through {@link RandomAccessByteBufferReader}, on 1, 2 and 4 threads, the file channel overtakes the
     * mapping at about 16 KB (4 threads) to 64 KB (1 thread) on Linux x86-64, 32 KB to 128 KB on Linux aarch64, 32
     * KB to 64 KB on macOS aarch64, and at 16 KB or less on macOS x86-64, where it is 1.2 to 2.8 times as fast from
     * 16 KB up. On Windows, reading through a channel for each thread (see {@code CHANNEL_PER_READ}), it overtakes
     * the mapping at about 32 KB on 1 and 2 threads and 64 KB on 4. One channel shared by 4 threads overtakes it
     * only at about 256 KB, since Windows serializes the positional reads of a file channel.
     */
    static final int MIN_CHANNEL_READ_SIZE = switch (VersionFinder.OS) {
    case Linux -> 32 * 1024;
    case MacOSX -> VersionFinder.IS_X86_64 ? 16 * 1024 : 64 * 1024;
    default -> 64 * 1024;
    };

    /**
     * Whether a jarfile is memory-mapped. It always is on Windows, and on Linux and macOS from JDK 25. Below JDK 22
     * a mapping is released by {@code Unsafe::invokeCleaner}, which frees the address range whether or not another
     * thread is still reading it, and a file can be truncated under its mapping on Linux and macOS but not on
     * Windows, so off Windows the mapping is used only on the JDKs that ClassGraph 6 requires, where it has been
     * measured and tested.
     */
    static final boolean MAP_FILES = VersionFinder.OS == OperatingSystem.Windows
            || Runtime.version().feature() >= 25;

    /**
     * Whether {@link #read()} returns a view of the memory mapping rather than a copy. A file that is mapped cannot
     * be truncated on Windows, but it can be on Linux and macOS, and a read of a mapping beyond the new end of the
     * file throws an {@link InternalError}. The readers of the mapping turn that error into an {@link IOException},
     * but a view handed to the caller is read outside them, so off Windows the slice is copied instead.
     */
    private static final boolean READ_RETURNS_MAPPING = VersionFinder.OS == OperatingSystem.Windows;

    /**
     * Whether large reads from a memory-mapped file each take a file channel of their own from
     * {@link #idleChannels}, rather than all sharing {@link #fileChannel}. On Windows, a {@link FileChannel} lets
     * only one positional read run at a time: the JDK reads through a handle opened for synchronous I/O, where a
     * positional read moves the file pointer, so it holds the channel's position lock while it saves the pointer,
     * reads and restores the pointer. Threads sharing a channel therefore wait for each other. On Linux and macOS
     * positional reads of the same channel run in parallel, so the one channel is shared, and no more file
     * descriptors are used than one per file.
     *
     * <p>
     * TODO: this works around the JDK's Windows implementation of positional reads (in
     * {@code sun.nio.ch.FileDispatcherImpl}, {@code needsPositionLock()} returns true). When a JDK lets positional
     * reads of one channel run in parallel on Windows, share the one channel there too on that JDK and later.
     */
    private static final boolean CHANNEL_PER_READ = VersionFinder.OS == OperatingSystem.Windows;

    /**
     * The {@link Path} of the file, or null if opened from a {@link File} whose path cannot be represented as a
     * {@link Path} on the default filesystem.
     */
    private final @Nullable Path path;

    /** The {@link File} of the file, or null if opened from a {@link Path}. */
    private final @Nullable File file;

    /** The file path string, for log and exception messages. */
    private final String pathStr;

    /** The file length. */
    private final long fileLength;

    /**
     * The {@link FileChannel} opened on the file, or null on sub-slices or once closed. Owned by the toplevel slice
     * and reopened if closed by thread interruption during a read.
     */
    private volatile @Nullable FileChannel fileChannel;

    /** Lock synchronizing reopening and closing of {@link #fileChannel} on the toplevel slice. */
    private final Object fileChannelLock;

    /** The toplevel file slice that owns {@link #fileChannel} and {@link #fileMapping} (or {@code this}). */
    private final PathSlice topLevelPathSlice;

    /** The memory mapping of the file (only set on the toplevel slice), or null if unmapped or closed. */
    private volatile @Nullable FileMapping fileMapping;

    /** The mapped {@link ByteBuffer} (only set on the toplevel slice), or null if unmapped or closed. */
    private volatile @Nullable ByteBuffer backingByteBuffer;

    /**
     * The file channels of the toplevel slice that are not in use by a read, if {@link #CHANNEL_PER_READ} is true.
     * A large read from the mapping takes one, or opens one if there is none, and puts it back afterward, so there
     * are never more of them than the number of reads that have run at the same time.
     */
    private final ConcurrentLinkedQueue<FileChannel> idleChannels;

    /** True if {@link #close} has been called. */
    private final AtomicBoolean isClosed = new AtomicBoolean();

    /**
     * The temporary file owned by this toplevel slice and deleted on {@link #close()}, or null if this slice does
     * not own a temporary file.
     */
    private final @Nullable File tempFile;

    /** The log node for reporting temporary file deletion failures, or null to skip logging. */
    private final @Nullable LogNode tempFileLog;

    /**
     * Return the open {@link FileChannel} on the toplevel slice, reopening it if a previous read on an interrupted
     * thread closed it.
     *
     * @return the open {@link FileChannel}
     * @throws InterruptedIOException
     *             if the current thread is interrupted
     * @throws IOException
     *             if this slice is closed or the channel could not be reopened
     */
    private FileChannel fileChannel() throws IOException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException("Interrupted while reading " + pathStr);
        }
        // Read the field into a local, so that a close running concurrently cannot null it between the check and
        // the use
        final var channel = fileChannel;
        if (channel != null && channel.isOpen()) {
            return channel;
        }
        synchronized (fileChannelLock) {
            final var currentChannel = fileChannel;
            if (currentChannel == null) {
                throw new IOException("Cannot read " + pathStr + " after it has been closed");
            }
            if (currentChannel.isOpen()) {
                // Another thread reopened the channel first
                return currentChannel;
            }
            final var reopened = openAnotherChannel();
            fileChannel = reopened;
            return reopened;
        }
    }

    /**
     * Open another {@link FileChannel} on the file of the toplevel slice.
     *
     * @return the channel.
     * @throws IOException
     *             if the file could not be opened, or if its length is not what it was when the slice was opened.
     */
    private FileChannel openAnotherChannel() throws IOException {
        final var opened = openChannel(path, file, /* checkAccess = */ false);
        if (opened.fileLength() != fileLength) {
            // The offsets that were read from the file when it was first opened would not be valid for the file
            // that is there now
            final var e = new IOException(pathStr + " changed after it was opened");
            try {
                opened.channel().close();
            } catch (final IOException e2) {
                e.addSuppressed(e2);
            }
            throw e;
        }
        return opened.channel();
    }

    /**
     * Read a range of the memory-mapped file of the toplevel slice through a {@link FileChannel} rather than
     * through the mapping, as {@link RandomAccessReader#read(long, ByteBuffer, int, int)} reads it.
     *
     * @param sliceStartPos
     *            the start of the slice being read, within the file.
     * @param sliceLength
     *            the length of the slice being read.
     * @param srcOffset
     *            the offset within the slice to read from.
     * @param dstBuf
     *            the buffer to read into.
     * @param dstBufStart
     *            the index in the buffer to read into.
     * @param numBytes
     *            the number of bytes to read.
     * @return the number of bytes read, or -1 if {@code srcOffset} is at or past the end of the slice.
     * @throws IOException
     *             if this slice has been closed, the thread has been interrupted, or the file could not be read.
     */
    int readThroughChannel(final long sliceStartPos, final long sliceLength, final long srcOffset,
            final ByteBuffer dstBuf, final int dstBufStart, final int numBytes) throws IOException {
        if (!CHANNEL_PER_READ) {
            return new RandomAccessFileChannelReader(this::fileChannel, sliceStartPos, sliceLength).read(srcOffset,
                    dstBuf, dstBufStart, numBytes);
        }
        // A read of a FileChannel by an interrupted thread closes the channel, so check first, as fileChannel()
        // does, rather than lose the channel
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException("Interrupted while reading " + pathStr);
        }
        if (isClosed.get()) {
            throw new IOException("Cannot read " + pathStr + " after it has been closed");
        }
        final var idleChannel = idleChannels.poll();
        final var channel = idleChannel != null ? idleChannel : openAnotherChannel();
        try {
            // Nothing but this read uses the channel until it is put back, so nothing else can close it, except an
            // interrupt of this thread, which ends the read
            return new RandomAccessFileChannelReader(channel, sliceStartPos, sliceLength).read(srcOffset, dstBuf,
                    dstBufStart, numBytes);
        } finally {
            if (channel.isOpen()) {
                idleChannels.offer(channel);
                // A close that ran during the read did not see this channel among the idle ones, so close it here.
                // A close that runs from here on does see it.
                if (isClosed.get()) {
                    closeIdleChannels();
                }
            }
        }
    }

    /** Close the file channels in {@link #idleChannels}. */
    private void closeIdleChannels() {
        FileChannel channel;
        while ((channel = idleChannels.poll()) != null) {
            try {
                channel.close();
            } catch (final IOException e) {
                // Ignore
            }
        }
    }

    /**
     * Get the {@link File} that this slice reads.
     *
     * @return the {@link File}, or null if backed by a {@link Path} whose filesystem does not support
     *         {@link Path#toFile()}.
     */
    public @Nullable File getFile() {
        if (file != null) {
            return file;
        }
        try {
            return path == null ? null : path.toFile();
        } catch (final UnsupportedOperationException e) {
            // The filesystem supports the Path API but not the File API
            return null;
        }
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
     * @param vfs
     *            the {@link Vfs} that opened this slice
     */
    private PathSlice(final PathSlice parentSlice, final long offset, final long length, final Vfs vfs) {
        super(parentSlice, offset, length, vfs);

        this.path = parentSlice.path;
        this.file = parentSlice.file;
        this.pathStr = parentSlice.pathStr;
        this.fileLength = parentSlice.fileLength;
        this.topLevelPathSlice = parentSlice.topLevelPathSlice;
        this.fileChannelLock = parentSlice.fileChannelLock;
        this.idleChannels = parentSlice.idleChannels;
        // Only the toplevel slice owns the temporary file, if the file it reads is one, so a sub slice has nothing
        // to delete
        this.tempFile = null;
        this.tempFileLog = null;

        // A sub slice reads through the toplevel slice's file channel and memory mapping rather than keeping
        // copies of its own, so that closing the toplevel slice releases both of them for every slice of the file
        // at once. A copy of the mapped buffer would matter most: below JDK 22 the toplevel slice unmaps the file
        // by freeing its address range, so a sub slice that kept reading through a copy of the mapping would be
        // reading memory that is no longer there. The mapping always covers the whole file, and is addressed in
        // whole-file coordinates by way of sliceStartPos, in a sub slice as much as in the toplevel slice. A sub
        // slice therefore holds nothing of its own to release.
    }

    /**
     * Constructor for a toplevel file slice. At least one of {@code path} and {@code file} must be non-null.
     *
     * @param path
     *            the {@link Path}, or null if only reachable via {@code file}
     * @param file
     *            the {@link File}, or null if opened from {@code path}
     * @param pathStr
     *            the file path string for log and exception messages
     * @param vfs
     *            the {@link Vfs} that opened this slice
     * @param checkAccess
     *            if true, verify that the path is a readable regular file before opening it
     * @param memoryMapWholeFile
     *            if true, memory-map the file on platforms where memory mapping is enabled
     * @param isTempFile
     *            if true, delete {@code file} when this slice is closed
     * @param log
     *            the log node, or null to skip logging
     * @throws IOException
     *             if the file cannot be opened
     */
    private PathSlice(final @Nullable Path path, final @Nullable File file, final String pathStr, final Vfs vfs,
            final boolean checkAccess, final boolean memoryMapWholeFile, final boolean isTempFile,
            final @Nullable LogNode log) throws IOException {
        this(openChannel(path, file, checkAccess), path, file, pathStr, vfs, memoryMapWholeFile, isTempFile, log);
    }

    /**
     * A file channel that has just been opened, and the length of the file it was opened on.
     *
     * @param channel
     *            the file channel
     * @param fileLength
     *            the length of the file
     */
    private record OpenedChannel(FileChannel channel, long fileLength) {
    }

    /**
     * Open a {@link FileChannel} for a toplevel file slice and read its length.
     *
     * @param path
     *            the {@link Path}, or null if only reachable via {@code file}
     * @param file
     *            the {@link File}, or null if opened from {@code path}
     * @param checkAccess
     *            if true, verify that the path is a readable regular file before opening it
     * @return the opened channel and its file length
     * @throws IOException
     *             if the file cannot be opened or its length cannot be read
     */
    private static OpenedChannel openChannel(final @Nullable Path path, final @Nullable File file,
            final boolean checkAccess) throws IOException {
        final FileChannel channel;
        if (path != null) {
            if (checkAccess) {
                // Make sure the file is readable and is a regular file
                FileUtils.checkCanReadAndIsFile(path);
            }
            channel = FileChannel.open(path, StandardOpenOption.READ);
        } else {
            // The file's path cannot be represented as a Path on the default filesystem, so the channel has to be
            // opened through the File API instead. (Closing the channel closes the RandomAccessFile with it, as
            // RandomAccessFile#getChannel specifies, so close() does not have to hold on to it. This channel is
            // opened without FILE_SHARE_DELETE on Windows, unlike the channel that FileChannel#open returns, so
            // while it is open the file cannot be deleted there.)
            final var fileToOpen = Objects.requireNonNull(file);
            if (checkAccess) {
                // Make sure the file is readable and is a regular file
                FileUtils.checkCanReadAndIsFile(fileToOpen);
            }
            channel = new RandomAccessFile(fileToOpen, "r").getChannel();
        }
        try {
            return new OpenedChannel(channel, channel.size());
        } catch (final IOException | RuntimeException | Error e) {
            try {
                channel.close();
            } catch (final IOException e2) {
                e.addSuppressed(e2);
            }
            throw e;
        }
    }

    /**
     * Constructor for toplevel file slice, given the file channel opened on the file.
     *
     * @param openedChannel
     *            the file channel, which this slice takes ownership of, and the length of the file
     * @param path
     *            the path, or null if the file is only reachable through the {@link File} API
     * @param file
     *            the file, if this slice is opened from a {@link File}, or null if it is opened from a {@link Path}
     * @param pathStr
     *            the path of the file, as it was given, for use in log and exception messages
     * @param vfs
     *            the {@link Vfs} that opened this slice
     * @param memoryMapWholeFile
     *            if true, and files are memory-mapped on this platform, memory-map the whole file.
     * @param isTempFile
     *            if true, the file is a temporary file that this slice owns, and deletes when it is closed.
     * @param log
     *            the log node, or null to skip logging
     */
    private PathSlice(final OpenedChannel openedChannel, final @Nullable Path path, final @Nullable File file,
            final String pathStr, final Vfs vfs, final boolean memoryMapWholeFile, final boolean isTempFile,
            final @Nullable LogNode log) {
        super(openedChannel.fileLength(), vfs);

        this.path = path;
        this.file = file;
        this.pathStr = pathStr;
        this.fileLength = openedChannel.fileLength();
        this.tempFile = isTempFile ? file : null;
        this.tempFileLog = isTempFile ? log : null;
        // Set before anything below can fail, since it is what tells close() that this slice owns the file channel
        this.topLevelPathSlice = this;
        this.fileChannelLock = new Object();
        this.idleChannels = new ConcurrentLinkedQueue<>();
        final var fileChannelOpened = openedChannel.channel();
        this.fileChannel = fileChannelOpened;
        // Nothing but this constructor knows about the file channel yet, so if anything below throws, this is the
        // only place the channel can be released. A temporary file is not deleted here: until this constructor
        // returns, the caller that created the file still owns it, and deletes it when this throws
        try {
            if (memoryMapWholeFile && MAP_FILES) {
                // Memory-map the whole file, if it can be mapped -- otherwise fall through and read through the
                // FileChannel API instead. Small reads through the mapping are faster than through a file channel,
                // most of all when the file is not yet in the page cache, and large reads still go through a file
                // channel (see MIN_CHANNEL_READ_SIZE). (The measurements are at
                // https://github.com/classgraph/classgraph/wiki/Memory-Mapping-Benchmark .)
                final var mapping = FileMapping.map(fileChannelOpened, fileLength, pathStr, log);
                fileMapping = mapping;
                backingByteBuffer = mapping == null ? null : mapping.byteBuffer;
            }
        } catch (final RuntimeException | Error e) {
            // The mapping was not made, since nothing after FileMapping#map can throw, so there is only the
            // channel to release
            isClosed.set(true);
            fileChannel = null;
            try {
                fileChannelOpened.close();
            } catch (final IOException e2) {
                e.addSuppressed(e2);
            }
            throw e;
        }
    }

    /**
     * Open a non-memory-mapped toplevel slice for a directory entry.
     *
     * @param path
     *            the path of the file
     * @param vfs
     *            the {@link Vfs} that opened this slice
     * @return the slice over the file, which the caller owns and must close
     * @throws IOException
     *             if the file cannot be opened
     */
    public static PathSlice forDirEntry(final Path path, final Vfs vfs) throws IOException {
        return new PathSlice(path, /* file = */ null, path.toString(), vfs, /* checkAccess = */ false,
                /* memoryMapWholeFile = */ false, /* isTempFile = */ false, /* log = */ null);
    }

    /**
     * Convert a {@link File} to a {@link Path}, returning null if {@link File#toPath()} throws
     * {@link InvalidPathException}.
     *
     * @param file
     *            the file
     * @return the {@link Path}, or null if the path is rejected by {@link File#toPath()}
     */
    private static @Nullable Path pathOrNull(final File file) {
        try {
            return file.toPath();
        } catch (final InvalidPathException e) {
            return null;
        }
    }

    /**
     * Constructor for a toplevel slice of a whole zipfile named by a {@link File} on the default filesystem.
     *
     * @param file
     *            the file
     * @param vfs
     *            the {@link Vfs} that opened this slice
     * @param log
     *            the log node, or null to skip logging
     * @throws IOException
     *             if the file cannot be opened.
     */
    public PathSlice(final File file, final Vfs vfs, final @Nullable LogNode log) throws IOException {
        this(pathOrNull(file), file, file.toString(), vfs, /* checkAccess = */ true,
                /* memoryMapWholeFile = */ true, /* isTempFile = */ false, log);
    }

    /**
     * Open a toplevel slice over a temporary file. The returned slice takes ownership of {@code tempFile} and
     * deletes it when closed.
     *
     * @param tempFile
     *            the temporary file created by {@link TempFile#create(String)}
     * @param vfs
     *            the {@link Vfs} that opened this slice
     * @param log
     *            the log node, or null to skip logging
     * @return the slice over the temporary file
     * @throws IOException
     *             if the file cannot be opened (in which case the caller retains ownership of {@code tempFile})
     */
    public static PathSlice forTempFile(final File tempFile, final Vfs vfs, final @Nullable LogNode log)
            throws IOException {
        return new PathSlice(pathOrNull(tempFile), tempFile, tempFile.toString(), vfs, /* checkAccess = */ true,
                /* memoryMapWholeFile = */ true, /* isTempFile = */ true, log);
    }

    /**
     * Constructor for a toplevel slice of a whole zipfile.
     *
     * @param path
     *            the path
     * @param vfs
     *            the {@link Vfs} that opened this slice
     * @param log
     *            the log node, or null to skip logging
     * @throws IOException
     *             if the file cannot be opened.
     */
    public PathSlice(final Path path, final Vfs vfs, final @Nullable LogNode log) throws IOException {
        this(path, /* file = */ null, path.toString(), vfs, /* checkAccess = */ true,
                /* memoryMapWholeFile = */ true, /* isTempFile = */ false, log);
    }

    /**
     * Slice the file.
     *
     * @param offset
     *            the offset of the sub-slice within the parent slice
     * @param length
     *            the length of the sub-slice
     * @return the slice
     */
    @Override
    public Slice slice(final long offset, final long length) {
        return new PathSlice(this, offset, length, vfs);
    }

    /**
     * Create a new {@link RandomAccessReader} for this slice, reading from the memory mapping if present (except
     * for large reads, which go through a {@link FileChannel}), or from the {@link FileChannel} otherwise.
     *
     * @return the random access reader
     * @throws IOException
     *             if this slice has been closed
     */
    @Override
    public RandomAccessReader randomAccessReader() throws IOException {
        // Read the field into a local, so that a close running concurrently cannot null it between the check and
        // the use
        final var mappedByteBuffer = topLevelPathSlice.backingByteBuffer;
        if (mappedByteBuffer == null) {
            // If file was not mmap'd, return a RandomAccessReader that uses the FileChannel. The reader gets the
            // channel from the toplevel slice before every read, rather than keeping the channel it was made with,
            // since the toplevel slice replaces a channel that was closed by an interrupted thread
            if (topLevelPathSlice.isClosed.get()) {
                throw new IOException("Cannot read " + pathStr + " after it has been closed");
            }
            return new RandomAccessFileChannelReader(topLevelPathSlice::fileChannel, sliceStartPos, sliceLength);
        } else {
            // If file was mmap'd, return a RandomAccessReader that uses the ByteBuffer. The reader keeps a view
            // of the mapping for as long as it is alive, and readers are not closed, so it is given the toplevel
            // slice's closed flag to check before each read: reading a file that has been unmapped is not merely
            // wrong, it reads memory that is no longer there
            return new MappedFileReader(
                    new RandomAccessByteBufferReader(mappedByteBuffer, sliceStartPos, sliceLength,
                            ByteOrder.LITTLE_ENDIAN, topLevelPathSlice.isClosed::get),
                    topLevelPathSlice, sliceStartPos, sliceLength);
        }
    }

    /**
     * Load the slice as a byte array.
     *
     * @return the content of the slice
     * @throws IOException
     *             if the slice could not be read.
     */
    @Override
    public byte[] load() throws IOException {
        // Copy from either the memory mapping or the FileChannel to a byte array
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

    @Override
    public Runnable acquireMappingView() throws IOException {
        // Read the field into a local, so that a close running concurrently cannot null it between the check and
        // the use
        final var mapping = topLevelPathSlice.fileMapping;
        if (mapping == null) {
            // The file is not memory-mapped, so there is no mapping that a view could hold open
            return super.acquireMappingView();
        }
        if (!mapping.acquireView()) {
            throw new IOException("Cannot read " + pathStr + " after it has been closed");
        }
        return mapping::releaseView;
    }

    /**
     * Read the slice into a {@link ByteBuffer}. If the file is memory-mapped on Windows, the buffer is a read-only
     * view of the mapping, and nothing is copied (see {@link #READ_RETURNS_MAPPING}).
     *
     * @return the byte buffer
     * @throws IOException
     *             if the slice could not be read.
     */
    @Override
    public ByteBuffer read() throws IOException {
        // Read the field into a local, so that a close running concurrently cannot null it between the check and
        // the use
        final var mappedByteBuffer = topLevelPathSlice.backingByteBuffer;
        if (mappedByteBuffer == null || !READ_RETURNS_MAPPING) {
            // Copy the slice into a byte array, and wrap the array
            return super.read();
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
            if (topLevelPathSlice != this) {
                // Only the toplevel file slice owns the file channel and the memory mapping -- a sub slice reads
                // through the toplevel slice's, and has nothing of its own to release
                return;
            }
            // Take what has to be released, and drop the references to it, before releasing any of it: this slice
            // is already marked as closed, so a second call must not release the same resource twice. The file
            // channel is taken under the lock that a reopen of it holds, so that a reopen cannot replace it after
            // it has been taken
            final var mapping = fileMapping;
            fileMapping = null;
            backingByteBuffer = null;
            final FileChannel fileChannelToClose;
            synchronized (fileChannelLock) {
                fileChannelToClose = fileChannel;
                fileChannel = null;
            }
            FileMapping mappingStillHeldOpen = null;
            try {
                if (mapping != null && !mapping.unmap()) {
                    // The file is still mapped, because a view of the mapping that the caller can still read is
                    // open. Releasing that view is what unmaps the file, so a delete that the mapping is in the
                    // way of has to wait for it -- see deleteTempFile below
                    // #939
                    mappingStillHeldOpen = mapping;
                }
            } finally {
                try {
                    // The file channel is closed even if the file could not be unmapped -- this slice is already
                    // marked as closed, so nothing else would release it
                    try {
                        if (fileChannelToClose != null) {
                            fileChannelToClose.close();
                        }
                    } catch (final IOException e) {
                        // Ignore
                    }
                    // A channel that a read has taken is closed by the read when it puts the channel back
                    closeIdleChannels();
                } finally {
                    // The temporary file can only be deleted once the mapping and the file channel over it have
                    // been released, so it goes last
                    if (tempFile != null) {
                        deleteTempFile(tempFile, mappingStillHeldOpen);
                    }
                }
            }
        }
    }

    /**
     * Delete the temporary file owned by this slice after its mapping and file channel have been closed.
     *
     * @param fileToDelete
     *            the temporary file.
     * @param mappingStillHeldOpen
     *            the memory mapping of the file if an open view prevented unmapping on close, or null if unmapped.
     */
    private void deleteTempFile(final File fileToDelete, final @Nullable FileMapping mappingStillHeldOpen) {
        if (TempFile.delete(fileToDelete)) {
            return;
        }
        if (mappingStillHeldOpen != null) {
            // Some versions of Windows refuse to delete a file that is still memory-mapped, and this one is, because a
            // view of the mapping that the caller can still read is open. No garbage collection can unmap a file
            // whose buffer the caller is still holding, so rather than asking for one, the delete is retried when
            // the last view is released, which is what unmaps the file.
            // #939
            mappingStillHeldOpen
                    .runWhenUnmapped(() -> deleteTempFile(fileToDelete, /* mappingStillHeldOpen = */ null));
            return;
        }
        // Windows can refuse to delete a file that is still memory-mapped (Windows Server 2022 refuses whenever the
        // file is mapped, and Windows Server 2025 and Windows 11 refuse only when it was mapped through a
        // RandomAccessFile, which opens a file without letting it be deleted), so a delete that failed may be
        // waiting on a mapping that only the garbage collector can release -- one that Unsafe::invokeCleaner
        // failed to unmap below JDK 22, or one that something other than this slice made through FileChannel#map,
        // from the File that getFile() hands out. (The garbage collector cannot release a mapping whose arena
        // would not close: only closing the arena can.) The collector only runs when it chooses to, so ask for a
        // collection and try again. (If the JVM was started with -XX:+DisableExplicitGC then this is a no-op.) A
        // file that still cannot be deleted is scheduled for deletion when the JVM exits, which succeeds if
        // whatever kept it mapped has released it by then.
        // #939
        freeUnreachableBuffers();
        if (TempFile.delete(fileToDelete)) {
            return;
        }
        if (TempFile.deleteOnExit(fileToDelete)) {
            if (tempFileLog != null) {
                tempFileLog.log("Could not remove temporary file, so it will be removed when the JVM exits: "
                        + fileToDelete);
            }
        } else if (tempFileLog != null) {
            boolean fileStillExists;
            try {
                fileStillExists = fileToDelete.exists();
            } catch (final SecurityException e) {
                // A security manager does not allow the file to be read, so it cannot be known to be gone
                fileStillExists = true;
            }
            if (fileStillExists) {
                tempFileLog.log("Removing temporary file failed: " + fileToDelete);
            }
        }
    }

    /**
     * Request garbage collection and wait briefly for reference processing so unreachable cleaner-backed mapped
     * buffers can be released.
     */
    // #939, #331
    static void freeUnreachableBuffers() {
        // System.gc() returns once the collection itself is over, which is before the references that the
        // collection found have been processed. A phantom reference to an object that the same collection finds
        // unreachable is enqueued while those references are processed, so waiting for it to be enqueued waits for
        // most of that processing -- but the order within one batch of references is arbitrary, so this is a wait
        // that sometimes helps rather than a guarantee. (Measured by benchmark/MapProbe.java, over 300 rounds of
        // mapping eight files and dropping every reference to them, on JDK 8, 17, 21 and 26: a file was still
        // mapped when System.gc() returned in between 5 and all 300 of the rounds, depending on the JDK and the
        // machine, and waiting for reference processing first moved that in both directions. Two more collections
        // and 100ms cleared every straggler in every run but one, so the collector does get there -- just not by
        // the time the request to collect returns, which is when a caller waiting to delete or overwrite the file
        // needs it gone.)
        final var collected = new ReferenceQueue<>();
        final var canary = new PhantomReference<>(new Object(), collected);
        System.gc();
        try {
            // Bounded, so that a JVM that ignores the request to collect cannot make this wait forever
            collected.remove(REFERENCE_PROCESSING_TIMEOUT_MILLIS);
        } catch (final InterruptedException e) {
            // Leave the files to be unmapped by a later collection, and let the caller see the interruption
            Thread.currentThread().interrupt();
        }
        // Keep the canary reachable until it has been waited for -- a phantom reference that has itself become
        // unreachable is never enqueued
        canary.clear();
    }
}
