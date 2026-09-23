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
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.Path;
import java.util.Locale;

import io.github.classgraph.base.LogNode;
import io.github.classgraph.vfs.Vfs;
import org.jspecify.annotations.Nullable;

/** Fetch a jarfile named by a URL, so that it can be scanned. */
final class JarURLDownloader {
    /** The connect timeout and the read timeout of a URL connection, in milliseconds. */
    private static final int TIMEOUT_MILLIS = 5000;

    /** The most redirects that are followed for one URL. (The JDK's default for {@code http.maxRedirects}.) */
    private static final int MAX_REDIRECTS = 20;

    /** Not instantiable. */
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
     * Read a jar from a URL into RAM, or into a temporary file if it is larger than
     * {@link io.github.classgraph.vfs.VfsSpec#getMaxBufferedJarRAMSize()}. A URL that names a {@link Path} in an
     * installed filesystem, such as a "file:" URL, is opened as that {@link Path} rather than read through the URL.
     * An http or https redirect is followed, as long as {@link #redirectTarget(URL, String, Vfs)} allows it.
     *
     * @param jarURL
     *            the jar URL
     * @param vfs
     *            the {@link Vfs} that is opening this jarfile
     * @param log
     *            the log node, or null to skip logging
     * @return the jar, as a {@link PhysicalZipFile}.
     * @throws IOException
     *             If the jar could not be read, the jar URL is malformed, a redirect could not be followed, or the
     *             temporary file could not be created or written.
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
                throw new IOException("Could not convert URL to a path (" + e + "): " + url, e);
            } catch (final FileSystemNotFoundException e) {
                // Not a custom filesystem
            }
        }
        for (var numRedirects = 0;; numRedirects++) {
            try (final CloseableUrlConnection urlConn = new CloseableUrlConnection(url)) {
                urlConn.conn.setConnectTimeout(TIMEOUT_MILLIS);
                // Without a read timeout, a server that accepts the connection and then sends nothing blocks the
                // scan for as long as it cares to hold the socket open, and a blocked socket read does not answer
                // to the interruption checker, so nothing can stop the scan. This bounds the wait for the next
                // block of the response, not the time the whole download is allowed to take.
                urlConn.conn.setReadTimeout(TIMEOUT_MILLIS);
                if (urlConn.httpConn != null) {
                    // HttpURLConnection only follows a redirect that keeps the same scheme, so it does not follow
                    // a redirect from http to https. Redirects are followed below instead, so that every redirect
                    // is checked the same way.
                    urlConn.httpConn.setInstanceFollowRedirects(false);
                }
                urlConn.conn.connect();
                if (urlConn.httpConn != null) {
                    final var responseCode = urlConn.httpConn.getResponseCode();
                    if (isRedirect(responseCode)) {
                        if (numRedirects == MAX_REDIRECTS) {
                            throw new IOException("Redirected more than " + MAX_REDIRECTS + " times: " + jarURL);
                        }
                        final var location = urlConn.httpConn.getHeaderField("Location");
                        if (location == null) {
                            throw new IOException(
                                    "Got response code " + responseCode + " with no Location for URL " + url);
                        }
                        final var redirectURL = redirectTarget(url, location, vfs);
                        if (log != null) {
                            log.log("URL " + url + " redirects to " + redirectURL);
                        }
                        url = redirectURL;
                        continue;
                    }
                    if (responseCode != HttpURLConnection.HTTP_OK) {
                        throw new IOException("Got response code " + responseCode + " for URL " + url);
                    }
                }
                return download(urlConn, jarURL, vfs, log);
            }
        }
    }

    /**
     * Whether an HTTP response code is a redirect that should be followed.
     *
     * @param responseCode
     *            the HTTP response code.
     * @return true for 301, 302, 303, 307 and 308. (300 and 305 are left alone, as HttpURLConnection leaves them.)
     */
    private static boolean isRedirect(final int responseCode) {
        return switch (responseCode) {
        case HttpURLConnection.HTTP_MOVED_PERM, HttpURLConnection.HTTP_MOVED_TEMP, HttpURLConnection.HTTP_SEE_OTHER,
                307, 308 ->
            true;
        default -> false;
        };
    }

    /**
     * Find the URL that a redirect points to, and check that it may be fetched. A redirect may only go to an http
     * or https URL, may not go from https to http, and may not go to a scheme that has been denied.
     *
     * @param url
     *            the URL that was redirected.
     * @param location
     *            the value of the redirect's {@code Location} header, which may be relative to {@code url}.
     * @param vfs
     *            the {@link Vfs} that is opening the jarfile, whose denied URL schemes are checked.
     * @return the URL to fetch next.
     * @throws IOException
     *             if the location is not a valid URL, or the redirect may not be followed.
     */
    static URL redirectTarget(final URL url, final String location, final Vfs vfs) throws IOException {
        final URL redirectURL;
        try {
            redirectURL = url.toURI().resolve(location).toURL();
        } catch (final URISyntaxException | IllegalArgumentException | MalformedURLException e) {
            throw new IOException("Could not follow the redirect from " + url + " to " + location, e);
        }
        final var scheme = redirectURL.getProtocol().toLowerCase(Locale.ROOT);
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            throw new IOException("Not following the redirect from " + url + " to a URL that is not http or https: "
                    + redirectURL);
        }
        if ("https".equalsIgnoreCase(url.getProtocol()) && "http".equals(scheme)) {
            throw new IOException("Not following the redirect from https to http: " + url + " to " + redirectURL);
        }
        if (vfs.getVfsSpec().getDeniedURLSchemes().contains(scheme)) {
            throw new IOException("Fetching a jarfile over \"" + scheme + ":\" is not allowed: " + redirectURL
                    + " (redirected from " + url + ")");
        }
        return redirectURL;
    }

    /**
     * Read a jar from a URL connection that has been connected, and has answered with the jar.
     *
     * @param urlConn
     *            the URL connection.
     * @param jarURL
     *            the jar URL, as it was given, before any redirect.
     * @param vfs
     *            the {@link Vfs} that is opening this jarfile.
     * @param log
     *            the log node, or null to skip logging.
     * @return the jar, as a {@link PhysicalZipFile}.
     * @throws IOException
     *             If the jar could not be read, or the temporary file could not be created or written.
     */
    private static PhysicalZipFile download(final CloseableUrlConnection urlConn, final String jarURL,
            final Vfs vfs, final @Nullable LogNode log) throws IOException {
        // Try to read content length hint
        var contentLengthHint = urlConn.conn.getContentLengthLong();
        if (contentLengthHint < -1L) {
            contentLengthHint = -1L;
        }
        // Fetch content from URL
        final var subLog = log == null ? null : log.log("Downloading jar from URL " + jarURL);
        try (var inputStream = urlConn.conn.getInputStream()) {
            // Fetch the jar contents from the URL's InputStream. If it doesn't fit in RAM, spill over to disk.
            final PhysicalZipFile physicalZipFile = new PhysicalZipFile(inputStream, contentLengthHint, jarURL, vfs,
                    subLog);
            if (subLog != null) {
                subLog.addElapsedTime();
                subLog.log("***** Note that a jar at a non-\"file:\" URL is downloaded by every Vfs that opens it, "
                        + "and the ClassLoader downloads it separately *****");
            }
            return physicalZipFile;

        } catch (final MalformedURLException e) {
            // Chain the cause, as well as naming the URL -- otherwise which part of the URL the stream handler
            // could not make sense of is lost
            throw new IOException("Malformed URL: " + jarURL, e);
        }
    }
}
