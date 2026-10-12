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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.Timeout.ThreadMode;

/**
 * A thread that asks for a jarfile that another thread is already opening waits for that thread, but gives up once
 * that thread has made no progress for as long as {@link VfsSpec#setOpenWaitTimeout(Duration)} allows, since the
 * other thread may be stuck.
 */
public class VfsOpenWaitTimeoutTest {
    /**
     * An HTTP server on the loopback interface that answers a request for a jarfile by sending the response headers
     * and then nothing more until it is closed, so the download makes no progress. This stands in for a server, or
     * a network filesystem, that has stopped answering. (A server that kept sending bytes, however slowly, would
     * not do, since each byte that arrives is progress, and the waiting thread keeps waiting while there is
     * progress.)
     */
    private static final class StalledJarServer implements Closeable {
        /** The socket the server listens on. */
        private final ServerSocket serverSocket;

        /** Counted down once the server has received a request. */
        private final CountDownLatch requestReceived = new CountDownLatch(1);

        /** Counted down when the server is closed, to end the response. */
        private final CountDownLatch closed = new CountDownLatch(1);

        /**
         * Constructor. Starts the server.
         *
         * @throws IOException
         *             if the server socket could not be opened.
         */
        StalledJarServer() throws IOException {
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
                    outputStream.write(("HTTP/1.1 200 OK\r\nContent-Length: 1000000\r\nConnection: close\r\n\r\n")
                            .getBytes(StandardCharsets.ISO_8859_1));
                    outputStream.flush();
                    closed.await();
                } catch (final IOException e) {
                    // The server socket was closed at the end of the test, or the client hung up
                } catch (final InterruptedException e) {
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
            assertThat(requestReceived.await(30, TimeUnit.SECONDS)).isTrue();
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
     * While one thread is stuck downloading a jarfile, another thread that opens the same jarfile, or a package
     * root within it, which opens the jarfile on the way, gives up after the timeout. The timeout is recorded in
     * the interruption checker, so that a scan reading through the {@link Vfs} fails rather than skipping the
     * jarfile, and the failure is not remembered, so that the jarfile can be opened again later.
     *
     * @throws Exception
     *             if the test could not be run.
     */
    @Test
    @Timeout(value = 60, threadMode = ThreadMode.SEPARATE_THREAD)
    public void aThreadWaitingForAnotherThreadToOpenTheSameJarfileTimesOut() throws Exception {
        try (var vfs = new Vfs(new VfsSpec().setOpenWaitTimeout(Duration.ofMillis(300)))) {
            final var server = new StalledJarServer();
            final var jarURL = server.jarURL();
            final var opener = new Thread(() -> {
                try {
                    vfs.open(jarURL);
                } catch (final IOException e) {
                    // The server is closed at the end of the test, so the download fails
                }
            });
            opener.start();
            try {
                server.awaitRequest();

                assertThatThrownBy(() -> vfs.open(jarURL)).isInstanceOf(IOException.class)
                        .hasMessageStartingWith(
                                "Timed out after 300ms with no progress, waiting for another thread to open "
                                        + jarURL + ".")
                        .hasCauseInstanceOf(TimeoutException.class);
                assertThat(vfs.interruptionChecker().getExecutionException()).isNotNull();

                assertThatThrownBy(() -> vfs.open(jarURL + "!/pkgroot")).isInstanceOf(IOException.class)
                        .hasMessageContaining("Timed out after 300ms with no progress, waiting for another thread "
                                + "to open " + jarURL + ".")
                        .hasRootCauseInstanceOf(TimeoutException.class);
            } finally {
                server.close();
                opener.join();
            }

            // The server is gone, so the jarfile cannot be opened, but trying again reports that, rather than the
            // timeout of the earlier attempt
            assertThatThrownBy(() -> vfs.open(jarURL + "!/pkgroot")).isInstanceOf(IOException.class)
                    .hasMessageNotContaining("Timed out");
        }
    }
}
