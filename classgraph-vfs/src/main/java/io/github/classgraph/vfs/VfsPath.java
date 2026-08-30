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

import io.github.classgraph.base.internal.utils.Assert;
import org.jspecify.annotations.Nullable;

/**
 * A path within a {@link VfsFileSystem}. Paths are separated by {@code '/'}, whichever kind of root the filesystem
 * is a view of, and the root directory is {@code "/"}.
 *
 * <p>
 * Paths are made by {@link VfsFileSystem#getPath(String, String...)} and by every method that returns a
 * {@link Path} of such a filesystem, rather than being constructed directly. The type is public only because
 * {@link #toCgvfsUri()} has no equivalent on {@link Path}: cast to this type to call it.
 */
public final class VfsPath implements Path {
    /** The filesystem this path belongs to. */
    private final VfsFileSystem fileSystem;

    /** True if this path starts at the root directory. */
    private final boolean absolute;

    /**
     * The name elements of this path. A relative path with no name elements is stored as a single empty name, which
     * is how {@link Path#of(String, String...)} stores the empty path.
     */
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
     * Returns whether this is the empty path, i.e. a relative path whose only name element is the empty string.
     *
     * @return true if this is the empty path.
     */
    private boolean isEmptyPath() {
        return !absolute && names.length == 1 && names[0].isEmpty();
    }

    /**
     * Returns the name elements of this path, with the empty path treated as having none, which is what the
     * relative-path arithmetic needs.
     *
     * @return the name elements.
     */
    private String[] nameElements() {
        return isEmptyPath() ? new String[0] : names;
    }

    /**
     * Returns the name of the entry this path names, i.e. the path made absolute and normalized, with the leading
     * {@code '/'} removed, which is the form that {@link VfsRoot#getEntry(String)} takes.
     *
     * @return the entry name, which is the empty string for the root directory.
     */
    String entryName() {
        final var normalized = (VfsPath) toAbsolutePath().normalize();
        return String.join("/", normalized.names);
    }

    // -------------------------------------------------------------------------------------------------------------

    @Override
    public VfsFileSystem getFileSystem() {
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

    @Override
    public Path relativize(final Path other) {
        final var otherPath = check(other);
        if (otherPath.absolute != absolute) {
            throw new IllegalArgumentException("Cannot relativize " + other + " against " + this
                    + " : one is absolute and the other is relative");
        }
        final var from = nameElements();
        final var to = otherPath.nameElements();
        var commonLength = 0;
        while (commonLength < from.length && commonLength < to.length
                && from[commonLength].equals(to[commonLength])) {
            commonLength++;
        }
        final List<String> relative = new ArrayList<>();
        for (var i = commonLength; i < from.length; i++) {
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
     * This is the URI of the storage the path is read from -- a {@code "file:"} URI for a file in a directory, a
     * {@code "jar:"} URI for an entry of a jarfile, a {@code "jrt:"} URI for a file of a module -- and not a
     * {@code "cgvfs:"} URI, so it names the same bytes to code that has never heard of ClassGraph. See
     * {@link #toCgvfsUri()} for the URI that names this path through this provider.
     */
    @Override
    public URI toUri() {
        return fileSystem.uriOf(this);
    }

    /**
     * Returns the {@code "cgvfs:"} URI of this path, which names it through this provider, so that
     * {@link Path#of(URI)} and {@link java.nio.file.Paths#get(URI)} give back an equal path.
     *
     * <p>
     * Resolving the returned URI needs the filesystem to still be open, and to have been created by
     * {@link java.nio.file.FileSystems#newFileSystem(URI, Map)} rather than by {@link VfsRoot#asFileSystem()},
     * since only those are registered under their path -- the same rule zipfs follows for {@code "jar:"} URIs.
     *
     * <p>
     * See {@link #toUri()} for the URI of the underlying storage, which is what an unrelated library can read.
     *
     * @return the {@code "cgvfs:"} URI of this path.
     * @throws FileSystemNotFoundException
     *             if the {@code "cgvfs:"} scheme is not installed in this JVM, so that the returned URI could not
     *             be resolved by anything. This happens when classgraph-vfs was loaded by a class loader other than
     *             the system class loader, since that is the only one {@link java.nio.file.spi.FileSystemProvider}
     *             searches for installed providers.
     */
    public URI toCgvfsUri() {
        if (!VfsFileSystemProvider.isInstalled()) {
            throw new FileSystemNotFoundException("The \"" + VfsFileSystemProvider.SCHEME
                    + ":\" scheme is not installed in this JVM, so this URI could not be resolved. It is installed"
                    + " by ServiceLoader when classgraph-vfs is loaded by the system class loader, which is not the"
                    + " case here.");
        }
        final var rootPath = fileSystem.getRoot().reportedPath();
        final var name = ((VfsPath) toAbsolutePath().normalize()).entryName();
        try {
            // This URI constructor quotes the characters that a URI cannot hold, so a path with a space in it
            // gives a URI with "%20" in it, which FastPathResolver decodes back to the space when the URI is
            // read. The separators are not quoted, since the reported path of a root is already resolved, so
            // they are forward slashes even on Windows: a separator is written as a separator, never as "%2F"
            return new URI(VfsFileSystemProvider.SCHEME, rootPath + (name.isEmpty() ? "" : "!/" + name),
                    /* fragment = */ null);
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
