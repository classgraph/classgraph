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
package io.github.classgraph.base.internal.utils;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileChannel.MapMode;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.classgraph.base.internal.utils.VersionFinder.OperatingSystem;
import org.jspecify.annotations.Nullable;

/**
 * File utilities.
 */
public final class FileUtils {
    /**
     * The current directory path (only reads the current directory once, the first time this field is accessed, so
     * will not reflect subsequent changes to the current directory). Volatile, so that the lazy initialization in
     * {@link #currDirPath()} is not a data race.
     */
    private static volatile @Nullable String currDirPath;

    /**
     * The maximum size of a file buffer array. Eight bytes smaller than {@link Integer#MAX_VALUE}, since some VMs
     * reserve header words in arrays.
     */
    public static final int MAX_BUFFER_SIZE = Integer.MAX_VALUE - 8;

    /**
     * The separator between the random part of a ClassGraph temporary filename and the leafname of the file that
     * was extracted to it.
     */
    public static final String TEMP_FILENAME_LEAF_SEPARATOR = "---";

    /** The default size of a file buffer. */
    private static final int DEFAULT_BUFFER_SIZE = 16384;

    /** The maximum initial buffer size. */
    private static final int MAX_INITIAL_BUFFER_SIZE = 16 * 1024 * 1024;

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
        var currDirPathCached = currDirPath;
        if (currDirPathCached == null) {
            // user.dir should be the current directory at the time the JVM is started, which is where classpath
            // elements should be resolved relative to
            Path path = null;
            final var currDirPathStr = VersionFinder.getProperty("user.dir");
            if (currDirPathStr != null) {
                try {
                    path = Path.of(currDirPathStr);
                } catch (final InvalidPathException e) {
                    // Fall through
                }
            }
            if (path == null) {
                // "user.dir" is one of the system properties that System#getProperties() guarantees, but reading it
                // can still fail: a security manager can deny the read, and an application can replace the system
                // properties wholesale with a set that omits it. Fall back on the directory the JVM is running in.
                try {
                    path = Path.of("");
                } catch (final InvalidPathException e) {
                    // Fall through
                }
            }

            // Normalize current directory the same way all other paths are normalized in ClassGraph, for
            // consistency. Two threads racing here compute the same value, so whichever write lands last is
            // equivalent
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

        // A '!' is only a nested jar separator if the path before it names an existing jarfile -- it is otherwise a
        // legal filename character, and must not be treated as a path hierarchy root (#903)
        final var nestedJarSepIdx = JarUtils.indexOfNestedJarSeparator(path);

        final var pathLen = path.length();
        final var pathHasInitialSlash = path.charAt(0) == '/';
        final var pathHasInitialSlashSlash = pathHasInitialSlash && pathLen > 1 && path.charAt(1) == '/';
        final StringBuilder pathSanitized = new StringBuilder(pathLen + 16);
        if (hasSegmentToSanitize(path, nestedJarSepIdx)) {
            appendSanitizedSegments(path, nestedJarSepIdx, pathSanitized);
            if (pathSanitized.isEmpty() && pathHasInitialSlash) {
                pathSanitized.append('/');
            }
        } else {
            pathSanitized.append(path);
        }

        // Intended to preserve the double slash at the start of UNC paths (#736). e.g. //server/file/path
        if (VersionFinder.OS == OperatingSystem.Windows && pathHasInitialSlashSlash) {
            pathSanitized.insert(0, '/');
        }

        // Strip the final slashes before the initial ones, so that for a path consisting only of slashes (which is
        // what "/.." and "/." normalize to), truncating the buffer cannot leave it shorter than startIdx
        if (removeFinalSlash) {
            while (!pathSanitized.isEmpty() && pathSanitized.charAt(pathSanitized.length() - 1) == '/') {
                pathSanitized.setLength(pathSanitized.length() - 1);
            }
        }
        var startIdx = 0;
        if (removeInitialSlash || !pathHasInitialSlash) {
            // Strip off leading "/" if it needs to be removed, or if it wasn't present in the original path (the
            // string-building code above prepends "/" to every segment). Note that "/" is always added after "!",
            // since "jar:" URLs expect this.
            while (startIdx < pathSanitized.length() && pathSanitized.charAt(startIdx) == '/') {
                startIdx++;
            }
        }

        return pathSanitized.substring(startIdx);
    }

