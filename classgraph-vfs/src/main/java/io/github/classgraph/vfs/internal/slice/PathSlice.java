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
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.classgraph.base.LogNode;
import io.github.classgraph.base.internal.path.FileUtils;
import io.github.classgraph.base.internal.utils.VersionFinder;
import io.github.classgraph.base.internal.utils.VersionFinder.OperatingSystem;
import io.github.classgraph.vfs.Vfs;
import io.github.classgraph.vfs.internal.TempFile;
import io.github.classgraph.vfs.reader.RandomAccessByteBufferReader;
import io.github.classgraph.vfs.reader.RandomAccessFileChannelReader;
import io.github.classgraph.vfs.reader.RandomAccessReader;
import org.jspecify.annotations.Nullable;

/** A {@link Path} slice. */
public final class PathSlice extends Slice {
    /**
     * The longest {@link #freeUnreachableBuffers()} waits for the garbage collector to process the references that
     * a collection found, in milliseconds.
     */
    private static final int REFERENCE_PROCESSING_TIMEOUT_MILLIS = 100;

    /**
     * The {@link Path} of the file, or null if this slice was opened from a {@link File} whose path cannot be
     * represented as a {@link Path} on the default filesystem.
     */
    private final @Nullable Path path;

    /** The {@link File} of the file, or null if this slice was opened from a {@link Path}. */
    private final @Nullable File file;

    /** The path of the file, as it was given, for use in log and exception messages. */
    private final String pathStr;

    /** The file length. */
    private final long fileLength;

    /**
     * The {@link FileChannel} opened on the {@link Path}, or null once closed. Only set on the toplevel file slice,
     * which owns the file channel. Volatile, since every slice of the file reads it, and it is replaced when a
     * thread that was interrupted while reading it closes it.
     */
    private volatile @Nullable FileChannel fileChannel;

    /**
     * The lock that the toplevel slice's {@link #fileChannel} is replaced and set to null under, which every slice
     * of the file shares.
     */
    private final Object fileChannelLock;

    /**
     * The toplevel file slice, which owns the file channel and the memory mapping, or {@code this} if this is the
     * toplevel slice.
     */
    private final PathSlice topLevelPathSlice;

    /**
     * The memory mapping of the file, if it was memory-mapped. Only set on the toplevel file slice, which owns the
     * mapping. Volatile, since every slice of the file reads it, but only the toplevel slice writes it.
     */
    private volatile @Nullable FileMapping fileMapping;

    /**
     * The mapped byte buffer, if the file was memory-mapped, or null once closed. Only set on the toplevel file
     * slice, which owns the mapping. Volatile, since every slice of the file reads it, but only the toplevel slice
     * writes it.
     */
    private volatile @Nullable ByteBuffer backingByteBuffer;

    /** True if {@link #close} has been called. */
    private final AtomicBoolean isClosed = new AtomicBoolean();

    /**
     * The temporary file that this slice reads and owns, or null if this slice reads a file that something else
     * owns. Only set on the toplevel slice of a nested jarfile that was extracted to a temporary file, which is
     * deleted by {@link #close()}, once the mapping and the file channel over it have been released.
     */
    private final @Nullable File tempFile;

    /**
     * The log node to report a temporary file that could not be deleted to, or null to skip logging. Only set
     * alongside {@link #tempFile}.
     */
    private final @Nullable LogNode tempFileLog;

