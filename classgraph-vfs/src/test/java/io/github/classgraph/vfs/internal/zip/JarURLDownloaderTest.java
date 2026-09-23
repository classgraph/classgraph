package io.github.classgraph.vfs.internal.zip;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.MalformedURLException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.classgraph.base.LogNode;
import io.github.classgraph.base.internal.concurrency.InterruptionChecker;
import io.github.classgraph.vfs.VfsSpec;
import io.github.classgraph.vfs.Vfs;
import org.jspecify.annotations.Nullable;

/**
 * Tests fetching a jar named by a URL, which is how a jar that is not a file on the local filesystem is reached.
 */
public class JarURLDownloaderTest {
    /** The path of the single entry in the jars built by this test. */
    private static final String ENTRY_PATH = "testpkg/entry.txt";

    /** The resources owned by the scan, closed when the test ends. */
    private final Vfs vfs = new Vfs(new VfsSpec(), new InterruptionChecker());

    /** Close the slices that the test opened. */
    @AfterEach
    public void closeSession() {
        vfs.close(/* log = */ null);
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
            this(statusLine, contentLength, body, /* location = */ null);
        }

        /**
         * Constructor. Starts a server that answers with a redirect.
         *
         * @param statusLine
         *            the status line to answer with, e.g. {@code "301 Moved Permanently"}
         * @param location
         *            the value of the {@code Location} header
         * @throws IOException
         *             if the server socket could not be opened
         */
        CannedResponseHttpServer(final String statusLine, final String location) throws IOException {
            this(statusLine, "0", new byte[0], location);
        }

