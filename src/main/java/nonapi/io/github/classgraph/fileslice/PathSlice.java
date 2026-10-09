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

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.atomic.AtomicBoolean;

import nonapi.io.github.classgraph.fastzipfilereader.NestedJarHandler;
import nonapi.io.github.classgraph.fileslice.reader.RandomAccessFileChannelReader;
import nonapi.io.github.classgraph.fileslice.reader.RandomAccessReader;
import nonapi.io.github.classgraph.utils.FileUtils;

/** A {@link Path} slice. */
public class PathSlice extends Slice {
    /** The {@link Path}. */
    public final Path path;

    /** The file length. */
    private final long fileLength;

    /**
     * The {@link FileChannel} opened on the {@link Path}, or null once closed. Only set on the toplevel file slice,
     * which owns the file channel. Volatile, since every slice of the file reads it, and it is replaced when a
     * thread that was interrupted while reading it closes it.
     */
    private volatile FileChannel fileChannel;

    /**
     * The lock that the toplevel slice's {@link #fileChannel} is replaced and set to null under, which every slice
     * of the file shares.
     */
    private final Object fileChannelLock;

    /** The toplevel file slice, which owns the file channel, or {@code this} if this is the toplevel slice. */
    private final PathSlice topLevelPathSlice;

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
     *            the uncompressed size of a deflated zip entry, or -1 if unknown, or 0 if this is not a deflated
     *            zip entry.
     * @param nestedJarHandler
     *            the nested jar handler
     */
    private PathSlice(final PathSlice parentSlice, final long offset, final long length,
            final boolean isDeflatedZipEntry, final long inflatedLengthHint,
            final NestedJarHandler nestedJarHandler) {
        super(parentSlice, offset, length, isDeflatedZipEntry, inflatedLengthHint, nestedJarHandler);

        this.path = parentSlice.path;
        this.fileLength = parentSlice.fileLength;
        this.fileChannelLock = parentSlice.fileChannelLock;
        this.topLevelPathSlice = parentSlice.topLevelPathSlice;

        // Only mark toplevel file slices as open (sub slices don't need to be marked as open since
        // they don't need to be closed, they just copy the resource references of the toplevel slice)
    }

    /**
     * Constructor for toplevel file slice.
     *
     * @param path
     *            the path
     * @param isDeflatedZipEntry
     *            true if this is a deflated zip entry
     * @param inflatedLengthHint
     *            the uncompressed size of a deflated zip entry, or -1 if unknown, or 0 if this is not a deflated
     *            zip entry.
     * @param nestedJarHandler
     *            the nested jar handler
     * @throws IOException
     *             if the file cannot be opened.
     */
    public PathSlice(final Path path, final boolean isDeflatedZipEntry, final long inflatedLengthHint,
            final NestedJarHandler nestedJarHandler) throws IOException {
        this(path, isDeflatedZipEntry, inflatedLengthHint, nestedJarHandler, true);
    }

    /**
     * Constructor for toplevel file slice.
     *
     * @param path
     *            the path
     * @param isDeflatedZipEntry
     *            true if this is a deflated zip entry
     * @param inflatedLengthHint
     *            the uncompressed size of a deflated zip entry, or -1 if unknown, or 0 if this is not a deflated
     *            zip entry.
     * @param nestedJarHandler
     *            the nested jar handler
     * @param checkAccess
     *            whether it is needed to check read access and if it is a file
     * @throws IOException
     *             if the file cannot be opened.
     */
    public PathSlice(final Path path, final boolean isDeflatedZipEntry, final long inflatedLengthHint,
            final NestedJarHandler nestedJarHandler, final boolean checkAccess) throws IOException {
        super(0L, isDeflatedZipEntry, inflatedLengthHint, nestedJarHandler);

        if (checkAccess) {
            // Make sure the File is readable and is a regular file
            FileUtils.checkCanReadAndIsFile(path);
        }

        this.path = path;
        this.fileChannel = FileChannel.open(path, StandardOpenOption.READ);
        this.fileLength = fileChannel.size();
        this.fileChannelLock = new Object();
        this.topLevelPathSlice = this;

        // Had to use 0L for sliceLength in call to super, since FileChannel wasn't open yet
        // => update sliceLength
        this.sliceLength = fileLength;

        // Mark toplevel slice as open
        nestedJarHandler.markSliceAsOpen(this);
    }

