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
package io.github.classgraph.vfs;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.NonWritableChannelException;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

import org.jspecify.annotations.Nullable;

/**
 * A read-only {@link FileChannel} over the content of one {@link VfsEntry}. It is returned both by
 * {@link VfsFileSystemProvider#newFileChannel} and by {@link VfsFileSystemProvider#newByteChannel}, since a
 * {@link FileChannel} is a {@link SeekableByteChannel}, and it is the object the root tracks, so that when the root
 * closes it, the channel the caller holds reports itself closed.
 *
 * <p>
 * Reads are not interruptible: the channel does not use {@link #begin()} and {@link #end(boolean)}, so interrupting
 * a thread that is reading does not close the channel.
 */
final class VfsFileChannel extends FileChannel {
    /**
     * State retained only while open. A close detaches the reader, its content buffer, and the owning root without
     * waiting for a read to release this channel's monitor. An in-flight read keeps its own local reference.
     */
    private final AtomicReference<@Nullable OpenState> state;

    /**
     * Resources owned by an open channel.
     *
     * @param content
     *            the content and its release action.
     * @param root
     *            the root that tracks and closes the channel.
     * @param owner
     *            the filesystem view that also owns the channel's lifetime.
     * @param entry
     *            the entry to stream for bounded-memory transfers.
     */
    private record OpenState(VfsEntry.RandomAccessContent content, VfsRoot root, VfsFileSystem owner,
            VfsEntry entry) {
    }

    /**
     * The read position, which is allowed to be beyond the end of the content, where reads return end-of-file.
     * Every method that reads or moves it is synchronized, since {@link FileChannel} allows only one operation on
     * the position at a time.
     */
    private long position;

    /** The size of the buffer {@link #transferTo(long, long, WritableByteChannel)} copies through. */
    private static final int TRANSFER_BUF_SIZE = 16384;

    /**
     * The stream that {@link #transferTo(long, long, WritableByteChannel)} reads through, kept between calls. A
     * caller that copies the content a chunk at a time, or that calls again after a target took only part of a
     * transfer, then continues from where the last transfer stopped, rather than inflating or streaming the content
     * again from the beginning on every call. Null when there is none yet, and while a transfer is using it.
     * {@link #implCloseChannel()} closes it.
     */
    private final AtomicReference<@Nullable TransferCursor> transferCursor = new AtomicReference<>();

    /** Serializes transfers, which share {@link #transferCursor}. */
    private final Object transferLock = new Object();

    /**
     * A stream over the content, and the last buffer read from it, which still holds whatever part of it a target
     * did not take.
     */
    private static final class TransferCursor {
        /** The stream, which only reads forward. */
        final InputStream stream;

        /** The bytes last read from the stream. */
        final byte[] buffer = new byte[TRANSFER_BUF_SIZE];

        /** The position in the content of the first byte in {@link #buffer}. */
        long bufferPosition;

        /** The number of bytes in {@link #buffer}. The stream is at {@code bufferPosition + bufferLength}. */
        int bufferLength;

        /**
         * Constructor.
         *
         * @param stream
         *            a stream over the content, at its beginning.
         */
        TransferCursor(final InputStream stream) {
            this.stream = stream;
        }
    }

    /**
     * Constructor.
     *
     * @param content
     *            the content of the entry.
     * @param root
     *            the root the entry was read from. {@link #open(VfsEntry, VfsFileSystem)} registers this channel
     *            with the root -- this constructor does not, so that the channel is not published before it is
     *            built.
     * @param owner
     *            the filesystem view.
     * @param entry
     *            the entry to stream for transfers.
     */
    private VfsFileChannel(final VfsEntry.RandomAccessContent content, final VfsRoot root,
            final VfsFileSystem owner, final VfsEntry entry) {
        state = new AtomicReference<>(new OpenState(content, root, owner, entry));
    }

    /**
     * Open a read-only channel over the content of an entry. How much of the content is brought into memory to
     * serve a read at an offset is decided by the entry -- see {@link VfsEntry#openRandomAccess()} -- since a
     * caller that opens a channel rather than a stream is one that may not read the content straight through.
     *
     * @param entry
     *            the entry.
     * @param owner
     *            the filesystem view that owns the returned handle.
     * @return the channel, which the caller owns and must close.
     * @throws IOException
     *             if the entry could not be read, or its root has been closed.
     */
    static VfsFileChannel open(final VfsEntry entry, final VfsFileSystem owner) throws IOException {
        final var root = entry.getRoot();
        final var content = entry.openRandomAccess();
        final VfsFileChannel channel;
        try {
            channel = new VfsFileChannel(content, root, owner, entry);
        } catch (final RuntimeException | Error e) {
            // The caller never sees the channel if this throws, so nothing else would release the content
            content.closeAction().run();
            throw e;
        }
        // From here the channel owns the content, so a failure to track it is reported by closing the channel
        // rather than by releasing the content directly, which would release it twice
        if (!root.trackOpenHandle(channel)) {
            channel.close();
            throw new IOException("Cannot read " + entry.getPath() + " after the root has been closed");
        }
        return owner.register(channel);
    }

