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

import io.github.classgraph.base.LogNode;
import io.github.classgraph.vfs.Vfs;
import org.jspecify.annotations.Nullable;

/**
 * Opens jarfiles, however they are named -- by a canonical {@link File}, by a URL, by a {@link Path} in a
 * non-default filesystem, from an {@link InputStream}, or as an entry nested within an already-opened jarfile --
 * and hands back the {@link LogicalZipFile} for each. This is the doorway between the root layer, which caches and
 * owns the opened roots, and the zipfile layer, whose classes are not visible outside this package.
 *
 * <p>
 * Nothing here is cached: a jarfile is opened once per call, and the caller decides what is shared. The caller owns
 * the {@link LogicalZipFile} it is handed, and releases what was opened to read it by calling
 * {@link LogicalZipFile#releaseOwnedResources()}. If opening fails, nothing is handed back, so whatever was opened
 * before the failure is released here.
 */
public final class JarOpener {
    /** Constructor. */
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
     * @return the {@link LogicalZipFile} for the jarfile.
     * @throws IOException
     *             if the jarfile could not be opened or read.
     * @throws InterruptedException
     *             if the thread was interrupted.
     */
    public static LogicalZipFile openJarFile(final File canonicalFile, final Vfs vfs, final @Nullable LogNode log)
            throws IOException, InterruptedException {
        final var physicalZipFile = new PhysicalZipFile(canonicalFile, vfs, log);
        try {
            return physicalZipFile.getLogicalZipFile(log);
        } catch (final IOException | InterruptedException | RuntimeException | Error e) {
            // The caller's cache records the failure rather than opening the file again, so nothing would ever
            // reach the file handle that was just opened
            physicalZipFile.releaseUnreachable(e);
            throw e;
        }
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
     * @return the {@link LogicalZipFile} for the jarfile.
     * @throws IOException
     *             if the URL's scheme has been denied, or the jarfile could not be downloaded or read.
     * @throws InterruptedException
     *             if the thread was interrupted.
     */
    public static LogicalZipFile openJarFromURL(final String url, final Vfs vfs, final @Nullable LogNode log)
            throws IOException, InterruptedException {
        // URL schemes are case-insensitive, and are denied in lowercase, so the scheme has to be lowercased
        // before it is looked up -- otherwise "HTTP://host/x.jar" is fetched even though "http" was denied
        final var scheme = url.substring(0, url.indexOf(':')).toLowerCase(Locale.ROOT);
        // Whatever the JVM can open is opened, so only a scheme the caller took away is refused here. A
        // scheme that nothing has registered a handler for is left to the download below, which reports the
        // JVM's own reason for not being able to open it. ("file:" and "jar:" never reach here --
        // FastPathResolver.resolve() has already stripped them)
        if (vfs.getVfsSpec().getDeniedURLSchemes().contains(scheme)) {
            throw new IOException("Fetching a jarfile over \"" + scheme
                    + ":\" is not allowed -- cannot read classpath element: " + url);
        }
        // Download jar from URL to a ByteBuffer in RAM, or to a temp file on disk
        final var physicalZipFile = JarURLDownloader.downloadJarFromURL(url, vfs, log);
        try {
            return physicalZipFile.getLogicalZipFile(log);
        } catch (final IOException | InterruptedException | RuntimeException | Error e) {
            // The caller's cache records the failure rather than downloading the jarfile again, so nothing would
            // ever reach what was just downloaded
            physicalZipFile.releaseUnreachable(e);
            throw e;
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
     * @return the {@link LogicalZipFile} for the jarfile.
     * @throws IOException
     *             if the jarfile could not be opened or read.
     * @throws InterruptedException
     *             if the thread was interrupted.
     */
    public static LogicalZipFile openJarFromPath(final Path path, final Vfs vfs, final @Nullable LogNode log)
            throws IOException, InterruptedException {
        final var physicalZipFile = new PhysicalZipFile(path, vfs, log);
        try {
            return physicalZipFile.getLogicalZipFile(log);
        } catch (final IOException | InterruptedException | RuntimeException | Error e) {
            // The caller's cache records the failure rather than opening the path again, so nothing would ever
            // reach the file handle that was just opened
            physicalZipFile.releaseUnreachable(e);
            throw e;
        }
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
     * @return the {@link LogicalZipFile} for the jarfile.
     * @throws IOException
     *             if the jarfile could not be read.
     * @throws InterruptedException
     *             if the thread was interrupted.
     */
    public static LogicalZipFile openJarFromInputStream(final InputStream inputStream,
            final long inputStreamLengthHint, final String name, final Vfs vfs, final @Nullable LogNode log)
            throws IOException, InterruptedException {
        final var physicalZipFile = new PhysicalZipFile(inputStream, inputStreamLengthHint, name, vfs, log);
        try {
            // The physical zipfile was created here rather than fetched from a cache, so nothing else can reach it,
            // and two streams read under the same name stay two separate jarfiles even though the two physical
            // zipfiles wrapping them compare equal
            return physicalZipFile.getLogicalZipFile(log);
        } catch (final IOException | InterruptedException | RuntimeException | Error e) {
            // Nothing is cached here, so the caller can only try again by reading the stream again, and nothing
            // would ever reach what was just read
            physicalZipFile.releaseUnreachable(e);
            throw e;
        }
    }

    /**
     * Open a jarfile nested within an already-opened jarfile. The nested jarfile is read in place, as a byte range
     * of the enclosing jarfile, if it is stored, or inflated to RAM or to a temporary file if it is deflated.
     *
     * @param zipEntry
     *            the entry that holds the nested jarfile, found with {@link #findEntry(LogicalZipFile, String)}.
     * @param log
     *            the log node, or null to skip logging.
     * @return the {@link LogicalZipFile} for the nested jarfile.
     * @throws IOException
     *             if the nested jarfile could not be opened or read.
     * @throws InterruptedException
     *             if the thread was interrupted.
     */
    public static LogicalZipFile openNestedJar(final FastZipEntry zipEntry, final @Nullable LogNode log)
            throws IOException, InterruptedException {
        return zipEntry.parentLogicalZipFile.openNestedJar(zipEntry, log);
    }

    /**
     * Find a non-directory zip entry with a given name. {@link LogicalZipFile} discards directory entries ending
     * with a slash when it reads the central directory of a zipfile, so only file entries can be matched.
     *
     * <p>
     * N.B. this performs an O(N) search, because the number of classpath elements containing {@code '!'} sections
     * is assumed to be small relative to the total number of entries in all jarfiles (i.e. building a map from
     * entry path to entry for every jarfile would generally be more expensive than performing this linear search,
     * and unless the classpath is enormous, the overall time performance will not tend towards O(N^2)).
     *
     * @param logicalZipFile
     *            the zipfile to search
     * @param entryName
     *            the entry name to search for
     * @return the matching {@link FastZipEntry}, or null if there is no entry with that name
     */
    public static @Nullable FastZipEntry findEntry(final LogicalZipFile logicalZipFile, final String entryName) {
        for (final FastZipEntry entry : logicalZipFile.entries) {
            // Match the unversioned name, since that is the name the entry is served under -- an entry stored only
            // under "META-INF/versions/N/" is named without that prefix once multi-release versions are resolved.
            // The unversioned name is the same as the stored name for an entry that is not versioned, and for every
            // entry when multi-release versions are not resolved
            if (entry.entryNameUnversioned.equals(entryName)) {
                return entry;
            }
        }
        return null;
    }

    /**
     * Test whether any entry of a zipfile is within a given directory, i.e. whether the directory path is a prefix
     * of any entry name.
     *
     * @param logicalZipFile
     *            the zipfile to search
     * @param dirPath
     *            the directory path, without a trailing slash
     * @return true if at least one entry is within the directory
     */
    public static boolean hasEntriesUnderDir(final LogicalZipFile logicalZipFile, final String dirPath) {
        final var dirPathPrefix = dirPath + "/";
        for (final FastZipEntry entry : logicalZipFile.entries) {
            // Match the unversioned name, for the same reason as findEntry(LogicalZipFile, String): a package root
            // that exists only under "META-INF/versions/N/" is a package root of the jarfile all the same
            if (entry.entryNameUnversioned.startsWith(dirPathPrefix)) {
                return true;
            }
        }
        return false;
    }
}
