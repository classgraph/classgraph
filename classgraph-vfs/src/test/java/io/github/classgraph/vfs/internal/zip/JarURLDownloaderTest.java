package io.github.classgraph.vfs.internal.zip;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.classgraph.base.internal.concurrency.InterruptionChecker;
import io.github.classgraph.base.internal.utils.LogNode;
import io.github.classgraph.vfs.internal.spec.VfsScanSpec;
import io.github.classgraph.vfs.internal.ScanResources;
import org.jspecify.annotations.Nullable;

/**
 * Tests fetching a jar named by a URL, which is how a jar that is not a file on the local filesystem is reached.
 */
public class JarURLDownloaderTest {
    /** The path of the single entry in the jars built by this test. */
    private static final String ENTRY_PATH = "testpkg/entry.txt";

    /** The resources owned by the scan, closed when the test ends. */
    private final ScanResources scanResources = new ScanResources(new VfsScanSpec(), new InterruptionChecker());

    /** Close the slices that the test opened. */
    @AfterEach
    public void closeScanResources() {
        scanResources.close(/* log = */ null);
    }

    /**
     * Build a jar containing a single entry.
     *
     * @param jar
     *            the file to write the jar to
     * @param entryContent
     *            the content of the entry in the jar
     * @return the bytes of the jar file
     * @throws IOException
     *             if the jar could not be written
     */
    private static byte[] buildJar(final Path jar, final String entryContent) throws IOException {
        try (var zipOutputStream = new ZipOutputStream(Files.newOutputStream(jar))) {
            zipOutputStream.putNextEntry(new ZipEntry(ENTRY_PATH));
            zipOutputStream.write(entryContent.getBytes(StandardCharsets.UTF_8));
            zipOutputStream.closeEntry();
        }
        return Files.readAllBytes(jar);
    }

    /**
     * An HTTP server on the loopback interface that answers every request with the same canned response, so that a
     * jar can be fetched over HTTP without reaching the network. The response is written by hand rather than
     * through an HTTP library, so that a response that no library would produce (such as a negative content length)
     * can still be sent.
     */
    private static final class CannedResponseHttpServer implements Closeable {
        /** The socket the server listens on. */
        private final ServerSocket serverSocket;

        /**
         * Constructor. Starts the server.
         *
         * @param statusLine
         *            the status line to answer with, e.g. {@code "200 OK"}
         * @param contentLength
         *            the value of the {@code Content-Length} header, or null not to send that header, in which case
         *            the body is delimited by the server closing the connection
         * @param body
         *            the body to answer with
         * @throws IOException
         *             if the server socket could not be opened
         */
        CannedResponseHttpServer(final String statusLine, final @Nullable String contentLength, final byte[] body)
                throws IOException {
            serverSocket = new ServerSocket(0, /* backlog = */ 1, InetAddress.getLoopbackAddress());
            final var thread = new Thread(() -> serve(statusLine, contentLength, body));
            thread.setDaemon(true);
            thread.start();
        }

        /**
         * Answer requests until the server is closed.
         *
         * @param statusLine
         *            the status line to answer with
         * @param contentLength
         *            the value of the {@code Content-Length} header, or null not to send that header
         * @param body
         *            the body to answer with
         */
        private void serve(final String statusLine, final @Nullable String contentLength, final byte[] body) {
            while (!serverSocket.isClosed()) {
                try (var socket = serverSocket.accept()) {
                    readRequest(socket.getInputStream());
                    final var headers = new StringBuilder("HTTP/1.1 ").append(statusLine).append("\r\n");
                    if (contentLength != null) {
                        headers.append("Content-Length: ").append(contentLength).append("\r\n");
                    }
                    // Close the connection after the response, so that a body with no content length is delimited
                    headers.append("Connection: close\r\n\r\n");
                    final var outputStream = socket.getOutputStream();
                    outputStream.write(headers.toString().getBytes(StandardCharsets.ISO_8859_1));
                    outputStream.write(body);
                    outputStream.flush();
                } catch (final IOException e) {
                    // The server socket was closed at the end of the test, or the client hung up
                }
            }
        }