    /**
     * Constructor for toplevel file slice.
     *
     * @param path
     *            the path
     * @param nestedJarHandler
     *            the nested jar handler
     * @throws IOException
     *             if the file cannot be opened.
     */
    public PathSlice(final Path path, final NestedJarHandler nestedJarHandler) throws IOException {
        this(path, /* isDeflatedZipEntry = */ false, /* inflatedSizeHint = */ 0L, nestedJarHandler);
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
        return new PathSlice(this, offset, length, isDeflatedZipEntry, inflatedLengthHint, nestedJarHandler);
    }

    /**
     * Get the {@link FileChannel} to read the file through. Called on the toplevel slice before every read.
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
            throw new InterruptedIOException("Interrupted while reading " + path);
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
                throw new IOException("Cannot read " + path + " after it has been closed");
            }
            if (currentChannel.isOpen()) {
                // Another thread reopened the channel first
                return currentChannel;
            }
            final FileChannel reopenedChannel = FileChannel.open(path, StandardOpenOption.READ);
            try {
                if (reopenedChannel.size() != fileLength) {
                    // The offsets that were read from the file when it was first opened would not be valid for
                    // the file that is there now
                    throw new IOException(path + " changed after it was opened");
                }
            } catch (final IOException e) {
                try {
                    reopenedChannel.close();
                } catch (final IOException e2) {
                    e.addSuppressed(e2);
                }
                throw e;
            }
            fileChannel = reopenedChannel;
            return reopenedChannel;
        }
    }

    /**
     * Read directly from FileChannel (slow path, but handles &gt;2GB).
     *
     * @return the random access reader
     */
    @Override
    public RandomAccessReader randomAccessReader() {
        // Return a RandomAccessReader that uses the FileChannel. The reader gets the channel from the toplevel
        // slice before every read, rather than keeping the channel it was made with, since the toplevel slice
        // replaces a channel that was closed by an interrupted thread
        final PathSlice topLevelSlice = topLevelPathSlice;
        return new RandomAccessFileChannelReader(new RandomAccessFileChannelReader.FileChannelSource() {
            @Override
            public FileChannel fileChannel() throws IOException {
                return topLevelSlice.fileChannel();
            }
        }, sliceStartPos, sliceLength);
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
            // Copy from FileChannel to byte array
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
     * Read the slice into a {@link ByteBuffer}. (A {@link PathSlice} is never memory-mapped, unlike a
     * {@link FileSlice} -- the content is always copied into a heap {@link ByteBuffer}.)
     *
     * @return the byte buffer
     * @throws IOException
     *             Signals that an I/O exception has occurred.
     */
    @Override
    public ByteBuffer read() throws IOException {
        if (isDeflatedZipEntry) {
            // Inflate to RAM if deflated (unfortunately there is no lazy-loading ByteBuffer that will
            // decompress partial streams on demand, so we have to decompress the whole zip entry)
            if (inflatedLengthHint > FileUtils.MAX_BUFFER_SIZE) {
                throw new IOException("Uncompressed size is larger than 2GB");
            }
            return ByteBuffer.wrap(load());
        }
        // Copy from FileChannel to byte array, then wrap in a ByteBuffer
        if (sliceLength > FileUtils.MAX_BUFFER_SIZE) {
            throw new IOException("File is larger than 2GB");
        }
        return ByteBuffer.wrap(load());
    }

    @Override
    public boolean equals(final Object o) {
        return super.equals(o);
    }

    @Override
    public int hashCode() {
        return super.hashCode();
    }

    /** Close the slice, closing the {@link FileChannel} if this is the toplevel slice. */
    @Override
    public void close() {
        if (!isClosed.getAndSet(true)) {
            if (topLevelPathSlice == this) {
                // Only close the FileChannel in the toplevel file slice, which owns it, so that it is only closed
                // once. The channel is taken under the lock that a reopen of it holds, so that a reopen cannot
                // replace it after it has been taken.
                final FileChannel fileChannelToClose;
                synchronized (fileChannelLock) {
                    fileChannelToClose = fileChannel;
                    fileChannel = null;
                }
                if (fileChannelToClose != null) {
                    try {
                        fileChannelToClose.close();
                    } catch (final IOException e) {
                        // Ignore
                    }
                }
            }
            nestedJarHandler.markSliceAsClosed(this);
        }
    }
}
