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
import java.util.regex.Pattern;

import io.github.classgraph.base.internal.path.PathSyntax;

/**
 * Helper methods for creating and deleting temporary files used when streaming jarfiles exceed the in-memory buffer
 * limit.
 */
public final class TempFile {
    /** Not instantiable. */
    private TempFile() {
    }

    /** Characters that are invalid or unsafe in filenames across supported platforms, or unpaired surrogates. */
    private static final Pattern UNSAFE_FILENAME_CHARS = Pattern.compile("[\\x00-\\x1f\"*/:<>?\\\\|&= ]|\\p{Cs}");

    /** Non-ASCII characters, replaced when the platform filename encoding cannot represent them. */
    private static final Pattern NON_ASCII_CHARS = Pattern.compile("\\P{ASCII}");

    /** The maximum number of leafname characters retained in a temporary filename. */
    private static final int MAX_LEAFNAME_LENGTH = 64;

    /**
     * Replace characters that are invalid or unsafe in a filename with underscores.
     *
     * @param filename
     *            the filename
     * @return the sanitized filename
     */
    private static String sanitizeFilename(final String filename) {
        return UNSAFE_FILENAME_CHARS.matcher(filename).replaceAll("_");
    }

    /**
     * Create a temporary file readable only by its owner, derived from the leafname of {@code path}. The caller
     * owns the returned file and must delete it with {@link #delete(File)} when done.
     *
     * @param path
     *            the path from which to derive the temporary filename.
     * @return the temporary {@link File}.
     * @throws IOException
     *             if the temporary file could not be created.
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
        try {
            return createReadableOnlyByOwner(leafname);
        } catch (final InvalidPathException _) {
            // The encoding that filenames are stored in cannot represent some character of the leafname, as happens
            // to every non-ASCII character when the JVM is started under the C or POSIX locale on Linux
            return createReadableOnlyByOwner(NON_ASCII_CHARS.matcher(leafname).replaceAll("_"));
        }
    }

    /**
     * Create a temporary file in {@code java.io.tmpdir} that is readable only by its owner (or inherits the
     * temporary directory's ACL on Windows).
     *
     * @param leafname
     *            the sanitized leafname suffix for the temporary filename.
     * @return the temporary {@link File}.
     * @throws IOException
     *             if the temporary file or temporary directory could not be created.
     * @throws InvalidPathException
     *             if the filename cannot be encoded by the platform filesystem.
     */
    private static File createReadableOnlyByOwner(final String leafname) throws IOException {
        try {
            return Files.createTempFile(PathSyntax.TEMP_FILENAME_PREFIX,
                    PathSyntax.TEMP_FILENAME_LEAF_SEPARATOR + leafname).toFile();
        } catch (final ExceptionInInitializerError | NoClassDefFoundError e) {
            // As happens when the JVM is started under the C or POSIX locale on Linux with a temporary directory
            // that has a non-ASCII name
            throw new IOException("The JDK could not set up its temporary directory, which is the value that the "
                    + "java.io.tmpdir system property had when the JVM started, perhaps because that path cannot "
                    + "be represented in the encoding used for filenames", e);
        }
    }

    /**
     * Delete a temporary file, ignoring any failure.
     *
     * @param tempFile
     *            the temporary file
     * @return true if the file was deleted.
     */
    public static boolean delete(final File tempFile) {
        try {
            Files.delete(tempFile.toPath());
            return true;
        } catch (final IOException _) {
            return false;
        }
    }

    /**
     * Schedule a temporary file for deletion on JVM exit if it still exists. Should be called only after
     * {@link #delete(File)} has failed, since {@link File#deleteOnExit()} retains the path in memory until JVM
     * exit.
     *
     * @param tempFile
     *            the temporary file
     * @return true if the file exists and was scheduled for deletion; false if it no longer exists or JVM shutdown
     *         is already in progress.
     */
    public static boolean deleteOnExit(final File tempFile) {
        if (!tempFile.exists()) {
            return false;
        }
        try {
            tempFile.deleteOnExit();
            return true;
        } catch (final IllegalStateException _) {
            // "Shutdown in progress": the JVM has started deleting the files scheduled for deletion
            return false;
        }
    }
}