        /**
         * Read a request, up to the blank line that ends its headers. (The requests this server answers have no
         * body.)
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
         * The URL of a jar served by this server.
         *
         * @return the URL
         */
        String jarURL() {
            return "http://" + serverSocket.getInetAddress().getHostAddress() + ":" + serverSocket.getLocalPort()
                    + "/downloaded.jar";
        }

        @Override
        public void close() throws IOException {
            serverSocket.close();
        }
    }

    // -----------------------------------------------------------------------------------------------------------

    /**
     * A jar at an http URL is downloaded into RAM, and the download is logged with the time it took, along with a
     * warning that scanning a jar at a URL is slow.
     *
     * @param tempDir
     *            a temporary directory to build the jar in
     * @throws IOException
     *             if the jar could not be built, or the jar could not be downloaded
     */
    @Test
    public void aJarIsDownloadedFromAnHttpUrl(@TempDir final Path tempDir) throws IOException {
        final var jarBytes = buildJar(tempDir.resolve("http.jar"), "Downloaded over http");
        final var log = new LogNode();
        try (var server = new CannedResponseHttpServer("200 OK", String.valueOf(jarBytes.length), jarBytes)) {
            final var physicalZipFile = JarURLDownloader.downloadJarFromURL(server.jarURL(), scanResources, log);

            assertThat(physicalZipFile.slice.load()).isEqualTo(jarBytes);
            assertThat(physicalZipFile.length()).isEqualTo(jarBytes.length);
            // The jar was downloaded into RAM, rather than being read from a file on the local filesystem
            assertThat(physicalZipFile.getFile()).isNull();
            assertThat(physicalZipFile.getPath()).isNull();
            assertThat(physicalZipFile.getPathString()).isEqualTo(server.jarURL());

            assertThat(log.toString()).contains("Downloading jar from URL " + server.jarURL() + " (took ")
                    .contains("time-consuming to scan jars at non-\"file:\" URLs");
        }
    }

    /**
     * A jar is still downloaded from a server that does not say how long the jar is, in which case the body is
     * delimited by the server closing the connection.
     *
     * @param tempDir
     *            a temporary directory to build the jar in
     * @throws IOException
     *             if the jar could not be built, or the jar could not be downloaded
     */
    @Test
    public void aJarIsDownloadedWhenTheServerSendsNoContentLength(@TempDir final Path tempDir) throws IOException {
        final var jarBytes = buildJar(tempDir.resolve("nolength.jar"), "Downloaded with no content length");
        try (var server = new CannedResponseHttpServer("200 OK", /* contentLength = */ null, jarBytes)) {
            final var physicalZipFile = JarURLDownloader.downloadJarFromURL(server.jarURL(), scanResources,
                    /* log = */ null);

            assertThat(physicalZipFile.slice.load()).isEqualTo(jarBytes);
        }
    }

    /**
     * A jar is still downloaded from a server that sends a content length that cannot be a length, which is ignored
     * rather than being used as the size of the buffer to download the jar into.
     *
     * @param tempDir
     *            a temporary directory to build the jar in
     * @throws IOException
     *             if the jar could not be built, or the jar could not be downloaded
     */
    @Test
    public void aJarIsDownloadedWhenTheServerSendsANegativeContentLength(@TempDir final Path tempDir)
            throws IOException {
        final var jarBytes = buildJar(tempDir.resolve("badlength.jar"),
                "Downloaded with a negative content length");
        try (var server = new CannedResponseHttpServer("200 OK", "-2", jarBytes)) {
            final var physicalZipFile = JarURLDownloader.downloadJarFromURL(server.jarURL(), scanResources,
                    /* log = */ null);

            assertThat(physicalZipFile.slice.load()).isEqualTo(jarBytes);
        }
    }

