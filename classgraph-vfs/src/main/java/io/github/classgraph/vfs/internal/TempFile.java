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
package io.github.classgraph.vfs.internal;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.regex.Pattern;

import io.github.classgraph.base.internal.path.PathSyntax;

/**
 * The temporary files that jarfiles read from a stream (a deflated nested jarfile, or a jarfile downloaded from a
 * URL) are written to when they are too large to buffer in RAM. A temporary file is owned by the slice that reads
 * through it, and is deleted when that slice is closed. Closing the {@link io.github.classgraph.vfs.VfsRoot} that
 * owns the slice closes it, as does closing the {@link io.github.classgraph.vfs.Vfs} that opened that root, but a
 * slice that no root owns belongs to whoever opened it, and closing the {@link io.github.classgraph.vfs.Vfs} does
 * not close it.
 *
 * <p>
 * A temporary file is scheduled for deletion when the JVM exits, with {@link File#deleteOnExit()}, only if deleting
 * it failed and it is still there, which happens mainly on Windows, when the file is still memory-mapped. Temporary
 * files are not all scheduled that way when they are created, because the JDK keeps every path passed to
 * {@link File#deleteOnExit()} until the JVM exits, even after the file has been deleted, so a long-running JVM that
 * scans repeatedly would accumulate them without limit. A failed delete is rare, so the paths kept for it are few.
 * Deleting the file when the JVM exits is best effort: it succeeds only if whatever kept the file mapped has
 * released it by then, since a mapping that is still held when the JVM exits is only released when the process
 * ends, after the JVM has tried to delete the files scheduled for deletion.
 */
public final class TempFile {
    /** Not instantiable. */
    private TempFile() {
    }

    /**
     * Characters that may not appear in a filename. Windows rejects every ASCII control character, and also
     * {@code " * / < > ? \ |}, whereas Linux and macOS reject only {@code /}. Windows accepts {@code :}, but treats
     * it as the start of an NTFS alternate data stream rather than as part of the filename. The remaining
     * characters are legal everywhere, but are replaced anyway so that a temporary filename can be pasted into a
     * shell command or a log message without quoting. A surrogate that is not part of a pair ({@code \p{Cs}}) is
     * not a character at all, and cannot be encoded in a filename on any platform; a surrogate pair matches as one
     * supplementary character, which is not in {@code \p{Cs}}, so it is kept.
     */
    private static final Pattern UNSAFE_FILENAME_CHARS = Pattern.compile("[\\x00-\\x1f\"*/:<>?\\\\|&= ]|\\p{Cs}");

    /** Characters outside ASCII, replaced only when the platform cannot encode them in a filename. */
    private static final Pattern NON_ASCII_CHARS = Pattern.compile("\\P{ASCII}");

    /**
     * The most characters of a zip entry's leafname that are kept in the name of its temporary file. A filename is
     * limited to 255 bytes on Linux and macOS, and to 255 UTF-16 characters on Windows. A UTF-16 character takes at
     * most 3 bytes in UTF-8, so this leaves room for the prefix and the random number that
     * {@link Files#createTempFile(Path, String, String, java.nio.file.attribute.FileAttribute...)} adds.
     */
    private static final int MAX_LEAFNAME_LENGTH = 64;

    /**
     * Replace any character that is not valid in a filename on every supported platform with an underscore. Zip
     * entry names may contain almost any byte, whereas filenames may not, so the temporary file that a nested jar
     * is extracted to cannot simply be named after the zip entry it came from.
     *
     * @param filename
     *            the filename
     * @return the sanitized filename
     */
    private static String sanitizeFilename(final String filename) {
        return UNSAFE_FILENAME_CHARS.matcher(filename).replaceAll("_");
    }