    /**
     * Check whether a path contains any segment that has to be removed, i.e. a {@code ".."} segment, a {@code "."}
     * segment, or an empty segment.
     *
     * @param path
     *            The path to check.
     * @param nestedJarSepIdx
     *            The index of the first nested jar separator {@code '!'} in the path, or -1 if there is none.
     * @return true if the path has to be sanitized.
     */
    private static boolean hasSegmentToSanitize(final String path, final int nestedJarSepIdx) {
        // Find all '/' and nested jar separator '!' character positions, which split a path into segments. This
        // scan reads the path via charAt() rather than copying it into a char[], since the common case is that
        // nothing needs sanitizing, and the copy would then be pure overhead.
        final var pathLen = path.length();
        var lastSepIdx = -1;
        var prevC = '\0';
        for (int i = 0, ii = pathLen + 1; i < ii; i++) {
            final var c = i == pathLen ? '\0' : path.charAt(i);
            if (c == '/' || (c == '!' && nestedJarSepIdx >= 0 && i >= nestedJarSepIdx) || c == '\0') {
                final var segmentLength = i - (lastSepIdx + 1);
                if (
                // Found empty segment "//" or "!!"
                (segmentLength == 0 && prevC == c)
                        // Found segment "."
                        || (segmentLength == 1 && path.charAt(i - 1) == '.')
                        // Found segment ".."
                        || (segmentLength == 2 && path.charAt(i - 2) == '.' && path.charAt(i - 1) == '.')) {
                    return true;
                }
                lastSepIdx = i;
            }
            prevC = c;
        }
        return false;
    }