    /**
     * A response other than {@code 200 OK} is reported, rather than the body of the response being scanned as if it
     * were the jar that was asked for.
     *
     * @throws IOException
     *             if the server could not be started
     */
    @Test
    public void aResponseOtherThanOkIsReported() throws IOException {
        try (var server = new CannedResponseHttpServer("404 Not Found", "0", new byte[0])) {
            assertThatThrownBy(
                    () -> JarURLDownloader.downloadJarFromURL(server.jarURL(), scanResources, /* log = */ null))
                    .isInstanceOf(IOException.class).hasMessage("Got response code 404 for URL " + server.jarURL());
        }
    }

    /**
     * A jar at a {@code "file:"} URL is read straight from the filesystem that backs the URL, rather than being
     * downloaded through a URL connection.
     *
     * @param tempDir
     *            a temporary directory to build the jar in
     * @throws IOException
     *             if the jar could not be built, or the jar could not be read
     */
    @Test
    public void aJarAtAFileUrlIsReadThroughTheFilesystemThatBacksIt(@TempDir final Path tempDir)
            throws IOException {
        final var jar = tempDir.resolve("local.jar");
        final var jarBytes = buildJar(jar, "Read from the local filesystem");
        final var log = new LogNode();
        final var physicalZipFile = JarURLDownloader.downloadJarFromURL(jar.toUri().toString(), scanResources, log);

        assertThat(physicalZipFile.slice.load()).isEqualTo(jarBytes);
        assertThat(physicalZipFile.getPath()).isEqualTo(jar);
        assertThat(log.toString()).contains("is backed by filesystem");
    }

    /**
     * A jar nested inside another jar is downloaded through the URL connection of a {@code "jar:"} URL, since no
     * filesystem is open for the outer jar.
     *
     * @param tempDir
     *            a temporary directory to build the jars in
     * @throws IOException
     *             if the jars could not be built, or the inner jar could not be downloaded
     */
    @Test
    public void aJarInsideAnotherJarIsDownloadedThroughAJarUrl(@TempDir final Path tempDir) throws IOException {
        final var innerJarBytes = buildJar(tempDir.resolve("inner.jar"), "Nested inside another jar");
        final var outerJar = tempDir.resolve("outer.jar");
        try (var zipOutputStream = new ZipOutputStream(Files.newOutputStream(outerJar))) {
            zipOutputStream.putNextEntry(new ZipEntry("lib/inner.jar"));
            zipOutputStream.write(innerJarBytes);
            zipOutputStream.closeEntry();
        }
        final var jarURL = "jar:" + outerJar.toUri() + "!/lib/inner.jar";

        final var physicalZipFile = JarURLDownloader.downloadJarFromURL(jarURL, scanResources, /* log = */ null);

        assertThat(physicalZipFile.slice.load()).isEqualTo(innerJarBytes);
        assertThat(physicalZipFile.getPathString()).isEqualTo(jarURL);
    }

    /** A URL that is not a URL at all is reported, rather than being opened. */
    @Test
    public void aUrlThatCannotBeParsedIsReported() {
        assertThatThrownBy(() -> JarURLDownloader.downloadJarFromURL("not a url", scanResources, /* log = */ null))
                .isInstanceOf(IOException.class).hasMessage("Could not parse URL: not a url");
    }

    /**
     * A URL that cannot be turned into a URI, because it contains a character that has to be escaped, is reported
     * rather than being silently skipped. (The jar is never opened, since the URL cannot be resolved to a path, so
     * the URL does not have to name a jar that exists.)
     */
    @Test
    public void aUrlThatIsNotAValidUriIsReported() {
        final var jarURL = "file:/jars/a jar with spaces.jar";
        assertThatThrownBy(() -> JarURLDownloader.downloadJarFromURL(jarURL, scanResources, /* log = */ null))
                .isInstanceOf(IOException.class).hasMessageStartingWith("Could not convert URL to URI (")
                .hasMessageEndingWith(jarURL);
    }
}
