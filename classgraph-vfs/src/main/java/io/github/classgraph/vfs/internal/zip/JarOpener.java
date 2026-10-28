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

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Locale;

import org.jspecify.annotations.Nullable;

import io.github.classgraph.base.LogNode;
import io.github.classgraph.vfs.Vfs;

/**
 * Opens toplevel jarfiles from a {@link File}, URL, {@link Path}, or {@link InputStream} (nested jarfiles are
 * opened via {@link LogicalZipFile#openNestedJar(FastZipEntry, LogNode)}).
 */
public final class JarOpener {
    /**
     * An opened jarfile and the backing {@link PhysicalZipFile} owned by the caller, if any.
     *
     * @param zipFile
     *            the opened {@link LogicalZipFile}.
     * @param ownedPhysicalZipFile
     *            the {@link PhysicalZipFile} owned by the caller (which the caller must
     *            {@linkplain PhysicalZipFile#close() close} when done), or null if the jarfile is a stored nested
     *            entry sharing an enclosing {@link PhysicalZipFile}.
     */
    public record OpenedJar(LogicalZipFile zipFile, @Nullable PhysicalZipFile ownedPhysicalZipFile) {
    }

    /** Not instantiable. */
    private JarOpener() {
        // Cannot be constructed
    }

    /**
     * Open a jarfile on disk.
     *
     * @param canonicalFile
     *            the canonical {@link File} for the jarfile.
     * @param vfs
     *            the {@link Vfs} opening this jarfile.
     * @param log
     *            the log node, or null to skip logging.
     * @return the opened jarfile and its backing {@link PhysicalZipFile}.
     * @throws IOException
     *             if the jarfile could not be opened or read.
     */
    public static OpenedJar openJarFile(final File canonicalFile, final Vfs vfs, final @Nullable LogNode log)
            throws IOException {
        return readCentralDirectory(new PhysicalZipFile(canonicalFile, vfs, log), vfs, log);
    }

    /**
     * Open a jarfile from a URL, downloading it to RAM or spilling to a temporary file if it exceeds the configured
     * buffer limit.
     *
     * @param url
     *            the jarfile URL, with any {@code "jar:"} prefix stripped.
     * @param vfs
     *            the {@link Vfs} opening this jarfile.
     * @param log
     *            the log node, or null to skip logging.
     * @return the opened jarfile and its backing {@link PhysicalZipFile}.
     * @throws IOException
     *             if the URL's scheme is denied or the jarfile could not be downloaded or read.
     */
    public static OpenedJar openJarFromURL(final String url, final Vfs vfs, final @Nullable LogNode log)
            throws IOException {
        // Whatever the JVM can open is opened, so only a scheme the caller took away is refused here. A
        // scheme that nothing has registered a URL stream handler or a filesystem provider for is left to the
        // download below, which reports the JVM's own reason for not being able to open it. ("file:" and "jar:"
        // never reach here -- FastPathResolver.resolve() has already stripped them.) The download checks the
        // scheme again once the URL is parsed, since the parser can read a different scheme from the same text
        checkURLSchemeNotDenied(url, vfs);
        // Read the jar from the URL into RAM, or into a temporary file if it is too large for RAM
        return readCentralDirectory(JarURLDownloader.downloadJarFromURL(url, vfs, log), vfs, log);
    }

    /**
     * Throw {@link IOException} if the scheme of {@code url} has been denied via
     * {@link io.github.classgraph.vfs.VfsSpec#denyURLScheme(String)}.
     *
     * @param url
     *            the URL (with any {@code "jar:"} prefix stripped).
     * @param vfs
     *            the {@link Vfs} whose denied URL schemes are checked.
     * @throws IOException
     *             if the URL's scheme is denied.
     */
    public static void checkURLSchemeNotDenied(final String url, final Vfs vfs) throws IOException {
        checkURLSchemeNotDenied(url.substring(0, url.indexOf(':')), url, vfs);
    }