    /**
     * Create a temporary file that only its owner can read. The caller owns the file that is returned, and must
     * delete it with {@link #delete(File)} once nothing is reading through it.
     *
     * <p>
     * The file is named after the leafname of {@code path}, with any character that is not valid in a filename
     * replaced, and with all but the last {@value #MAX_LEAFNAME_LENGTH} characters removed if it is longer than
     * that. If the encoding that filenames are stored in cannot represent some character of what remains, every
     * non-ASCII character is replaced too.
     *
     * @param path
     *            The path to derive the temporary filename from.
     * @return The temporary {@link File}.
     * @throws IOException
     *             If the temporary file could not be created, including when the temporary directory named by the
     *             {@code java.io.tmpdir} system property cannot be represented in the encoding used for filenames.
     */
    public static File create(final String path) throws IOException {
        var leafname = sanitizeFilename(PathSyntax.simpleName(path));
        if (leafname.length() > MAX_LEAFNAME_LENGTH) {
            // Keep the end of the name, which holds the extension, without splitting a surrogate pair
            var startIdx = leafname.length() - MAX_LEAFNAME_LENGTH;
            if (Character.isLowSurrogate(leafname.charAt(startIdx))) {
                startIdx++;
            }
            leafname = leafname.substring(startIdx);
        }
        // Resolve the temporary directory here rather than leaving it to Files.createTempFile(String, String),
        // whose helper class resolves it once in its static initializer: if that fails, the class can never be
        // used again in this JVM, and every call throws an Error rather than an IOException
        final var tempDirName = System.getProperty("java.io.tmpdir");
        final Path tempDir;
        try {
            tempDir = Path.of(tempDirName);
        } catch (final InvalidPathException e) {
            // As happens to a non-ASCII directory name when the JVM is started under the C or POSIX locale on Linux
            throw new IOException("The temporary directory " + tempDirName
                    + " cannot be represented in the encoding used for filenames", e);
        }
        try {
            return createReadableOnlyByOwner(tempDir, leafname);
        } catch (final InvalidPathException e) {
            // The encoding that filenames are stored in cannot represent some character of the leafname, as happens
            // to every non-ASCII character when the JVM is started under the C or POSIX locale on Linux
            return createReadableOnlyByOwner(tempDir, NON_ASCII_CHARS.matcher(leafname).replaceAll("_"));
        }
    }

    /**
     * Create a temporary file that only its owner can read, unlike {@link File#createTempFile(String, String)},
     * whose file can be read by every local user under the usual umask of 022. On Windows the file takes the access
     * control list of the temporary directory.
     *
     * @param tempDir
     *            The directory to create the temporary file in.
     * @param leafname
     *            The sanitized leafname to end the temporary filename with.
     * @return The temporary {@link File}.
     * @throws IOException
     *             If the temporary file could not be created.
     * @throws InvalidPathException
     *             If the temporary filename cannot be represented in the encoding that filenames are stored in.
     */
    private static File createReadableOnlyByOwner(final Path tempDir, final String leafname) throws IOException {
        return Files.createTempFile(tempDir, PathSyntax.TEMP_FILENAME_PREFIX,
                PathSyntax.TEMP_FILENAME_LEAF_SEPARATOR + leafname).toFile();
    }

    /**
     * Delete a temporary file, ignoring any failure. The caller decides whether a failure is worth logging.
     *
     * @param tempFile
     *            the temp file
     * @return true if the file was deleted.
     */
    public static boolean delete(final File tempFile) {
        try {
            Files.delete(tempFile.toPath());
            return true;
        } catch (IOException | SecurityException e) {
            return false;
        }
    }

    /**
     * Schedule a temporary file that could not be deleted for deletion when the JVM exits, if the file is still
     * there. Call this only once the last attempt to delete the file has failed, since the JDK keeps the path until
     * the JVM exits, even if the file is deleted before then.
     *
     * @param tempFile
     *            the temp file
     * @return true if the file is still there and was scheduled for deletion; false if the file is gone, if the JVM
     *         is already exiting and deleting the files scheduled for deletion, in which case it is too late to
     *         schedule another, or if a security manager does not allow the file to be read or deleted.
     */
    public static boolean deleteOnExit(final File tempFile) {
        try {
            if (!tempFile.exists()) {
                return false;
            }
            tempFile.deleteOnExit();
            return true;
        } catch (final IllegalStateException | SecurityException e) {
            // IllegalStateException is "Shutdown in progress": the JVM has started deleting the files scheduled for
            // deletion. SecurityException is thrown by File#exists or File#deleteOnExit when a security manager does
            // not allow the file to be read or deleted.
            return false;
        }
    }
}
