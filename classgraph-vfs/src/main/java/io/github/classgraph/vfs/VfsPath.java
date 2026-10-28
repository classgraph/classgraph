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
package io.github.classgraph.vfs;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.ProviderMismatchException;
import java.nio.file.WatchEvent.Kind;
import java.nio.file.WatchEvent.Modifier;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.jspecify.annotations.Nullable;

import io.github.classgraph.base.internal.utils.Assert;

/**
 * A path within a {@link VfsFileSystem}. Paths use {@code '/'} as the separator and {@code "/"} as the root
 * directory.
 *
 * <p>
 * Obtain instances via {@link VfsFileSystem#getPath(String, String...)} or other {@link VfsFileSystem} methods;
 * cast a {@link Path} to {@code VfsPath} to call {@link #toCgvfsUri()}.
 */
public final class VfsPath implements Path {
    /** The filesystem this path belongs to. */
    private final VfsFileSystem fileSystem;

    /** True if this path starts at the root directory. */
    private final boolean absolute;

    /** The name elements of this path (a single empty string for the empty relative path). */
    private final String[] names;

    /**
     * Constructor.
     *
     * @param fileSystem
     *            the filesystem this path belongs to.
     * @param absolute
     *            true if this path starts at the root directory.
     * @param names
     *            the name elements of this path.
     */
    private VfsPath(final VfsFileSystem fileSystem, final boolean absolute, final String[] names) {
        this.fileSystem = fileSystem;
        this.absolute = absolute;
        this.names = names;
    }

    /**
     * Parse a path.
     *
     * @param fileSystem
     *            the filesystem the path belongs to.
     * @param path
     *            the path, with {@code '/'} as the separator.
     * @return the parsed path.
     */
    static VfsPath parse(final VfsFileSystem fileSystem, final String path) {
        Assert.notNull(path, "path");
        final List<String> names = new ArrayList<>();
        for (final var name : path.split("/")) {
            // Repeated and trailing separators are not name elements
            if (!name.isEmpty()) {
                names.add(name);
            }
        }
        final var absolute = path.startsWith("/");
        if (names.isEmpty() && !absolute) {
            // The empty path has one name element, which is the empty string
            names.add("");
        }
        return new VfsPath(fileSystem, absolute, names.toArray(new String[0]));
    }

    /**
     * Cast a path to a {@link VfsPath}.
     *
     * @param path
     *            the path.
     * @return the path, as a {@link VfsPath}.
     * @throws ProviderMismatchException
     *             if the path did not come from a {@link VfsFileSystem}.
     */
    private static VfsPath check(final Path path) {
        Assert.notNull(path, "path");
        if (!(path instanceof final VfsPath vfsPath)) {
            throw new ProviderMismatchException("Not a path of a virtual filesystem: " + path);
        }
        return vfsPath;
    }

    /**
     * Returns whether this is the empty relative path.
     *
     * @return true if this is the empty path.
     */
    private boolean isEmptyPath() {
        return !absolute && names.length == 1 && names[0].isEmpty();
    }

    /**
     * Returns the name elements of this path, or an empty array for the empty relative path.
     *
     * @return the name elements.
     */
    private String[] nameElements() {
        return isEmptyPath() ? new String[0] : names;
    }

    /**
     * Returns the normalized absolute entry path without a leading {@code '/'}, suitable for
     * {@link VfsRoot#getEntry(String)}.
     *
     * @return the entry name, or the empty string for the root directory.
     */
    String entryName() {
        final var normalized = (VfsPath) toAbsolutePath().normalize();
        return String.join("/", normalized.names);
    }

    // -------------------------------------------------------------------------------------------------------------

    @Override
    public FileSystem getFileSystem() {
        return fileSystem;
    }

    /**
     * Returns the {@link VfsFileSystem} this path belongs to.
     *
     * @return the filesystem.
     */
    VfsFileSystem vfsFileSystem() {
        return fileSystem;
    }

    @Override
    public boolean isAbsolute() {
        return absolute;
    }

    @Override
    public @Nullable Path getRoot() {
        return absolute ? new VfsPath(fileSystem, true, new String[0]) : null;
    }

    @Override
    public @Nullable Path getFileName() {
        return names.length == 0 ? null : new VfsPath(fileSystem, false, new String[] { names[names.length - 1] });
    }

