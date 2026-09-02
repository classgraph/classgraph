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
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.classgraph.base.LogNode;
import io.github.classgraph.base.internal.path.FileUtils;
import io.github.classgraph.vfs.Vfs;
import io.github.classgraph.vfs.internal.TempFile;
import io.github.classgraph.vfs.reader.RandomAccessByteBufferReader;
import io.github.classgraph.vfs.reader.RandomAccessFileChannelReader;
import io.github.classgraph.vfs.reader.RandomAccessReader;
import org.jspecify.annotations.Nullable;

/** A {@link Path} slice. */
public final class PathSlice extends Slice {
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
     * which owns the file channel.
     */
    private @Nullable FileChannel fileChannel;

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
            throw new IOException("Cannot read " + pathStr + " after it has been closed");
        }
        return channel;
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
     * @param isDeflatedZipEntry
     *            true if this is a deflated zip entry
     * @param inflatedLengthHint
     *            the uncompressed size of a deflated zip entry, or -1 if unknown, or 0 if this is not a deflated
     *            zip entry.
     * @param vfs
     *            the {@link Vfs} that opened this slice
     */
    private PathSlice(final PathSlice parentSlice, final long offset, final long length,
            final boolean isDeflatedZipEntry, final long inflatedLengthHint, final Vfs vfs) {
        super(parentSlice, offset, length, isDeflatedZipEntry, inflatedLengthHint, vfs);

        this.path = parentSlice.path;
        this.file = parentSlice.file;
        this.pathStr = parentSlice.pathStr;
        this.fileLength = parentSlice.fileLength;
        this.topLevelPathSlice = parentSlice.topLevelPathSlice;
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
        // slice is therefore not registered with the vfs as open: it holds nothing of its own to release.
    }

    /**
     * Constructor for toplevel file slice. Exactly one of {@code path} and {@code file} is non-null.
     *
     * @param path
     *            the path, or null if the file is only reachable through the {@link File} API
     * @param file
     *            the file, or null if the file is reachable through the {@link Path} API
     * @param pathStr
     *            the path of the file, as it was given, for use in log and exception messages
     * @param vfs
     *            the {@link Vfs} that opened this slice
     * @param checkAccess
     *            whether it is needed to check read access and if it is a file
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
        super(0L, /* isDeflatedZipEntry = */ false, /* inflatedLengthHint = */ 0L, vfs);

        this.path = path;
        this.file = file;
        this.pathStr = pathStr;
        this.tempFile = isTempFile ? file : null;
        this.tempFileLog = isTempFile ? log : null;
        // Set before the file is opened, since it is what tells close() that this slice owns the file channel
        this.topLevelPathSlice = this;

        final FileChannel fileChannelOpened;
        if (path != null) {
            if (checkAccess) {
                // Make sure the file is readable and is a regular file
                FileUtils.checkCanReadAndIsFile(path);
            }
            fileChannelOpened = FileChannel.open(path, StandardOpenOption.READ);
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
            fileChannelOpened = new RandomAccessFile(fileToOpen, "r").getChannel();
        }
        this.fileChannel = fileChannelOpened;
        // Nothing but this constructor knows about the file channel yet, so if anything below throws, this is the
        // only place the channel -- and the temporary file, if this slice owns one -- can be released
        try {
            this.fileLength = fileChannelOpened.size();

            // Had to use 0L for sliceLength in call to super, since FileChannel wasn't open yet => update
            // sliceLength
            this.sliceLength = fileLength;

            if (memoryMapWholeFile && vfs.getVfsSpec().isMemoryMappingFiles()) {
                // Memory-map the whole file, if it can be mapped -- otherwise fall through and read through the
                // FileChannel API instead
                final var mapping = FileMapping.map(fileChannelOpened, fileLength, pathStr, log);
                fileMapping = mapping;
                backingByteBuffer = mapping == null ? null : mapping.byteBuffer;
            }
        } catch (final IOException | RuntimeException | Error e) {
            close();
            throw e;
        }
    }

    /**
     * Constructor for toplevel file slice.
     *
     * @param path
     *            the path
     * @param vfs
     *            the {@link Vfs} that opened this slice
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
    public PathSlice(final Path path, final Vfs vfs, final boolean checkAccess, final boolean memoryMapWholeFile,
            final @Nullable LogNode log) throws IOException {
        this(path, /* file = */ null, path.toString(), vfs, checkAccess, memoryMapWholeFile,
                /* isTempFile = */ false, log);
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
     *            the temporary file, as returned by {@link TempFile#create(String, boolean)}.
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
        this(path, vfs, /* checkAccess = */ true, /* memoryMapWholeFile = */ true, log);
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
        return new PathSlice(this, offset, length, isDeflatedZipEntry, inflatedLengthHint, vfs);
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
            // of the mapping for as long as it is alive, and readers are not closed, so it is given the toplevel
            // slice's closed flag to check before each read: reading a file that has been unmapped is not merely
            // wrong, it reads memory that is no longer there
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
            if (topLevelPathSlice != this) {
                // Only the toplevel file slice owns the file channel and the memory mapping -- a sub slice reads
                // through the toplevel slice's, and has nothing of its own to release
                return;
            }
            // Take what has to be released, and drop the references to it, before releasing any of it: this slice
            // is already marked as closed, so a second call must not release the same resource twice
            final var mapping = fileMapping;
            final var fileChannelToClose = fileChannel;
            fileMapping = null;
            backingByteBuffer = null;
            fileChannel = null;
            try {
                if (mapping != null) {
                    // A file that could not be unmapped here is left to the garbage collector, which deleteTempFile
                    // below asks for if the mapping is what is stopping the file from being deleted
                    mapping.unmap();
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
                        deleteTempFile(tempFile);
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
     */
    private void deleteTempFile(final File fileToDelete) {
        if (TempFile.delete(fileToDelete)) {
            return;
        }
        // Windows refuses to delete a file that is still memory-mapped, so a delete that failed may be waiting on
        // a mapping that could not be unmapped explicitly -- one whose buffer a caller can still read, or one
        // mapped on a JDK old enough to need sun.misc.Unsafe where that class could not be reached. Those are left
        // to the garbage collector, which only runs when it chooses to, so ask for a collection and try again. If
        // the JVM was started with -XX:+DisableExplicitGC then this is a no-op, and the file is left to the
        // File#deleteOnExit() hook that TempFile#create registered.
        // #939
        OffHeapMemory.freeUnreachableBuffers();
        if (!TempFile.delete(fileToDelete) && tempFileLog != null) {
            tempFileLog.log("Removing temporary file failed: " + fileToDelete);
        }
    }

    /**
     * Returns whether this slice owns a temporary file that has not been deleted yet.
     *
     * @return true if this slice owns a temporary file that has not been deleted yet.
     */
    public boolean hasUndeletedTempFile() {
        return tempFile != null && !isClosed.get();
    }
}
