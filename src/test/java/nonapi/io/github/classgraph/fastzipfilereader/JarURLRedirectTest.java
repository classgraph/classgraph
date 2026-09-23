package nonapi.io.github.classgraph.fastzipfilereader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;

import nonapi.io.github.classgraph.concurrency.InterruptionChecker;
import nonapi.io.github.classgraph.reflection.ReflectionUtils;
import nonapi.io.github.classgraph.scanspec.ScanSpec;
import nonapi.io.github.classgraph.utils.LogNode;

/** Following an http or https redirect when a jarfile is downloaded. */
public class JarURLRedirectTest {
    /** An HTTP server on the loopback interface that answers every request with the same response. */
    private static final class CannedResponseHttpServer implements AutoCloseable {
        /** The server socket. */
        private final ServerSocket serverSocket;

        /**
         * Constructor. Starts the server.
         *
         * @param statusLine
         *            the status line to answer with, e.g. {@code "301 Moved Permanently"}
         * @param location
         *            the value of the {@code Location} header, or null not to send that header
         * @param body
         *            the body to answer with
         * @throws IOException
         *             if the server socket could not be opened
         */
        CannedResponseHttpServer(final String statusLine, final String location, final byte[] body)
                throws IOException {
            serverSocket = new ServerSocket(0, /* backlog = */ 1, InetAddress.getLoopbackAddress());
            final Thread thread = new Thread(new Runnable() {
                @Override
                public void run() {
                    serve(statusLine, location, body);
                }
            });
            thread.setDaemon(true);
            thread.start();
        }

