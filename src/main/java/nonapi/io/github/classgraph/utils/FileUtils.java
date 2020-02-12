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
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.ArrayList;
import java.util.Arrays;
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
     * The minimum filesize at which it becomes more efficient to read a file with a memory-mapped file channel
     * rather than an InputStream. Based on benchmark testing using the following benchmark, averaged over three
     * separate runs, then plotted as a speedup curve for 1, 2, 4 and 8 concurrent threads:
     * 
     * https://github.com/lukehutch/FileReadingBenchmark
     */
    public static final int FILECHANNEL_FILE_SIZE_THRESHOLD;

    /**
     * The current directory path (only reads the current directory once, the first time this field is accessed, so
     * will not reflect subsequent changes to the current directory).
     */
    public static final String CURR_DIR_PATH;

    /** The default size of a file buffer. */
    private static final int DEFAULT_BUFFER_SIZE = 16384;

    /**
     * The maximum size of a file buffer array. Eight bytes smaller than {@link Integer#MAX_VALUE}, since some VMs
     * reserve header words in arrays.
     */
    public static final int MAX_BUFFER_SIZE = Integer.MAX_VALUE - 8;

    /** The maximum initial buffer size. */
    private static final int MAX_INITIAL_BUFFER_SIZE = 16 * 1024 * 1024;

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

    static {
        switch (VersionFinder.OS) {
        case Linux:
            // On Linux, FileChannel is more efficient once file sizes are larger than 16kb,
            // and the speedup increases superlinearly, reaching 1.5-3x for a filesize of 1MB
            // (and the performance increase does not level off at 1MB either -- that is as
            // far as this was benchmarked).
        case MacOSX:
            // On older/slower Mac OS X machines, FileChannel is always 10-20% slower than InputStream,
            // except for very large files (>1MB), and only for single-threaded reading.
            // But on newer/faster Mac OS X machines, you get a 10-20% speedup between 16kB and 128kB,
            // then a much larger speedup for files larger than 128kb (topping out at about 2.5x speedup).
            // It's probably worth setting the threshold to 16kB to get the 10-20% speedup for files
            // larger than 16kB in size on modern machines.
        case Solaris:
        case BSD:
        case Unix:
            // No testing has been performed yet on the other unices, so just pick the same val as MacOSX and Linux
            FILECHANNEL_FILE_SIZE_THRESHOLD = 16384;
            break;

        case Windows:
            // Windows is always 10-20% faster with FileChannel than with InputStream, even for small files.
            FILECHANNEL_FILE_SIZE_THRESHOLD = -1;
            break;

        case Unknown:
            // For any other operating system
        default:
            FILECHANNEL_FILE_SIZE_THRESHOLD = 16384;
            break;
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Read all the bytes in an {@link InputStream}.
     * 
     * @param inputStream
     *            The {@link InputStream}.
     * @param fileSizeHint
     *            The file size, if known, otherwise -1L.
     * @return The contents of the {@link InputStream} as a byte array.
     * @throws IOException
     *             If the contents could not be read.
     */
    private static byte[] readAllBytes(final InputStream inputStream, final long fileSizeHint) throws IOException {
        if (fileSizeHint > MAX_BUFFER_SIZE) {
            throw new IOException("InputStream is too large to read");
        }
        final int bufferSize = fileSizeHint < 1L
                // If fileSizeHint is zero or unknown, use default buffer size 
                ? DEFAULT_BUFFER_SIZE
                // fileSizeHint is just a hint -- limit the max allocated buffer size, so that invalid ZipEntry
                // lengths do not become a memory allocation attack vector
                : Math.min((int) fileSizeHint, MAX_INITIAL_BUFFER_SIZE);
        byte[] buf = new byte[bufferSize];
        int totBytesRead = 0;
        for (int bytesRead;;) {
            while ((bytesRead = inputStream.read(buf, totBytesRead, buf.length - totBytesRead)) > 0) {
                // Fill buffer until nothing more can be read
                totBytesRead += bytesRead;
            }
            if (bytesRead < 0) {
                // Reached end of stream
                break;
            }
            // bytesRead == 0 => grow buffer (avoid integer overflow in next line)
            if (buf.length <= MAX_BUFFER_SIZE - buf.length) {
                buf = Arrays.copyOf(buf, buf.length * 2);
            } else {
                if (buf.length == MAX_BUFFER_SIZE) {
                    // Try reading one more byte, just in case the stream is exactly MAX_BUFFER_SIZE in length
                    if (inputStream.read() == -1) {
                        break;
                    } else {
                        throw new IOException("InputStream too large to read into array");
                    }
                }
                // Can't double the size of the buffer, but increase it to max size
                buf = Arrays.copyOf(buf, MAX_BUFFER_SIZE);
            }
        }
        // Return buffer and number of bytes read
        return totBytesRead == buf.length ? buf : Arrays.copyOf(buf, totBytesRead);
    }

    /**
     * Read all the bytes in an {@link InputStream} as a byte array.
     * 
     * @param inputStream
     *            The {@link InputStream}.
     * @param fileSizeHint
     *            The file size, if known, otherwise -1L.
     * @return The contents of the {@link InputStream} as a byte array.
     * @throws IOException
     *             If the contents could not be read.
     */
    public static byte[] readAllBytesAsArray(final InputStream inputStream, final long fileSizeHint)
            throws IOException {
        return readAllBytes(inputStream, fileSizeHint);
    }

    /**
     * Read all the bytes in an {@link InputStream} as a {@link ByteBuffer}.
     * 
     * @param inputStream
     *            The {@link InputStream}.
     * @param fileSizeHint
     *            The file size, if known, otherwise -1L.
     * @return The contents of the {@link InputStream} as a {@link ByteBuffer}.
     * @throws IOException
     *             If the contents could not be read.
     */
    public static ByteBuffer readAllBytesAsByteBuffer(final InputStream inputStream, final long fileSizeHint)
            throws IOException {
        final byte[] buf = readAllBytes(inputStream, fileSizeHint);
        return ByteBuffer.wrap(buf, 0, buf.length);
    }

    /**
     * Read all the bytes in an {@link InputStream} as a String.
     * 
     * @param inputStream
     *            The {@link InputStream}.
     * @param fileSizeHint
     *            The file size, if known, otherwise -1L.
     * @return The contents of the {@link InputStream} as a String.
     * @throws IOException
     *             If the contents could not be read.
     */
    public static String readAllBytesAsString(final InputStream inputStream, final long fileSizeHint)
            throws IOException {
        final byte[] buf = readAllBytes(inputStream, fileSizeHint);
        return new String(buf, 0, buf.length, StandardCharsets.UTF_8);
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Produce an {@link InputStream} that is able to read from a {@link ByteBuffer}.
     * 
     * @param byteBuffer
     *            The {@link ByteBuffer}.
     * @return An {@link InputStream} that reads from the {@link ByteBuffer}.
     */
    public static InputStream byteBufferToInputStream(final ByteBuffer byteBuffer) {
        // https://stackoverflow.com/questions/4332264/wrapping-a-bytebuffer-with-an-inputstream/6603018#6603018
        return new InputStream() {
            /** The intermediate buffer. */
            final ByteBuffer buf = byteBuffer;

            @Override
            public int read() {
                if (!buf.hasRemaining()) {
                    return -1;
                }
                return buf.get() & 0xFF;
            }

            @Override
            public int read(final byte[] bytes, final int off, final int len) {
                if (!buf.hasRemaining()) {
                    return -1;
                }

                final int bytesRead = Math.min(len, buf.remaining());
                buf.get(bytes, off, bytesRead);
                return bytesRead;
            }
        };
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
