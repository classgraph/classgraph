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
import java.util.regex.Pattern;

import io.github.classgraph.base.internal.path.PathSyntax;

/**
 * The temporary files that jarfiles read from a stream (a deflated nested jarfile, or a jarfile downloaded from a
 * URL) are written to when they are too large to buffer in RAM. A temporary file is owned by the slice that reads
 * through it, and is deleted when that slice is closed, which closing the {@link io.github.classgraph.vfs.Vfs} that
 * opened it does.
 *
 * <p>
 * {@link File#deleteOnExit()} is not used: the JDK keeps every path passed to it until the JVM exits, even after
 * the file has been deleted, so a long-running JVM that scans repeatedly would accumulate them without limit.
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
     * shell command or a log message without quoting.
     */
    private static final Pattern UNSAFE_FILENAME_CHARS = Pattern.compile("[\\x00-\\x1f\"*/:<>?\\\\|&= ]");

    /**
     * The most characters of a zip entry's leafname that are kept in the name of its temporary file. A filename is
     * limited to 255 bytes on Linux and macOS, and to 255 UTF-16 characters on Windows. A UTF-16 character takes at
     * most 3 bytes in UTF-8, so this leaves room for the prefix and the random number that
     * {@link File#createTempFile(String, String)} adds.
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
     * Create a temporary file. The caller owns the file that is returned, and must delete it with
     * {@link #delete(File)} once nothing is reading through it.
     *
     * <p>
     * The file is named after the leafname of {@code path}, with any character that is not valid in a filename
     * replaced, and with all but the last {@value #MAX_LEAFNAME_LENGTH} characters removed if it is longer than
     * that.
     *
     * @param path
     *            The path to derive the temporary filename from.
     * @return The temporary {@link File}.
     * @throws IOException
     *             If the temporary file could not be created.
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
        return File.createTempFile(PathSyntax.TEMP_FILENAME_PREFIX,
                PathSyntax.TEMP_FILENAME_LEAF_SEPARATOR + leafname);
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
}