    @Override
    public synchronized int read(final ByteBuffer dst) throws IOException {
        final var numBytes = read(dst, position);
        if (numBytes > 0) {
            position += numBytes;
        }
        return numBytes;
    }

    @Override
    public synchronized long read(final ByteBuffer[] dsts, final int offset, final int length) throws IOException {
        Objects.checkFromIndexSize(offset, length, dsts.length);
        checkOpen();
        var total = 0L;
        for (var i = offset; i < offset + length; i++) {
            final var numBytes = read(dsts[i]);
            if (numBytes < 0) {
                // Only end-of-file before anything was read is reported as end-of-file, which is what a scattering
                // read of a file does
                return total == 0 ? -1 : total;
            }
            total += numBytes;
        }
        return total;
    }

    /**
     * Read from a given position, without moving the position of the channel. This is safe to do from several
     * threads at once, which is what {@link FileChannel} promises: the reader keeps state of its own between reads,
     * and is not safe to use from more than one thread at a time, so every read through this channel is serialized.
     * There is one reader per open channel, so this does not serialize threads that are reading different files, or
     * even the same file through channels of their own.
     *
     * @param dst
     *            the buffer to read into.
     * @param fromPosition
     *            the position to read from, which is allowed to be beyond the end of the content.
     * @return the number of bytes read, or -1 at end of file.
     * @throws IOException
     *             if the content could not be read.
     */
    @Override
    public synchronized int read(final ByteBuffer dst, final long fromPosition) throws IOException {
        if (fromPosition < 0) {
            throw new IllegalArgumentException("Negative position: " + fromPosition);
        }
        checkOpen();
        final var numBytes = dst.remaining();
        if (numBytes == 0) {
            return 0;
        }
        // The end of the content is where the reader says it is, rather than being checked against a length
        // first: a deflated entry or a module resource does not know its own length until it has been read to the
        // end, and reading a header should not have to inflate or stream the whole of the entry to find out where
        // it ends

        // The reader writes at an index and leaves the destination's position and limit alone, so the position is
        // advanced here over what it wrote, which is what the contract of this method asks for
        final var dstStart = dst.position();
        final var numBytesRead = Math
                .max(openState().content().reader().read(fromPosition, dst, dstStart, numBytes), 0);
        dst.position(dstStart + numBytesRead);
        // The reader returns -1, which Math#max above turned into 0, once the read starts at or past the end of the
        // content
        return numBytesRead == 0 ? -1 : numBytesRead;
    }

    @Override
    public int write(final ByteBuffer src) {
        throw new NonWritableChannelException();
    }

    @Override
    public long write(final ByteBuffer[] srcs, final int offset, final int length) {
        throw new NonWritableChannelException();
    }

    @Override
    public int write(final ByteBuffer src, final long atPosition) {
        throw new NonWritableChannelException();
    }

    @Override
    public synchronized long position() throws IOException {
        checkOpen();
        return position;
    }

    @Override
    public synchronized FileChannel position(final long newPosition) throws IOException {
        checkOpen();
        if (newPosition < 0) {
            throw new IllegalArgumentException("Negative position: " + newPosition);
        }
        // Seeking beyond the end is allowed, the position reads back as the one that was asked for, and reads
        // there return -1
        position = newPosition;
        return this;
    }

    @Override
    public synchronized long size() throws IOException {
        checkOpen();
        // For an entry that is inflated or streamed, this reads the whole of the content, since that is the only
        // way to find out how long it is. That is what Files#readAllBytes and Files#readString do first, so those
        // read the entry once from beginning to end, and then copy it out of the reader's buffer. It is
        // synchronized for the same reason as a read is: it uses the reader, which one thread at a time may use.
        return openState().content().reader().length();
    }

    @Override
    public FileChannel truncate(final long size) {
        throw new NonWritableChannelException();
    }

    @Override
    public void force(final boolean metaData) throws IOException {
        // Nothing to flush, since nothing can be written, but a closed channel still has to be reported
        checkOpen();
    }