    /**
     * Append a path to a {@link StringBuilder}, dropping empty and {@code "."} segments, and removing the preceding
     * segment for each {@code ".."} segment. Each segment is preceded by {@code '/'}, and each nested jar separator
     * is written as {@code '!'} (so that {@code "jar:"} URL syntax is produced, since {@code '/'} always follows).
     *
     * @param path
     *            The path to sanitize.
     * @param nestedJarSepIdx
     *            The index of the first nested jar separator {@code '!'} in the path, or -1 if there is none.
     * @param pathSanitized
     *            The buffer to append the sanitized path to.
     */
    private static void appendSanitizedSegments(final String path, final int nestedJarSepIdx,
            final StringBuilder pathSanitized) {
        // Sanitize between "!" section markers separately (".." should not apply past preceding "!")
        final var pathLen = path.length();
        final List<List<CharSequence>> allSectionSegments = new ArrayList<>();
        List<CharSequence> currSectionSegments = new ArrayList<>();
        allSectionSegments.add(currSectionSegments);
        var lastSepIdx = -1;
        for (var i = 0; i < pathLen + 1; i++) {
            final var c = i == pathLen ? '\0' : path.charAt(i);
            final var isSectionMarker = c == '!' && nestedJarSepIdx >= 0 && i >= nestedJarSepIdx;
            if (c == '/' || isSectionMarker || c == '\0') {
                final var segmentStartIdx = lastSepIdx + 1;
                final var segmentLen = i - segmentStartIdx;
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
                // Delineate sections with "!"
                if (!pathSanitized.isEmpty()) {
                    pathSanitized.append('!');
                }
                for (final CharSequence sectionSegment : sectionSegments) {
                    pathSanitized.append('/');
                    pathSanitized.append(sectionSegment);
                }
            }
        }
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
        final var len = path.length();
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
     * Canonicalize a {@link File}, resolving symlinks (and, on Windows, junctions and 8.3 short names), so that a
     * file reached through two different paths is given the same canonical path everywhere in ClassGraph. See
     * {@link #canonicalize(Path)}.
     *
     * @param file
     *            A {@link File}.
     * @return the canonical form of the file.
     * @throws IOException
     *             if the file could not be canonicalized.
     */
    public static File canonicalize(final File file) throws IOException {
        try {
            return canonicalize(file.toPath()).toFile();
        } catch (final RuntimeException e) {
            // The path is not valid for the default filesystem (e.g. on Windows it contains a character that is
            // not allowed in a filename)
            return file.getCanonicalFile();
        }
    }

    /**
     * Canonicalize a {@link Path}, resolving symlinks (and, on Windows, junctions and 8.3 short names), so that a
     * file reached through two different paths is given the same canonical path everywhere in ClassGraph.
     *
     * <p>
     * {@link Path#toRealPath(java.nio.file.LinkOption...)} is used rather than {@link File#getCanonicalFile()},
     * since on Windows the latter resolves neither directory symlinks and junctions nor 8.3 short names (e.g.
     * {@code C:\Users\RUNNER~1} for {@code C:\Users\runneradmin}).
     *
     * <p>
     * {@link Path#toRealPath(java.nio.file.LinkOption...)} requires the file to exist, so for a path that does not
     * exist, the closest ancestor directory that does exist is canonicalized, and the rest of the path is appended
     * to it. Only the part of the path that exists can be resolved, so the result is the best that can be done: the
     * same path as if the missing part of it were created.
     *
     * @param path
     *            A {@link Path}.
     * @return the canonical form of the path.
     * @throws IOException
     *             if the path could not be canonicalized.
     */
    public static Path canonicalize(final Path path) throws IOException {
        try {
            return path.toRealPath();
        } catch (final IOException | RuntimeException e) {
            // The path does not exist -- canonicalize the closest ancestor directory that does exist, then append
            // the rest of the path to it
            final var normalizedPath = path.toAbsolutePath().normalize();
            for (var ancestor = normalizedPath.getParent(); ancestor != null; ancestor = ancestor.getParent()) {
                try {
                    return ancestor.toRealPath().resolve(ancestor.relativize(normalizedPath));
                } catch (final IOException | RuntimeException e2) {
                    // This ancestor does not exist either -- try the next one up
                }
            }
            // Not even the filesystem root could be canonicalized -- return the normalized path
            return normalizedPath;
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
        final var lastSlashIdx = path.lastIndexOf(separator);
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
     * Create a {@link FileAttributesGetter} that reads the attributes of each {@link Path} at most once, caching
     * the result. The returned getter is not thread safe, so it should only be used within the scope of the code
     * that created it.
     *
     * @return the caching {@link FileAttributesGetter}.
     */
    public static FileAttributesGetter createCachedAttributesGetter() {
        final Map<Path, BasicFileAttributes> cache = new HashMap<>();
        // readAttributes never returns null, so computeIfAbsent caches every path after the first read
        return path -> cache.computeIfAbsent(path, FileUtils::readAttributes);
    }

    /**
     * Read the {@link BasicFileAttributes} of a {@link Path}. If the attributes cannot be read, returns a
     * best-effort implementation backed by the {@link File} API, which throws {@link UnsupportedOperationException}
     * from the accessors it cannot support.
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

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Read all the bytes in an {@link InputStream}.
     *
     * @param inputStream
     *            The {@link InputStream}.
     * @param uncompressedLengthHint
     *            The length of the data once inflated from the {@link InputStream}, if known, otherwise -1L.
     * @return The contents of the {@link InputStream} as a byte array.
     * @throws IOException
     *             If the contents could not be read.
     */
    public static byte[] readAllBytesAsArray(final InputStream inputStream, final long uncompressedLengthHint)
            throws IOException {
        if (uncompressedLengthHint > MAX_BUFFER_SIZE) {
            throw new IOException("InputStream is too large to read");
        }
        try (inputStream) {
            final var bufferSize = uncompressedLengthHint < 1L
                    // If fileSizeHint is zero or unknown, use default buffer size
                    ? DEFAULT_BUFFER_SIZE
                    // fileSizeHint is just a hint -- limit the max allocated buffer size, so that invalid ZipEntry
                    // lengths do not become a memory allocation attack vector
                    : Math.min((int) uncompressedLengthHint, MAX_INITIAL_BUFFER_SIZE);
            var buf = new byte[bufferSize];
            var totBytesRead = 0;
            for (int bytesRead;;) {
                while ((bytesRead = inputStream.read(buf, totBytesRead, buf.length - totBytesRead)) > 0) {
                    // Fill buffer until nothing more can be read
                    totBytesRead += bytesRead;
                }
                if (bytesRead < 0) {
                    // Reached end of stream without filling buf
                    break;
                }

                // bytesRead == 0: either the buffer was the correct size and the end of the stream has been
                // reached, or the buffer was too small. Need to try reading one more byte to see which is the case.
                final var extraByte = inputStream.read();
                if (extraByte == -1) {
                    // Reached end of stream
                    break;
                }

                // Haven't reached end of stream yet. Need to grow the buffer (double its size), and append the
                // extra byte that was just read.
                if (buf.length == MAX_BUFFER_SIZE) {
                    throw new IOException("InputStream too large to read into array");
                }
                buf = Arrays.copyOf(buf, (int) Math.min(buf.length * 2L, MAX_BUFFER_SIZE));
                buf[totBytesRead++] = (byte) extraByte;
            }
            // Return buffer and number of bytes read
            return totBytesRead == buf.length ? buf : Arrays.copyOf(buf, totBytesRead);
        }
    }
}