    /**
     * Throw {@link IOException} if {@code scheme} has been denied via
     * {@link io.github.classgraph.vfs.VfsSpec#denyURLScheme(String)}.
     *
     * @param scheme
     *            the URL scheme (case-insensitive, without trailing {@code ':'}).
     * @param location
     *            the URL or location string for the exception message.
     * @param vfs
     *            the {@link Vfs} whose denied URL schemes are checked.
     * @throws IOException
     *             if the scheme is denied.
     */
    static void checkURLSchemeNotDenied(final String scheme, final String location, final Vfs vfs)
            throws IOException {
        // URL schemes are case-insensitive, and are denied in lowercase, so the scheme has to be lowercased
        // before it is looked up -- otherwise "HTTP://host/x.jar" is fetched even though "http" was denied
        final var lowercaseScheme = scheme.toLowerCase(Locale.ROOT);
        if (vfs.getVfsSpec().getDeniedURLSchemes().contains(lowercaseScheme)) {
            throw new IOException("The URL scheme \"" + lowercaseScheme + ":\" has been denied: " + location);
        }
    }

    /**
     * Open a jarfile from a {@link Path}.
     *
     * @param path
     *            the path of the jarfile.
     * @param vfs
     *            the {@link Vfs} opening this jarfile.
     * @param log
     *            the log node, or null to skip logging.
     * @return the opened jarfile and its backing {@link PhysicalZipFile}.
     * @throws IOException
     *             if the jarfile could not be opened or read.
     */
    public static OpenedJar openJarFromPath(final Path path, final Vfs vfs, final @Nullable LogNode log)
            throws IOException {
        return readCentralDirectory(new PhysicalZipFile(path, vfs, log), vfs, log);
    }

    /**
     * Open a jarfile from an {@link InputStream}, buffering it in RAM or spilling to a temporary file if it exceeds
     * the configured buffer limit.
     *
     * @param inputStream
     *            the stream to read the jarfile from (not closed by this method).
     * @param inputStreamLengthHint
     *            the expected stream length in bytes, or -1 if unknown.
     * @param name
     *            the display name for the jarfile, used in log messages and entry paths.
     * @param vfs
     *            the {@link Vfs} opening this jarfile.
     * @param log
     *            the log node, or null to skip logging.
     * @return the opened jarfile and its backing {@link PhysicalZipFile}.
     * @throws IOException
     *             if the jarfile could not be read.
     */
    public static OpenedJar openJarFromInputStream(final InputStream inputStream, final long inputStreamLengthHint,
            final String name, final Vfs vfs, final @Nullable LogNode log) throws IOException {
        // The physical zipfile is created here rather than fetched from a cache, so nothing else can reach it, and
        // each call reads its own copy of its stream, so two streams read under the same name stay two separate
        // jarfiles
        return readCentralDirectory(new PhysicalZipFile(inputStream, inputStreamLengthHint, name, vfs, log), vfs,
                log);
    }

    /**
     * Read the central directory of a newly opened {@link PhysicalZipFile}, releasing {@code physicalZipFile} if an
     * exception is thrown.
     *
     * @param physicalZipFile
     *            the newly opened physical zipfile.
     * @param vfs
     *            the {@link Vfs} opening this jarfile.
     * @param log
     *            the log node, or null to skip logging.
     * @return the opened jarfile and {@code physicalZipFile}.
     * @throws IOException
     *             if the central directory could not be read or the {@link Vfs} is closed.
     */
    private static OpenedJar readCentralDirectory(final PhysicalZipFile physicalZipFile, final Vfs vfs,
            final @Nullable LogNode log) throws IOException {
        try {
            // The Vfs may have been closed while the jarfile was being opened or downloaded. Nothing would leak if
            // the central directory were read anyway, since the Vfs closes any root built after it was closed, and
            // that releases this zipfile, but the read would be wasted
            if (vfs.isClosed()) {
                throw new IOException("Already closed");
            }
            return new OpenedJar(
                    new LogicalZipFile(/* parent = */ null, physicalZipFile, physicalZipFile.getPathString(),
                            physicalZipFile.slice, vfs, log, vfs.getVfsSpec().isMultiReleaseVersionsEnabled()),
                    physicalZipFile);
        } catch (final IOException | RuntimeException | Error e) {
            // The caller gets the exception rather than the zipfile, so nothing would ever reach what was just
            // opened
            physicalZipFile.releaseUnreachable(e);
            throw e;
        }
    }
}
