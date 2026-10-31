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

import java.io.EOFException;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

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

/**
 * Compares ways of reading each file of a directory once by name, in random order, from one or more threads,
 * against the way classgraph-vfs reads a file of a directory root. It finds what a lookup followed by a read costs
 * over a read alone on each OS.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Threads(1)
public class DirReadMethodBenchmark {
    /** A way of reading a file by name. */
    public enum Method {
        /** {@code VfsRoot.load(name)}, on a root opened for each pass over the files. */
        CGVFS {
            @Override
            Library.Reader open(final Path dir) throws IOException {
                return Library.CGVFS.open(dir, Layout.DIR);
            }
        },

        /** Read the file's metadata, then open it, read its size, read it, and close it. */
        STAT_THEN_CHANNEL {
            @Override
            StatelessReader open(final Path dir) {
                return name -> {
                    final var path = dir.resolve(name);
                    if (!Files.readAttributes(path, BasicFileAttributes.class).isRegularFile()) {
                        throw new IOException("Not a file: " + path);
                    }
                    return readChannel(path);
                };
            }
        },

        /** Open the file, read its size, read it, and close it. */
        CHANNEL {
            @Override
            StatelessReader open(final Path dir) {
                return name -> readChannel(dir.resolve(name));
            }
        },

        /**
         * Read the file's metadata, then open it, read its size, read it, and close it, all relative to a
         * {@link SecureDirectoryStream} kept open on its directory, so that neither the metadata read nor the open
         * walks the whole path. Where there is no {@link SecureDirectoryStream} (Windows), this is
         * {@link #STAT_THEN_CHANNEL}.
         */
        SDS_STAT_THEN_CHANNEL {
            @Override
            Library.Reader open(final Path dir) throws IOException {
                return new SecureDirReader(dir, /* stat = */ true);
            }
        },

        /**
         * Open the file, read its size, read it, and close it, relative to a {@link SecureDirectoryStream} kept
         * open on its directory. Where there is no {@link SecureDirectoryStream} (Windows), this is
         * {@link #CHANNEL}.
         */
        SDS_CHANNEL {
            @Override
            Library.Reader open(final Path dir) throws IOException {
                return new SecureDirReader(dir, /* stat = */ false);
            }
        },

        /**
         * List each directory once, to find whether a name is in it, then open the file, read its size, read it,
         * and close it, without reading its metadata by name.
         */
        LISTING_THEN_CHANNEL {
            @Override
            Library.Reader open(final Path dir) {
                final Map<String, Set<String>> listings = new ConcurrentHashMap<>();
                return (StatelessReader) name -> {
                    final var slashIdx = name.lastIndexOf('/');
                    final var parent = slashIdx < 0 ? "" : name.substring(0, slashIdx);
                    final var listing = listings.computeIfAbsent(parent, p -> {
                        final var names = new HashSet<String>();
                        try (var stream = Files.newDirectoryStream(p.isEmpty() ? dir : dir.resolve(p))) {
                            for (final var child : stream) {
                                names.add(child.getFileName().toString());
                            }
                        } catch (final IOException e) {
                            throw new UncheckedIOException(e);
                        }
                        return names;
                    });
                    if (!listing.contains(name.substring(slashIdx + 1))) {
                        throw new FileNotFoundException(name);
                    }
                    return readChannel(dir.resolve(name));
                };
            }
        },

        /**
         * Open the file, read its size, and read it as classgraph-vfs reads a file of a directory root: in chunks
         * of {@link #CHUNK_SIZE}, with the last chunk read together with one extra byte, to find the end of the
         * file.
         */
        CHANNEL_CGVFS_LOOP {
            @Override
            StatelessReader open(final Path dir) {
                return name -> {
                    try (var channel = FileChannel.open(dir.resolve(name))) {
                        final var length = (int) channel.size();
                        final var bytes = new byte[length];
                        final var buffer = ByteBuffer.wrap(bytes);
                        final var buffers = new ByteBuffer[] { buffer, ByteBuffer.allocate(1) };
                        var numBytesRead = 0L;
                        do {
                            final long n;
                            if (length - numBytesRead > CHUNK_SIZE) {
                                buffer.limit((int) numBytesRead + CHUNK_SIZE);
                                n = channel.read(buffer);
                            } else {
                                buffer.limit(length);
                                n = channel.read(buffers);
                            }
                            if (n < 0L) {
                                throw new EOFException();
                            }
                            numBytesRead += n;
                        } while (numBytesRead < length);
                        return bytes;
                    }
                };
            }
        },

        /**
         * Open the file, read its size, read it in chunks of {@link #CHUNK_SIZE}, and then read one more byte, to
         * find the end of the file, as {@link Files#readAllBytes(Path)} does.
         */
        CHANNEL_CHUNKED_EOF_READ {
            @Override
            StatelessReader open(final Path dir) {
                return name -> {
                    try (var channel = FileChannel.open(dir.resolve(name))) {
                        final var bytes = new byte[(int) channel.size()];
                        final var buffer = ByteBuffer.wrap(bytes);
                        while (buffer.position() < bytes.length) {
                            buffer.limit(Math.min(bytes.length, buffer.position() + CHUNK_SIZE));
                            if (channel.read(buffer) < 0) {
                                throw new EOFException();
                            }
                        }
                        if (channel.read(ByteBuffer.allocate(1)) >= 0) {
                            throw new IOException("Longer than its size: " + name);
                        }
                        return bytes;
                    }
                };
            }
        },

        /** Open the file, read its size, and read it in chunks of {@link #CHUNK_SIZE}. */
        CHANNEL_CHUNKED {
            @Override
            StatelessReader open(final Path dir) {
                return name -> {
                    try (var channel = FileChannel.open(dir.resolve(name))) {
                        final var bytes = new byte[(int) channel.size()];
                        final var buffer = ByteBuffer.wrap(bytes);
                        while (buffer.position() < bytes.length) {
                            buffer.limit(Math.min(bytes.length, buffer.position() + CHUNK_SIZE));
                            if (channel.read(buffer) < 0) {
                                throw new EOFException();
                            }
                        }
                        return bytes;
                    }
                };
            }
        },

        /**
         * Open the file, read its size, and read it in one scattering read into the array and one extra byte, to
         * find the end of the file.
         */
        CHANNEL_SCATTER {
            @Override
            StatelessReader open(final Path dir) {
                return name -> {
                    try (var channel = FileChannel.open(dir.resolve(name))) {
                        final var bytes = new byte[(int) channel.size()];
                        final var buffer = ByteBuffer.wrap(bytes);
                        final var buffers = new ByteBuffer[] { buffer, ByteBuffer.allocate(1) };
                        while (buffer.hasRemaining()) {
                            if (channel.read(buffers) < 0) {
                                throw new EOFException();
                            }
                        }
                        return bytes;
                    }
                };
            }
        },

        /** {@link Files#readAllBytes(Path)}. */
        READ_ALL_BYTES {
            @Override
            StatelessReader open(final Path dir) {
                return name -> Files.readAllBytes(dir.resolve(name));
            }
        },

        /** {@link FileInputStream#readAllBytes()}. */
        FILE_INPUT_STREAM {
            @Override
            StatelessReader open(final Path dir) {
                return name -> {
                    try (var in = new FileInputStream(dir.resolve(name).toFile())) {
                        return in.readAllBytes();
                    }
                };
            }
        },

        /** Open a {@link RandomAccessFile}, read its length, read it, and close it. */
        RANDOM_ACCESS_FILE {
            @Override
            StatelessReader open(final Path dir) {
                return name -> {
                    try (var file = new RandomAccessFile(dir.resolve(name).toFile(), "r")) {
                        final var bytes = new byte[(int) file.length()];
                        file.readFully(bytes);
                        return bytes;
                    }
                };
            }
        };

        /**
         * Start a pass over the files of a directory.
         *
         * @param dir
         *            the directory.
         * @return the reader, which is closed at the end of the pass.
         * @throws IOException
         *             if the directory could not be opened.
         */
        abstract Library.Reader open(Path dir) throws IOException;

        /** The size of the chunks that classgraph-vfs reads a file of a directory root in. */
        static final int CHUNK_SIZE = 256 * 1024;

        /**
         * Open a file, read its size, read it, and close it.
         *
         * @param path
         *            the file.
         * @return its content.
         * @throws IOException
         *             if the file could not be read.
         */
        static byte[] readChannel(final Path path) throws IOException {
            try (var channel = FileChannel.open(path)) {
                final var bytes = new byte[(int) channel.size()];
                final var buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) {
                    if (channel.read(buffer) < 0) {
                        throw new EOFException();
                    }
                }
                return bytes;
            }
        }
    }

    /**
     * Reads each file relative to a {@link SecureDirectoryStream} opened once on its directory, or by its whole
     * path where there is no {@link SecureDirectoryStream}.
     */
    private static final class SecureDirReader implements Library.Reader {
        /** The directory. */
        private final Path dir;

        /** Whether to read a file's metadata before opening it. */
        private final boolean stat;

        /** The stream on the directory, or null where there is no {@link SecureDirectoryStream}. */
        private final SecureDirectoryStream<Path> rootStream;

        /** The streams on the directories under the directory, by their path relative to it. */
        private final Map<String, SecureDirectoryStream<Path>> subdirStreams = new ConcurrentHashMap<>();

        /**
         * Open the directory.
         *
         * @param dir
         *            the directory.
         * @param stat
         *            whether to read a file's metadata before opening it.
         * @throws IOException
         *             if the directory could not be opened.
         */
        SecureDirReader(final Path dir, final boolean stat) throws IOException {
            this.dir = dir;
            this.stat = stat;
            final var stream = Files.newDirectoryStream(dir);
            if (stream instanceof final SecureDirectoryStream<Path> secureStream) {
                rootStream = secureStream;
            } else {
                stream.close();
                rootStream = null;
            }
        }

        @Override
        public byte[] read(final String name) throws IOException {
            if (rootStream == null) {
                final var path = dir.resolve(name);
                if (stat && !Files.readAttributes(path, BasicFileAttributes.class).isRegularFile()) {
                    throw new IOException("Not a file: " + path);
                }
                return Method.readChannel(path);
            }
            final var slashIdx = name.lastIndexOf('/');
            final SecureDirectoryStream<Path> stream;
            if (slashIdx < 0) {
                stream = rootStream;
            } else {
                stream = subdirStreams.computeIfAbsent(name.substring(0, slashIdx), p -> {
                    try {
                        return rootStream.newDirectoryStream(dir.getFileSystem().getPath(p));
                    } catch (final IOException e) {
                        throw new UncheckedIOException(e);
                    }
                });
            }
            final var fileName = dir.getFileSystem().getPath(name.substring(slashIdx + 1));
            if (stat && !stream.getFileAttributeView(fileName, BasicFileAttributeView.class).readAttributes()
                    .isRegularFile()) {
                throw new IOException("Not a file: " + name);
            }
            try (var channel = stream.newByteChannel(fileName, Set.of(StandardOpenOption.READ))) {
                final var bytes = new byte[(int) channel.size()];
                final var buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) {
                    if (channel.read(buffer) < 0) {
                        throw new EOFException();
                    }
                }
                return bytes;
            }
        }

        @Override
        public void close() throws IOException {
            for (final var stream : subdirStreams.values()) {
                stream.close();
            }
            if (rootStream != null) {
                rootStream.close();
            }
        }
    }

    /** A reader that keeps nothing open between reads. */
    interface StatelessReader extends Library.Reader {
        @Override
        default void close() {
            // Nothing is kept open
        }
    }

    /** The way to read. */
    @Param("CGVFS")
    public Method method;

    /** What the files hold. */
    @Param("CLASSES")
    public Content content;

    /** The number of threads that read the files. */
    @Param("1")
    public int threads;

    /** The directory. */
    private Path dir;

    /** The shuffled file names, split into one share per thread. */
    private List<List<String>> shares;

    /** The total size of the files, to check that every byte was read. */
    private long totalBytes;

    /** The worker threads. */
    private ExecutorService executor;

    /**
     * Find, and if necessary generate, the directory, and start the worker threads.
     *
     * @throws IOException
     *             if the directory could not be generated.
     * @throws InterruptedException
     *             if interrupted while starting the worker threads.
     */
    @Setup(Level.Trial)
    public void setup() throws IOException, InterruptedException {
        dir = BenchmarkFixtures.path(content, Layout.DIR);
        totalBytes = BenchmarkFixtures.totalBytes(content);
        final List<String> names = new ArrayList<>(BenchmarkFixtures.entryNames(content));
        Collections.shuffle(names, new Random(3));
        shares = new ArrayList<>();
        for (var i = 0; i < threads; i++) {
            shares.add(names.subList(names.size() * i / threads, names.size() * (i + 1) / threads));
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
     * Read every file once.
     *
     * @return the number of bytes read.
     * @throws Exception
     *             if a file could not be read, or not every byte was read.
     */
    @Benchmark
    public long readEveryFileOnce() throws Exception {
        var bytesRead = 0L;
        try (var reader = method.open(dir)) {
            final List<Callable<Long>> tasks = new ArrayList<>(threads);
            for (final var share : shares) {
                tasks.add(() -> {
                    var shareBytesRead = 0L;
                    for (final var name : share) {
                        shareBytesRead += reader.read(name).length;
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
}