    @Override
    public @Nullable Path getParent() {
        if (names.length <= 1) {
            // The parent of a name element at the top of an absolute path is the root directory; the parent of a
            // name element of a relative path, and of the root directory itself, is nothing
            return absolute && names.length == 1 ? getRoot() : null;
        }
        return new VfsPath(fileSystem, absolute, Arrays.copyOf(names, names.length - 1));
    }

    @Override
    public int getNameCount() {
        return names.length;
    }

    @Override
    public Path getName(final int index) {
        if (index < 0 || index >= names.length) {
            throw new IllegalArgumentException("No name element at index " + index + " in " + this);
        }
        return new VfsPath(fileSystem, false, new String[] { names[index] });
    }

    @Override
    public Path subpath(final int beginIndex, final int endIndex) {
        if (beginIndex < 0 || endIndex > names.length || beginIndex >= endIndex) {
            throw new IllegalArgumentException(
                    "Invalid range [" + beginIndex + ", " + endIndex + ") of name elements in " + this);
        }
        return new VfsPath(fileSystem, false, Arrays.copyOfRange(names, beginIndex, endIndex));
    }

    @Override
    public boolean startsWith(final Path other) {
        Assert.notNull(other, "other");
        // A path of another filesystem is answered, not rejected, as Path#startsWith requires
        if (!(other instanceof final VfsPath otherPath) || otherPath.fileSystem != fileSystem
                || otherPath.absolute != absolute || otherPath.names.length > names.length) {
            return false;
        }
        return Arrays.equals(names, 0, otherPath.names.length, otherPath.names, 0, otherPath.names.length);
    }

    @Override
    public boolean endsWith(final Path other) {
        Assert.notNull(other, "other");
        // A path of another filesystem is answered, not rejected, as Path#endsWith requires
        if (!(other instanceof final VfsPath otherPath) || otherPath.fileSystem != fileSystem
                || otherPath.names.length > names.length) {
            return false;
        }
        if (otherPath.absolute) {
            // An absolute path is only the end of an identical absolute path
            return equals(otherPath);
        }
        final var offset = names.length - otherPath.names.length;
        return Arrays.equals(names, offset, names.length, otherPath.names, 0, otherPath.names.length);
    }

    @Override
    public Path normalize() {
        final var normalized = new ArrayDeque<String>();
        for (final var name : names) {
            if (name.isEmpty() || ".".equals(name)) {
                continue;
            }
            if ("..".equals(name)) {
                final var last = normalized.peekLast();
                if (last != null && !"..".equals(last)) {
                    normalized.removeLast();
                } else if (!absolute) {
                    // A relative path can start above where it began; an absolute path cannot go above the root
                    normalized.addLast(name);
                }
            } else {
                normalized.addLast(name);
            }
        }
        if (normalized.isEmpty() && !absolute) {
            return parse(fileSystem, "");
        }
        return new VfsPath(fileSystem, absolute, normalized.toArray(new String[0]));
    }

    @Override
    public Path resolve(final Path other) {
        final var otherPath = check(other);
        if (otherPath.absolute) {
            return otherPath;
        }
        if (otherPath.isEmptyPath()) {
            return this;
        }
        if (isEmptyPath()) {
            return otherPath;
        }
        final var resolved = Arrays.copyOf(names, names.length + otherPath.names.length);
        System.arraycopy(otherPath.names, 0, resolved, names.length, otherPath.names.length);
        return new VfsPath(fileSystem, absolute, resolved);
    }

    /**
     * {@inheritDoc}
     *
     * <p>
     * Both paths are normalized before computing the relative path.
     *
     * @throws IllegalArgumentException
     *             if the paths belong to different filesystem views, if one path is absolute and the other is
     *             relative, or if no relative path can be constructed.
     */
    @Override
    public Path relativize(final Path other) {
        final var otherPath = check(other);
        if (fileSystem != otherPath.fileSystem) {
            throw new IllegalArgumentException("Cannot relativize paths from different filesystem views");
        }
        if (otherPath.absolute != absolute) {
            throw new IllegalArgumentException("Cannot relativize " + other + " against " + this
                    + " : one is absolute and the other is relative");
        }
        if (isEmptyPath()) {
            return otherPath;
        }
        final var from = ((VfsPath) normalize()).nameElements();
        final var to = ((VfsPath) otherPath.normalize()).nameElements();
        var commonLength = 0;
        while (commonLength < from.length && commonLength < to.length
                && from[commonLength].equals(to[commonLength])) {
            commonLength++;
        }
        final List<String> relative = new ArrayList<>();
        for (var i = commonLength; i < from.length; i++) {
            if ("..".equals(from[i])) {
                // The base climbs above where it began, to a directory whose name it does not know
                throw new IllegalArgumentException("Cannot relativize " + other + " against " + this);
            }
            relative.add("..");
        }
        relative.addAll(Arrays.asList(to).subList(commonLength, to.length));
        if (relative.isEmpty()) {
            return parse(fileSystem, "");
        }
        return new VfsPath(fileSystem, false, relative.toArray(new String[0]));
    }

