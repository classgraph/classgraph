package io.github.classgraph;

import static io.github.classgraph.vfs.VfsSpec.DEFAULT_MAX_BUFFERED_JAR_RAM_SIZE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RunnableFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A classpath element that is named twice, once on the classpath and once by the {@code Class-Path} manifest entry
 * of another classpath element, is opened once, by whichever thread reaches it first, and the other thread waits
 * for it. If the thread opening it makes no progress, the waiting thread must give up after the worker timeout, as
 * every other wait in a scan does, rather than wait forever. If the thread opening it makes progress, such as
 * reading the parts of a slow download as they arrive, the waiting thread keeps waiting, however long that takes.
 */
public class StuckClasspathElementTimeoutTest {
    /**
     * Make a directory classpath element whose manifest has a {@code Class-Path} entry.
     *
     * @param dir
     *            the directory.
     * @param classPath
     *            the value of the {@code Class-Path} entry.
     * @throws IOException
     *             if the directory could not be made.
     */
    private static void makeDirWithClassPath(final Path dir, final String classPath) throws IOException {
        final var manifest = dir.resolve("META-INF/MANIFEST.MF");
        Files.createDirectories(manifest.getParent());
        Files.writeString(manifest, "Manifest-Version: 1.0\nClass-Path: " + classPath + "\n\n");
    }

    /**
     * Get whether a classpath element location names the given directory.
     *
     * @param location
     *            the location.
     * @param dirName
     *            the name of the directory.
     * @return true if the last segment of the location is the directory name.
     */
    private static boolean names(final String location, final String dirName) {
        final var trimmed = location.endsWith("/") ? location.substring(0, location.length() - 1) : location;
        return trimmed.endsWith("/" + dirName) || trimmed.endsWith("\\" + dirName);
    }