    /**
     * Get the {@link FileChannel} opened on the {@link Path}, to read it. Called on the toplevel slice before every
     * read.
     *
     * <p>
     * A file channel is closed by a read on a thread that has been interrupted, or that is interrupted during the
     * read, and every slice of the file reads through the same channel. So that one interrupted thread cannot stop
     * every other thread from reading the file, a thread that has been interrupted is not allowed to read the
     * channel, and a channel that was closed by an interruption during a read is reopened.
     *
     * @return the {@link FileChannel}
     * @throws InterruptedIOException
     *             if this thread has been interrupted
     * @throws IOException
     *             if {@link #close()} has been called, or if the channel had to be reopened and could not be
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
            final var reopened = openChannel(path, file, /* checkAccess = */ false);
            if (reopened.fileLength() != fileLength) {
                // The offsets that were read from the file when it was first opened would not be valid for the
                // file that is there now
                final var e = new IOException(pathStr + " changed after it was opened");
                try {
                    reopened.channel().close();
                } catch (final IOException e2) {
                    e.addSuppressed(e2);
                }
                throw e;
            }
            fileChannel = reopened.channel();
            return reopened.channel();
        }
    }

    /**
     * Get the {@link File} that this slice reads.
     *
     * @return the {@link File} that this slice reads, or null if this slice reads a {@link Path} in a filesystem
     *         that has no {@link File} view of its files.
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
     * Constructor for toplevel file slice. At least one of {@code path} and {@code file} is non-null, and the file
     * is opened through {@code path} if it is non-null.
     *
     * @param path
     *            the path, or null if the file is only reachable through the {@link File} API
     * @param file
     *            the file, if this slice is opened from a {@link File}, or null if it is opened from a {@link Path}
     * @param pathStr
     *            the path of the file, as it was given, for use in log and exception messages
     * @param vfs
     *            the {@link Vfs} that opened this slice
     * @param checkAccess
     *            if true, check that the path is a readable regular file before opening it
     * @param memoryMapWholeFile
     *            if true, and files are memory-mapped on this platform, memory-map the whole file. Only pass true
     *            for a file that is read many times at random offsets, such as a zipfile -- for a file that is read
     *            once and then closed, mapping and unmapping the file costs more than reading it.
     * @param isTempFile
     *            if true, the file is a temporary file that this slice owns, and deletes when it is closed.
     * @param log
     *            the log node, or null to skip logging
     * @throws IOException
     *             if the file cannot be opened.
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
     * Open a file channel for a toplevel file slice, and read the length of the file, which the slice's length has
     * to be set to before anything else in the slice can be.
     *
     * @param path
     *            the path, or null if the file is only reachable through the {@link File} API
     * @param file
     *            the file, if the slice is opened from a {@link File}, or null if it is opened from a {@link Path}
     * @param checkAccess
     *            if true, check that the path is a readable regular file before opening it
     * @return the file channel, and the length of the file.
     * @throws IOException
     *             if the file cannot be opened, or its length cannot be read, in which case the channel is closed.
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
        final var fileChannelOpened = openedChannel.channel();
        this.fileChannel = fileChannelOpened;
        // Nothing but this constructor knows about the file channel yet, so if anything below throws, this is the
        // only place the channel can be released. A temporary file is not deleted here: until this constructor
        // returns, the caller that created the file still owns it, and deletes it when this throws
        try {
            if (memoryMapWholeFile && VersionFinder.OS == OperatingSystem.Windows) {
                // Memory-map the whole file, if it can be mapped -- otherwise fall through and read through the
                // FileChannel API instead. Mapping is measurably faster on Windows and is not on Linux or macOS,
                // where it can even be slower, so it is done on Windows only. (The measurements are at
                // https://github.com/classgraph/classgraph/wiki/Memory-Mapping-Benchmark .) Being read on Windows
                // only is also what makes it safe for a root to unmap a file as it closes: unmapping below JDK 22
                // frees the address range whether or not another thread is still reading it
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
     * Open a toplevel slice of a file in a directory, to read it once and then close it. The file is not checked
     * before it is opened, since the caller has already found it, and it is not memory-mapped, since mapping and
     * unmapping a file that is read once costs more than reading it.
     *
     * @param path
     *            the path of the file
     * @param vfs
     *            the {@link Vfs} that opened this slice
     * @return the slice over the file, which the caller owns and must close.
     * @throws IOException
     *             if the file cannot be opened.
     */
    public static PathSlice forDirEntry(final Path path, final Vfs vfs) throws IOException {
        return new PathSlice(path, /* file = */ null, path.toString(), vfs, /* checkAccess = */ false,
                /* memoryMapWholeFile = */ false, /* isTempFile = */ false, /* log = */ null);
    }

    /**
     * Get the {@link Path} for a {@link File} on the default filesystem.
     *
     * @param file
     *            the file
     * @return the {@link Path} for the file, or null if the file's path cannot be represented as a {@link Path}. On
     *         Windows a filename can contain characters that {@link Path} rejects -- the name of an NTFS alternate
     *         data stream, say -- and such a file can only be opened through the {@link File} API.
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
     * Open a toplevel slice of a temporary file that a nested jarfile was extracted to, which the returned slice
     * owns: closing the slice deletes the file. Nothing else may delete it, and nothing else may read it once the
     * slice is closed.
     *
     * @param tempFile
     *            the temporary file, as returned by {@link TempFile#create(String)}.
     * @param vfs
     *            the {@link Vfs} that opened this slice
     * @param log
     *            the log node, or null to skip logging
     * @return the slice over the temporary file.
     * @throws IOException
     *             if the file cannot be opened, in which case the temporary file is not deleted -- the caller that
     *             created it still owns it.
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
     * Create a new {@link RandomAccessReader} for this slice. The reader reads the memory mapping of the file if
     * the file is mapped, and otherwise reads the file channel.
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
            return new RandomAccessByteBufferReader(mappedByteBuffer, sliceStartPos, sliceLength,
                    ByteOrder.LITTLE_ENDIAN, topLevelPathSlice.isClosed::get);
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
     * Read the slice into a {@link ByteBuffer}. If the file is memory-mapped, the buffer is a read-only view of the
     * mapping, and nothing is copied.
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
        if (mappedByteBuffer == null) {
            // Copy the slice from the FileChannel into a byte array, and wrap the array
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
     * Delete the temporary file that this slice owns, once the mapping and the file channel over it have been
     * released.
     *
     * @param fileToDelete
     *            the temporary file.
     * @param mappingStillHeldOpen
     *            the memory mapping of the file, if it could not be released when this slice closed because a view
     *            of it that the caller can still read is open, or null if the file is not mapped any more.
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
     * Ask the garbage collector to run, and wait for the references that the collection found to be processed, so
     * that a mapping that nothing can reach any more is more likely to have been released by the time this returns.
     *
     * <p>
     * This is best effort, and cannot be made reliable: a file is unmapped while the reference to its mapped buffer
     * is processed, and nothing can observe that a particular reference has been processed. That is why a file is
     * unmapped explicitly rather than left to this -- this is only for what an explicit unmapping cannot reach: an
     * address range that has to be freed before a mapping can be retried, a buffer that could not be unmapped
     * explicitly, and a temporary file that Windows would not let ClassGraph delete. A JVM started with
     * {@code -XX:+DisableExplicitGC} ignores the request to collect altogether, in which case this returns once the
     * wait times out, having done nothing.
     *
     * <p>
     * This is in {@link PathSlice} because closing a slice calls it, and closing may happen from a shutdown hook or
     * a container's teardown code, after the classloader that loaded ClassGraph has been closed. A class that
     * nothing loaded before then could no longer be loaded, and closing would throw {@link NoClassDefFoundError}.
     * {@link PathSlice} is always loaded by the time one of its instances is closed.
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
