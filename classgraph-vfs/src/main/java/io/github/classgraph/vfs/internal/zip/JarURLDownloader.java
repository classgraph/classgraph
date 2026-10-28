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

import org.jspecify.annotations.Nullable;

import io.github.classgraph.base.LogNode;
import io.github.classgraph.vfs.Vfs;

/** Downloads or opens a jarfile referenced by a URL. */
final class JarURLDownloader {
    /** Connect and read timeout for URL connections, in milliseconds. */
    private static final int TIMEOUT_MILLIS = 5000;

    /** Maximum number of HTTP redirects followed for a single URL. */
    private static final int MAX_REDIRECTS = 20;

    /** Not instantiable. */
    private JarURLDownloader() {
        // Cannot be constructed
    }

    /**
     * An {@link AutoCloseable} wrapper around {@link URLConnection} that disconnects HTTP connections and closes
     * underlying jarfiles when closed.
     */
    private static final class CloseableUrlConnection implements AutoCloseable {
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
                } catch (final IOException _) {
                    // The jar was never opened, or is already closed
                }
            }
        }
    }

    /**
     * Read a jarfile from a URL into RAM (or a temporary file if it exceeds
     * {@link io.github.classgraph.vfs.VfsSpec#getMaxBufferedJarRAMSize()}), or open it directly as a {@link Path}
     * if backed by an installed filesystem.
     *
     * @param jarURL
     *            the jar URL
     * @param vfs
     *            the {@link Vfs} opening this jarfile
     * @param log
     *            the log node, or null to skip logging
     * @return the opened {@link PhysicalZipFile}.
     * @throws IOException
     *             if the URL is malformed, its scheme is denied, a redirect fails, or the jarfile could not be
     *             read.
     */
    // new URL(String) is deprecated from JDK 20, but it accepts URLs that new URI(String) rejects (such as one
    // containing a space), so it is still tried first
    @SuppressWarnings("deprecation")
    static PhysicalZipFile downloadJarFromURL(final String jarURL, final Vfs vfs, final @Nullable LogNode log)
            throws IOException {
        URL url = null;
        try {
            url = new URL(jarURL);
        } catch (final MalformedURLException _) {
            final URI uri;
            try {
                uri = new URI(jarURL);
            } catch (final URISyntaxException e2) {
                throw new IOException("Could not parse URL (" + e2 + "): " + jarURL, e2);
            }
            try {
                url = uri.toURL();
            } catch (final MalformedURLException | IllegalArgumentException e2) {
                // No URL stream handler is registered for the URI's scheme, but a filesystem may still be
                // installed for it, as the Scanner finds when it turns a classpath element into a Path
                final var scheme = uri.getScheme();
                if (scheme != null) {
                    JarOpener.checkURLSchemeNotDenied(scheme, jarURL, vfs);
                    final var physicalZipFile = openThroughFilesystem(uri, jarURL, vfs, log);
                    if (physicalZipFile != null) {
                        return physicalZipFile;
                    }
                }
                // Chain the cause, as well as naming it in the message -- for a URL whose scheme nothing has
                // registered a URL stream handler or a filesystem for, the cause is the JVM's own "unknown
                // protocol" report, which is the whole reason the URL could not be opened
                throw new IOException("Could not parse URL (" + e2 + "): " + jarURL, e2);
            }
        }

        // JarOpener has already checked the text before the first ':', but that need not be the scheme the URL
        // uses: new URL(String) skips a leading "url:" in any case, so "url:mailto:x@y" is a "mailto:" URL. Check
        // the scheme that is actually opened, before anything is opened through it.
        final var scheme = url.getProtocol();
        JarOpener.checkURLSchemeNotDenied(scheme, jarURL, vfs);
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
            // Check if this URL is backed by a filesystem -- if it is, don't download a copy of the file over the
            // URL; instead, access the filesystem directly
            URI uri = null;
            try {
                uri = url.toURI();
            } catch (final URISyntaxException e) {
                // The URL cannot be written as a URI (it may contain a space), so no filesystem can be looked up
                // for it, but new URL(String) accepted it, so its URL handler may still be able to read it
                if (log != null) {
                    log.log("URL " + jarURL + " is not a valid URI, so reading it through its URL handler: " + e);
                }
            }
            if (uri != null) {
                final var physicalZipFile = openThroughFilesystem(uri, jarURL, vfs, log);
                if (physicalZipFile != null) {
                    return physicalZipFile;
                }
                // No filesystem is installed for the URL's scheme, so read it through its URL handler
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
     * Open a jarfile at {@code uri} directly via an installed {@link java.nio.file.FileSystem}, if available.
     *
     * @param uri
     *            the jarfile URI.
     * @param jarURL
     *            the original jar URL string.
     * @param vfs
     *            the {@link Vfs} opening this jarfile.
     * @param log
     *            the log node, or null to skip logging.
     * @return the {@link PhysicalZipFile}, or null if no open filesystem handles {@code uri}.
     * @throws IOException
     *             if the filesystem rejected {@code uri} or the jarfile could not be opened.
     */
    private static @Nullable PhysicalZipFile openThroughFilesystem(final URI uri, final String jarURL,
            final Vfs vfs, final @Nullable LogNode log) throws IOException {
        try {
            // Throws FileSystemNotFoundException if no filesystem provider is installed for the URI's scheme, or
            // the provider has no filesystem open that holds the URI
            final var path = Path.of(uri);
            if (log != null) {
                log.log("URL " + jarURL + " is backed by filesystem " + path.getFileSystem().getClass().getName());
            }
            return new PhysicalZipFile(path, vfs, log);
        } catch (final IllegalArgumentException e) {
            // A filesystem is installed for the URI's scheme, but it rejected the URI
            throw new IOException("Could not convert URL to a path (" + e + "): " + uri, e);
        } catch (final FileSystemNotFoundException _) {
            return null;
        }
    }

    /**
     * Return true if {@code responseCode} is an HTTP redirect status code (301, 302, 303, 307, or 308).
     *
     * @param responseCode
     *            the HTTP response code.
     * @return true if {@code responseCode} is a redirect.
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
     * Resolve and validate an HTTP redirect target URL, rejecting non-HTTP(S) targets, HTTPS-to-HTTP downgrades,
     * and denied schemes.
     *
     * @param url
     *            the URL being redirected.
     * @param location
     *            the {@code Location} header value.
     * @param vfs
     *            the {@link Vfs} whose denied URL schemes are checked.
     * @return the resolved redirect target URL.
     * @throws IOException
     *             if {@code location} is invalid or the redirect is disallowed.
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
        JarOpener.checkURLSchemeNotDenied(scheme, redirectURL + " (redirected from " + url + ")", vfs);
        return redirectURL;
    }

    /**
     * Read a jarfile from an established {@link CloseableUrlConnection}.
     *
     * @param urlConn
     *            the connected URL connection.
     * @param jarURL
     *            the original jar URL string.
     * @param vfs
     *            the {@link Vfs} opening this jarfile.
     * @param log
     *            the log node, or null to skip logging.
     * @return the downloaded {@link PhysicalZipFile}.
     * @throws IOException
     *             if the jarfile could not be downloaded or written to a temporary file.
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
