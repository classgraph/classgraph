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

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;

import org.jspecify.annotations.Nullable;

/**
 * Filesystem operations on {@link File} and {@link Path} instances.
 */
public final class FileUtils {
    /** Cached normalized path of the current working directory. */
    private static volatile @Nullable String currDirPath;

    // -------------------------------------------------------------------------------------------------------------

    /** Not instantiable. */
    private FileUtils() {
        // Cannot be constructed
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Return the normalized string form of a {@link Path}, resolving paths on non-default filesystems via their
     * URI.
     *
     * @param path
     *            A {@link Path}.
     * @return the normalized path string.
     * @throws java.io.IOError
     *             if the URI of a path on a non-default filesystem could not be formed.
     */
    public static String pathStr(final Path path) {
        if (path.getFileSystem() == FileSystems.getDefault()) {
            return FastPathResolver.resolve(currDirPath(), path.toString());
        }
        final var uriStr = path.toUri().toString();
        final var resolved = FastPathResolver.resolve(uriStr);
        return uriStr.endsWith("!/") && !resolved.endsWith("/") ? resolved + "!/" : resolved;
    }

    /**
     * Return the cached normalized path of the current working directory.
     *
     * @return The current working directory path.
     */
    public static String currDirPath() {
        // Read the volatile field once, so that the value returned cannot differ from the value tested
        var currDirPathCached = currDirPath;
        if (currDirPathCached == null) {
            // user.dir should be the current directory at the time the JVM is started, which is where classpath
            // elements should be resolved relative to
            Path path = null;
            final var currDirPathStr = System.getProperty("user.dir");
            if (currDirPathStr != null) {
                try {
                    path = Path.of(currDirPathStr);
                } catch (final InvalidPathException _) {
                    // Fall through
                }
            }
            if (path == null) {
                // "user.dir" is one of the system properties that System#getProperties() guarantees, but an
                // application can replace the system properties wholesale with a set that omits it. Fall back on
                // the directory the JVM is running in.
                try {
                    path = Path.of("");
                } catch (final InvalidPathException _) {
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
        return file.canRead();
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
        } catch (final UnsupportedOperationException _) {
            // Path#toFile() throws this for a path on a filesystem other than the default one, which has no
            // File equivalent -- fall through and ask the NIO API instead
        }
        return Files.isReadable(path);
    }

    /**
     * Check if a {@link File} exists, is a regular file, and can be read.
     *
     * @param file
     *            A {@link File}.
     * @return true if the file exists, is a regular file, and can be read.
     */
    public static boolean canReadAndIsFile(final File file) {
        return file.canRead() && file.isFile();
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
        } catch (final UnsupportedOperationException _) {
            // Path#toFile() throws this for a path on a filesystem other than the default one, which has no
            // File equivalent -- fall through and ask the NIO API instead
        }
        return Files.isReadable(path) && Files.isRegularFile(path);
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
        } catch (final UnsupportedOperationException _) {
            return Files.isRegularFile(path);
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
        if (!file.canRead()) {
            throw new FileNotFoundException("File does not exist or cannot be read: " + file);
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
        } catch (final UnsupportedOperationException _) {
            // Path#toFile() throws this for a path on a filesystem other than the default one, which has no
            // File equivalent -- fall through and ask the NIO API instead
        }
        if (!Files.isReadable(path)) {
            throw new FileNotFoundException("Path does not exist or cannot be read: " + path);
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
        return file.canRead() && file.isDirectory();
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
        } catch (final UnsupportedOperationException _) {
            // Path#toFile() throws this for a path on a filesystem other than the default one, which has no
            // File equivalent -- fall through and ask the NIO API instead
        }
        return Files.isReadable(path) && Files.isDirectory(path);
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
        } catch (final UnsupportedOperationException _) {
            return Files.isDirectory(path);
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Canonicalize a {@link File}, resolving symlinks, junctions, and Windows 8.3 short names.
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
        } catch (final RuntimeException _) {
            // The path is not valid for the default filesystem (e.g. on Windows it contains a character that is
            // not allowed in a filename)
            return file.getCanonicalFile();
        }
    }

    /**
     * Canonicalize a {@link Path}, resolving symlinks, junctions, and Windows 8.3 short names. If the path does not
     * exist, canonicalizes its closest existing ancestor and resolves the remaining segments lexically.
     *
     * @param path
     *            A {@link Path}.
     * @return the canonical form of the path.
     */
    public static Path canonicalize(final Path path) {
        try {
            return path.toRealPath();
        } catch (final IOException | RuntimeException _) {
            // The path does not exist -- canonicalize the closest ancestor directory that does exist, then append
            // the rest of the path to it. The path is deliberately not normalized before the ancestors are walked,
            // so that the filesystem is the one to resolve any ".." segment that it can reach
            final var absolutePath = path.toAbsolutePath();
            for (var ancestor = absolutePath.getParent(); ancestor != null; ancestor = ancestor.getParent()) {
                try {
                    return ancestor.toRealPath().resolve(ancestor.relativize(absolutePath)).normalize();
                } catch (final IOException | RuntimeException _) {
                    // This ancestor does not exist either -- try the next one up
                }
            }
            // Not even the filesystem root could be canonicalized -- resolve the whole path lexically instead
            return absolutePath.normalize();
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Read the {@link BasicFileAttributes} of a {@link Path}, falling back to a best-effort {@link File}-backed
     * implementation if attributes cannot be read via NIO.
     *
     * @param path
     *            A {@link Path}.
     * @return the attributes of the path.
     */
    public static BasicFileAttributes readAttributes(final Path path) {
        try {
            return Files.readAttributes(path, BasicFileAttributes.class);
        } catch (final IOException _) {
            return new BasicFileAttributes() {
                @Override
                public FileTime lastModifiedTime() {
                    try {
                        return FileTime.fromMillis(path.toFile().lastModified());
                    } catch (final UnsupportedOperationException _) {
                        // Path#toFile() throws UnsupportedOperationException for a path on a filesystem other than
                        // the default one, which has no File equivalent -- fall through and ask the NIO API instead
                    }
                    try {
                        return Files.getLastModifiedTime(path);
                    } catch (final IOException _) {
                        // File#lastModified() returns zero when the time cannot be read, so match that rather
                        // than throwing from an accessor that the File-backed path never throws from
                        return FileTime.fromMillis(0);
                    }
                }

                @Override
                public FileTime lastAccessTime() {
                    // BasicFileAttributes allows the last-modified time as the default when this is unsupported
                    return lastModifiedTime();
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
                    try {
                        return path.toFile().length();
                    } catch (final UnsupportedOperationException _) {
                        // Path#toFile() throws UnsupportedOperationException for a path on a filesystem other than
                        // the default one, which has no File equivalent -- fall through and ask the NIO API instead
                    }
                    try {
                        return Files.size(path);
                    } catch (final IOException _) {
                        // File#length() returns zero when the length cannot be read, so match that rather than
                        // throwing from an accessor that the File-backed path never throws from
                        return 0L;
                    }
                }

                @Override
                public @Nullable Object fileKey() {
                    // BasicFileAttributes specifies null when no file key is available
                    return null;
                }
            };
        }
    }
}
