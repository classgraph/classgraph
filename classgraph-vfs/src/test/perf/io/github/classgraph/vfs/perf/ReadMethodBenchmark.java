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
package io.github.classgraph.vfs.perf;

import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.RandomAccessFile;
import java.lang.foreign.Arena;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousFileChannel;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.apache.commons.compress.archivers.zip.ZipFile;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Threads;

import io.github.classgraph.vfs.perf.BenchmarkFixtures.Content;
import io.github.classgraph.vfs.perf.BenchmarkFixtures.Layout;
import io.github.classgraph.vfs.reader.RandomAccessByteBufferReader;

/**
 * Compares ways of reading a file, for the reads that classgraph-vfs makes of a stored jarfile: open the file, read
 * the central directory, then read each entry's local header and data once, in random order, from one or more
 * threads, and close the file. It decides whether a jarfile is memory-mapped on each OS.
 *
 * <p>
 * {@link java.io.FileReader} is not compared: it decodes bytes to characters, which changes binary content, and it
 * cannot read from a byte offset, so it cannot read a jarfile entry at all.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Threads(1)
public class ReadMethodBenchmark {
    /** A way of reading a file. */
    public enum Method {
        /** Map the whole file through a shared {@link Arena}, and unmap it on close, as classgraph-vfs does. */
        MMAP_ARENA {
            @Override
            FileReader open(final Path path) throws IOException {
                final var channel = FileChannel.open(path, StandardOpenOption.READ);
                final var arena = Arena.ofShared();
                final var buffer = channel.map(FileChannel.MapMode.READ_ONLY, 0L, channel.size(), arena)
                        .asByteBuffer();
                return new FileReader() {
                    @Override
                    public void read(final long pos, final byte[] dst, final int len) {
                        buffer.get((int) pos, dst, 0, len);
                    }

                    @Override
                    public void close() throws IOException {
                        arena.close();
                        channel.close();
                    }
                };
            }
        },

        /**
         * Map the whole file through a shared {@link Arena}, and copy out of it through the
         * {@link RandomAccessByteBufferReader} that classgraph-vfs reads a mapping with, which copies so that a
         * read of a file truncated after it was mapped throws inside the read.
         */
        MMAP_READER {
            @Override
            FileReader open(final Path path) throws IOException {
                final var channel = FileChannel.open(path, StandardOpenOption.READ);
                final var arena = Arena.ofShared();
                final var buffer = channel.map(FileChannel.MapMode.READ_ONLY, 0L, channel.size(), arena)
                        .asByteBuffer();
                final var mappingReader = new RandomAccessByteBufferReader(buffer, 0L, buffer.capacity());
                return new FileReader() {
                    @Override
                    public void read(final long pos, final byte[] dst, final int len) throws IOException {
                        if (mappingReader.read(pos, dst, 0, len) != len) {
                            throw new EOFException();
                        }
                    }

                    @Override
                    public void close() throws IOException {
                        arena.close();
                        channel.close();
                    }
                };
            }
        },

        /** Map the whole file as a {@link java.nio.MappedByteBuffer}, which is unmapped only by the GC. */
        MMAP_BUFFER {
            @Override
            FileReader open(final Path path) throws IOException {
                final var channel = FileChannel.open(path, StandardOpenOption.READ);
                final var buffer = channel.map(FileChannel.MapMode.READ_ONLY, 0L, channel.size());
                return new FileReader() {
                    @Override
                    public void read(final long pos, final byte[] dst, final int len) {
                        buffer.get((int) pos, dst, 0, len);
                    }

                    @Override
                    public void close() throws IOException {
                        channel.close();
                    }
                };
            }
        },

        /** Positional {@link FileChannel} reads into the array, as classgraph-vfs does where it does not map. */
        CHANNEL {
            @Override
            FileReader open(final Path path) throws IOException {
                final var channel = FileChannel.open(path, StandardOpenOption.READ);
                return new FileReader() {
                    @Override
                    public void read(final long pos, final byte[] dst, final int len) throws IOException {
                        readFully(channel, pos, ByteBuffer.wrap(dst, 0, len));
                    }

                    @Override
                    public void close() throws IOException {
                        channel.close();
                    }
                };
            }
        },

        /** Positional {@link FileChannel} reads into a direct buffer for each thread, copied to the array. */
        CHANNEL_DIRECT {
            @Override
            FileReader open(final Path path) throws IOException {
                final var channel = FileChannel.open(path, StandardOpenOption.READ);
                final var buffers = ThreadLocal.withInitial(() -> ByteBuffer.allocateDirect(64 * 1024));
                return new FileReader() {
                    @Override
                    public void read(final long pos, final byte[] dst, final int len) throws IOException {
                        final var buffer = buffers.get();
                        for (var off = 0; off < len;) {
                            final var chunk = Math.min(len - off, buffer.capacity());
                            buffer.clear().limit(chunk);
                            readFully(channel, pos + off, buffer);
                            buffer.flip().get(dst, off, chunk);
                            off += chunk;
                        }
                    }

                    @Override
                    public void close() throws IOException {
                        channel.close();
                    }
                };
            }
        },

        /** One {@link RandomAccessFile}, which a thread locks to seek and read. */
        RANDOM_ACCESS_FILE {
            @Override
            FileReader open(final Path path) throws IOException {
                final var file = new RandomAccessFile(path.toFile(), "r");
                return new FileReader() {
                    @Override
                    public void read(final long pos, final byte[] dst, final int len) throws IOException {
                        synchronized (file) {
                            file.seek(pos);
                            file.readFully(dst, 0, len);
                        }
                    }

                    @Override
                    public void close() throws IOException {
                        file.close();
                    }
                };
            }
        },

        /** A {@link RandomAccessFile} for each thread, so that no thread waits for another. */
        RANDOM_ACCESS_FILE_PER_THREAD {
            @Override
            FileReader open(final Path path) throws IOException {
                final List<RandomAccessFile> opened = Collections.synchronizedList(new ArrayList<>());
                final var files = ThreadLocal.withInitial(() -> {
                    try {
                        final var file = new RandomAccessFile(path.toFile(), "r");
                        opened.add(file);
                        return file;
                    } catch (final IOException e) {
                        throw new IllegalStateException(e);
                    }
                });
                return new FileReader() {
                    @Override
                    public void read(final long pos, final byte[] dst, final int len) throws IOException {
                        final var file = files.get();
                        file.seek(pos);
                        file.readFully(dst, 0, len);
                    }

                    @Override
                    public void close() throws IOException {
                        for (final var file : opened) {
                            file.close();
                        }
                    }
                };
            }
        },

        /**
         * Positional reads through a {@link FileChannel} for each thread, so that no thread waits for another's
         * position lock, which the channel takes for a positional read on Windows.
         */
        CHANNEL_PER_THREAD {
            @Override
            FileReader open(final Path path) throws IOException {
                final List<FileChannel> opened = Collections.synchronizedList(new ArrayList<>());
                final var channels = ThreadLocal.withInitial(() -> {
                    try {
                        final var channel = FileChannel.open(path, StandardOpenOption.READ);
                        opened.add(channel);
                        return channel;
                    } catch (final IOException e) {
                        throw new IllegalStateException(e);
                    }
                });
                return new FileReader() {
                    @Override
                    public void read(final long pos, final byte[] dst, final int len) throws IOException {
                        readFully(channels.get(), pos, ByteBuffer.wrap(dst, 0, len));
                    }

                    @Override
                    public void close() throws IOException {
                        for (final var channel : opened) {
                            channel.close();
                        }
                    }
                };
            }
        },

        /**
         * Reads through one {@link AsynchronousFileChannel}, each waited for, which reads at an offset without
         * taking a position lock on any OS.
         */
        ASYNC_CHANNEL {
            @Override
            FileReader open(final Path path) throws IOException {
                final var channel = AsynchronousFileChannel.open(path, StandardOpenOption.READ);
                return new FileReader() {
                    @Override
                    public void read(final long pos, final byte[] dst, final int len) throws IOException {
                        final var buffer = ByteBuffer.wrap(dst, 0, len);
                        try {
                            while (buffer.hasRemaining()) {
                                if (channel.read(buffer, pos + buffer.position()).get() < 0) {
                                    throw new EOFException();
                                }
                            }
                        } catch (final InterruptedException _) {
                            Thread.currentThread().interrupt();
                            throw new InterruptedIOException();
                        } catch (final ExecutionException e) {
                            throw new IOException(e.getCause());
                        }
                    }

                    @Override
                    public void close() throws IOException {
                        channel.close();
                    }
                };
            }
        },

        /** An {@link java.io.InputStream} opened for each read, skipped to the offset. */
        INPUT_STREAM {
            @Override
            FileReader open(final Path path) {
                return new FileReader() {
                    @Override
                    public void read(final long pos, final byte[] dst, final int len) throws IOException {
                        try (var in = Files.newInputStream(path)) {
                            in.skipNBytes(pos);
                            if (in.readNBytes(dst, 0, len) != len) {
                                throw new EOFException();
                            }
                        }
                    }

                    @Override
                    public void close() {
                        // Nothing is kept open
                    }
                };
            }
        };

        /**
         * Open a file.
         *
         * @param path
         *            the file.
         * @return the reader.
         * @throws IOException
         *             if the file could not be opened.
         */
        abstract FileReader open(Path path) throws IOException;
    }

    /** Reads byte ranges of an open file, from any thread. */
    interface FileReader extends Closeable {
        /**
         * Read a byte range.
         *
         * @param pos
         *            the offset in the file.
         * @param dst
         *            the array to read into, from index 0.
         * @param len
         *            the number of bytes to read.
         * @throws IOException
         *             if the range could not be read.
         */
        void read(long pos, byte[] dst, int len) throws IOException;
    }

    /** The way to read. */
    @Param("CHANNEL")
    public Method method;

    /** What the entries hold. */
    @Param("CLASSES")
    public Content content;

    /** The number of threads that read the entries. */
    @Param("1")
    public int threads;

    /**
     * If nonzero, read a jarfile of entries that are all this size (see {@link BenchmarkFixtures#sizedPath(int)}),
     * instead of the jarfile of {@link #content}, to find the entry size at which one method overtakes another.
     */
    @Param("0")
    public int entrySize;

    /** The stored jarfile. */
    private Path archive;

    /** The offset of the central directory, which runs to the end of the file. */
    private long centralDirOffset;

    /** The length of the file. */
    private long fileLength;

    /** The shuffled local header offsets and data lengths, split into one share per thread. */
    private List<List<long[]>> shares;

    /** The total size of the entries, to check that every byte was read. */
    private long totalBytes;

    /** The worker threads. */
    private ExecutorService executor;

    /**
     * Find, and if necessary generate, the jarfile, find its entries, and start the worker threads.
     *
     * @throws IOException
     *             if the jarfile could not be generated or read.
     * @throws InterruptedException
     *             if interrupted while starting the worker threads.
     */
    @Setup(Level.Trial)
    public void setup() throws IOException, InterruptedException {
        archive = entrySize == 0 ? BenchmarkFixtures.path(content, Layout.STORED)
                : BenchmarkFixtures.sizedPath(entrySize);
        totalBytes = entrySize == 0 ? BenchmarkFixtures.totalBytes(content) : BenchmarkFixtures.SIZED_TOTAL_BYTES;
        fileLength = Files.size(archive);
        final List<long[]> entries = new ArrayList<>();
        try (var zipFile = ZipFile.builder().setPath(archive).get()) {
            for (final var entry : Collections.list(zipFile.getEntries())) {
                entries.add(new long[] { entry.getLocalHeaderOffset(), entry.getSize() });
                centralDirOffset = Math.max(centralDirOffset, entry.getDataOffset() + entry.getCompressedSize());
            }
        }
        Collections.shuffle(entries, new Random(3));
        shares = new ArrayList<>();
        for (var i = 0; i < threads; i++) {
            shares.add(entries.subList(entries.size() * i / threads, entries.size() * (i + 1) / threads));
        }
        executor = Executors.newFixedThreadPool(threads);
        final Callable<Object> noop = () -> null;
        executor.invokeAll(Collections.nCopies(threads, noop));
    }

    /** Stop the worker threads. */
    @TearDown(Level.Trial)
    public void tearDown() {
        executor.shutdownNow();
    }

    /**
     * Open the jarfile, read the central directory, read each entry's local header and data once, and close it.
     *
     * @return the number of entry bytes read.
     * @throws Exception
     *             if the jarfile could not be read, or not every byte was read.
     */
    @Benchmark
    public long readEveryEntryOnce() throws Exception {
        var bytesRead = 0L;
        try (var reader = method.open(archive)) {
            reader.read(centralDirOffset, new byte[(int) (fileLength - centralDirOffset)],
                    (int) (fileLength - centralDirOffset));
            final List<Callable<Long>> tasks = new ArrayList<>(threads);
            for (final var share : shares) {
                tasks.add(() -> {
                    var shareBytesRead = 0L;
                    final var header = new byte[30];
                    for (final var entry : share) {
                        reader.read(entry[0], header, header.length);
                        final var nameLen = (header[26] & 0xff) | (header[27] & 0xff) << 8;
                        final var extraLen = (header[28] & 0xff) | (header[29] & 0xff) << 8;
                        final var data = new byte[(int) entry[1]];
                        reader.read(entry[0] + header.length + nameLen + extraLen, data, data.length);
                        shareBytesRead += data.length;
                    }
                    return shareBytesRead;
                });
            }
            for (final var future : executor.invokeAll(tasks)) {
                try {
                    bytesRead += future.get();
                } catch (final ExecutionException e) {
                    throw e.getCause() instanceof Exception cause ? cause : e;
                }
            }
        }
        if (bytesRead != totalBytes) {
            throw new IOException("Read " + bytesRead + " bytes, expected " + totalBytes);
        }
        return bytesRead;
    }

    /**
     * Read until a buffer is full.
     *
     * @param channel
     *            the channel.
     * @param pos
     *            the offset in the file.
     * @param buffer
     *            the buffer.
     * @throws IOException
     *             if the file ends first, or could not be read.
     */
    private static void readFully(final FileChannel channel, final long pos, final ByteBuffer buffer)
            throws IOException {
        for (var p = pos; buffer.hasRemaining();) {
            final var n = channel.read(buffer, p);
            if (n < 0) {
                throw new EOFException();
            }
            p += n;
        }
    }
}
