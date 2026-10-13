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
 * Opens toplevel jarfiles, however they are named -- by a canonical {@link File}, by a URL, by a {@link Path} in a
 * non-default filesystem, or from an {@link InputStream} -- and hands back the {@link LogicalZipFile} for each.
 * This is the doorway between the root layer, which caches and owns the opened roots, and the zipfile layer, whose
 * constructors are not visible outside this package. A jarfile nested within an opened jarfile is opened by
 * {@link LogicalZipFile#openNestedJar(FastZipEntry, LogNode)} on the jarfile that holds it.
 *
 * <p>
 * Nothing here is cached: a jarfile is opened once per call, and the caller decides what is shared. Each open hands
 * back an {@link OpenedJar}: the {@link LogicalZipFile} to read, and the {@link PhysicalZipFile} that was opened to
 * read it, if one was, which the caller now owns and must close. If opening fails, nothing is handed back, so
 * whatever was opened before the failure is released here.
 */
public final class JarOpener {
    /**
     * A jarfile that was just opened, and the resource that was opened to read it, if any.
     *
     * @param zipFile
     *            the jarfile to read.
     * @param ownedPhysicalZipFile
     *            the physical zipfile that was opened, downloaded, extracted or inflated to read the jarfile, which
     *            the caller now owns, and must {@linkplain PhysicalZipFile#close() close} when it is done with the
     *            jarfile -- or null if nothing was opened, because the jarfile is read in place through a physical
     *            zipfile that something else owns.
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
     *            the jarfile, as a canonical {@link File}, so that the path of the opened zipfile names the file
     *            the same way however the caller reached it.
     * @param vfs
     *            the {@link Vfs} that is opening this jarfile.
     * @param log
     *            the log node, or null to skip logging.
     * @return the jarfile, with the physical zipfile that was opened to read it, which the caller owns.
     * @throws IOException
     *             if the jarfile could not be opened or read.
     */
    public static OpenedJar openJarFile(final File canonicalFile, final Vfs vfs, final @Nullable LogNode log)
            throws IOException {
        return readCentralDirectory(new PhysicalZipFile(canonicalFile, vfs, log), vfs, log);
    }

    /**
     * Open a jarfile named by a URL, downloading it to a buffer in RAM, or to a temporary file if it is too large.
     *
     * @param url
     *            the URL of the jarfile, with any {@code "jar:"} prefix already stripped.
     * @param vfs
     *            the {@link Vfs} that is opening this jarfile.
     * @param log
     *            the log node, or null to skip logging.
     * @return the jarfile, with the physical zipfile it was downloaded to, which the caller owns.
     * @throws IOException
     *             if the URL's scheme has been denied, or the jarfile could not be downloaded or read.
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
     * Refuse a URL whose scheme {@link io.github.classgraph.vfs.VfsSpec#denyURLScheme(String)} denied, whether it
     * names a jarfile or a directory.
     *
     * @param url
     *            the URL, with any {@code "jar:"} prefix already stripped, which has a URL scheme.
     * @param vfs
     *            the {@link Vfs} that is opening the URL, whose denied URL schemes are checked.
     * @throws IOException
     *             if the URL's scheme has been denied.
     */
    public static void checkURLSchemeNotDenied(final String url, final Vfs vfs) throws IOException {
        checkURLSchemeNotDenied(url.substring(0, url.indexOf(':')), url, vfs);
    }

    /**
     * Refuse a URL scheme that {@link io.github.classgraph.vfs.VfsSpec#denyURLScheme(String)} denied. Every check
     * of a denied scheme goes through here, so that the refusal reads the same however the scheme was found.
     *
     * @param scheme
     *            the URL scheme, in any case, without the trailing {@code ':'}.
     * @param location
     *            the URL or other text that names what was being opened, for the exception message.
     * @param vfs
     *            the {@link Vfs} that is opening the URL, whose denied URL schemes are checked.
     * @throws IOException
     *             if the scheme has been denied.
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
     * Open a jarfile named by a {@link Path}. Use this only for a {@link Path} that is not in the default
     * filesystem, such as a zipfile mounted as a filesystem, since a path in the default filesystem can name a
     * jarfile nested within another jarfile, which only the string form of the path can express.
     *
     * @param path
     *            the path of the jarfile.
     * @param vfs
     *            the {@link Vfs} that is opening this jarfile.
     * @param log
     *            the log node, or null to skip logging.
     * @return the jarfile, with the physical zipfile that was opened to read it, which the caller owns.
     * @throws IOException
     *             if the jarfile could not be opened or read.
     */
    public static OpenedJar openJarFromPath(final Path path, final Vfs vfs, final @Nullable LogNode log)
            throws IOException {
        return readCentralDirectory(new PhysicalZipFile(path, vfs, log), vfs, log);
    }

    /**
     * Open a jarfile read from an {@link InputStream}. The stream is read to an array in RAM, or spilled to a
     * temporary file if it is longer than the configured maximum, since a zipfile's central directory is at the end
     * of the file and so cannot be reached by reading forwards.
     *
     * @param inputStream
     *            the stream to read the jarfile from. The caller retains ownership of the stream, and this method
     *            does not close it.
     * @param inputStreamLengthHint
     *            the number of bytes to read from {@code inputStream}, or -1 if unknown.
     * @param name
     *            a name for the jarfile, used in log messages and in the paths of its entries.
     * @param vfs
     *            the {@link Vfs} that is opening this jarfile.
     * @param log
     *            the log node, or null to skip logging.
     * @return the jarfile, with the physical zipfile it was read to, which the caller owns.
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
     * Read the central directory of a physical zipfile that was just opened, downloaded or read.
     *
     * @param physicalZipFile
     *            the physical zipfile, which passes to the caller if this returns, and is released here if it
     *            throws.
     * @param vfs
     *            the {@link Vfs} that is opening this jarfile.
     * @param log
     *            the log node, or null to skip logging.
     * @return the jarfile, with the physical zipfile, which the caller owns.
     * @throws IOException
     *             if the central directory could not be read, or the {@link Vfs} has been closed.
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
