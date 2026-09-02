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
package io.github.classgraph.vfs.internal.zip;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.JarURLConnection;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLConnection;
import java.nio.ByteBuffer;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.Path;

import io.github.classgraph.base.LogNode;
import io.github.classgraph.vfs.Vfs;
import org.jspecify.annotations.Nullable;

/** Fetch a jarfile named by a URL, so that it can be scanned. */
final class JarURLDownloader {
    /** HTTP(S) timeout, ms. */
    private static final int HTTP_TIMEOUT = 5000;

    /** Constructor. */
    private JarURLDownloader() {
        // Cannot be constructed
    }

    /**
     * A {@link URLConnection} that can be used in a try-with-resources block, so that the underlying HTTP
     * connection is disconnected when it goes out of scope.
     */
    private static class CloseableUrlConnection implements AutoCloseable {
        /** The connection. */
        public final URLConnection conn;

        /** The connection, if it is an HTTP connection, otherwise null. */
        public final @Nullable HttpURLConnection httpConn;

        /**
         * Constructor.
         *
         * @param url
         *            the URL to open a connection to
         * @throws IOException
         *             if the connection could not be opened
         */
        public CloseableUrlConnection(final URL url) throws IOException {
            conn = url.openConnection();
            // A "jar:" URL connection would otherwise put the jar it names into the JVM-wide jar file cache, which
            // never closes what it holds, so the jar would stay open for the life of the JVM -- and on Windows, an
            // open file cannot be deleted or overwritten. With caching turned off, the jar is this connection's to
            // close.
            conn.setUseCaches(false);
            httpConn = conn instanceof final HttpURLConnection httpUrlConn ? httpUrlConn : null;
        }

        @Override
        public void close() {
            if (httpConn != null) {
                httpConn.disconnect();
            } else if (conn instanceof final JarURLConnection jarConn) {
                // Closing the connection's InputStream closes the jar, but the InputStream is only opened if the
                // jar is actually read, so close the jar here in case it was not
                try {
                    jarConn.getJarFile().close();
                } catch (final IOException e) {
                    // The jar was never opened, or is already closed
                }
            }
        }
    }

    /**
     * Download a jar from a URL to a temporary file, or to a ByteBuffer if the temporary directory is not writeable
     * or full. The downloaded jar is returned wrapped in a {@link PhysicalZipFile} instance.
     *
     * @param jarURL
     *            the jar URL
     * @param vfs
     *            the {@link Vfs} that is opening this jarfile
     * @param log
     *            the log node, or null to skip logging
     * @return the temporary file or {@link ByteBuffer} the jar was downloaded to, wrapped in a
     *         {@link PhysicalZipFile} instance.
     * @throws IOException
     *             If the jar could not be downloaded, or the jar URL is malformed.
     * @throws IllegalArgumentException
     *             If the temp dir is not writeable, or has insufficient space to download the jar. (This is thrown
     *             as a separate exception from IOException, so that the case of an unwriteable temp dir can be
     *             handled separately, by downloading the jar to a ByteBuffer in RAM.)
     */
    static PhysicalZipFile downloadJarFromURL(final String jarURL, final Vfs vfs, final @Nullable LogNode log)
            throws IOException {
        URL url = null;
        try {
            url = new URL(jarURL);
        } catch (final MalformedURLException e1) {
            try {
                url = new URI(jarURL).toURL();
            } catch (final MalformedURLException | IllegalArgumentException | URISyntaxException e2) {
                // Chain the cause, as well as naming it in the message -- for a URL whose scheme nothing has
                // registered a URL stream handler for, the cause is the JVM's own "unknown protocol" report,
                // which is the whole reason the URL could not be opened
                throw new IOException("Could not parse URL (" + e2 + "): " + jarURL, e2);
            }
        }

        final var scheme = url.getProtocol();
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
            // Check if this URL is backed by a filesystem -- if it is, don't download a copy of the file over the
            // URL; instead, access the filesystem directly
            try {
                final var path = Path.of(url.toURI());
                // Fails with FileSystemNotFoundException if filesystem not registered for URL
                final var fs = path.getFileSystem();
                if (log != null) {
                    log.log("URL " + jarURL + " is backed by filesystem " + fs.getClass().getName());
                }
                // Wrap Path in PhysicalZipFile and return it
                return new PhysicalZipFile(path, vfs, log);
            } catch (final IllegalArgumentException | SecurityException | URISyntaxException e) {
                throw new IOException("Could not convert URL to URI (" + e + "): " + url, e);
            } catch (final FileSystemNotFoundException e) {
                // Not a custom filesystem
            }
        }
        try (final CloseableUrlConnection urlConn = new CloseableUrlConnection(url)) {
            urlConn.conn.setConnectTimeout(HTTP_TIMEOUT);
            // Without a read timeout, a server that accepts the connection and then sends nothing blocks the scan
            // for as long as it cares to hold the socket open, and a blocked socket read does not answer to the
            // interruption checker, so nothing can stop the scan. This bounds the wait for the next block of the
            // response, not the time the whole download is allowed to take.
            urlConn.conn.setReadTimeout(HTTP_TIMEOUT);
            urlConn.conn.connect();
            if (urlConn.httpConn != null) {
                if (urlConn.httpConn.getResponseCode() != HttpURLConnection.HTTP_OK) {
                    throw new IOException(
                            "Got response code " + urlConn.httpConn.getResponseCode() + " for URL " + url);
                }
            } else if ("file".equalsIgnoreCase(url.getProtocol())) {
                // We ended up with a "file:" URL, which can happen as a result of a custom URL scheme that rewrites
                // its URLs into "file:" URLs (see Issue400.java).
                try {
                    // If this is a "file:" URL, get the file from the URL and return it as a new PhysicalZipFile
                    // (this avoids going through an InputStream). Throws IOException if the file cannot be read.
                    final var file = Path.of(url.toURI()).toFile();
                    return new PhysicalZipFile(file, vfs, log);

                } catch (final Exception e) {
                    // Fall through -- unknown URL type
                }
            }
            // Try to read content length hint
            var contentLengthHint = urlConn.conn.getContentLengthLong();
            if (contentLengthHint < -1L) {
                contentLengthHint = -1L;
            }
            // Fetch content from URL
            final var subLog = log == null ? null : log.log("Downloading jar from URL " + jarURL);
            try (var inputStream = urlConn.conn.getInputStream()) {
                // Fetch the jar contents from the URL's InputStream. If it doesn't fit in RAM, spill over to disk.
                final PhysicalZipFile physicalZipFile = new PhysicalZipFile(inputStream, contentLengthHint, jarURL,
                        vfs, subLog);
                if (subLog != null) {
                    subLog.addElapsedTime();
                    subLog.log("***** Note that it is time-consuming to scan jars at non-\"file:\" URLs, "
                            + "the URL must be opened (possibly after an http(s) fetch) for every scan, "
                            + "and the same URL must also be separately opened by the ClassLoader *****");
                }
                return physicalZipFile;

            } catch (final MalformedURLException e) {
                // Chain the cause, as well as naming the URL -- otherwise which part of the URL the stream handler
                // could not make sense of is lost
                throw new IOException("Malformed URL: " + jarURL, e);
            }
        }
    }
}
