package io.github.classgraph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
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

import io.github.classgraph.vfs.VfsSpec;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A classpath element that is named twice, once on the classpath and once by the {@code Class-Path} manifest entry
 * of another classpath element, is opened once, by whichever thread reaches it first, and the other thread waits
 * for it. If the thread opening it never finishes, the waiting thread must give up once the worker timeout has
 * passed with no progress, as every other wait in a scan does, rather than wait forever. If the thread opening it
 * is slow but still making progress, the waiting thread must keep waiting.
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
                } catch (final InterruptedException _) {
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
                                        } catch (final InterruptedException _) {
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

    /**
     * An HTTP server on the loopback interface that answers a request for a jarfile. Given a body, it sends the
     * body in 20 parts, 100 ms apart, which stands in for a slow download that is still making progress. Given no
     * body, it sends the headers and then nothing until it is closed, which stands in for a server or a network
     * filesystem that has stopped sending: the thread downloading the jarfile is stuck until the read timeout of
     * the download, which is longer than the worker timeouts used here. (A server that sends a byte now and then is
     * not stuck, since every byte that arrives counts as progress.)
     */
    private static final class SlowJarServer implements Closeable {
        /** The socket the server listens on. */
        private final ServerSocket serverSocket;

        /** The body to send, or null to send none. */
        private final byte[] body;

        /** Counted down once the server has received a request. */
        private final CountDownLatch requestReceived = new CountDownLatch(1);

        /** Counted down when the server is closed, to stop sending the response. */
        private final CountDownLatch closed = new CountDownLatch(1);

        /**
         * Constructor. Starts the server.
         *
         * @param body
         *            the body to send, or null to send none.
         * @throws IOException
         *             if the server socket could not be opened.
         */
        SlowJarServer(final byte[] body) throws IOException {
            this.body = body;
            serverSocket = new ServerSocket(0, /* backlog = */ 50, InetAddress.getLoopbackAddress());
            final var thread = new Thread(this::serve);
            thread.setDaemon(true);
            thread.start();
        }

        /** Answer requests until the server is closed. */
        private void serve() {
            while (!serverSocket.isClosed()) {
                try (var socket = serverSocket.accept()) {
                    readRequest(socket.getInputStream());
                    requestReceived.countDown();
                    final var outputStream = socket.getOutputStream();
                    outputStream
                            .write(("HTTP/1.1 200 OK\r\nContent-Length: " + (body == null ? 1000000 : body.length)
                                    + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
                    outputStream.flush();
                    if (body == null) {
                        closed.await();
                    } else {
                        final var partLength = (body.length + 19) / 20;
                        for (var offset = 0; offset < body.length
                                && !closed.await(100, TimeUnit.MILLISECONDS); offset += partLength) {
                            outputStream.write(body, offset, Math.min(partLength, body.length - offset));
                            outputStream.flush();
                        }
                    }
                } catch (final IOException _) {
                    // The server socket was closed at the end of the test, or the client hung up
                } catch (final InterruptedException _) {
                    return;
                }
            }
        }

        /**
         * Read a request, up to the blank line that ends its headers.
         *
         * @param inputStream
         *            the stream to read the request from.
         * @throws IOException
         *             if the request could not be read.
         */
        private static void readRequest(final InputStream inputStream) throws IOException {
            var numConsecutiveNewlines = 0;
            for (int nextByte; numConsecutiveNewlines < 2 && (nextByte = inputStream.read()) != -1;) {
                if (nextByte == '\n') {
                    numConsecutiveNewlines++;
                } else if (nextByte != '\r') {
                    numConsecutiveNewlines = 0;
                }
            }
        }

        /**
         * Wait until the server has received a request.
         *
         * @throws InterruptedException
         *             if the thread was interrupted while waiting.
         */
        void awaitRequest() throws InterruptedException {
            requestReceived.await(30, TimeUnit.SECONDS);
        }

        /**
         * The URL of the jarfile served by this server.
         *
         * @return the URL.
         */
        String jarURL() {
            return "http://" + serverSocket.getInetAddress().getHostAddress() + ":" + serverSocket.getLocalPort()
                    + "/stuck.jar";
        }

        @Override
        public void close() throws IOException {
            closed.countDown();
            serverSocket.close();
        }
    }

    /**
     * Two classpath elements that are different, but that are read from the same jarfile -- here the jarfile, and a
     * package root within it -- are opened by two threads, and the jarfile is downloaded once, by whichever thread
     * reaches it first. If that thread never finishes downloading it, the other thread must give up after the
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
        // The calling thread opens "parent", and is held in the filter when it is asked about "other", the
        // classpath element that "parent" names, until the worker thread has started downloading the jarfile. The
        // calling thread then opens the package root of the jarfile, and waits for the worker thread to finish
        // downloading it
        makeDirWithClassPath(tempDir.resolve("parent"), "other/");
        Files.createDirectories(tempDir.resolve("other"));
        final var parentOpening = new CountDownLatch(1);

        // Hold back the worker thread until the calling thread has taken the first work unit, "parent"
        final var executorService = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>()) {
            @Override
            protected void beforeExecute(final Thread t, final Runnable r) {
                try {
                    parentOpening.await(30, TimeUnit.SECONDS);
                } catch (final InterruptedException _) {
                    t.interrupt();
                }
            }
        };
        try (var server = new SlowJarServer(/* body = */ null)) {
            final var jarURL = server.jarURL();
            final var thrown = assertTimeoutPreemptively(Duration.ofSeconds(20),
                    () -> catchThrowable(() -> new ClassGraph().enableRemoteJarScanning()
                            .enableClasspathEntries(tempDir.resolve("parent").toString(), jarURL,
                                    jarURL + "!/pkgroot")
                            .filterClasspathElements(location -> {
                                if (names(location, "other")) {
                                    parentOpening.countDown();
                                    try {
                                        server.awaitRequest();
                                    } catch (final InterruptedException _) {
                                        Thread.currentThread().interrupt();
                                    }
                                }
                                return true;
                            }).setWorkerTimeout(Duration.ofMillis(500)).scan(executorService, 2).close()));
            assertThat(thrown).isInstanceOf(ClassGraphException.class);
            assertThat(thrown.getCause()).isInstanceOf(IOException.class)
                    .hasMessageStartingWith(
                            "Timed out after 500ms waiting for another thread to open " + jarURL + ".")
                    .hasMessageContaining("ClassGraph#setWorkerTimeout(Duration)");
        } finally {
            parentOpening.countDown();
            executorService.shutdown();
            assertThat(executorService.awaitTermination(20, TimeUnit.SECONDS)).isTrue();
        }
    }

    /**
     * A jarfile holding one resource, {@code pkgroot/resource.txt}.
     *
     * @return the jarfile's bytes.
     * @throws IOException
     *             if the jarfile could not be written.
     */
    private static byte[] jarWithOneResource() throws IOException {
        final var bytes = new ByteArrayOutputStream();
        try (var jarOut = new JarOutputStream(bytes)) {
            jarOut.putNextEntry(new JarEntry("pkgroot/resource.txt"));
            jarOut.write(new byte[100000]);
            jarOut.closeEntry();
        }
        return bytes.toByteArray();
    }

    /**
     * Scan a directory "parent" and the given remote classpath entries with a worker timeout of one second, while
     * the server sends the remote jarfile over about two seconds. As in
     * {@link #aThreadWaitingForAnotherThreadToOpenTheSameJarfileTimesOut(Path)}, the calling thread opens "parent"
     * and is held there until the worker thread has started downloading the jarfile, and then waits for the worker
     * thread. The download makes progress the whole time, so the scan must not time out, however long the download
     * takes in all.
     *
     * @param tempDir
     *            the directory to make "parent" in.
     * @param server
     *            the server.
     * @param maxBufferedJarRAMSize
     *            the largest jarfile to download into RAM rather than to a temporary file.
     * @param remoteClasspathEntries
     *            the remote classpath entries.
     * @throws Exception
     *             if the scan failed.
     */
    private static void scanWhileTheDownloadMakesProgress(final Path tempDir, final SlowJarServer server,
            final int maxBufferedJarRAMSize, final String... remoteClasspathEntries) throws Exception {
        makeDirWithClassPath(tempDir.resolve("parent"), "other/");
        Files.createDirectories(tempDir.resolve("other"));
        final List<Object> classpath = new ArrayList<>();
        classpath.add(tempDir.resolve("parent").toString());
        classpath.addAll(Arrays.asList(remoteClasspathEntries));
        final var parentOpening = new CountDownLatch(1);

        // Hold back the worker thread until the calling thread has taken the first work unit, "parent"
        final var executorService = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>()) {
            @Override
            protected void beforeExecute(final Thread t, final Runnable r) {
                try {
                    parentOpening.await(30, TimeUnit.SECONDS);
                } catch (final InterruptedException _) {
                    t.interrupt();
                }
            }
        };
        try (server) {
            assertTimeoutPreemptively(Duration.ofSeconds(20), () -> {
                try (var scanResult = new ClassGraph().enableRemoteJarScanning().enableClasspathEntries(classpath)
                        .filterClasspathElements(location -> {
                            if (names(location, "other")) {
                                parentOpening.countDown();
                                try {
                                    server.awaitRequest();
                                } catch (final InterruptedException _) {
                                    Thread.currentThread().interrupt();
                                }
                            }
                            return true;
                        }).setMaxBufferedJarRAMSize(maxBufferedJarRAMSize).setWorkerTimeout(Duration.ofSeconds(1))
                        .scan(executorService, 2)) {
                    assertThat(scanResult.getResourcesWithLeafName("resource.txt")).isNotEmpty();
                }
            });
        } finally {
            parentOpening.countDown();
            executorService.shutdown();
            assertThat(executorService.awaitTermination(20, TimeUnit.SECONDS)).isTrue();
        }
    }

    /**
     * The calling thread waits in the work queue for a worker thread that is downloading a classpath element slowly
     * but steadily, for longer than the worker timeout. The worker timeout counts time with no progress, so the
     * scan succeeds.
     *
     * @param tempDir
     *            the directory to make the classpath elements in.
     * @throws Exception
     *             if the scan failed.
     */
    @Test
    public void aThreadWaitingForASlowButProgressingWorkerDoesNotTimeOut(@TempDir final Path tempDir)
            throws Exception {
        final var server = new SlowJarServer(jarWithOneResource());
        scanWhileTheDownloadMakesProgress(tempDir, server, VfsSpec.DEFAULT_MAX_BUFFERED_JAR_RAM_SIZE,
                server.jarURL());
    }

    /**
     * As {@link #aThreadWaitingForASlowButProgressingWorkerDoesNotTimeOut(Path)}, but the jarfile is larger than
     * the largest jarfile that is downloaded into RAM, so it is written to a temporary file as it arrives, and the
     * progress is reported from there. The scan succeeds.
     *
     * @param tempDir
     *            the directory to make the classpath elements in.
     * @throws Exception
     *             if the scan failed.
     */
    @Test
    public void aThreadWaitingForAWorkerThatSpillsASlowDownloadToDiskDoesNotTimeOut(@TempDir final Path tempDir)
            throws Exception {
        final var jar = jarWithOneResource();
        final var server = new SlowJarServer(jar);
        scanWhileTheDownloadMakesProgress(tempDir, server, /* maxBufferedJarRAMSize = */ jar.length / 20,
                server.jarURL());
    }

    /**
     * As {@link #aThreadWaitingForAStuckClasspathElementTimesOut(Path)}, but the classpath element is a remote
     * jarfile, named twice with the scheme spelled differently, whose download is slow and steady rather than
     * stuck, and takes longer than the worker timeout. The two names are the same URL, so the calling thread waits
     * for the worker thread to open the classpath element. The scan succeeds.
     *
     * @param tempDir
     *            the directory to make the classpath elements in.
     * @throws Exception
     *             if the scan failed.
     */
    @Test
    public void aThreadWaitingForAProgressingClasspathElementDoesNotTimeOut(@TempDir final Path tempDir)
            throws Exception {
        final var server = new SlowJarServer(jarWithOneResource());
        scanWhileTheDownloadMakesProgress(tempDir, server, VfsSpec.DEFAULT_MAX_BUFFERED_JAR_RAM_SIZE,
                server.jarURL(), "HTTP" + server.jarURL().substring("http".length()));
    }

    /**
     * As {@link #aThreadWaitingForAnotherThreadToOpenTheSameJarfileTimesOut(Path)}, but the download of the remote
     * jarfile is slow and steady rather than stuck, and takes longer than the worker timeout. The scan succeeds.
     *
     * @param tempDir
     *            the directory to make the classpath elements in.
     * @throws Exception
     *             if the scan failed.
     */
    @Test
    public void aThreadWaitingForAnotherThreadToDownloadTheSameJarfileDoesNotTimeOut(@TempDir final Path tempDir)
            throws Exception {
        final var server = new SlowJarServer(jarWithOneResource());
        scanWhileTheDownloadMakesProgress(tempDir, server, VfsSpec.DEFAULT_MAX_BUFFERED_JAR_RAM_SIZE,
                server.jarURL(), server.jarURL() + "!/pkgroot");
    }
}
