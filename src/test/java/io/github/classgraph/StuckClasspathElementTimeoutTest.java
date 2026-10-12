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
package io.github.classgraph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

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
     * An HTTP server on the loopback interface that answers one request for a jarfile. Given a body, it sends the
     * body in 20 parts, 100 ms apart, which stands in for a slow download that is still making progress. Given no
     * body, it sends the headers and then nothing, for as long as it is not released, which stands in for a read
     * that never returns: the thread downloading the jarfile is stuck until the read timeout of the download,
     * which is longer than the worker timeouts used here.
     */
    private static final class SlowHttpServer implements AutoCloseable {
        /** The server socket. */
        private final ServerSocket serverSocket;

        /** The body to send, or null to send none. */
        private final byte[] body;

        /** Counted down once the request for the jarfile has been received. */
        final CountDownLatch requestReceived = new CountDownLatch(1);

        /** Counted down to stop sending the body and close the connection. */
        private final CountDownLatch released = new CountDownLatch(1);

        /**
         * Constructor. Starts the server.
         *
         * @param body
         *            the body to send, or null to send none.
         * @throws IOException
         *             if the server socket could not be opened
         */
        SlowHttpServer(final byte[] body) throws IOException {
            this.body = body;
            serverSocket = new ServerSocket(0, /* backlog = */ 50, InetAddress.getLoopbackAddress());
            final Thread thread = new Thread(new Runnable() {
                @Override
                public void run() {
                    serve();
                }
            });
            thread.setDaemon(true);
            thread.start();
        }

        /** Answer one request, sending a part of the body every 100 ms, or nothing until released. */
        private void serve() {
            try (Socket socket = serverSocket.accept()) {
                readRequest(socket.getInputStream());
                requestReceived.countDown();
                final OutputStream outputStream = socket.getOutputStream();
                outputStream.write(("HTTP/1.1 200 OK\r\nContent-Length: " + (body == null ? 1000000 : body.length)
                        + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                outputStream.flush();
                if (body == null) {
                    released.await();
                    return;
                }
                final int partLength = (body.length + 19) / 20;
                for (int offset = 0; offset < body.length; offset += partLength) {
                    if (released.await(100, TimeUnit.MILLISECONDS)) {
                        return;
                    }
                    outputStream.write(body, offset, Math.min(partLength, body.length - offset));
                    outputStream.flush();
                }
            } catch (final IOException | InterruptedException e) {
                // The server socket was closed, or the client went away
            }
        }

        /**
         * Read a request up to the blank line that ends its headers.
         *
         * @param inputStream
         *            the socket's input stream
         * @throws IOException
         *             if the request could not be read
         */
        private static void readRequest(final InputStream inputStream) throws IOException {
            int matched = 0;
            final byte[] end = { '\r', '\n', '\r', '\n' };
            for (int b; matched < end.length && (b = inputStream.read()) != -1;) {
                matched = b == end[matched] ? matched + 1 : b == '\r' ? 1 : 0;
            }
        }

        /**
         * The URL of the jarfile on this server.
         *
         * @return the URL
         */
        String jarURL() {
            return "http://" + serverSocket.getInetAddress().getHostAddress() + ":" + serverSocket.getLocalPort()
                    + "/stuck.jar";
        }

        @Override
        public void close() throws IOException {
            released.countDown();
            serverSocket.close();
        }
    }

    /**
     * The calling thread waits for a worker thread that is stuck opening a classpath element both of them need, and
     * the scan fails with the worker timeout rather than hanging.
     *
     * <p>
     * The classpath is a jarfile on an HTTP server, followed by a local jarfile whose manifest {@code Class-Path}
     * entry names the same URL. The server never finishes sending the remote jarfile, which stands in for a read
     * from a network or filesystem that never returns. The worker thread opens the remote jarfile and is stuck
     * downloading it. Only then does the calling thread start on the local jarfile: the executor's
     * {@code execute} runs on the calling thread when the scan starts the worker, and does not return until the
     * server has received the request. The calling thread then reaches the remote jarfile again, through the
     * manifest of the local jarfile, and waits for the worker thread to finish opening it.
     *
     * <p>
     * The scan fails about one worker timeout after the worker thread got stuck. Once the calling thread has timed
     * out, it does not wait for the stuck worker thread for a second worker timeout before failing the scan.
     *
     * @param tempDir
     *            the directory to make the local jarfile in.
     * @throws Exception
     *             if the local jarfile could not be made, or the server could not be started.
     */
    @Test
    public void aThreadWaitingForAStuckClasspathElementTimesOut(@TempDir final Path tempDir) throws Exception {
        try (final SlowHttpServer server = new SlowHttpServer(/* body = */ null)) {
            final String stuckJarURL = server.jarURL();
            final Path parentJar = tempDir.resolve("parent.jar");
            final Manifest manifest = new Manifest();
            manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
            manifest.getMainAttributes().put(Attributes.Name.CLASS_PATH, stuckJarURL);
            try (JarOutputStream jarOut = new JarOutputStream(Files.newOutputStream(parentJar), manifest)) {
                // The manifest is the only entry
            }

            // Hold the calling thread in execute until the worker thread is stuck downloading the remote jarfile
            final AtomicLong workerStuckNanos = new AtomicLong();
            final ThreadPoolExecutor executorService = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                    new LinkedBlockingQueue<Runnable>()) {
                @Override
                public void execute(final Runnable command) {
                    super.execute(command);
                    try {
                        server.requestReceived.await(10, TimeUnit.SECONDS);
                    } catch (final InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    workerStuckNanos.set(System.nanoTime());
                }
            };
            try {
                final Throwable thrown = assertTimeoutPreemptively(Duration.ofSeconds(20),
                        () -> catchThrowable(() -> new ClassGraph()
                                .overrideClasspath(stuckJarURL, parentJar.toString()).enableURLScheme("http")
                                .setWorkerTimeout(2, TimeUnit.SECONDS).scan(executorService, 2).close()));
                final long failedAfterNanos = System.nanoTime() - workerStuckNanos.get();
                assertThat(server.requestReceived.getCount()).isZero();
                assertThat(thrown).isInstanceOf(ClassGraphException.class);
                assertThat(thrown.getCause()).isInstanceOf(IllegalStateException.class)
                        .hasMessageStartingWith("Timed out after 2000ms waiting for another thread to open the "
                                + "classpath element " + stuckJarURL);
                // One worker timeout, not two. One takes about 2 s. Two take at least 4 s, which is less than the
                // 5 s HTTP read timeout that would end the stuck download, so the bound leaves 1.8 s for a slow
                // machine and still fails if the stuck worker is waited for a second time
                assertThat(failedAfterNanos).isLessThan(TimeUnit.MILLISECONDS.toNanos(3800));
            } finally {
                server.close();
                executorService.shutdown();
                assertThat(executorService.awaitTermination(20, TimeUnit.SECONDS)).isTrue();
            }
        }
    }

    /**
     * Two classpath elements that are different, but that are read from the same jarfile -- here the jarfile, and
     * a package root within it -- are opened by two threads, and the jarfile is downloaded once, by whichever
     * thread reaches it first. If that thread never finishes downloading it, the other thread must give up after
     * the worker timeout rather than wait forever, since the waiting thread may be the calling thread, which is the
     * thread that applies the worker timeout to the other threads.
     *
     * <p>
     * The worker thread opens the remote jarfile, the first classpath element, and is stuck downloading it. The
     * executor's {@code execute} holds the calling thread until then, as in
     * {@link #aThreadWaitingForAStuckClasspathElementTimesOut(Path)}. The calling thread then opens the package
     * root, which is a different classpath element, so it does not wait for the worker thread to open the
     * classpath element, but it does wait for the worker thread to download the jarfile that the package root is
     * in.
     *
     * @throws Exception
     *             if the server could not be started.
     */
    @Test
    public void aThreadWaitingForAnotherThreadToOpenTheSameJarfileTimesOut() throws Exception {
        try (final SlowHttpServer server = new SlowHttpServer(/* body = */ null)) {
            final String stuckJarURL = server.jarURL();

            // Hold the calling thread in execute until the worker thread is stuck downloading the remote jarfile
            final ThreadPoolExecutor executorService = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                    new LinkedBlockingQueue<Runnable>()) {
                @Override
                public void execute(final Runnable command) {
                    super.execute(command);
                    try {
                        server.requestReceived.await(10, TimeUnit.SECONDS);
                    } catch (final InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            };
            try {
                final Throwable thrown = assertTimeoutPreemptively(Duration.ofSeconds(20),
                        () -> catchThrowable(() -> new ClassGraph()
                                .overrideClasspath(stuckJarURL, stuckJarURL + "!/pkgroot").enableURLScheme("http")
                                .setWorkerTimeout(500, TimeUnit.MILLISECONDS).scan(executorService, 2).close()));
                assertThat(server.requestReceived.getCount()).isZero();
                assertThat(thrown).isInstanceOf(ClassGraphException.class);
                assertThat(thrown.getCause()).isInstanceOf(IllegalStateException.class)
                        .hasMessageStartingWith(
                                "Timed out after 500ms waiting for another thread to open " + stuckJarURL + ".")
                        .hasMessageContaining("ClassGraph#setWorkerTimeout(long, TimeUnit)");
            } finally {
                server.close();
                executorService.shutdown();
                assertThat(executorService.awaitTermination(20, TimeUnit.SECONDS)).isTrue();
            }
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
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (JarOutputStream jarOut = new JarOutputStream(bytes)) {
            jarOut.putNextEntry(new JarEntry("pkgroot/resource.txt"));
            jarOut.write(new byte[100000]);
            jarOut.closeEntry();
        }
        return bytes.toByteArray();
    }

    /**
     * Scan with a worker timeout of one second, while the server sends the remote jarfile over about two seconds,
     * holding the calling thread in {@code execute} until the worker thread has started downloading it, as in
     * {@link #aThreadWaitingForAStuckClasspathElementTimesOut(Path)}. The download makes progress the whole time,
     * so the scan must not time out, however long the download takes in all.
     *
     * @param classGraph
     *            the {@link ClassGraph} to scan with, configured with anything else the test needs.
     * @param server
     *            the server.
     * @param classpath
     *            the classpath.
     * @throws Exception
     *             if the scan failed.
     */
    private static void scanWhileTheDownloadMakesProgress(final ClassGraph classGraph, final SlowHttpServer server,
            final String... classpath) throws Exception {
        final ThreadPoolExecutor executorService = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<Runnable>()) {
            @Override
            public void execute(final Runnable command) {
                super.execute(command);
                try {
                    server.requestReceived.await(10, TimeUnit.SECONDS);
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        };
        try {
            assertTimeoutPreemptively(Duration.ofSeconds(20), () -> {
                try (ScanResult scanResult = classGraph.overrideClasspath((Object[]) classpath)
                        .enableURLScheme("http").setWorkerTimeout(1, TimeUnit.SECONDS)
                        .scan(executorService, 2)) {
                    assertThat(scanResult.getResourcesWithLeafName("resource.txt")).isNotEmpty();
                }
            });
            assertThat(server.requestReceived.getCount()).isZero();
        } finally {
            server.close();
            executorService.shutdown();
            assertThat(executorService.awaitTermination(20, TimeUnit.SECONDS)).isTrue();
        }
    }

    /**
     * The calling thread waits in the work queue for a worker thread that is downloading a classpath element
     * slowly but steadily, for longer than the worker timeout. The worker timeout counts time with no progress, so
     * the scan succeeds.
     *
     * @throws Exception
     *             if the server could not be started.
     */
    @Test
    public void aThreadWaitingForASlowButProgressingWorkerDoesNotTimeOut() throws Exception {
        try (final SlowHttpServer server = new SlowHttpServer(jarWithOneResource())) {
            scanWhileTheDownloadMakesProgress(new ClassGraph(), server, server.jarURL());
        }
    }

    /**
     * As {@link #aThreadWaitingForASlowButProgressingWorkerDoesNotTimeOut()}, but the remote jarfile is longer than
     * the maximum RAM buffer size, so it is downloaded straight to a temporary file. Writing the download to disk
     * reports progress as reading it into memory does, so the scan succeeds.
     *
     * @throws Exception
     *             if the server could not be started.
     */
    @Test
    public void aSlowDownloadThatSpillsToDiskDoesNotTimeOut() throws Exception {
        final byte[] jar = jarWithOneResource();
        try (final SlowHttpServer server = new SlowHttpServer(jar)) {
            scanWhileTheDownloadMakesProgress(new ClassGraph().setMaxBufferedJarRAMSize(jar.length / 2), server,
                    server.jarURL());
        }
    }

    /**
     * As {@link #aThreadWaitingForAStuckClasspathElementTimesOut(Path)}, but the download of the remote jarfile is
     * slow and steady rather than stuck, and takes longer than the worker timeout. The scan succeeds.
     *
     * @param tempDir
     *            the directory to make the local jarfile in.
     * @throws Exception
     *             if the local jarfile could not be made, or the server could not be started.
     */
    @Test
    public void aThreadWaitingForAProgressingClasspathElementDoesNotTimeOut(@TempDir final Path tempDir)
            throws Exception {
        try (final SlowHttpServer server = new SlowHttpServer(jarWithOneResource())) {
            final Path parentJar = tempDir.resolve("parent.jar");
            final Manifest manifest = new Manifest();
            manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
            manifest.getMainAttributes().put(Attributes.Name.CLASS_PATH, server.jarURL());
            try (JarOutputStream jarOut = new JarOutputStream(Files.newOutputStream(parentJar), manifest)) {
                // The manifest is the only entry
            }
            scanWhileTheDownloadMakesProgress(new ClassGraph(), server, server.jarURL(), parentJar.toString());
        }
    }

    /**
     * As {@link #aThreadWaitingForAnotherThreadToOpenTheSameJarfileTimesOut()}, but the download of the remote
     * jarfile is slow and steady rather than stuck, and takes longer than the worker timeout. The scan succeeds.
     *
     * @throws Exception
     *             if the server could not be started.
     */
    @Test
    public void aThreadWaitingForAnotherThreadToDownloadTheSameJarfileDoesNotTimeOut() throws Exception {
        try (final SlowHttpServer server = new SlowHttpServer(jarWithOneResource())) {
            scanWhileTheDownloadMakesProgress(new ClassGraph(), server, server.jarURL(),
                    server.jarURL() + "!/pkgroot");
        }
    }
}
