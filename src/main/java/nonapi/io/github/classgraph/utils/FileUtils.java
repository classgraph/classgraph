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
import java.nio.channels.FileChannel;
import java.nio.channels.FileChannel.MapMode;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

import nonapi.io.github.classgraph.reflection.ReflectionUtils;
import nonapi.io.github.classgraph.utils.VersionFinder.OperatingSystem;

/**
 * File utilities.
 */
public final class FileUtils {
    /** The DirectByteBuffer.cleaner() method. */
    private static Method directByteBufferCleanerMethod;

    /** The Cleaner.clean() method. */
    private static Method cleanerCleanMethod;

    /** The attachment() method. */
    private static Method attachmentMethod;

    /** The Unsafe object. */
    private static Object theUnsafe;

    /**
     * True if the reflective handles above have been initialized. Volatile, and only ever assigned while holding
     * the lock on {@link FileUtils}, so that the double-checked locking in {@link #closeDirectByteBuffer} is
     * correctly synchronized: a thread that reads true here is guaranteed to see the fully-initialized handles.
     */
    private static volatile boolean initialized;

    /**
     * The current directory path (only reads the current directory once, the first time this field is accessed, so
     * will not reflect subsequent changes to the current directory). Volatile, so that the lazy initialization in
     * {@link #currDirPath()} is not a data race.
     */
    private static volatile String currDirPath;

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

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Get the current directory (only looks at the current directory the first time it is called, then caches this
     * value for future reads).
     * 
     * @return The current directory, as a string
     */
    public static String currDirPath() {
        // Read the volatile field once, so that the value returned cannot differ from the value tested
        String currDirPathCached = currDirPath;
        if (currDirPathCached == null) {
            // user.dir should be the current directory at the time the JVM is started, which is
            // where classpath elements should be resolved relative to
            Path path = null;
            final String currDirPathStr = VersionFinder.getProperty("user.dir");
            if (currDirPathStr != null) {
                try {
                    path = Paths.get(currDirPathStr);
                } catch (final InvalidPathException e) {
                    // Fall through
                }
            }
            if (path == null) {
                // user.dir should probably always be set. But just in case it is not, try reading the
                // actual current directory at the time ClassGraph is first invoked.
                try {
                    path = Paths.get("");
                } catch (final InvalidPathException e) {
                    // Fall through
                }
            }

            // Normalize current directory the same way all other paths are normalized in ClassGraph,
            // for consistency
            // Two threads racing here compute the same value, so whichever write lands last is equivalent
            currDirPathCached = FastPathResolver.resolve(path == null ? "" : path.toString());
            currDirPath = currDirPathCached;
        }
        return currDirPathCached;
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
     *            If true, remove any '/' character(s) from the beginning of the returned path.
     * @param removeFinalSlash
     *            If true, remove any '/' character(s) from the end of the returned path.
     * @return The sanitized path.
     */
    public static String sanitizeEntryPath(final String path, final boolean removeInitialSlash,
            final boolean removeFinalSlash) {
        if (path.isEmpty()) {
            return "";
        }

        // A '!' is only a nested jar separator if the path before it names an existing jarfile -- it is
        // otherwise a legal filename character, and must not be treated as a path hierarchy root (#903)
        final int nestedJarSepIdx = JarUtils.indexOfNestedJarSeparator(path);

        // Find all '/' and nested jar separator '!' character positions, which split a path into segments.
        // This scan reads the path via charAt() rather than copying it into a char[], since the common case is
        // that nothing needs sanitizing, and the copy would then be pure overhead.
        boolean foundSegmentToSanitize = false;
        final int pathLen = path.length();
        {
            int lastSepIdx = -1;
            char prevC = '\0';
            for (int i = 0, ii = pathLen + 1; i < ii; i++) {
                final char c = i == pathLen ? '\0' : path.charAt(i);
                if (c == '/' || (c == '!' && nestedJarSepIdx >= 0 && i >= nestedJarSepIdx) || c == '\0') {
                    final int segmentLength = i - (lastSepIdx + 1);
                    if (
                    // Found empty segment "//" or "!!"
                    (segmentLength == 0 && prevC == c)
                            // Found segment "."
                            || (segmentLength == 1 && path.charAt(i - 1) == '.')
                            // Found segment ".."
                            || (segmentLength == 2 && path.charAt(i - 2) == '.' && path.charAt(i - 1) == '.')) {
                        foundSegmentToSanitize = true;
                    }
                    lastSepIdx = i;
                }
                prevC = c;
            }
        }

        // Handle "..", "." and empty path segments, if any were found
        final boolean pathHasInitialSlash = path.charAt(0) == '/';
        final boolean pathHasInitialSlashSlash = pathHasInitialSlash && pathLen > 1 && path.charAt(1) == '/';
        final StringBuilder pathSanitized = new StringBuilder(pathLen + 16);
        if (foundSegmentToSanitize) {
            // Sanitize between "!" section markers separately (".." should not apply past preceding "!")
            final List<List<CharSequence>> allSectionSegments = new ArrayList<>();
            List<CharSequence> currSectionSegments = new ArrayList<>();
            allSectionSegments.add(currSectionSegments);
            int lastSepIdx = -1;
            for (int i = 0; i < pathLen + 1; i++) {
                final char c = i == pathLen ? '\0' : path.charAt(i);
                final boolean isSectionMarker = c == '!' && nestedJarSepIdx >= 0 && i >= nestedJarSepIdx;
                if (c == '/' || isSectionMarker || c == '\0') {
                    final int segmentStartIdx = lastSepIdx + 1;
                    final int segmentLen = i - segmentStartIdx;
                    if (segmentLen == 0 || (segmentLen == 1 && path.charAt(segmentStartIdx) == '.')) {
                        // Ignore empty segment "//" or idempotent segment "/./"
                    } else if (segmentLen == 2 && path.charAt(segmentStartIdx) == '.'
                            && path.charAt(segmentStartIdx + 1) == '.') {
                        // Remove one segment if ".." encountered, but do not allow ".." above top of hierarchy
                        if (!currSectionSegments.isEmpty()) {
                            currSectionSegments.remove(currSectionSegments.size() - 1);
                        }
                    } else {
                        // Encountered normal path segment
                        currSectionSegments.add(path.subSequence(segmentStartIdx, segmentStartIdx + segmentLen));
                    }
                    if (isSectionMarker && !currSectionSegments.isEmpty()) {
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

        // Intended to preserve the double slash at the start of UNC paths (#736).
        // e.g. //server/file/path
        if (VersionFinder.OS == OperatingSystem.Windows && pathHasInitialSlashSlash) {
            pathSanitized.insert(0, '/');
        }

        // Strip the final slashes before the initial ones, so that for a path consisting only of slashes (which is
        // what "/.." and "/." normalize to), truncating the buffer cannot leave it shorter than startIdx
        if (removeFinalSlash) {
            while (pathSanitized.length() > 0 && pathSanitized.charAt(pathSanitized.length() - 1) == '/') {
                pathSanitized.setLength(pathSanitized.length() - 1);
            }
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
     * Check if a {@link Path} exists and can be read.
     *
     * @param path
     *            A {@link Path}.
     * @return true if the file exists and can be read.
     */
    public static boolean canRead(final Path path) {
        try {
            return canRead(path.toFile());
        } catch (final UnsupportedOperationException ignored) {
        }
        try {
            return Files.isReadable(path);
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
     * Check if a {@link Path} exists, is a regular file, and can be read.
     *
     * @param path
     *            A {@link Path}.
     * @return true if the file exists, is a regular file, and can be read.
     */
    public static boolean canReadAndIsFile(final Path path) {
        try {
            return canReadAndIsFile(path.toFile());
        } catch (final UnsupportedOperationException ignored) {
        }
        try {
            if (!Files.isReadable(path)) {
                return false;
            }
        } catch (final SecurityException e) {
            return false;
        }
        return Files.isRegularFile(path);
    }

    /**
     * Check if a {@link Path} is a regular file.
     *
     * @param path
     *            A {@link Path}.
     * @return true if the path is a regular file.
     */
    public static boolean isFile(final Path path) {
        try {
            return path.toFile().isFile();
        } catch (final UnsupportedOperationException e) {
            return Files.isRegularFile(path);
        } catch (final SecurityException e) {
            return false;
        }
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
     * Check if a {@link Path} exists, is a regular file, and can be read.
     *
     * @param path
     *            A {@link Path}.
     * @throws IOException
     *             if the path does not exist, is not a regular file, or cannot be read.
     */
    public static void checkCanReadAndIsFile(final Path path) throws IOException {
        try {
            checkCanReadAndIsFile(path.toFile());
            return;
        } catch (final UnsupportedOperationException ignored) {
        }
        try {
            if (!Files.isReadable(path)) {
                throw new FileNotFoundException("Path does not exist or cannot be read: " + path);
            }
        } catch (final SecurityException e) {
            throw new FileNotFoundException("Path " + path + " cannot be accessed: " + e);
        }
        if (!Files.isRegularFile(path)) {
            throw new IOException("Not a regular file: " + path);
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
     * Check if a {@link Path} exists, is a directory, and can be read.
     *
     * @param path
     *            A {@link Path}.
     * @return true if the file exists, is a directory, and can be read.
     */
    public static boolean canReadAndIsDir(final Path path) {
        try {
            return canReadAndIsDir(path.toFile());
        } catch (final UnsupportedOperationException ignored) {
        }
        try {
            if (!Files.isReadable(path)) {
                return false;
            }
        } catch (final SecurityException e) {
            return false;
        }
        return Files.isDirectory(path);
    }

    /**
     * Check if a {@link Path} is a directory.
     *
     * @param path
     *            A {@link Path}.
     * @return true if the path is a directory.
     */
    public static boolean isDir(final Path path) {
        try {
            return path.toFile().isDirectory();
        } catch (final UnsupportedOperationException e) {
            return Files.isDirectory(path);
        } catch (final SecurityException e) {
            return false;
        }
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
                // See:
                // https://stackoverflow.com/a/19447758/3950982
                cleanerCleanMethod = Class.forName("sun.misc.Cleaner").getDeclaredMethod("clean");
                cleanerCleanMethod.setAccessible(true);
                final Class<?> directByteBufferClass = Class.forName("sun.nio.ch.DirectBuffer");
                directByteBufferCleanerMethod = directByteBufferClass.getDeclaredMethod("cleaner");
                attachmentMethod = directByteBufferClass.getMethod("attachment");
                attachmentMethod.setAccessible(true);
            } catch (final SecurityException e) {
                throw new RuntimeException(
                        "You need to grant classgraph RuntimePermission(\"accessClassInPackage.sun.misc\") "
                                + "and ReflectPermission(\"suppressAccessChecks\")",
                        e);
            } catch (final ReflectiveOperationException | LinkageError e) {
                // Ignore
            }
        } else if (VersionFinder.JAVA_MAJOR_VERSION < 22) {
            // Unsafe::invokeCleaner is terminally deprecated, and JDK 24+ reports: "A terminally
            // deprecated method in sun.misc.Unsafe has been called" if it is used. On JDK 22+, direct
            // ByteBuffers are allocated and memory-mapped using the java.lang.foreign.Arena API instead,
            // and they are freed/unmapped by closing the arena that created them, so the cleaner method
            // is only needed on JDK 9-21.
            // See: https://github.com/classgraph/classgraph/issues/899
            // and: https://github.com/classgraph/classgraph/issues/939
            try {
                Class<?> unsafeClass;
                try {
                    unsafeClass = Class.forName("sun.misc.Unsafe");
                } catch (final ReflectiveOperationException | LinkageError e) {
                    throw new RuntimeException("Could not get class sun.misc.Unsafe", e);
                }
                final Field theUnsafeField = unsafeClass.getDeclaredField("theUnsafe");
                theUnsafeField.setAccessible(true);
                theUnsafe = theUnsafeField.get(null);
                cleanerCleanMethod = unsafeClass.getMethod("invokeCleaner", ByteBuffer.class);
                cleanerCleanMethod.setAccessible(true);
            } catch (final SecurityException e) {
                throw new RuntimeException(
                        "You need to grant classgraph RuntimePermission(\"accessClassInPackage.sun.misc\") "
                                + "and ReflectPermission(\"suppressAccessChecks\")",
                        e);
            } catch (final ReflectiveOperationException | LinkageError ex) {
                // Ignore
            }
        }
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
        if (!byteBuffer.isDirect()) {
            // Nothing to do
            return true;
        }
        try {
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
                if (directByteBufferCleanerMethod == null) {
                    if (log != null) {
                        log.log("Could not unmap ByteBuffer, cleanerMethod == null");
                    }
                    return false;
                }
                try {
                    directByteBufferCleanerMethod.setAccessible(true);
                } catch (final Exception e) {
                    if (log != null) {
                        log.log("Could not unmap ByteBuffer, cleanerMethod.setAccessible(true) failed");
                    }
                    return false;
                }
                final Object cleanerInstance = directByteBufferCleanerMethod.invoke(byteBuffer);
                if (cleanerInstance == null) {
                    if (log != null) {
                        log.log("Could not unmap ByteBuffer, cleaner == null");
                    }
                    return false;
                }
                if (cleanerCleanMethod == null) {
                    if (log != null) {
                        log.log("Could not unmap ByteBuffer, cleanMethod == null");
                    }
                    return false;
                }
                try {
                    cleanerCleanMethod.invoke(cleanerInstance);
                    return true;
                } catch (final Exception e) {
                    if (log != null) {
                        log.log("Could not unmap ByteBuffer, cleanMethod.invoke(cleaner) failed: " + e);
                    }
                    return false;
                }
            } else if (VersionFinder.JAVA_MAJOR_VERSION < 22) {
                if (theUnsafe == null) {
                    if (log != null) {
                        log.log("Could not unmap ByteBuffer, theUnsafe == null");
                    }
                    return false;
                }
                if (cleanerCleanMethod == null) {
                    if (log != null) {
                        log.log("Could not unmap ByteBuffer, cleanMethod == null");
                    }
                    return false;
                }
                try {
                    cleanerCleanMethod.invoke(theUnsafe, byteBuffer);
                    return true;
                } catch (final IllegalArgumentException e) {
                    // Buffer is a duplicate or slice
                    return false;
                }
            } else {
                // JDK 22+: direct ByteBuffers are allocated or memory-mapped using the
                // java.lang.foreign.Arena API, and they are freed/unmapped by closing the arena that
                // created them (see FileSlice#close()), rather than by calling the terminally-deprecated
                // Unsafe::invokeCleaner method (#939). A ByteBuffer that was not created from an arena
                // cannot be closed explicitly, so return false here.
                return false;
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
     * @param reflectionUtils
     *            The reflection utils (the cleaner method has to be looked up and invoked reflectively).
     * @param log
     *            The log.
     * @return True if the byteBuffer was closed/unmapped.
     */
    public static boolean closeDirectByteBuffer(final ByteBuffer byteBuffer, final ReflectionUtils reflectionUtils,
            final LogNode log) {
        if (byteBuffer != null && byteBuffer.isDirect()) {
            // Double-checked locking, so that two threads calling this for the first time concurrently cannot
            // both run the lookup and race on the static fields it assigns
            if (!initialized) {
                synchronized (FileUtils.class) {
                    if (!initialized) {
                        try {
                            reflectionUtils.doPrivileged(new Callable<Void>() {
                                @Override
                                public Void call() throws Exception {
                                    lookupCleanMethodPrivileged();
                                    return null;
                                }
                            });
                        } catch (final Throwable e) {
                            throw new RuntimeException("Cannot get buffer cleaner method", e);
                        }
                        initialized = true;
                    }
                }
            }
            try {
                return reflectionUtils.doPrivileged(new Callable<Boolean>() {
                    @Override
                    public Boolean call() throws Exception {
                        return closeDirectByteBufferPrivileged(byteBuffer, log);
                    }
                });
            } catch (final Throwable t) {
                return false;
            }
        } else {
            // Nothing to unmap
            return false;
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    // TODO: once ClassGraph's minimum supported JDK version is 22 or later, the Unsafe reflection code above
    // (lookupCleanMethodPrivileged and closeDirectByteBufferPrivileged) can be removed, and the arena methods
    // below can open and close arenas, and allocate and memory-map ByteBuffers, by calling the
    // java.lang.foreign API directly rather than through reflection.

    /** The fully-qualified name of the JDK 22+ {@code java.lang.foreign.Arena} interface. */
    private static final String ARENA_CLASS_NAME = "java.lang.foreign.Arena";

    /**
     * Open a new shared {@code java.lang.foreign.Arena} (JDK 22+), which can be used to allocate direct
     * {@link ByteBuffer}s ({@link #allocateDirectByteBufferUsingArena(Object, long, ReflectionUtils)}) and to
     * memory-map files to {@link ByteBuffer}s
     * ({@link #mapFileUsingArena(Object, FileChannel, long, long, ReflectionUtils)}). Closing the arena
     * ({@link #closeArena(Object, ReflectionUtils, LogNode)}) frees or unmaps all {@link ByteBuffer}s obtained
     * from it, which on JDK 22+ replaces the use of the terminally-deprecated {@code Unsafe::invokeCleaner}
     * method (#939).
     *
     * @param reflectionUtils
     *            the reflection utils (the {@code java.lang.foreign} API has to be invoked using reflection,
     *            since ClassGraph needs to compile and run on JDK 8+)
     * @return a new shared {@code Arena} instance, or null if the arena API is not available (JDK older than 22).
     */
    public static Object openArena(final ReflectionUtils reflectionUtils) {
        if (VersionFinder.JAVA_MAJOR_VERSION < 22) {
            // The java.lang.foreign API was only finalized in JDK 22 (the preview versions of the API in
            // JDK 19-21 cannot be invoked reflectively without --enable-preview)
            return null;
        }
        final Class<?> arenaClass = reflectionUtils.classForNameOrNull(ARENA_CLASS_NAME);
        if (arenaClass == null) {
            return null;
        }
        // Invoke Arena.ofShared() -- a shared arena is needed rather than a confined arena, since the
        // ByteBuffers obtained from the arena may be read and closed by multiple threads
        return reflectionUtils.invokeStaticMethod(/* throwException = */ false, arenaClass, "ofShared");
    }

    /**
     * Allocate a direct {@link ByteBuffer} using a shared arena (JDK 22+). The buffer is freed by closing the
     * arena.
     *
     * @param arena
     *            an arena obtained from {@link #openArena(ReflectionUtils)}.
     * @param size
     *            the number of bytes to allocate.
     * @param reflectionUtils
     *            the reflection utils
     * @return the allocated {@link ByteBuffer}, or null if the buffer could not be allocated.
     */
    public static ByteBuffer allocateDirectByteBufferUsingArena(final Object arena, final long size,
            final ReflectionUtils reflectionUtils) {
        // Invoke arena.allocate(size).asByteBuffer()
        final Object memorySegment = reflectionUtils.invokeMethod(/* throwException = */ false, arena, "allocate",
                long.class, size);
        return memorySegment == null ? null
                : (ByteBuffer) reflectionUtils.invokeMethod(/* throwException = */ false, memorySegment,
                        "asByteBuffer");
    }

    /**
     * Memory-map a region of a {@link FileChannel} to a read-only {@link ByteBuffer} using a shared arena
     * (JDK 22+). The buffer is unmapped by closing the arena.
     *
     * @param arena
     *            an arena obtained from {@link #openArena(ReflectionUtils)}.
     * @param fileChannel
     *            the file channel to map.
     * @param position
     *            the position within the file at which the mapped region is to start.
     * @param size
     *            the size of the region to map (must not be larger than {@link #MAX_BUFFER_SIZE}, since the
     *            mapped memory segment has to be projected to a single {@link ByteBuffer}).
     * @param reflectionUtils
     *            the reflection utils
     * @return the mapped {@link ByteBuffer}, or null if the arena-based mapping API could not be invoked
     *         reflectively.
     * @throws IOException
     *             if mapping the file failed with an I/O error (mapping may succeed if retried after garbage
     *             collection, see FileSlice).
     */
    public static ByteBuffer mapFileUsingArena(final Object arena, final FileChannel fileChannel,
            final long position, final long size, final ReflectionUtils reflectionUtils) throws IOException {
        final Class<?> arenaClass = reflectionUtils.classForNameOrNull(ARENA_CLASS_NAME);
        if (arenaClass == null) {
            return null;
        }
        try {
            // Invoke fileChannel.map(MapMode.READ_ONLY, position, size, arena).asByteBuffer()
            final Object memorySegment = reflectionUtils.invokeMethod(/* throwException = */ true, fileChannel,
                    "map", new Class<?>[] { MapMode.class, long.class, long.class, arenaClass },
                    new Object[] { MapMode.READ_ONLY, position, size, arena });
            return memorySegment == null ? null
                    : (ByteBuffer) reflectionUtils.invokeMethod(/* throwException = */ true, memorySegment,
                            "asByteBuffer");
        } catch (final Exception e) {
            // Mapping the file can fail with IOException or OutOfMemoryError, which the reflective method
            // invocation wraps in other exceptions -- unwrap and rethrow, so that the caller can retry
            // mapping after running garbage collection
            for (Throwable t = e; t != null; t = t.getCause()) {
                if (t instanceof IOException) {
                    throw (IOException) t;
                } else if (t instanceof OutOfMemoryError) {
                    throw (OutOfMemoryError) t;
                }
            }
            // The reflective invocation itself failed -- the caller will fall back to the FileChannel API
            return null;
        }
    }

    /**
     * Close an arena obtained from {@link #openArena(ReflectionUtils)}, freeing any direct {@link ByteBuffer}s
     * allocated from it and unmapping any files mapped with it. The buffers must no longer be in use by any
     * thread.
     *
     * @param arena
     *            the arena to close.
     * @param reflectionUtils
     *            the reflection utils
     * @param log
     *            the log
     * @return true if the arena was successfully closed.
     */
    public static boolean closeArena(final Object arena, final ReflectionUtils reflectionUtils,
            final LogNode log) {
        try {
            reflectionUtils.invokeMethod(/* throwException = */ true, arena, "close");
            return true;
        } catch (final Exception e) {
            if (log != null) {
                log.log("Could not close arena: " + e);
            }
            return false;
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Create a {@link FileAttributesGetter} that reads the attributes of each {@link Path} at most once, caching
     * the result. The returned getter is not thread safe, so it should only be used within the scope of the code
     * that created it.
     *
     * @return the caching {@link FileAttributesGetter}.
     */
    public static FileAttributesGetter createCachedAttributesGetter() {
        final Map<Path, BasicFileAttributes> cache = new HashMap<>();
        return new FileAttributesGetter() {
            @Override
            public BasicFileAttributes get(final Path path) {
                BasicFileAttributes attributes = cache.get(path);
                if (attributes == null) {
                    attributes = readAttributes(path);
                    cache.put(path, attributes);
                }
                return attributes;
            }
        };
    }

    /**
     * Read the {@link BasicFileAttributes} of a {@link Path}. If the attributes cannot be read, returns a
     * best-effort implementation backed by the {@link File} API, which throws
     * {@link UnsupportedOperationException} from the accessors it cannot support.
     *
     * @param path
     *            A {@link Path}.
     * @return the attributes of the path.
     */
    public static BasicFileAttributes readAttributes(final Path path) {
        try {
            return Files.readAttributes(path, BasicFileAttributes.class);
        } catch (final IOException e) {
            return new BasicFileAttributes() {
                @Override
                public FileTime lastModifiedTime() {
                    return FileTime.fromMillis(path.toFile().lastModified());
                }

                @Override
                public FileTime lastAccessTime() {
                    throw new UnsupportedOperationException();
                }

                @Override
                public FileTime creationTime() {
                    return FileTime.fromMillis(0);
                }

                @Override
                public boolean isRegularFile() {
                    return FileUtils.isFile(path);
                }

                @Override
                public boolean isDirectory() {
                    return FileUtils.isDir(path);
                }

                @Override
                public boolean isSymbolicLink() {
                    return false;
                }

                @Override
                public boolean isOther() {
                    return !isRegularFile() && !isDirectory();
                }

                @Override
                public long size() {
                    return path.toFile().length();
                }

                @Override
                public Object fileKey() {
                    throw new UnsupportedOperationException();
                }
            };
        }
    }

    /** Gets the {@link BasicFileAttributes} of a {@link Path}. */
    public interface FileAttributesGetter {
        /**
         * Get the attributes of a {@link Path}.
         *
         * @param path
         *            A {@link Path}.
         * @return the attributes of the path.
         */
        BasicFileAttributes get(Path path);
    }
}