        /**
         * Constructor. Starts the server.
         *
         * @param statusLine
         *            the status line to answer with
         * @param contentLength
         *            the value of the {@code Content-Length} header, or null not to send that header
         * @param body
         *            the body to answer with
         * @param location
         *            the value of the {@code Location} header, or null not to send that header
         * @throws IOException
         *             if the server socket could not be opened
         */
        private CannedResponseHttpServer(final String statusLine, final @Nullable String contentLength,
                final byte[] body, final @Nullable String location) throws IOException {
            serverSocket = new ServerSocket(0, /* backlog = */ 1, InetAddress.getLoopbackAddress());
            final var thread = new Thread(() -> serve(statusLine, contentLength, body, location));
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
         * @param location
         *            the value of the {@code Location} header, or null not to send that header
         */
        private void serve(final String statusLine, final @Nullable String contentLength, final byte[] body,
                final @Nullable String location) {
            while (!serverSocket.isClosed()) {
                try (var socket = serverSocket.accept()) {
                    readRequest(socket.getInputStream());
                    final var headers = new StringBuilder("HTTP/1.1 ").append(statusLine).append("\r\n");
                    if (contentLength != null) {
                        headers.append("Content-Length: ").append(contentLength).append("\r\n");
                    }
                    if (location != null) {
                        headers.append("Location: ").append(location).append("\r\n");
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
            final var physicalZipFile = JarURLDownloader.downloadJarFromURL(server.jarURL(), vfs, log);

            assertThat(physicalZipFile.slice.load()).isEqualTo(jarBytes);
            assertThat(physicalZipFile.length()).isEqualTo(jarBytes.length);
            // The jar was downloaded into RAM, rather than being read from a file on the local filesystem
            assertThat(physicalZipFile.getFile()).isNull();
            assertThat(physicalZipFile.getPath()).isNull();
            assertThat(physicalZipFile.getPathString()).isEqualTo(server.jarURL());

            assertThat(log.toString()).contains("Downloading jar from URL " + server.jarURL() + " (took ")
                    .contains("a jar at a non-\"file:\" URL is downloaded by every Vfs that opens it");
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
            final var physicalZipFile = JarURLDownloader.downloadJarFromURL(server.jarURL(), vfs, /* log = */ null);

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
            final var physicalZipFile = JarURLDownloader.downloadJarFromURL(server.jarURL(), vfs, /* log = */ null);

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
            assertThatThrownBy(() -> JarURLDownloader.downloadJarFromURL(server.jarURL(), vfs, /* log = */ null))
                    .isInstanceOf(IOException.class).hasMessage("Got response code 404 for URL " + server.jarURL());
        }
    }

    /**
     * A redirect is followed to the jar, which keeps the URL it was asked for by, and the redirect is logged.
     *
     * @param tempDir
     *            a temporary directory to build the jar in
     * @throws IOException
     *             if the jar could not be built, or the jar could not be downloaded
     */
    @Test
    public void aRedirectIsFollowedToTheJar(@TempDir final Path tempDir) throws IOException {
        final var jarBytes = buildJar(tempDir.resolve("redirected.jar"), "Downloaded after a redirect");
        final var log = new LogNode();
        try (var jarServer = new CannedResponseHttpServer("200 OK", String.valueOf(jarBytes.length), jarBytes);
                var redirectServer = new CannedResponseHttpServer("302 Found", jarServer.jarURL())) {
            final var physicalZipFile = JarURLDownloader.downloadJarFromURL(redirectServer.jarURL(), vfs, log);

            assertThat(physicalZipFile.slice.load()).isEqualTo(jarBytes);
            assertThat(physicalZipFile.getPathString()).isEqualTo(redirectServer.jarURL());
            assertThat(log.toString())
                    .contains("URL " + redirectServer.jarURL() + " redirects to " + jarServer.jarURL());
        }
    }

    /**
     * A redirect from http to https is followed. (HttpURLConnection only follows a redirect that keeps the same
     * scheme.) The test has no TLS server, so it checks that the https URL was connected to with a TLS handshake,
     * whose first byte is 0x16, and that the handshake then failed.
     *
     * @throws IOException
     *             if a server could not be started
     * @throws InterruptedException
     *             if the thread was interrupted
     */
    @Test
    public void aRedirectFromHttpToHttpsIsFollowed() throws IOException, InterruptedException {
        final var firstByteReceived = new AtomicInteger(-1);
        try (var tlsServerSocket = new ServerSocket(0, /* backlog = */ 1, InetAddress.getLoopbackAddress())) {
            final var thread = new Thread(() -> {
                try (var socket = tlsServerSocket.accept()) {
                    firstByteReceived.set(socket.getInputStream().read());
                } catch (final IOException e) {
                    // The server socket was closed at the end of the test
                }
            });
            thread.setDaemon(true);
            thread.start();
            final var httpsURL = "https://" + tlsServerSocket.getInetAddress().getHostAddress() + ":"
                    + tlsServerSocket.getLocalPort() + "/downloaded.jar";
            try (var redirectServer = new CannedResponseHttpServer("301 Moved Permanently", httpsURL)) {
                assertThatThrownBy(
                        () -> JarURLDownloader.downloadJarFromURL(redirectServer.jarURL(), vfs, /* log = */ null))
                        .isInstanceOf(IOException.class);
            }
            thread.join(10_000);
        }
        assertThat(firstByteReceived.get()).isEqualTo(0x16);
    }

    /**
     * A redirect to a scheme that has been denied is refused.
     *
     * @throws IOException
     *             if the server could not be started
     */
    @Test
    public void aRedirectToADeniedSchemeIsRefused() throws IOException {
        final var httpsDeniedVfs = new Vfs(new VfsSpec().denyURLScheme("https"), new InterruptionChecker());
        try (var redirectServer = new CannedResponseHttpServer("301 Moved Permanently",
                "https://example.com/downloaded.jar")) {
            assertThatThrownBy(() -> JarURLDownloader.downloadJarFromURL(redirectServer.jarURL(), httpsDeniedVfs,
                    /* log = */ null)).isInstanceOf(IOException.class).hasMessage(
                            "Fetching a jarfile over \"https:\" is not allowed: https://example.com/downloaded.jar"
                                    + " (redirected from " + redirectServer.jarURL() + ")");
        } finally {
            httpsDeniedVfs.close(/* log = */ null);
        }
    }

    /**
     * A redirect to a URL that is not http or https is refused, so that a remote server cannot point the scan at a
     * local file.
     *
     * @throws IOException
     *             if the server could not be started
     */
    @Test
    public void aRedirectToAFileUrlIsRefused() throws IOException {
        try (var redirectServer = new CannedResponseHttpServer("302 Found", "file:/etc/passwd")) {
            assertThatThrownBy(
                    () -> JarURLDownloader.downloadJarFromURL(redirectServer.jarURL(), vfs, /* log = */ null))
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("to a URL that is not http or https: file:/etc/passwd");
        }
    }

    /**
     * A redirect from https to http is refused, since it would fetch the jar without TLS. A relative location is
     * resolved against the URL that was redirected.
     *
     * @throws IOException
     *             if the URL could not be built
     */
    @Test
    public void aRedirectFromHttpsToHttpIsRefused() throws IOException {
        final var httpsURL = URI.create("https://example.com/lib/a.jar").toURL();
        assertThatThrownBy(() -> JarURLDownloader.redirectTarget(httpsURL, "http://example.com/lib/a.jar", vfs))
                .isInstanceOf(IOException.class)
                .hasMessageStartingWith("Not following the redirect from https to http");
        assertThat(JarURLDownloader.redirectTarget(httpsURL, "../b.jar", vfs).toString())
                .isEqualTo("https://example.com/b.jar");
    }

    /**
     * A server that redirects to itself is given up on after a fixed number of redirects. (The redirect is to a
     * relative location, the server's own path.)
     *
     * @throws IOException
     *             if the server could not be started
     */
    @Test
    public void aRedirectLoopIsGivenUpOn() throws IOException {
        try (var loopServer = new CannedResponseHttpServer("307 Temporary Redirect", "/downloaded.jar")) {
            assertThatThrownBy(
                    () -> JarURLDownloader.downloadJarFromURL(loopServer.jarURL(), vfs, /* log = */ null))
                    .isInstanceOf(IOException.class)
                    .hasMessage("Redirected more than 20 times: " + loopServer.jarURL());
        }
    }

    /**
     * A server that accepts the connection and then sends nothing is given up on, rather than holding the scan open
     * for as long as it cares to keep the socket open. (A blocked socket read cannot be interrupted, so without a
     * read timeout there is nothing that can stop such a scan.)
     *
     * @throws IOException
     *             if the server socket could not be opened
     */
    @Test
    public void aServerThatAcceptsTheConnectionAndThenStallsIsGivenUpOn() throws IOException {
        final var accepted = new AtomicReference<Socket>();
        try (var serverSocket = new ServerSocket(0, /* backlog = */ 1, InetAddress.getLoopbackAddress())) {
            // The connection is accepted and then left open, with no response ever written to it
            final var thread = new Thread(() -> {
                try {
                    accepted.set(serverSocket.accept());
                } catch (final IOException e) {
                    // The server socket was closed at the end of the test
                }
            });
            thread.setDaemon(true);
            thread.start();
            final var jarURL = "http://" + serverSocket.getInetAddress().getHostAddress() + ":"
                    + serverSocket.getLocalPort() + "/stalled.jar";

            assertThatThrownBy(() -> JarURLDownloader.downloadJarFromURL(jarURL, vfs, /* log = */ null))
                    .isInstanceOf(SocketTimeoutException.class);
        } finally {
            final var acceptedSocket = accepted.get();
            if (acceptedSocket != null) {
                acceptedSocket.close();
            }
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
        final var physicalZipFile = JarURLDownloader.downloadJarFromURL(jar.toUri().toString(), vfs, log);

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

        final var physicalZipFile = JarURLDownloader.downloadJarFromURL(jarURL, vfs, /* log = */ null);

        assertThat(physicalZipFile.slice.load()).isEqualTo(innerJarBytes);
        assertThat(physicalZipFile.getPathString()).isEqualTo(jarURL);
    }

    /** A URL that is not a URL at all is reported, rather than being opened. */
    @Test
    public void aUrlThatCannotBeParsedIsReported() {
        assertThatThrownBy(() -> JarURLDownloader.downloadJarFromURL("not a url", vfs, /* log = */ null))
                .isInstanceOf(IOException.class).hasMessageEndingWith(": not a url")
                .hasCauseInstanceOf(URISyntaxException.class);
    }

    /**
     * The reason a URL could not be opened is reported, so that a URL whose scheme nothing has registered a handler
     * for says so, rather than only saying that the URL could not be parsed.
     */
    @Test
    public void theReasonAUrlCouldNotBeParsedIsReported() {
        assertThatThrownBy(() -> JarURLDownloader.downloadJarFromURL("nosuchscheme:/x.jar", vfs, /* log = */ null))
                .isInstanceOf(IOException.class).hasMessageContaining("unknown protocol: nosuchscheme")
                .hasCauseInstanceOf(MalformedURLException.class);
    }

    /**
     * A URL that cannot be turned into a URI, because it contains a character that has to be escaped, is reported
     * rather than being silently skipped. (The jar is never opened, since the URL cannot be resolved to a path, so
     * the URL does not have to name a jar that exists.)
     */
    @Test
    public void aUrlThatIsNotAValidUriIsReported() {
        final var jarURL = "file:/jars/a jar with spaces.jar";
        assertThatThrownBy(() -> JarURLDownloader.downloadJarFromURL(jarURL, vfs, /* log = */ null))
                .isInstanceOf(IOException.class).hasMessageStartingWith("Could not convert URL to a path (")
                .hasMessageEndingWith(jarURL).hasCauseInstanceOf(URISyntaxException.class);
    }
}