    /**
     * {@inheritDoc}
     *
     * <p>
     * Returns the URI of the underlying storage ({@code "file:"}, {@code "jar:"}, or module URI) rather than a
     * {@code "cgvfs:"} URI. See {@link #toCgvfsUri()} for the provider URI.
     */
    @Override
    public URI toUri() {
        return fileSystem.uriOf(this);
    }

    /**
     * Returns the {@code "cgvfs:"} URI of this path for resolution via {@link Path#of(URI)} or
     * {@link java.nio.file.Paths#get(URI)}.
     *
     * <p>
     * Resolving the returned URI requires the filesystem to remain open and to have been created via
     * {@link java.nio.file.FileSystems#newFileSystem(URI, java.util.Map)}. See {@link #toUri()} for the URI of the
     * underlying storage.
     *
     * @return the {@code "cgvfs:"} URI of this path.
     * @throws FileSystemNotFoundException
     *             if the {@code "cgvfs:"} provider is not installed in the system class loader.
     */
    public URI toCgvfsUri() {
        if (!VfsFileSystemProvider.isInstalled()) {
            throw new FileSystemNotFoundException("The \"" + VfsFileSystemProvider.SCHEME
                    + ":\" scheme is not installed in this JVM, so this URI could not be resolved. It is installed"
                    + " by ServiceLoader when classgraph-vfs is loaded by the system class loader, which is not the"
                    + " case here.");
        }
        try {
            return VfsFileSystemProvider.cgvfsUriOf(fileSystem.getRoot(), entryName());
        } catch (final URISyntaxException e) {
            throw new IllegalArgumentException("Path cannot be written as a URI: " + this, e);
        }
    }

    @Override
    public Path toAbsolutePath() {
        return absolute ? this : new VfsPath(fileSystem, true, nameElements());
    }

    @Override
    public Path toRealPath(final LinkOption... options) throws IOException {
        final var real = toAbsolutePath().normalize();
        if (!fileSystem.exists(((VfsPath) real).entryName())) {
            throw new NoSuchFileException(real.toString());
        }
        return real;
    }

    @Override
    public WatchKey register(final WatchService watcher, final Kind<?>[] events, final Modifier... modifiers) {
        throw new UnsupportedOperationException("A virtual filesystem cannot be watched for changes");
    }

    // -------------------------------------------------------------------------------------------------------------

    @Override
    public int compareTo(final Path other) {
        if (!(other instanceof final VfsPath otherPath)) {
            // Path#compareTo specifies ClassCastException for a path of another provider, rather than the
            // ProviderMismatchException that the other methods of Path throw
            throw new ClassCastException("Not a path of a virtual filesystem: " + other);
        }
        final var diff = toString().compareTo(otherPath.toString());
        // Path#compareTo specifies that it returns zero only for a path that is equal to this one, and a path of
        // another view of the same root is not equal to this one, however it is spelled. The views are therefore
        // ordered by the order in which they were created, which is arbitrary but stable.
        return diff != 0 ? diff : Long.compare(fileSystem.serial, otherPath.fileSystem.serial);
    }

    @Override
    public boolean equals(final @Nullable Object obj) {
        if (obj == this) {
            return true;
        }
        return obj instanceof final VfsPath other && other.fileSystem == fileSystem && other.absolute == absolute
                && Arrays.equals(other.names, names);
    }

    @Override
    public int hashCode() {
        return fileSystem.hashCode() * 31 + toString().hashCode();
    }

    @Override
    public String toString() {
        final var joined = String.join("/", names);
        return absolute ? "/" + joined : joined;
    }
}
