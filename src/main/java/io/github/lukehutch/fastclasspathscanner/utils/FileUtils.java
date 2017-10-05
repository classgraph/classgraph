/*
 * This file is part of FastClasspathScanner.
 * 
 * Author: Luke Hutchison
 * 
 * Hosted at: https://github.com/lukehutch/fast-classpath-scanner
 * 
 * --
 *
 * The MIT License (MIT)
 *
 * Copyright (c) 2017 Luke Hutchison
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
package io.github.lukehutch.fastclasspathscanner.utils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;

public class FileUtils {
    /** Get current dir (without resolving symlinks), and normalize path by calling FastPathResolver.resolve(). */
    public static String getCurrDirPathStr() {
        String currDirPathStr = "";
        try {
            // The result is moved to currDirPathStr after each step, so we can provide fine-grained debug info
            // and a best guess at the path, if the current dir doesn't exist (#109), or something goes wrong
            // while trying to get the current dir path.
            Path currDirPath = Paths.get("").toAbsolutePath();
            currDirPathStr = currDirPath.toString();
            currDirPath = currDirPath.normalize();
            currDirPathStr = currDirPath.toString();
            currDirPath = currDirPath.toRealPath(LinkOption.NOFOLLOW_LINKS);
            currDirPathStr = currDirPath.toString();
            currDirPathStr = FastPathResolver.resolve(currDirPathStr);
        } catch (final IOException e) {
            throw new RuntimeException("Could not resolve current directory: " + currDirPathStr, e);
        }
        return currDirPathStr;
    }

    /** Read all the bytes in an InputStream. */
    public static byte[] readAllBytes(final InputStream inputStream, final long fileSize, final LogNode log)
            throws IOException {
        // Java arrays can only currently have 32-bit indices
        if (fileSize > Integer.MAX_VALUE
                // ZipEntry#getSize() can wrap around to negative for files larger than 2GB
                // http://bugs.java.com/bugdatabase/view_bug.do?bug_id=6916399
                || (fileSize < 0
                        // ZipEntry#getSize() can return -1 for unknown size 
                        && fileSize != -1L)) {
            throw new IOException("File larger that 2GB, cannot read contents into a Java array");
        }

        // We can't always trust the fileSize, unfortunately, so we just use it as a hint
        final ByteArrayOutputStream baos = new ByteArrayOutputStream(fileSize <= 0 ? 16384 : (int) fileSize);

        // N.B. there is a better solution for this in Java 9, byte[] bytes = inputStream.readAllBytes()
        final byte[] buf = new byte[4096];
        int totBytesRead = 0;
        for (int bytesRead; (bytesRead = inputStream.read(buf)) != -1;) {
            baos.write(buf, 0, bytesRead);
            totBytesRead += bytesRead;
        }
        if (totBytesRead != fileSize) {
            if (log != null) {
                log.log("File length expected to be " + fileSize + " bytes, but read " + totBytesRead + " bytes");
            }
        }
        return baos.toByteArray();
    }

    /** Returns true if path has a .class extension, ignoring case. */
    public static boolean isClassfile(final String path) {
        final int len = path.length();
        return len > 6 && path.regionMatches(true, len - 6, ".class", 0, 6);
    }
}