    @Override
    public long transferTo(final long fromPosition, final long count, final WritableByteChannel target)
            throws IOException {
        if (fromPosition < 0 || count < 0) {
            throw new IllegalArgumentException("Negative position or count");
        }
        checkOpen();
        if (!target.isOpen()) {
            throw new ClosedChannelException();
        }
        if (count == 0) {
            return 0;
        }
        final var open = openState();
        // Read through a stream of its own rather than through the random-access reader, which retains every
        // inflated byte before the offset it reads at, so it would retain the whole of the transferred prefix
        synchronized (transferLock) {
            var cursor = transferCursor.getAndSet(null);
            var keepCursor = false;
            try {
                if (cursor != null && fromPosition < cursor.bufferPosition) {
                    // The stream cannot go back, so start again from the beginning
                    cursor.stream.close();
                    cursor = null;
                }
                if (cursor == null) {
                    cursor = new TransferCursor(open.owner().openInputStream(open.entry()));
                }
                var numBytesTransferred = 0L;
                var pos = fromPosition;
                while (numBytesTransferred < count) {
                    checkOpen();
                    var streamPosition = cursor.bufferPosition + cursor.bufferLength;
                    if (pos >= streamPosition) {
                        // Nothing that is needed is left in the buffer, so skip forward and read some more
                        while (streamPosition < pos) {
                            final var skipped = cursor.stream.skip(pos - streamPosition);
                            if (skipped > 0) {
                                streamPosition += skipped;
                            } else if (cursor.stream.read() == -1) {
                                break;
                            } else {
                                streamPosition++;
                            }
                        }
                        cursor.bufferPosition = streamPosition;
                        cursor.bufferLength = 0;
                        if (streamPosition < pos) {
                            // The position is beyond the end of the content
                            break;
                        }
                        final var numRead = cursor.stream.read(cursor.buffer, 0,
                                (int) Math.min(cursor.buffer.length, count - numBytesTransferred));
                        if (numRead == -1) {
                            break;
                        }
                        cursor.bufferLength = numRead;
                    }
                    final var offset = (int) (pos - cursor.bufferPosition);
                    final var buf = ByteBuffer.wrap(cursor.buffer, offset,
                            (int) Math.min(cursor.bufferLength - offset, count - numBytesTransferred));
                    final var numWritten = target.write(buf);
                    numBytesTransferred += numWritten;
                    pos += numWritten;
                    if (buf.hasRemaining()) {
                        // A non-blocking target that will not take the whole buffer ends the transfer early. What
                        // it did not take stays in the buffer, for the call that the caller makes next
                        break;
                    }
                }
                keepCursor = true;
                return numBytesTransferred;
            } finally {
                if (cursor != null) {
                    if (keepCursor) {
                        transferCursor.set(cursor);
                        if (!isOpen()) {
                            // The channel was closed during the transfer, after implCloseChannel() looked for
                            // the cursor, so it is closed here instead
                            closeTransferCursor();
                        }
                    } else {
                        // After an exception, the buffer may not match the stream position, so start again on the
                        // next call. Close quietly, so that the exception that ended the transfer is the one thrown
                        try {
                            cursor.stream.close();
                        } catch (final IOException e) {
                            // Nothing can be done about a stream that cannot be closed
                        }
                    }
                }
            }
        }
    }

    /**
     * Close the stream that transfers read through, if there is one.
     *
     * @throws IOException
     *             if the stream could not be closed.
     */
    private void closeTransferCursor() throws IOException {
        final var cursor = transferCursor.getAndSet(null);
        if (cursor != null) {
            cursor.stream.close();
        }
    }

    @Override
    public long transferFrom(final ReadableByteChannel src, final long atPosition, final long count) {
        throw new NonWritableChannelException();
    }

    @Override
    public MappedByteBuffer map(final MapMode mode, final long fromPosition, final long size) {
        throw new UnsupportedOperationException(
                "An entry of an archive has no region of a file of its own that could be mapped");
    }

    @Override
    public FileLock lock(final long fromPosition, final long size, final boolean shared) {
        throw new UnsupportedOperationException("This read-only filesystem does not support file locking");
    }

    @Override
    public @Nullable FileLock tryLock(final long fromPosition, final long size, final boolean shared) {
        throw new UnsupportedOperationException("This read-only filesystem does not support file locking");
    }

    /**
     * Check that this channel is still open.
     *
     * @throws ClosedChannelException
     *             if it is not.
     */
    private void checkOpen() throws IOException {
        if (!isOpen()) {
            throw new ClosedChannelException();
        }
    }

    /**
     * Capture the state for one operation, so concurrent closure cannot turn a content access into a null read.
     *
     * @return the open state.
     * @throws ClosedChannelException
     *             if the channel has closed.
     */
    private OpenState openState() throws ClosedChannelException {
        final var open = state.get();
        if (open == null || !isOpen()) {
            throw new ClosedChannelException();
        }
        return open;
    }

    /**
     * Release the content. {@link #close()} calls this at most once, whether the caller closes the channel or the
     * root closes it. This does not take the monitor of the channel, so a close does not wait for a read that is in
     * progress.
     */
    @Override
    protected void implCloseChannel() {
        // Stop the root tracking this channel, so that a caller that closes its channels does not accumulate them
        // on the root for as long as it stays open. Harmless if the root is the one doing the closing, since it
        // drains its set before it closes what was in it
        final var open = state.getAndSet(null);
        try {
            closeTransferCursor();
        } catch (final IOException e) {
            // Nothing can be done about a stream that cannot be closed, and the channel is closed regardless
        }
        if (open != null) {
            open.root().untrackOpenHandle(this);
            open.owner().unregister(this);
            open.content().closeAction().run();
        }
    }
}
