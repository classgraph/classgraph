package io.github.classgraph;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.MalformedURLException;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;

/**
 * A jarfile fetched over HTTP is scanned whether it is named by its plain URL or by a {@code "jar:"} URL naming its
 * root, as the JDK's own {@link java.net.URLClassLoader} reads it either way.
 */
class RemoteJarClasspathEntryTest {
    /** The path of the single resource in the served jar. */
    private static final String RESOURCE_PATH = "remotepkg/resource.txt";

    /**
     * An HTTP server on the loopback interface that answers every request with the bytes of a jar, so that a jar
     * can be fetched over HTTP without reaching the network.
     */
    private static final class JarHttpServer implements Closeable {
        /** The socket the server listens on. */
        private final ServerSocket serverSocket;

        /**
         * Constructor. Starts the server.
         *
         * @param jarBytes
         *            the bytes of the jar to serve
         * @throws IOException
         *             if the server socket could not be opened
         */
        JarHttpServer(final byte[] jarBytes) throws IOException {
            serverSocket = new ServerSocket(0, /* backlog = */ 50, InetAddress.getLoopbackAddress());
            final var thread = new Thread(() -> serve(jarBytes));
            thread.setDaemon(true);
            thread.start();
        }

        /**
         * Answer requests until the server is closed.
         *
         * @param jarBytes
         *            the bytes of the jar to serve
         */
        private void serve(final byte[] jarBytes) {
            while (!serverSocket.isClosed()) {
                try (var socket = serverSocket.accept()) {
                    readRequest(socket.getInputStream());
                    final var outputStream = socket.getOutputStream();
                    outputStream.write(("HTTP/1.1 200 OK\r\nContent-Length: " + jarBytes.length
                            + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
                    outputStream.write(jarBytes);
                    outputStream.flush();
                } catch (final IOException e) {
                    // The server socket was closed at the end of the test, or the client hung up
                }
            }
        }

        /**
         * Read a request, up to the blank line that ends its headers.
         *
         * @param inputStream
         *            the stream to read the request from
         * @throws IOException
         *             if the request could not be read
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
         * The URL of the jar served by this server.
         *
         * @return the URL
         */
        String jarURL() {
            return "http://" + serverSocket.getInetAddress().getHostAddress() + ":" + serverSocket.getLocalPort()
                    + "/x.jar";
        }

        @Override
        public void close() throws IOException {
            serverSocket.close();
        }
    }

    /**
     * Build a jar holding a single resource.
     *
     * @return the bytes of the jar
     * @throws IOException
     *             if the jar could not be written
     */
    private static byte[] buildJar() throws IOException {
        final var byteArrayOutputStream = new ByteArrayOutputStream();
        try (var zipOutputStream = new ZipOutputStream(byteArrayOutputStream)) {
            zipOutputStream.putNextEntry(new ZipEntry(RESOURCE_PATH));
            zipOutputStream.write("content".getBytes(StandardCharsets.UTF_8));
            zipOutputStream.closeEntry();
        }
        return byteArrayOutputStream.toByteArray();
    }

    /**
     * Scan a single classpath entry, with remote jar scanning enabled.
     *
     * @param url
     *            the URL of the classpath entry
     * @return the paths of the resources found
     * @throws MalformedURLException
     *             if the URL is malformed
     */
    private static List<String> scanPaths(final String url) throws MalformedURLException {
        try (var scanResult = new ClassGraph().enableRemoteJarScanning()
                .enableClasspathEntries((Object) URI.create(url).toURL()).scan()) {
            return scanResult.getAllResources().getPaths();
        }
    }

    /**
     * A {@code "jar:"} URL ending in {@code "!/"} names the root of the jar it wraps, so the jar is scanned, as it
     * is when it is named by its plain URL.
     *
     * @throws IOException
     *             if the jar could not be built or served
     */
    @Test
    void aJarURLNamingTheRootOfARemoteJarIsScanned() throws IOException {
        try (var server = new JarHttpServer(buildJar())) {
            assertThat(scanPaths(server.jarURL())).containsExactly(RESOURCE_PATH);
            assertThat(scanPaths("jar:" + server.jarURL() + "!/")).containsExactly(RESOURCE_PATH);
        }
    }

    /**
     * A remote jar too large to hold in RAM is read from a temporary file, which is deleted when the scan is
     * closed. {@link ClassGraph#getClasspathFiles()} closes its scan before it returns, so it does not list that
     * file, while {@link ClassGraph#getClasspathURIs()} still lists the jar by its URL.
     *
     * @throws IOException
     *             if the jar could not be built or served
     */
    @Test
    void aRemoteJarReadFromATemporaryFileIsNotListedOnceTheScanIsClosed() throws IOException {
        try (var server = new JarHttpServer(buildJar())) {
            final var classGraph = new ClassGraph().enableRemoteJarScanning().setMaxBufferedJarRAMSize(0)
                    .enableClasspathEntries((Object) URI.create(server.jarURL()).toURL());
            try (var scanResult = classGraph.scan()) {
                assertThat(scanResult.getClasspathFiles()).singleElement()
                        .satisfies(file -> assertThat(file).exists());
            }
            assertThat(classGraph.getClasspathFiles()).isEmpty();
            assertThat(classGraph.getClasspath()).isEmpty();
            assertThat(classGraph.getClasspathURIs()).containsExactly(URI.create(server.jarURL()));
        }
    }
}
