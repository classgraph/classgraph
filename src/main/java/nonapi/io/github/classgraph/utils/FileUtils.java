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
 * Copyright (c) 2019 Luke Hutchison
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
package nonapi.io.github.classgraph.utils;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.ArrayList;
import java.util.List;

import io.github.classgraph.ClassGraphException;

/**
 * File utilities.
 */
public final class FileUtils {

    /** The clean() method. */
    private static Method cleanMethod;

    /** The attachment() method. */
    private static Method attachmentMethod;

    /** The Unsafe object. */
    private static Object theUnsafe;

    /**
     * The current directory path (only reads the current directory once, the first time this field is accessed, so
     * will not reflect subsequent changes to the current directory).
     */
    public static final String CURR_DIR_PATH;

    /**
     * The maximum size of a file buffer array. Eight bytes smaller than {@link Integer#MAX_VALUE}, since some VMs
     * reserve header words in arrays.
     */
    public static final int MAX_BUFFER_SIZE = Integer.MAX_VALUE - 8;

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Constructor.
     */
    private FileUtils() {
        // Cannot be constructed
    }

    static {
        String currDirPathStr = "";
        try {
            // The result is moved to currDirPathStr after each step, so we can provide fine-grained debug info and
            // a best guess at the path, if the current dir doesn't exist (#109), or something goes wrong while
            // trying to get the current dir path.
            Path currDirPath = Paths.get("").toAbsolutePath();
            currDirPathStr = currDirPath.toString();
            currDirPath = currDirPath.normalize();
            currDirPathStr = currDirPath.toString();
            currDirPath = currDirPath.toRealPath(LinkOption.NOFOLLOW_LINKS);
            currDirPathStr = currDirPath.toString();
            currDirPathStr = FastPathResolver.resolve(currDirPathStr);
        } catch (final IOException e) {
            throw ClassGraphException
                    .newClassGraphException("Could not resolve current directory: " + currDirPathStr, e);
        }
        CURR_DIR_PATH = currDirPathStr;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Sanitize relative paths against "zip slip" vulnerability, by removing path segments if ".." is found in the
     * URL, but without allowing navigation above the path hierarchy root. Treats each "!" character as a new path
     * hierarchy root. Also removes "." and empty path segments ("//").
     * 
     * @param path
     *            The path to sanitize.
     * @param removeInitialSlash
     *            If true, additionally removes any "/" character(s) from the beginning of the returned path.
     * @return The sanitized path.
     */
    public static String sanitizeEntryPath(final String path, final boolean removeInitialSlash) {
        if (path.isEmpty()) {
            return "";
        }

        // Find all '/' and '!' character positions, which split a path into segments 
        boolean foundSegmentToSanitize = false;
        final int pathLen = path.length();
        final char[] pathChars = new char[pathLen];
        path.getChars(0, pathLen, pathChars, 0);
        {
            int lastSepIdx = -1;
            char prevC = '\0';
            for (int i = 0, ii = pathLen + 1; i < ii; i++) {
                final char c = i == pathLen ? '\0' : pathChars[i];
                if (c == '/' || c == '!' || c == '\0') {
                    final int segmentLength = i - (lastSepIdx + 1);
                    if (
                    // Found empty segment "//" or "!!"
                    (segmentLength == 0 && prevC == c)
                            // Found segment "."
                            || (segmentLength == 1 && pathChars[i - 1] == '.')
                            // Found segment ".."
                            || (segmentLength == 2 && pathChars[i - 2] == '.' && pathChars[i - 1] == '.')) {
                        foundSegmentToSanitize = true;
                    }
                    lastSepIdx = i;
                }
                prevC = c;
            }
        }

        // Handle "..", "." and empty path segments, if any were found
        final boolean pathHasInitialSlash = pathLen > 0 && pathChars[0] == '/';
        final StringBuilder pathSanitized = new StringBuilder(pathLen + 16);
        if (foundSegmentToSanitize) {
            // Sanitize between "!" section markers separately (".." should not apply past preceding "!")
            final List<List<CharSequence>> allSectionSegments = new ArrayList<>();
            List<CharSequence> currSectionSegments = new ArrayList<>();
            allSectionSegments.add(currSectionSegments);
            int lastSepIdx = -1;
            for (int i = 0; i < pathLen + 1; i++) {
                final char c = i == pathLen ? '\0' : pathChars[i];
                if (c == '/' || c == '!' || c == '\0') {
                    final int segmentStartIdx = lastSepIdx + 1;
                    final int segmentLen = i - segmentStartIdx;
                    if (segmentLen == 0 || (segmentLen == 1 && pathChars[segmentStartIdx] == '.')) {
                        // Ignore empty segment "//" or idempotent segment "/./"
                    } else if (segmentLen == 2 && pathChars[segmentStartIdx] == '.'
                            && pathChars[segmentStartIdx + 1] == '.') {
                        // Remove one segment if ".." encountered, but do not allow ".." above top of hierarchy
                        if (!currSectionSegments.isEmpty()) {
                            currSectionSegments.remove(currSectionSegments.size() - 1);
                        }
                    } else {
                        // Encountered normal path segment
                        currSectionSegments.add(path.subSequence(segmentStartIdx, segmentStartIdx + segmentLen));
                    }
                    if (c == '!' && !currSectionSegments.isEmpty()) {
                        // Begin new section
                        currSectionSegments = new ArrayList<>();
                        allSectionSegments.add(currSectionSegments);
                    }
                    lastSepIdx = i;
                }
            }
            // Turn sections and segments back into path string
            for (final List<CharSequence> sectionSegments : allSectionSegments) {
                if (!sectionSegments.isEmpty()) {
                    // Delineate segments with "!"
                    if (pathSanitized.length() > 0) {
                        pathSanitized.append('!');
                    }
                    for (final CharSequence sectionSegment : sectionSegments) {
                        pathSanitized.append('/');
                        pathSanitized.append(sectionSegment);
                    }
                }
            }
            if (pathSanitized.length() == 0 && pathHasInitialSlash) {
                pathSanitized.append('/');
            }
        } else {
            pathSanitized.append(path);
        }

        int startIdx = 0;
        if (removeInitialSlash || !pathHasInitialSlash) {
            // Strip off leading "/" if it needs to be removed, or if it wasn't present in the original path
            // (the string-building code above prepends "/" to every segment). Note that "/" is always added
            // after "!", since "jar:" URLs expect this.
            while (startIdx < pathSanitized.length() && pathSanitized.charAt(startIdx) == '/') {
                startIdx++;
            }
        }
        return pathSanitized.substring(startIdx);
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Check if the path ends with a ".class" extension, ignoring case.
     *
     * @param path
     *            A file path.
     * @return true if path has a ".class" extension, ignoring case.
     */
    public static boolean isClassfile(final String path) {
        final int len = path.length();
        return len > 6 && path.regionMatches(true, len - 6, ".class", 0, 6);
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Check if a {@link File} exists and can be read.
     *
     * @param file
     *            A {@link File}.
     * @return true if a file exists and can be read.
     */
    public static boolean canRead(final File file) {
        try {
            return file.canRead();
        } catch (final SecurityException e) {
            return false;
        }
    }

    /**
     * Check if a {@link File} exists, is a regular file, and can be read.
     *
     * @param file
     *            A {@link File}.
     * @return true if the file exists, is a regular file, and can be read.
     */
    public static boolean canReadAndIsFile(final File file) {
        try {
            if (!file.canRead()) {
                return false;
            }
        } catch (final SecurityException e) {
            return false;
        }
        return file.isFile();
    }

    /**
     * Check if a {@link File} exists, is a regular file, and can be read.
     *
     * @param file
     *            A {@link File}.
     * @throws IOException
     *             if the file does not exist, is not a regular file, or cannot be read.
     */
    public static void checkCanReadAndIsFile(final File file) throws IOException {
        try {
            if (!file.canRead()) {
                throw new FileNotFoundException("File does not exist or cannot be read: " + file);
            }
        } catch (final SecurityException e) {
            throw new FileNotFoundException("File " + file + " cannot be accessed: " + e);
        }
        if (!file.isFile()) {
            throw new IOException("Not a regular file: " + file);
        }
    }

    /**
     * Check if a {@link File} exists, is a directory, and can be read.
     *
     * @param file
     *            A {@link File}.
     * @return true if the file exists, is a directory, and can be read.
     */
    public static boolean canReadAndIsDir(final File file) {
        try {
            if (!file.canRead()) {
                return false;
            }
        } catch (final SecurityException e) {
            return false;
        }
        return file.isDirectory();
    }

    /**
     * Check if a {@link File} exists, is a directory, and can be read.
     *
     * @param file
     *            A {@link File}.
     * @throws IOException
     *             if the file does not exist, is not a directory, or cannot be read.
     */
    public static void checkCanReadAndIsDir(final File file) throws IOException {
        try {
            if (!file.canRead()) {
                throw new FileNotFoundException("Directory does not exist or cannot be read: " + file);
            }
        } catch (final SecurityException e) {
            throw new FileNotFoundException("File " + file + " cannot be accessed: " + e);
        }
        if (!file.isDirectory()) {
            throw new IOException("Not a directory: " + file);
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Get the parent dir path.
     *
     * @param path
     *            the path
     * @param separator
     *            the separator
     * @return the parent dir path
     */
    public static String getParentDirPath(final String path, final char separator) {
        final int lastSlashIdx = path.lastIndexOf(separator);
        if (lastSlashIdx <= 0) {
            return "";
        }
        return path.substring(0, lastSlashIdx);
    }

    /**
     * Get the parent dir path.
     *
     * @param path
     *            the path
     * @return the parent dir path
     */
    public static String getParentDirPath(final String path) {
        return getParentDirPath(path, '/');
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Get the clean() method, attachment() method, and theUnsafe field, called inside doPrivileged.
     */
    private static void lookupCleanMethodPrivileged() {
        if (VersionFinder.JAVA_MAJOR_VERSION < 9) {
            try {
                // See: https://stackoverflow.com/a/19447758/3950982
                cleanMethod = Class.forName("sun.misc.Cleaner").getMethod("clean");
                cleanMethod.setAccessible(true);
                final Class<?> directByteBufferClass = Class.forName("sun.nio.ch.DirectBuffer");
                attachmentMethod = directByteBufferClass.getMethod("attachment");
                attachmentMethod.setAccessible(true);
            } catch (final SecurityException e) {
                throw ClassGraphException.newClassGraphException(
                        "You need to grant classgraph RuntimePermission(\"accessClassInPackage.sun.misc\") "
                                + "and ReflectPermission(\"suppressAccessChecks\")",
                        e);
            } catch (final ReflectiveOperationException | LinkageError ex) {
                // Ignore
            }
        } else {
            try {
                Class<?> unsafeClass;
                try {
                    unsafeClass = Class.forName("sun.misc.Unsafe");
                } catch (final ReflectiveOperationException | LinkageError e) {
                    // jdk.internal.misc.Unsafe doesn't yet have an invokeCleaner() method,
                    // but that method should be added if sun.misc.Unsafe is removed.
                    unsafeClass = Class.forName("jdk.internal.misc.Unsafe");
                }
                cleanMethod = unsafeClass.getMethod("invokeCleaner", ByteBuffer.class);
                cleanMethod.setAccessible(true);
                final Field theUnsafeField = unsafeClass.getDeclaredField("theUnsafe");
                theUnsafeField.setAccessible(true);
                theUnsafe = theUnsafeField.get(null);
            } catch (final SecurityException e) {
                throw ClassGraphException.newClassGraphException(
                        "You need to grant classgraph RuntimePermission(\"accessClassInPackage.sun.misc\"), "
                                + "RuntimePermission(\"accessClassInPackage.jdk.internal.misc\") "
                                + "and ReflectPermission(\"suppressAccessChecks\")",
                        e);
            } catch (final ReflectiveOperationException | LinkageError ex) {
                // Ignore
            }
        }
    }

    static {
        AccessController.doPrivileged(new PrivilegedAction<Object>() {
            @Override
            public Object run() {
                lookupCleanMethodPrivileged();
                return null;
            }
        });
    }

    /**
     * Close a direct byte buffer (run in doPrivileged).
     *
     * @param byteBuffer
     *            the byte buffer
     * @param log
     *            the log
     * @return true if successful
     */
    private static boolean closeDirectByteBufferPrivileged(final ByteBuffer byteBuffer, final LogNode log) {
        try {
            if (cleanMethod == null) {
                if (log != null) {
                    log.log("Could not unmap ByteBuffer, cleanMethod == null");
                }
                return false;
            }
            if (VersionFinder.JAVA_MAJOR_VERSION < 9) {
                if (attachmentMethod == null) {
                    if (log != null) {
                        log.log("Could not unmap ByteBuffer, attachmentMethod == null");
                    }
                    return false;
                }
                // Make sure duplicates and slices are not cleaned, since this can result in duplicate
                // attempts to clean the same buffer, which trigger a crash with:
                // "A fatal error has been detected by the Java Runtime Environment: EXCEPTION_ACCESS_VIOLATION"
                // See: https://stackoverflow.com/a/31592947/3950982
                if (attachmentMethod.invoke(byteBuffer) != null) {
                    // Buffer is a duplicate or slice
                    return false;
                }
                // Invoke ((DirectBuffer) byteBuffer).cleaner().clean()
                final Method cleaner = byteBuffer.getClass().getMethod("cleaner");
                if (cleaner == null) {
                    if (log != null) {
                        log.log("Could not unmap ByteBuffer, cleaner == null");
                    }
                    return false;
                }
                try {
                    cleaner.setAccessible(true);
                } catch (final Exception e) {
                    if (log != null) {
                        log.log("Could not unmap ByteBuffer, cleaner.setAccessible(true) failed");
                    }
                    return false;
                }
                final Object cleanerResult = cleaner.invoke(byteBuffer);
                if (cleanerResult == null) {
                    if (log != null) {
                        log.log("Could not unmap ByteBuffer, cleanerResult == null");
                    }
                    return false;
                }
                try {
                    cleanMethod.invoke(cleaner.invoke(byteBuffer));
                    return true;
                } catch (final Exception e) {
                    if (log != null) {
                        log.log("Could not unmap ByteBuffer, cleanMethod.invoke(cleanerResult) failed: " + e);
                    }
                    return false;
                }
            } else {
                if (theUnsafe == null) {
                    if (log != null) {
                        log.log("Could not unmap ByteBuffer, theUnsafe == null");
                    }
                    return false;
                }
                // In JDK9+, calling the above code gives a reflection warning on stderr,
                // need to call Unsafe.theUnsafe.invokeCleaner(byteBuffer) , which makes
                // the same call, but does not print the reflection warning.
                try {
                    cleanMethod.invoke(theUnsafe, byteBuffer);
                    return true;
                } catch (final IllegalArgumentException e) {
                    // Buffer is a duplicate or slice
                    return false;
                }
            }
        } catch (final ReflectiveOperationException | SecurityException e) {
            if (log != null) {
                log.log("Could not unmap ByteBuffer: " + e);
            }
            return false;
        }
    }

    /**
     * Close a {@code DirectByteBuffer} -- in particular, will unmap a {@link MappedByteBuffer}.
     * 
     * @param byteBuffer
     *            The {@link ByteBuffer} to close/unmap.
     * @param log
     *            The log.
     * @return True if the byteBuffer was closed/unmapped.
     */
    public static boolean closeDirectByteBuffer(final ByteBuffer byteBuffer, final LogNode log) {
        if (byteBuffer != null && byteBuffer.isDirect()) {
            return AccessController.doPrivileged(new PrivilegedAction<Boolean>() {
                @Override
                public Boolean run() {
                    return closeDirectByteBufferPrivileged(byteBuffer, log);
                }
            });
        } else {
            // Nothing to unmap
            return false;
        }
    }
}
