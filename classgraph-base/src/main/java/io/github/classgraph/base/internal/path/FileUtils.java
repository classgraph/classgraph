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
package io.github.classgraph.base.internal.path;

import io.github.classgraph.base.internal.utils.VersionFinder;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.HashMap;
import java.util.Map;

import org.jspecify.annotations.Nullable;

/**
 * The operations that ask the filesystem about a {@link File} or a {@link Path}: whether it exists, what kind of
 * file it is, whether it can be read, what its attributes are, and what its canonical path is. Reading a path as
 * text, without asking the filesystem what it names, is the business of {@link PathSyntax}.
 */
public final class FileUtils {
    /**
     * The current directory path (only reads the current directory once, the first time this field is accessed, so
     * will not reflect subsequent changes to the current directory). Volatile, so that the lazy initialization in
     * {@link #currDirPath()} is not a data race.
     */
    private static volatile @Nullable String currDirPath;

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
     * since on Windows the latter does not resolve a symlink or a junction, whereas on Linux and macOS it does, so
     * the same layout would otherwise be canonicalized one way on Windows and another way everywhere else. (Both
     * methods do expand a Windows 8.3 short name, such as {@code C:\Users\RUNNER~1} for
     * {@code C:\Users\runneradmin}.)
     *
     * <p>
     * {@link Path#toRealPath(java.nio.file.LinkOption...)} requires the file to exist, so for a path that does not
     * exist, the closest ancestor directory that does exist is canonicalized, and the rest of the path is appended
     * to it. Only the part of the path that exists can be resolved, so the result is the best that can be done: the
     * same path as if the missing part of it were created.
     *
     * <p>
     * A {@code ".."} segment is resolved by the platform as far as the platform can reach, and only lexically
     * beyond that. On Linux and macOS the two do not agree, since the filesystem resolves such a segment: after a
     * symlinked directory, {@code ".."} names the parent of the directory the symlink points at, not the parent of
     * the symlink. (On Windows the path APIs collapse it lexically, so there is nothing to disagree about there.)
     * Nothing below the closest existing ancestor exists, so nothing there can be a symlink, which is what makes it
     * safe to resolve the rest lexically.
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
            // the rest of the path to it. The path is deliberately not normalized before the ancestors are walked,
            // so that the filesystem is the one to resolve any ".." segment that it can reach
            final var absolutePath = path.toAbsolutePath();
            for (var ancestor = absolutePath.getParent(); ancestor != null; ancestor = ancestor.getParent()) {
                try {
                    return ancestor.toRealPath().resolve(ancestor.relativize(absolutePath)).normalize();
                } catch (final IOException | RuntimeException e2) {
                    // This ancestor does not exist either -- try the next one up
                }
            }
            // Not even the filesystem root could be canonicalized -- resolve the whole path lexically instead
            return absolutePath.normalize();
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
}
