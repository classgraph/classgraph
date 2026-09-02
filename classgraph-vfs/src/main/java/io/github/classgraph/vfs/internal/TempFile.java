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
 * The temporary files that nested jarfiles are extracted to, when a nested jarfile is deflated, or is too large to
 * buffer in RAM. A temporary file is owned by the slice that reads through it, and is deleted when that slice is
 * closed; the {@link File#deleteOnExit()} hook that {@link #create(String, boolean)} registers is the backstop for
 * a file that could not be deleted then.
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
     * Create a temporary file, and mark it for deletion on exit. The caller owns the file that is returned, and
     * must delete it with {@link #delete(File)} once nothing is reading through it.
     *
     * @param filePathBase
     *            The path to derive the temporary filename from.
     * @param onlyUseLeafname
     *            If true, only use the leafname of filePathBase to derive the temporary filename.
     * @return The temporary {@link File}.
     * @throws IOException
     *             If the temporary file could not be created.
     */
    public static File create(final String filePathBase, final boolean onlyUseLeafname) throws IOException {
        final var tempFile = File.createTempFile(PathSyntax.TEMP_FILENAME_PREFIX,
                PathSyntax.TEMP_FILENAME_LEAF_SEPARATOR
                        + sanitizeFilename(onlyUseLeafname ? PathSyntax.simpleName(filePathBase) : filePathBase));
        tempFile.deleteOnExit();
        return tempFile;
    }

    /**
     * Delete a temporary file, ignoring any failure. The file was created with {@link File#deleteOnExit()}, so a
     * file that cannot be deleted now is deleted when the JVM exits.
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