        /**
         * Answer requests until the server socket is closed.
         *
         * @param statusLine
         *            the status line to answer with
         * @param location
         *            the value of the {@code Location} header, or null not to send that header
         * @param body
         *            the body to answer with
         */
        private void serve(final String statusLine, final String location, final byte[] body) {
            while (!serverSocket.isClosed()) {
                try (Socket socket = serverSocket.accept()) {
                    readRequest(socket.getInputStream());
                    final StringBuilder headers = new StringBuilder("HTTP/1.1 " + statusLine + "\r\n");
                    headers.append("Content-Length: ").append(body.length).append("\r\n");
                    if (location != null) {
                        headers.append("Location: ").append(location).append("\r\n");
                    }
                    headers.append("Connection: close\r\n\r\n");
                    final OutputStream outputStream = socket.getOutputStream();
                    outputStream.write(headers.toString().getBytes(StandardCharsets.US_ASCII));
                    outputStream.write(body);
                    outputStream.flush();
                } catch (final IOException e) {
                    // The server socket was closed, or the client went away
                }
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
         * The URL of a jar on this server.
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

    /**
     * Build a jar holding one entry.
     *
     * @return the bytes of the jar
     * @throws IOException
     *             if the jar could not be built
     */
    private static byte[] buildJar() throws IOException {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zipOut = new ZipOutputStream(bytes)) {
            zipOut.putNextEntry(new ZipEntry("entry.txt"));
            zipOut.write("Downloaded after a redirect".getBytes(StandardCharsets.UTF_8));
            zipOut.closeEntry();
        }
        return bytes.toByteArray();
    }

    /**
     * Make a {@link NestedJarHandler} for a scan that has enabled the given URL schemes.
     *
     * @param schemes
     *            the URL schemes to enable
     * @return the {@link NestedJarHandler}
     */
    private static NestedJarHandler nestedJarHandler(final String... schemes) {
        final ScanSpec scanSpec = new ScanSpec();
        for (final String scheme : schemes) {
            scanSpec.enableURLScheme(scheme);
        }
        return new NestedJarHandler(scanSpec, new InterruptionChecker(), new ReflectionUtils());
    }

    /**
     * A redirect is followed to the jar, and the redirect is logged.
     *
     * @throws Exception
     *             if the jar could not be built or downloaded
     */
    @Test
    public void aRedirectIsFollowedToTheJar() throws Exception {
        final byte[] jarBytes = buildJar();
        final NestedJarHandler nestedJarHandler = nestedJarHandler("http");
        final LogNode log = new LogNode();
        try (CannedResponseHttpServer jarServer = new CannedResponseHttpServer("200 OK", null, jarBytes);
                CannedResponseHttpServer redirectServer = new CannedResponseHttpServer("302 Found",
                        jarServer.jarURL(), new byte[0])) {
            final PhysicalZipFile physicalZipFile = nestedJarHandler.downloadJarFromURL(redirectServer.jarURL(),
                    log);
            assertThat(physicalZipFile.slice.load()).isEqualTo(jarBytes);
            assertThat(log.toString())
                    .contains("URL " + redirectServer.jarURL() + " redirects to " + jarServer.jarURL());
        } finally {
            nestedJarHandler.close(/* log = */ null);
        }
    }

    /**
     * A redirect from http to https is followed. (HttpURLConnection only follows a redirect that keeps the same
     * scheme.) The test has no TLS server, so it checks that the https URL was connected to with a TLS handshake,
     * whose first byte is 0x16, and that the handshake then failed.
     *
     * @throws Exception
     *             if a server could not be started
     */
    @Test
    public void aRedirectFromHttpToHttpsIsFollowed() throws Exception {
        final AtomicInteger firstByteReceived = new AtomicInteger(-1);
        final NestedJarHandler nestedJarHandler = nestedJarHandler("http", "https");
        try (ServerSocket tlsServerSocket = new ServerSocket(0, /* backlog = */ 1,
                InetAddress.getLoopbackAddress())) {
            final Thread thread = new Thread(new Runnable() {
                @Override
                public void run() {
                    try (Socket socket = tlsServerSocket.accept()) {
                        firstByteReceived.set(socket.getInputStream().read());
                    } catch (final IOException e) {
                        // The server socket was closed at the end of the test
                    }
                }
            });
            thread.setDaemon(true);
            thread.start();
            final String httpsURL = "https://" + tlsServerSocket.getInetAddress().getHostAddress() + ":"
                    + tlsServerSocket.getLocalPort() + "/downloaded.jar";
            try (CannedResponseHttpServer redirectServer = new CannedResponseHttpServer("301 Moved Permanently",
                    httpsURL, new byte[0])) {
                assertThatThrownBy(() -> nestedJarHandler.downloadJarFromURL(redirectServer.jarURL(), null))
                        .isInstanceOf(IOException.class);
            }
            thread.join(10_000);
        } finally {
            nestedJarHandler.close(/* log = */ null);
        }
        assertThat(firstByteReceived.get()).isEqualTo(0x16);
    }

    /**
     * A redirect to a scheme that has not been enabled is refused, so enabling "http" alone does not let a server
     * redirect the download to "https".
     *
     * @throws Exception
     *             if the server could not be started
     */
    @Test
    public void aRedirectToASchemeThatIsNotEnabledIsRefused() throws Exception {
        final NestedJarHandler nestedJarHandler = nestedJarHandler("http");
        try (CannedResponseHttpServer redirectServer = new CannedResponseHttpServer("301 Moved Permanently",
                "https://example.com/downloaded.jar", new byte[0])) {
            assertThatThrownBy(() -> nestedJarHandler.downloadJarFromURL(redirectServer.jarURL(), null))
                    .isInstanceOf(IOException.class)
                    .hasMessage("Scanning of URL scheme \"https\" has not been enabled -- cannot follow the redirect"
                            + " from " + redirectServer.jarURL() + " to https://example.com/downloaded.jar");
        } finally {
            nestedJarHandler.close(/* log = */ null);
        }
    }

    /**
     * A redirect to a URL that is not http or https is refused, so that a remote server cannot point the scan at a
     * local file.
     *
     * @throws Exception
     *             if the server could not be started
     */
    @Test
    public void aRedirectToAFileUrlIsRefused() throws Exception {
        final NestedJarHandler nestedJarHandler = nestedJarHandler("http", "file");
        try (CannedResponseHttpServer redirectServer = new CannedResponseHttpServer("302 Found",
                "file:/etc/passwd", new byte[0])) {
            assertThatThrownBy(() -> nestedJarHandler.downloadJarFromURL(redirectServer.jarURL(), null))
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("to a URL that is not http or https: file:/etc/passwd");
        } finally {
            nestedJarHandler.close(/* log = */ null);
        }
    }

    /**
     * A redirect from https to http is refused, since it would fetch the jar without TLS. A relative location is
     * resolved against the URL that was redirected.
     *
     * @throws Exception
     *             if the URL could not be built
     */
    @Test
    public void aRedirectFromHttpsToHttpIsRefused() throws Exception {
        final URL httpsURL = new URL("https://example.com/lib/a.jar");
        final ScanSpec scanSpec = new ScanSpec();
        scanSpec.enableURLScheme("http");
        scanSpec.enableURLScheme("https");
        assertThatThrownBy(() -> NestedJarHandler.redirectTarget(httpsURL, "http://example.com/lib/a.jar",
                scanSpec.allowedURLSchemes)).isInstanceOf(IOException.class)
                        .hasMessageStartingWith("Not following the redirect from https to http");
        assertThat(NestedJarHandler.redirectTarget(httpsURL, "../b.jar", scanSpec.allowedURLSchemes).toString())
                .isEqualTo("https://example.com/b.jar");
    }

    /**
     * A server that redirects to itself is given up on after a fixed number of redirects. (The redirect is to a
     * relative location, the server's own path.)
     *
     * @throws Exception
     *             if the server could not be started
     */
    @Test
    public void aRedirectLoopIsGivenUpOn() throws Exception {
        final NestedJarHandler nestedJarHandler = nestedJarHandler("http");
        try (CannedResponseHttpServer loopServer = new CannedResponseHttpServer("307 Temporary Redirect",
                "/downloaded.jar", new byte[0])) {
            assertThatThrownBy(() -> nestedJarHandler.downloadJarFromURL(loopServer.jarURL(), null))
                    .isInstanceOf(IOException.class)
                    .hasMessage("Redirected more than 20 times: " + loopServer.jarURL());
        } finally {
            nestedJarHandler.close(/* log = */ null);
        }
    }
}