    /**
     * The calling thread waits for a worker thread that is stuck opening a classpath element both of them need, and
     * the scan fails with the worker timeout rather than hanging.
     *
     * <p>
     * The worker thread is made to get stuck by a classpath element filter that blocks when it is asked about a
     * child classpath element of the stuck classpath element, which the worker thread asks while opening it. The
     * filter stands in for anything that can block while a classpath element is opened, such as a read from a
     * network filesystem.
     *
     * <p>
     * Once the calling thread has timed out, the stuck worker thread is only polled, not waited for for a second
     * timeout, since the scan fails whatever the worker thread does. The executor records how long each wait for a
     * worker thread was allowed to take, so this is checked without timing the scan.
     *
     * @param tempDir
     *            the directory to make the classpath elements in.
     * @throws Exception
     *             if the classpath elements could not be made.
     */
    @Test
    public void aThreadWaitingForAStuckClasspathElementTimesOut(@TempDir final Path tempDir) throws Exception {
        // "parent" names "stuck", which names "other". The calling thread opens "parent", and the worker thread
        // opens "stuck", and blocks in the filter when it is asked about "other". The calling thread then finds
        // that "parent" names "stuck", and waits for the worker thread to finish opening it
        makeDirWithClassPath(tempDir.resolve("parent"), "stuck/");
        makeDirWithClassPath(tempDir.resolve("stuck"), "other/");
        Files.createDirectories(tempDir.resolve("other"));
        // Set when the calling thread submits the worker thread, which it does after the classpath elements named
        // directly, including "stuck", have been tested against the filter, and before it takes its first work unit
        final var workQueueStarted = new AtomicBoolean();
        final var parentOpening = new CountDownLatch(1);
        final var workerOpeningStuck = new CountDownLatch(1);
        final var releaseWorker = new CountDownLatch(1);
        // The timeout of each timed wait for a worker thread to finish, in nanoseconds
        final var workerWaitNanos = new ConcurrentLinkedQueue<Long>();

        // Hold back the worker thread until the calling thread has taken the first work unit, "parent"
        final var executorService = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>()) {
            @Override
            public void execute(final Runnable command) {
                workQueueStarted.set(true);
                super.execute(command);
            }

            @Override
            protected <T> RunnableFuture<T> newTaskFor(final Callable<T> callable) {
                return new FutureTask<>(callable) {
                    @Override
                    public T get(final long timeout, final TimeUnit unit)
                            throws InterruptedException, ExecutionException, TimeoutException {
                        workerWaitNanos.add(unit.toNanos(timeout));
                        return super.get(timeout, unit);
                    }
                };
            }

            @Override
            protected void beforeExecute(final Thread t, final Runnable r) {
                try {
                    parentOpening.await(30, TimeUnit.SECONDS);
                } catch (final InterruptedException e) {
                    t.interrupt();
                }
            }
        };
        try {
            final var thrown = assertTimeoutPreemptively(Duration.ofSeconds(20),
                    () -> catchThrowable(
                            () -> new ClassGraph()
                                    .enableClasspathEntries(tempDir.resolve("parent").toString(),
                                            tempDir.resolve("stuck").toString())
                                    .filterClasspathElements(location -> {
                                        try {
                                            if (names(location, "stuck") && workQueueStarted.get()) {
                                                // The calling thread is opening "parent". Hold it there until the
                                                // worker thread has started opening "stuck"
                                                parentOpening.countDown();
                                                workerOpeningStuck.await(30, TimeUnit.SECONDS);
                                            } else if (names(location, "other")) {
                                                // The worker thread is stuck opening "stuck"
                                                workerOpeningStuck.countDown();
                                                releaseWorker.await(30, TimeUnit.SECONDS);
                                            }
                                        } catch (final InterruptedException e) {
                                            Thread.currentThread().interrupt();
                                        }
                                        return true;
                                    }).setWorkerTimeout(Duration.ofMillis(500)).scan(executorService, 2).close()));
            assertThat(thrown).isInstanceOf(ClassGraphException.class);
            assertThat(thrown.getCause()).isInstanceOf(IllegalStateException.class)
                    .hasMessageStartingWith("Timed out after 500ms waiting for another thread to open the "
                            + "classpath element " + tempDir.resolve("stuck").toRealPath());
            assertThat(workerWaitNanos).isNotEmpty().containsOnly(0L);
        } finally {
            parentOpening.countDown();
            workerOpeningStuck.countDown();
            releaseWorker.countDown();
            executorService.shutdown();
            assertThat(executorService.awaitTermination(20, TimeUnit.SECONDS)).isTrue();
        }
    }

    /** The number of parts that {@link SlowJarServer} sends a slow jarfile in. */
    private static final int NUM_SLOW_PARTS = 20;

    /** The time between two parts of a slow jarfile, in milliseconds. */
    private static final long SLOW_PART_INTERVAL_MILLIS = 100L;

    /**
     * The worker timeout of the tests of a slow jarfile, in milliseconds. A slow jarfile takes four times this long
     * to download, and the time between two parts of it is a fifth of this.
     */
    private static final long SLOW_WORKER_TIMEOUT_MILLIS = 500L;

    /**
     * An HTTP server on the loopback interface that serves a jarfile named {@code slow.jar}, and any number of
     * other jarfiles. The slow jarfile is sent in {@link #NUM_SLOW_PARTS} parts, {@link #SLOW_PART_INTERVAL_MILLIS}
     * apart, which stands in for a slow server, or a network filesystem that is slow to read. If there are no bytes
     * for the slow jarfile, the server sends the headers of the response, and then nothing, until it is closed. The
     * download then never finishes, but makes no progress, and the read timeout of the download, 5 seconds, is ten
     * times the worker timeout of the tests. The other jarfiles are sent all at once.
     */
    private static final class SlowJarServer implements Closeable {
        /** The socket the server listens on. */
        private final ServerSocket serverSocket;

        /** The bytes of the slow jarfile, or null to stall after sending the headers. */
        private final byte @Nullable [] slowJarBytes;

        /** The other jarfiles, by name. */
        private final Map<String, byte[]> otherJars;

        /** Counted down once the server has received a request for the slow jarfile. */
        private final CountDownLatch slowJarRequested = new CountDownLatch(1);

        /** Counted down when the server is closed, to stop sending the response. */
        private final CountDownLatch closed = new CountDownLatch(1);

        /**
         * Constructor. Starts the server.
         *
         * @param slowJarBytes
         *            the bytes of the slow jarfile, or null to stall after sending the headers.
         * @param otherJars
         *            the other jarfiles, by name.
         * @throws IOException
         *             if the server socket could not be opened.
         */
        SlowJarServer(final byte @Nullable [] slowJarBytes, final Map<String, byte[]> otherJars)
                throws IOException {
            this.slowJarBytes = slowJarBytes;
            this.otherJars = otherJars;
            serverSocket = new ServerSocket(0, /* backlog = */ 50, InetAddress.getLoopbackAddress());
            final var thread = new Thread(this::serve);
            thread.setDaemon(true);
            thread.start();
        }

        /** Accept connections until the server is closed, and answer each one on a thread of its own. */
        private void serve() {
            while (!serverSocket.isClosed()) {
                try {
                    final var socket = serverSocket.accept();
                    final var thread = new Thread(() -> answer(socket));
                    thread.setDaemon(true);
                    thread.start();
                } catch (final IOException e) {
                    // The server socket was closed at the end of the test
                }
            }
        }

        /**
         * Answer a request.
         *
         * @param socket
         *            the socket the request was received on.
         */
        private void answer(final Socket socket) {
            try (socket) {
                final var name = readRequest(socket.getInputStream());
                final var outputStream = socket.getOutputStream();
                if (!"slow.jar".equals(name)) {
                    final var bytes = otherJars.get(name);
                    if (bytes == null) {
                        outputStream
                                .write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                                        .getBytes(StandardCharsets.ISO_8859_1));
                    } else {
                        outputStream.write(headers(bytes.length));
                        outputStream.write(bytes);
                    }
                    return;
                }
                slowJarRequested.countDown();
                final var bytes = slowJarBytes;
                if (bytes == null) {
                    outputStream.write(headers(1000000));
                    outputStream.flush();
                    closed.await();
                    return;
                }
                outputStream.write(headers(bytes.length));
                for (var i = 0; i < NUM_SLOW_PARTS; i++) {
                    if (closed.await(SLOW_PART_INTERVAL_MILLIS, TimeUnit.MILLISECONDS)) {
                        return;
                    }
                    final var start = (int) ((long) bytes.length * i / NUM_SLOW_PARTS);
                    final var end = (int) ((long) bytes.length * (i + 1) / NUM_SLOW_PARTS);
                    outputStream.write(bytes, start, end - start);
                    outputStream.flush();
                }
            } catch (final IOException e) {
                // The client hung up
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        /**
         * The headers of a response.
         *
         * @param contentLength
         *            the length of the content.
         * @return the headers.
         */
        private static byte[] headers(final int contentLength) {
            return ("HTTP/1.1 200 OK\r\nContent-Length: " + contentLength + "\r\nConnection: close\r\n\r\n")
                    .getBytes(StandardCharsets.ISO_8859_1);
        }

        /**
         * Read a request, up to the blank line that ends its headers.
         *
         * @param inputStream
         *            the stream to read the request from.
         * @return the name of the jarfile requested, the last segment of the path of the request.
         * @throws IOException
         *             if the request could not be read.
         */
        private static String readRequest(final InputStream inputStream) throws IOException {
            final var request = new StringBuilder();
            var numConsecutiveNewlines = 0;
            for (int nextByte; numConsecutiveNewlines < 2 && (nextByte = inputStream.read()) != -1;) {
                request.append((char) nextByte);
                if (nextByte == '\n') {
                    numConsecutiveNewlines++;
                } else if (nextByte != '\r') {
                    numConsecutiveNewlines = 0;
                }
            }
            // The first line is "GET /name HTTP/1.1"
            final var path = request.toString().split(" ", 3)[1];
            return path.substring(path.lastIndexOf('/') + 1);
        }

        /**
         * Wait until the server has received a request for the slow jarfile.
         *
         * @throws InterruptedException
         *             if the thread was interrupted while waiting.
         */
        void awaitSlowJarRequest() throws InterruptedException {
            slowJarRequested.await(30, TimeUnit.SECONDS);
        }

        /**
         * The URL of a jarfile served by this server.
         *
         * @param name
         *            the name of the jarfile.
         * @return the URL.
         */
        String jarURL(final String name) {
            return "http://" + serverSocket.getInetAddress().getHostAddress() + ":" + serverSocket.getLocalPort()
                    + "/" + name;
        }

        @Override
        public void close() throws IOException {
            closed.countDown();
            serverSocket.close();
        }
    }

    /**
     * Make a jarfile.
     *
     * @param classPath
     *            the value of the {@code Class-Path} entry of its manifest, or null for no manifest.
     * @param dataSize
     *            the size of a file {@code pkgroot/data.bin} of random bytes to put in the jarfile, stored without
     *            compression, or 0 for none.
     * @return the bytes of the jarfile.
     * @throws IOException
     *             if the jarfile could not be made.
     */
    private static byte[] makeJar(final @Nullable String classPath, final int dataSize) throws IOException {
        final var byteArrayOutputStream = new ByteArrayOutputStream();
        try (var jarOutputStream = new JarOutputStream(byteArrayOutputStream)) {
            if (classPath != null) {
                jarOutputStream.putNextEntry(new JarEntry("META-INF/MANIFEST.MF"));
                jarOutputStream.write(("Manifest-Version: 1.0\nClass-Path: " + classPath + "\n\n")
                        .getBytes(StandardCharsets.UTF_8));
                jarOutputStream.closeEntry();
            }
            if (dataSize > 0) {
                final var data = new byte[dataSize];
                new Random(1).nextBytes(data);
                final var crc = new CRC32();
                crc.update(data);
                final var entry = new JarEntry("pkgroot/data.bin");
                entry.setMethod(ZipEntry.STORED);
                entry.setSize(dataSize);
                entry.setCrc(crc.getValue());
                jarOutputStream.putNextEntry(entry);
                jarOutputStream.write(data);
                jarOutputStream.closeEntry();
            }
        }
        return byteArrayOutputStream.toByteArray();
    }

    /**
     * Make an executor with one thread, which holds back its thread until a latch is counted down.
     *
     * @param release
     *            the latch.
     * @return the executor.
     */
    private static ThreadPoolExecutor newHeldExecutor(final CountDownLatch release) {
        return new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>()) {
            @Override
            protected void beforeExecute(final Thread t, final Runnable r) {
                try {
                    release.await(30, TimeUnit.SECONDS);
                } catch (final InterruptedException e) {
                    t.interrupt();
                }
            }
        };
    }

    /**
     * Shut down an executor, and check that its thread finished.
     *
     * @param executorService
     *            the executor.
     * @throws InterruptedException
     *             if the thread was interrupted while waiting.
     */
    private static void shutDown(final ThreadPoolExecutor executorService) throws InterruptedException {
        executorService.shutdown();
        assertThat(executorService.awaitTermination(20, TimeUnit.SECONDS)).isTrue();
    }

    /**
     * Check that a scan did not throw, and if it did, fail with what it threw as the cause, so that the report of
     * the failure says why the scan failed.
     *
     * @param thrown
     *            what the scan threw, or null if it did not throw.
     */
    private static void assertScanSucceeded(final @Nullable Throwable thrown) {
        if (thrown != null) {
            throw new AssertionError("The scan failed", thrown);
        }
    }

    /**
     * Scan a classpath in which the calling thread opens a directory "parent", while the worker thread downloads
     * {@code slow.jar}, and then waits for the worker thread. The calling thread is held in the filter when it is
     * asked about "other", the classpath element that "parent" names, until the worker thread has started
     * downloading {@code slow.jar}.
     *
     * @param tempDir
     *            the directory to make "parent" and "other" in.
     * @param server
     *            the server of {@code slow.jar}.
     * @param maxBufferedJarRAMSize
     *            the largest jarfile that is downloaded into memory rather than to a temporary file.
     * @param classpathAfterParent
     *            the classpath elements after "parent".
     * @return what the scan threw, or null if it did not throw.
     * @throws Exception
     *             if the classpath elements could not be made.
     */
    private static @Nullable Throwable scanWhileDownloadingSlowJar(final Path tempDir, final SlowJarServer server,
            final int maxBufferedJarRAMSize, final String... classpathAfterParent) throws Exception {
        makeDirWithClassPath(tempDir.resolve("parent"), "other/");
        Files.createDirectories(tempDir.resolve("other"));
        final var parentOpening = new CountDownLatch(1);

        // Hold back the worker thread until the calling thread has taken the first work unit, "parent"
        final var executorService = newHeldExecutor(parentOpening);
        final var classpath = new ArrayList<String>();
        classpath.add(tempDir.resolve("parent").toString());
        Collections.addAll(classpath, classpathAfterParent);
        try {
            return assertTimeoutPreemptively(Duration.ofSeconds(20),
                    () -> catchThrowable(() -> new ClassGraph().enableRemoteJarScanning()
                            .enableClasspathEntries(classpath).setMaxBufferedJarRAMSize(maxBufferedJarRAMSize)
                            .filterClasspathElements(location -> {
                                if (names(location, "other")) {
                                    parentOpening.countDown();
                                    try {
                                        server.awaitSlowJarRequest();
                                    } catch (final InterruptedException e) {
                                        Thread.currentThread().interrupt();
                                    }
                                }
                                return true;
                            }).setWorkerTimeout(Duration.ofMillis(SLOW_WORKER_TIMEOUT_MILLIS))
                            .scan(executorService, 2).close()));
        } finally {
            parentOpening.countDown();
            shutDown(executorService);
        }
    }

    /**
     * Two classpath elements that are different, but that are read from the same jarfile -- here the jarfile, and a
     * package root within it -- are opened by two threads, and the jarfile is downloaded once, by whichever thread
     * reaches it first. If that thread makes no progress downloading it, the other thread must give up after the
     * worker timeout, rather than wait forever, since the waiting thread may be the calling thread, which is the
     * thread that applies the worker timeout to the other threads.
     *
     * @param tempDir
     *            the directory to make the classpath elements in.
     * @throws Exception
     *             if the classpath elements could not be made.
     */
    @Test
    public void aThreadWaitingForAnotherThreadToOpenTheSameJarfileTimesOut(@TempDir final Path tempDir)
            throws Exception {
        // The calling thread then opens the package root of the jarfile, and waits for the worker thread to finish
        // downloading it
        try (var server = new SlowJarServer(/* slowJarBytes = */ null, Map.of())) {
            final var jarURL = server.jarURL("slow.jar");
            final var thrown = scanWhileDownloadingSlowJar(tempDir, server, DEFAULT_MAX_BUFFERED_JAR_RAM_SIZE,
                    jarURL, jarURL + "!/pkgroot");
            assertThat(thrown).isInstanceOf(ClassGraphException.class);
            assertThat(thrown.getCause()).isInstanceOf(IOException.class)
                    .hasMessageStartingWith("Timed out after " + SLOW_WORKER_TIMEOUT_MILLIS
                            + "ms waiting for another thread to open " + jarURL + ".")
                    .hasMessageContaining("ClassGraph#setWorkerTimeout(Duration)");
        }
    }

    /**
     * The calling thread waits for the worker thread to finish a work unit that takes four times the worker
     * timeout, downloading a jarfile that arrives in parts a fifth of the worker timeout apart. The worker thread
     * makes progress all the time, so the scan does not time out.
     *
     * @param tempDir
     *            the directory to make the classpath elements in.
     * @throws Exception
     *             if the classpath elements could not be made.
     */
    @Test
    public void aThreadWaitingForASlowButProgressingWorkerDoesNotTimeOut(@TempDir final Path tempDir)
            throws Exception {
        try (var server = new SlowJarServer(makeJar(/* classPath = */ null, 100000), Map.of())) {
            // The calling thread has nothing left to do once it has opened "parent" and "other", so it waits for
            // the worker thread in the work queue
            assertScanSucceeded(scanWhileDownloadingSlowJar(tempDir, server, DEFAULT_MAX_BUFFERED_JAR_RAM_SIZE,
                    server.jarURL("slow.jar")));
        }
    }

    /**
     * As {@link #aThreadWaitingForASlowButProgressingWorkerDoesNotTimeOut}, but the jarfile is larger than
     * {@link ClassGraph#setMaxBufferedJarRAMSize(int)}, so it is downloaded to a temporary file rather than into
     * memory, and the bytes written to that file are the progress.
     *
     * @param tempDir
     *            the directory to make the classpath elements in.
     * @throws Exception
     *             if the classpath elements could not be made.
     */
    @Test
    public void aThreadWaitingForASlowJarfileDownloadedToDiskDoesNotTimeOut(@TempDir final Path tempDir)
            throws Exception {
        try (var server = new SlowJarServer(makeJar(/* classPath = */ null, 100000), Map.of())) {
            assertScanSucceeded(scanWhileDownloadingSlowJar(tempDir, server, /* maxBufferedJarRAMSize = */ 10000,
                    server.jarURL("slow.jar")));
        }
    }

    /**
     * Two classpath elements that are read from the same jarfile are opened by two threads, as in
     * {@link #aThreadWaitingForAnotherThreadToOpenTheSameJarfileTimesOut}, but the jarfile arrives in parts a fifth
     * of the worker timeout apart, so the thread that waits for it to be downloaded does not time out.
     *
     * @param tempDir
     *            the directory to make the classpath elements in.
     * @throws Exception
     *             if the classpath elements could not be made.
     */
    @Test
    public void aThreadWaitingForAnotherThreadToDownloadTheSameJarfileDoesNotTimeOut(@TempDir final Path tempDir)
            throws Exception {
        try (var server = new SlowJarServer(makeJar(/* classPath = */ null, 100000), Map.of())) {
            final var jarURL = server.jarURL("slow.jar");
            assertScanSucceeded(scanWhileDownloadingSlowJar(tempDir, server, DEFAULT_MAX_BUFFERED_JAR_RAM_SIZE,
                    jarURL, jarURL + "!/pkgroot"));
        }
    }

    /**
     * The calling thread downloads {@code parent.jar}, whose manifest names {@code slow.jar}, which the worker
     * thread is already downloading, and waits for the worker thread to finish opening it, as in
     * {@link #aThreadWaitingForAStuckClasspathElementTimesOut}. The jarfile arrives in parts a fifth of the worker
     * timeout apart, so the calling thread does not time out.
     *
     * @throws Exception
     *             if the jarfiles could not be made.
     */
    @Test
    public void aThreadWaitingForAProgressingClasspathElementDoesNotTimeOut() throws Exception {
        // Set when the calling thread submits the worker thread, which it does after the classpath elements named
        // directly, including "slow.jar", have been tested against the filter
        final var workQueueStarted = new AtomicBoolean();
        final var parentOpening = new CountDownLatch(1);
        final var executorService = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>()) {
            @Override
            public void execute(final Runnable command) {
                workQueueStarted.set(true);
                super.execute(command);
            }

            @Override
            protected void beforeExecute(final Thread t, final Runnable r) {
                try {
                    parentOpening.await(30, TimeUnit.SECONDS);
                } catch (final InterruptedException e) {
                    t.interrupt();
                }
            }
        };
        try (var server = new SlowJarServer(makeJar(/* classPath = */ null, 100000),
                Map.of("parent.jar", makeJar(/* classPath = */ "slow.jar", /* dataSize = */ 0)))) {
            final var thrown = assertTimeoutPreemptively(Duration.ofSeconds(20),
                    () -> catchThrowable(() -> new ClassGraph().enableRemoteJarScanning()
                            .enableClasspathEntries(server.jarURL("parent.jar"), server.jarURL("slow.jar"))
                            .filterClasspathElements(location -> {
                                if (names(location, "slow.jar") && workQueueStarted.get()) {
                                    // The calling thread is opening "parent.jar". Hold it there until the worker
                                    // thread has started downloading "slow.jar"
                                    parentOpening.countDown();
                                    try {
                                        server.awaitSlowJarRequest();
                                    } catch (final InterruptedException e) {
                                        Thread.currentThread().interrupt();
                                    }
                                }
                                return true;
                            }).setWorkerTimeout(Duration.ofMillis(SLOW_WORKER_TIMEOUT_MILLIS))
                            .scan(executorService, 2).close()));
            assertScanSucceeded(thrown);
        } finally {
            parentOpening.countDown();
            shutDown(executorService);
        }
    }
}
