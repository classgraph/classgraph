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

import java.io.IOError;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.NonWritableChannelException;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.file.AccessDeniedException;
import java.nio.file.AccessMode;
import java.nio.file.CopyOption;
import java.nio.file.DirectoryStream;
import java.nio.file.DirectoryStream.Filter;
import java.nio.file.FileStore;
import java.nio.file.FileSystem;
import java.nio.file.FileSystemAlreadyExistsException;
import java.nio.file.FileSystemException;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotDirectoryException;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.ProviderMismatchException;
import java.nio.file.ReadOnlyFileSystemException;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.FileAttributeView;
import java.nio.file.attribute.FileTime;
import java.nio.file.spi.FileSystemProvider;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.classgraph.base.internal.path.FastPathResolver;
import io.github.classgraph.base.internal.utils.Assert;
import org.jspecify.annotations.Nullable;

/**
 * The {@link FileSystemProvider} of the read-only {@link VfsFileSystem} views of a {@link VfsRoot}, registered
 * under the {@code "cgvfs:"} URL scheme.
 *
 * <p>
 * The scheme is installed by {@link java.util.ServiceLoader}, so putting classgraph-vfs on the classpath or the
 * module path is enough to make {@code cgvfs:} URIs work -- but only if it is loaded by the system class loader,
 * since that is the loader {@link FileSystemProvider#installedProviders()} searches. A copy loaded by a child class
 * loader -- a servlet container's per-application loader, a fat-jar loader, an OSGi bundle -- is not installed, and
 * {@link #isInstalled()} reports that.
 *
 * <p>
 * A URI is {@code "cgvfs:"} followed by anything {@link Vfs#open(String)} accepts, so {@code "file:"} is optional
 * and {@code "!/"} separates a jarfile from a nested jarfile or a package root within it:
 *
 * <pre>
 * cgvfs:/path/app.jar
 * cgvfs:file:/path/app.jar
 * cgvfs:/path/outer.jar!/lib/inner.jar
 * cgvfs:/path/spring-boot-app.jar!/BOOT-INF/classes
 * cgvfs:jrt:/java.logging
 * </pre>
 *
 * <p>
 * Which part of a URI names the filesystem and which part names a path within it is decided the same way
 * {@link Vfs#open(String)} decides it, by reading what is actually in storage rather than by looking at the
 * spelling, and by which method the URI is passed to -- exactly as with {@code jar:} URIs and zipfs.
 * {@link #newFileSystem(URI, Map)} and {@link #getFileSystem(URI)} read the whole URI as the name of a root, so
 * {@code "cgvfs:/path/outer.jar!/lib/inner.jar"} is the nested jarfile's filesystem, and
 * {@code "cgvfs:/path/spring-boot-app.jar!/BOOT-INF/classes"} is the package root's filesystem.
 * {@link #getPath(URI)} reads the last {@code "!/"} section as a path within the filesystem named by everything
 * before it, so {@code "cgvfs:/path/app.jar!/META-INF/MANIFEST.MF"} is a file of {@code app.jar}, and
 * {@code "cgvfs:/path/outer.jar!/lib/inner.jar!/com/xyz/W.class"} is a file of the nested jarfile.
 *
 * <p>
 * Every method that reads a path's filesystem throws {@link java.nio.file.ClosedFileSystemException} once that
 * filesystem, or the {@link Vfs} behind it, has been closed. The purely syntactic {@link Path} methods go on
 * working, since they need nothing from the filesystem's content.
 */
public final class VfsFileSystemProvider extends FileSystemProvider {
    /** The instance used by {@link VfsRoot#asFileSystem()}, which is the installed one where there is one. */
    static final VfsFileSystemProvider INSTANCE = new VfsFileSystemProvider();

    /**
     * The filesystems created from a URI by {@link #newFileSystem(URI, Map)}, keyed by every path they can be named
     * by. This is static rather than per-instance because {@link java.util.ServiceLoader} constructs an instance of
     * its own, so the instance a caller reaches through {@link FileSystemProvider#installedProviders()} need not be
     * the one a {@link VfsPath} reports from {@link VfsPath#getFileSystem()}.
     */
    private static final Map<String, VfsFileSystem> FILESYSTEMS_BY_PATH = new ConcurrentHashMap<>();

    /** Whether {@link #isInstalled()} has looked the provider up yet, and what it found. */
    private static volatile @Nullable Boolean installed;

    /**
     * Constructor.
     *
     * <p>
     * This is public only because {@link java.util.ServiceLoader} has to be able to call it. Use
     * {@link java.nio.file.FileSystems} to reach the provider, or {@link Vfs} and {@link VfsRoot#asFileSystem()} to
     * bypass it. Constructing one directly gives a provider that shares the filesystem registry with the installed
     * one, so it behaves the same, but a {@link Path} of a filesystem it created will not be recognized by code
     * that compares providers by identity.
     */
    public VfsFileSystemProvider() {
    }

    /**
     * Returns whether the {@code "cgvfs:"} scheme is installed in this JVM, i.e. whether this provider is one of
     * {@link FileSystemProvider#installedProviders()}, so that {@code cgvfs:} URIs can be resolved through
     * {@link java.nio.file.FileSystems} and {@link Path#of(URI)}.
     *
     * <p>
     * It is installed by {@link java.util.ServiceLoader} when classgraph-vfs is loaded by the system class loader,
     * and not installed when it is loaded by a child class loader. The answer cannot change during the life of the
     * JVM, since {@link FileSystemProvider#installedProviders()} loads the providers once, on its first call.
     *
     * @return true if the {@code "cgvfs:"} scheme is installed.
     */
    public static boolean isInstalled() {
        var isInstalled = installed;
        if (isInstalled == null) {
            // Not synchronized: two threads that race here look the answer up twice and reach the same answer,
            // which is cheaper than locking around a call that itself takes a lock inside the JDK
            isInstalled = false;
            for (final var provider : FileSystemProvider.installedProviders()) {
                if (provider instanceof VfsFileSystemProvider) {
                    isInstalled = true;
                    break;
                }
            }
            installed = isInstalled;
        }
        return isInstalled;
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
        if (!(path instanceof final VfsPath vfsPath)) {
            throw new ProviderMismatchException("Not a path of a virtual filesystem: " + path);
        }
        return vfsPath;
    }

    /**
     * Look up the entry a path names.
     *
     * @param path
     *            the path.
     * @return the entry.
     * @throws java.nio.file.FileSystemException
     *             if the path names a directory, which has no content to read.
     * @throws NoSuchFileException
     *             if the path does not name a file of the filesystem.
     * @throws IOException
     *             if the entries of the root could not be listed.
     */
    private static VfsEntry entryOf(final VfsPath path) throws IOException {
        final var fileSystem = path.getFileSystem();
        final var name = path.entryName();
        final var entry = fileSystem.entry(name);
        if (entry == null) {
            // Reported the same way as the default provider reports it, rather than as a missing file, since
            // Files#exists and Files#isDirectory both answer for a directory
            if (fileSystem.isDirectory(name)) {
                throw new FileSystemException(path.toString(), null, "Is a directory");
            }
            throw new NoSuchFileException(path.toString());
        }
        return entry;
    }

    /**
     * Check that a set of open options asks only to read.
     *
     * @param options
     *            the open options.
     * @throws ReadOnlyFileSystemException
     *             if an option asks to write.
     */
    private static void checkReadOnly(final Collection<? extends OpenOption> options) {
        for (final var option : options) {
            Objects.requireNonNull(option, "open option must not be null");
            if (option == StandardOpenOption.WRITE || option == StandardOpenOption.APPEND
                    || option == StandardOpenOption.CREATE || option == StandardOpenOption.CREATE_NEW
                    || option == StandardOpenOption.DELETE_ON_CLOSE
                    || option == StandardOpenOption.TRUNCATE_EXISTING || option == StandardOpenOption.SYNC
                    || option == StandardOpenOption.DSYNC) {
                throw new ReadOnlyFileSystemException();
            } else if (option != StandardOpenOption.READ) {
                throw new UnsupportedOperationException("Unsupported open option: " + option);
            }
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /** The URL scheme this provider is registered under. */
    static final String SCHEME = "cgvfs";

    /** The env key that names the {@link ModuleLayer} a {@code "cgvfs:jrt:/<module>"} URI is resolved in. */
    private static final String LAYER_ENV_KEY = "layer";

    /** The env key that names the {@link VfsSpec} the created {@link Vfs} reads storage with. */
    private static final String VFS_SPEC_ENV_KEY = "vfsSpec";

    @Override
    public String getScheme() {
        return SCHEME;
    }

    /**
     * Returns the part of a {@code "cgvfs:"} URI that names what to open, which is a path in the form
     * {@link Vfs#open(String)} takes.
     *
     * @param uri
     *            the URI.
     * @return the path the URI names.
     * @throws IllegalArgumentException
     *             if the URI is not a {@code "cgvfs:"} URI, or names nothing.
     */
    private static String pathOf(final URI uri) {
        Assert.notNull(uri, "uri");
        if (!SCHEME.equalsIgnoreCase(uri.getScheme())) {
            throw new IllegalArgumentException("Not a \"" + SCHEME + ":\" URI: " + uri);
        }
        // A "cgvfs:" URI is opaque, so the whole of it after the scheme is the scheme-specific part -- that is
        // what allows the nested "file:" or "jrt:" scheme, and the "!/" separators, to be written unescaped
        final var rawPath = uri.getRawSchemeSpecificPart();
        if (rawPath == null || rawPath.isEmpty()) {
            throw new IllegalArgumentException("URI names no path: " + uri);
        }
        if (uri.getFragment() != null) {
            throw new IllegalArgumentException("A \"" + SCHEME + ":\" URI cannot have a fragment: " + uri);
        }
        // Decoded, so that a path written with "%20" for a space names the file that has a space in its name. This
        // decodes by ClassGraph's rule rather than URI#getSchemeSpecificPart's: a separator is never produced by
        // decoding, so "%2F" and "%5C" stay as they are written. Under the rules of a URI they are a slash and a
        // backslash that are not separators, but no filesystem allows either character in a name, so decoding one
        // could only introduce a separator that was never in the path, and name a different file than the same
        // path string handed to Vfs#open -- which does not percent-decode a path at all, since a path is not
        // percent-encoded. That is what made a Coursier cache path unscannable in #255. Nothing but the percent
        // encoding is changed here: the separators are left for FastPathResolver#resolve, which knows which of
        // them belong to a nested URL's scheme and authority
        return FastPathResolver.decodePercentEncoding(rawPath);
    }

    /** Opens one root of a {@link Vfs}, so that both {@code newFileSystem} overloads share the same plumbing. */
    @FunctionalInterface
    private interface RootOpener {
        /**
         * Open the root.
         *
         * @param vfs
         *            the {@link Vfs} to open the root in.
         * @return the opened root.
         * @throws IOException
         *             if the root could not be opened.
         */
        VfsRoot open(Vfs vfs) throws IOException;
    }

    /**
     * Create a {@link Vfs} configured by an env map, and open one root of it.
     *
     * @param env
     *            the env map, which may hold a {@link VfsSpec} under {@code "vfsSpec"}. Any other key is ignored,
     *            since a caller that also drives zipfs passes the keys zipfs takes.
     * @param opener
     *            opens the root.
     * @return the opened root, whose {@link Vfs} was created by this method and is owned by the root's filesystem
     *         view.
     * @throws IOException
     *             if the root could not be opened.
     */
    private static VfsRoot openRoot(final Map<String, ?> env, final RootOpener opener) throws IOException {
        final var vfsSpec = envValue(env, VFS_SPEC_ENV_KEY, VfsSpec.class);
        final var vfs = new Vfs(vfsSpec == null ? new VfsSpec() : vfsSpec);
        var opened = false;
        try {
            final var root = opener.open(vfs);
            opened = true;
            return root;
        } finally {
            if (!opened) {
                // The Vfs was created here, so nothing else can release the file handles and temporary files it
                // took while it was failing to open the root
                vfs.close();
            }
        }
    }

    /**
     * Turn an opened root into a registered filesystem, closing the {@link Vfs} behind it if it could not be
     * registered.
     *
     * @param root
     *            the opened root.
     * @param requestedPath
     *            the path the caller named the root by.
     * @return the filesystem view of the root.
     * @throws FileSystemAlreadyExistsException
     *             if a filesystem is already open at that path.
     */
    private static FileSystem registeredFileSystemOf(final VfsRoot root, final String requestedPath) {
        final var fileSystem = (VfsFileSystem) root.asFileSystem();
        var registered = false;
        try {
            fileSystem.setRegisteredPath(requestedPath);
            register(fileSystem, requestedPath);
            registered = true;
            return fileSystem;
        } finally {
            if (!registered) {
                // This closes the Vfs that openRoot created, since a filesystem owns the Vfs behind it
                fileSystem.close();
            }
        }
    }

    /**
     * Returns the module name a {@code "jrt:/<module>"} path names.
     *
     * @param path
     *            the path.
     * @return the module name, or null if the path does not name a module.
     */
    private static @Nullable String moduleNameOf(final String path) {
        // "jrt:/java.logging", and also "jrt:/java.logging/" -- but not "jrt:/java.logging/java/util/logging",
        // which names an entry of the module rather than the module, and is left to Vfs#open
        if (!path.startsWith("jrt:/")) {
            return null;
        }
        final var name = path.substring("jrt:/".length());
        final var end = name.endsWith("/") ? name.length() - 1 : name.length();
        final var moduleName = name.substring(0, end);
        return moduleName.isEmpty() || moduleName.indexOf('/') >= 0 ? null : moduleName;
    }

    /**
     * Read a value of an expected type out of an env map.
     *
     * @param <T>
     *            the expected type.
     * @param env
     *            the env map.
     * @param key
     *            the key to read.
     * @param type
     *            the expected type.
     * @return the value, or null if the map has no value under that key.
     * @throws IllegalArgumentException
     *             if the map has a value under that key that is not of the expected type.
     */
    private static <T> @Nullable T envValue(final Map<String, ?> env, final String key, final Class<T> type) {
        final var value = env == null ? null : env.get(key);
        if (value == null) {
            return null;
        }
        if (!type.isInstance(value)) {
            throw new IllegalArgumentException("The \"" + key + "\" option must be a " + type.getName() + ", not a "
                    + value.getClass().getName());
        }
        return type.cast(value);
    }

    /**
     * Register a filesystem under every path it can be named by, so that {@link #getFileSystem(URI)} finds it
     * whichever of those names the caller writes.
     *
     * @param fileSystem
     *            the filesystem to register.
     * @param requestedPath
     *            the path the caller named it by, which need not be the path the root reports itself at -- an alias
     *            through a symlink, or a {@code "file:"} URL of the same jarfile, resolve to the same root.
     * @throws FileSystemAlreadyExistsException
     *             if a filesystem is already open under one of those names.
     */
    private static void register(final VfsFileSystem fileSystem, final String requestedPath) {
        for (final var key : keysOf(fileSystem, requestedPath)) {
            final var existing = FILESYSTEMS_BY_PATH.putIfAbsent(key, fileSystem);
            if (existing != null && existing != fileSystem) {
                // Roll back the keys that were claimed before the clash was found, so that a rejected filesystem
                // leaves nothing of itself behind in the registry
                unregister(fileSystem, requestedPath);
                throw new FileSystemAlreadyExistsException(
                        "A filesystem is already open at " + key + "; close it before opening another");
            }
        }
    }

    /**
     * Take a filesystem back out of the registry, so that its name can be opened again.
     *
     * @param fileSystem
     *            the filesystem to deregister.
     * @param requestedPath
     *            the path the caller named it by.
     */
    static void unregister(final VfsFileSystem fileSystem, final String requestedPath) {
        for (final var key : keysOf(fileSystem, requestedPath)) {
            FILESYSTEMS_BY_PATH.remove(key, fileSystem);
        }
    }

    /**
     * Returns the registry keys of a filesystem: the path it was named by, and the path its root reports itself at,
     * which differ when it was named through a symlink, by URL, or by a Windows short name.
     *
     * @param fileSystem
     *            the filesystem.
     * @param requestedPath
     *            the path the caller named it by.
     * @return the keys, without duplicates.
     */
    private static Collection<String> keysOf(final VfsFileSystem fileSystem, final String requestedPath) {
        final var keys = new LinkedHashSet<String>(4);
        keys.add(FastPathResolver.resolve(requestedPath));
        keys.add(fileSystem.getRoot().reportedPath());
        return keys;
    }

    /**
     * {@inheritDoc}
     *
     * <p>
     * The URI is {@code "cgvfs:"} followed by anything {@link Vfs#open(String)} accepts, and names a root: a
     * directory, a jarfile, a jarfile nested inside another jarfile, or a package root.
     * {@code "cgvfs:jrt:/<module>"} names a module of a {@link ModuleLayer} instead.
     *
     * <p>
     * The returned filesystem owns the {@link Vfs} this creates to open the root, so closing the filesystem
     * releases the file handles, memory mappings and temporary files that reading through it took, and the name
     * becomes free to open again.
     *
     * <p>
     * Two options are read out of the env map: {@code "vfsSpec"}, a {@link VfsSpec} to read storage with, and
     * {@code "layer"}, the {@link ModuleLayer} to resolve a {@code "jrt:/<module>"} URI in, which defaults to
     * {@link ModuleLayer#boot()}. Any other key is ignored rather than rejected, so that a caller which drives both
     * this provider and zipfs from one env map does not have to strip the keys zipfs takes.
     *
     * @throws FileSystemAlreadyExistsException
     *             if a filesystem created by this method is already open at that path.
     */
    @Override
    public FileSystem newFileSystem(final URI uri, final Map<String, ?> env) throws IOException {
        final var path = pathOf(uri);
        final var moduleName = moduleNameOf(path);
        final var root = openRoot(env, vfs -> {
            if (moduleName == null) {
                return vfs.open(path);
            }
            final var layer = envValue(env, LAYER_ENV_KEY, ModuleLayer.class);
            return vfs.openModule(moduleName, layer == null ? ModuleLayer.boot() : layer);
        });
        return registeredFileSystemOf(root, path);
    }

    /**
     * {@inheritDoc}
     *
     * <p>
     * The path may name a directory or a jarfile of any filesystem, not only of the default one, so a jarfile
     * inside a zipfs filesystem can be opened by handing its {@link Path} to this method.
     *
     * <p>
     * A path this provider cannot read as a filesystem -- one that names a file which is not an archive, or an
     * archive too damaged to read -- is declined with {@link UnsupportedOperationException}, carrying the reason as
     * its cause. That is what the contract of this method asks for, and it matters beyond this provider:
     * {@link java.nio.file.FileSystems#newFileSystem(Path)} tries each installed provider in turn and moves on to
     * the next only when one throws {@link UnsupportedOperationException}, so a provider that reports an
     * unrecognized file as an {@link IOException} instead would end that search and hide every provider behind it.
     * A path that does not exist or cannot be read at all is still reported as an {@link IOException}, since no
     * provider could open it.
     *
     * <p>
     * Note that this does leave one difference from a JVM without this provider installed:
     * {@link java.nio.file.FileSystems#newFileSystem(Path)} over a <i>directory</i> returns a filesystem here,
     * where it would otherwise throw {@link java.nio.file.ProviderNotFoundException}, since no built-in provider
     * reads a directory as a filesystem. An archive still goes to the JDK's own zipfs, which is tried first.
     *
     * <p>
     * The returned filesystem owns the {@link Vfs} this creates to open the path, so closing the filesystem
     * releases what reading through it took. The env map is read the same way {@link #newFileSystem(URI, Map)}
     * reads it.
     *
     * @throws FileSystemAlreadyExistsException
     *             if a filesystem created from a URI is already open at that path.
     * @throws UnsupportedOperationException
     *             if the path exists but cannot be read as a filesystem by this provider.
     */
    @Override
    public FileSystem newFileSystem(final Path path, final Map<String, ?> env) throws IOException {
        Assert.notNull(path, "path");
        final VfsRoot root;
        try {
            root = openRoot(env, vfs -> vfs.open(path));
        } catch (final IOException e) {
            if (!Files.isReadable(path)) {
                // Nothing is there to open, or it cannot be read at all, which is not this provider declining the
                // path -- no provider could open it
                throw e;
            }
            throw new UnsupportedOperationException("Cannot read " + path + " as a filesystem", e);
        }
        // Registered under the path the root reports itself at, rather than under the given Path's own spelling,
        // because a Path of another provider's filesystem -- a jarfile inside a zipfs filesystem, say -- has no
        // spelling that this provider could resolve back to it
        return registeredFileSystemOf(root, root.reportedPath());
    }

    /**
     * {@inheritDoc}
     *
     * <p>
     * Only a filesystem created by {@link #newFileSystem(URI, Map)} can be looked up by URI. A filesystem reached
     * through {@link VfsRoot#asFileSystem()} is not registered under its path, since the {@link Vfs} that opened
     * the root belongs to the caller, and two of them can have the same path open at once.
     *
     * @throws FileSystemNotFoundException
     *             if no filesystem created from a URI is open at that path.
     */
    @Override
    public FileSystem getFileSystem(final URI uri) {
        final var path = pathOf(uri);
        final var fileSystem = FILESYSTEMS_BY_PATH.get(FastPathResolver.resolve(path));
        if (fileSystem == null) {
            throw new FileSystemNotFoundException("No filesystem is open at " + path
                    + "; create one with FileSystems#newFileSystem, or open it through Vfs#open");
        }
        return fileSystem;
    }

    /**
     * {@inheritDoc}
     *
     * <p>
     * The longest prefix of the URI that names an open filesystem is the filesystem, and the rest is the path
     * within it, so {@code "cgvfs:/path/app.jar!/META-INF/MANIFEST.MF"} is a file of the filesystem of
     * {@code app.jar}, and {@code "cgvfs:/path/outer.jar!/lib/inner.jar!/com/xyz/W.class"} is a file of the
     * filesystem of the nested jarfile. A URI that names an open filesystem exactly, with nothing after it, is that
     * filesystem's root directory.
     *
     * @throws FileSystemNotFoundException
     *             if no prefix of the URI names a filesystem created from a URI.
     */
    @Override
    public Path getPath(final URI uri) {
        final var path = pathOf(uri);
        // Longest prefix first, so that the path of a nested jarfile is read against the nested jarfile's own
        // filesystem rather than against the enclosing jarfile that also has a filesystem open. The whole path is
        // tried before any prefix of it, so a URI that names a filesystem exactly is that filesystem's root
        var separatorIdx = path.length();
        while (separatorIdx > 0) {
            final var prefix = path.substring(0, separatorIdx);
            final var fileSystem = FILESYSTEMS_BY_PATH.get(FastPathResolver.resolve(prefix));
            if (fileSystem != null) {
                final var entryName = separatorIdx == path.length() ? ""
                        : path.substring(separatorIdx + "!/".length());
                return fileSystem.getPath("/" + entryName);
            }
            separatorIdx = path.lastIndexOf("!/", separatorIdx - 1);
        }
        throw new FileSystemNotFoundException("No filesystem is open at " + path
                + ", nor at any prefix of it; create one with FileSystems#newFileSystem");
    }

    // -------------------------------------------------------------------------------------------------------------

    @Override
    public InputStream newInputStream(final Path path, final OpenOption... options) throws IOException {
        // Arrays.asList rather than Set.of, because a repeated open option is accepted, not rejected
        checkReadOnly(Arrays.asList(options));
        return entryOf(check(path)).open();
    }

    @Override
    public SeekableByteChannel newByteChannel(final Path path, final Set<? extends OpenOption> options,
            final FileAttribute<?>... attrs) throws IOException {
        checkReadOnly(options);
        if (attrs.length != 0) {
            throw new UnsupportedOperationException(
                    "File attributes are not supported by this read-only filesystem");
        }
        return channelOver(entryOf(check(path)));
    }

    /**
     * Open a read-only channel over the content of an entry. How much of the content is brought into memory to
     * serve a read at an offset is decided by the entry -- see {@link VfsEntry#openRandomAccess()} -- since a
     * caller that opens a channel rather than a stream is one that may not read the content straight through.
     *
     * @param entry
     *            the entry.
     * @return the channel, which the caller owns and must close.
     * @throws IOException
     *             if the entry could not be read.
     */
    private static VfsRandomAccessChannel channelOver(final VfsEntry entry) throws IOException {
        return new VfsRandomAccessChannel(entry.openRandomAccess());
    }

    /**
     * {@inheritDoc}
     *
     * <p>
     * The returned channel is read-only, so every method that would write throws
     * {@link NonWritableChannelException}. {@link FileChannel#map(FileChannel.MapMode, long, long)} and the file
     * locking methods throw {@link UnsupportedOperationException}: an entry of an archive has no region of a file
     * of its own that could be mapped or locked, since it is usually stored compressed. Read the entry through
     * {@link #newInputStream} or {@link #newByteChannel} where a channel is not needed.
     */
    @Override
    public FileChannel newFileChannel(final Path path, final Set<? extends OpenOption> options,
            final FileAttribute<?>... attrs) throws IOException {
        checkReadOnly(options);
        if (attrs.length != 0) {
            throw new UnsupportedOperationException(
                    "File attributes are not supported by this read-only filesystem");
        }
        return new VfsFileChannel(channelOver(entryOf(check(path))));
    }

    @Override
    public DirectoryStream<Path> newDirectoryStream(final Path dir, final Filter<? super Path> filter)
            throws IOException {
        final var dirPath = check(dir);
        final var fileSystem = dirPath.getFileSystem();
        final var name = dirPath.entryName();
        // A name can be a file and a directory at the same time, if the archive holds both "a/b" and "a/b/c". The
        // file wins, so that a name is never reported as a file by Files#isDirectory and listed as a directory
        // here at the same time
        if (fileSystem.entry(name) != null) {
            throw new NotDirectoryException(dir.toString());
        }
        final var childNames = fileSystem.childNames(name);
        if (childNames == null) {
            throw new NoSuchFileException(dir.toString());
        }
        final List<Path> children = new ArrayList<>(childNames.size());
        for (final var childName : childNames) {
            final var child = dir.resolve(fileSystem.getPath(childName));
            if (filter.accept(child)) {
                children.add(child);
            }
        }
        return new VfsDirectoryStream(children);
    }

    /**
     * A {@link DirectoryStream} over a list of child paths that was built up front, so nothing is held open. It
     * hands out its iterator once, as {@link DirectoryStream#iterator()} requires.
     */
    private static final class VfsDirectoryStream implements DirectoryStream<Path> {
        /** The children of the directory. */
        private final List<Path> children;

        /** Whether {@link #iterator()} has been called, or this stream has been closed. */
        private final AtomicBoolean spent = new AtomicBoolean();

        /**
         * Constructor.
         *
         * @param children
         *            the children of the directory.
         */
        VfsDirectoryStream(final List<Path> children) {
            this.children = children;
        }

        @Override
        public Iterator<Path> iterator() {
            if (!spent.compareAndSet(false, true)) {
                throw new IllegalStateException("The iterator has already been returned, or the stream was closed");
            }
            // DirectoryStream requires its iterator to be thread-safe. The entries are an immutable snapshot, and
            // an atomic cursor lets multiple consumer threads share the one iterator without duplicates.
            return new Iterator<>() {
                private final AtomicInteger cursor = new AtomicInteger();

                @Override
                public boolean hasNext() {
                    return cursor.get() < children.size();
                }

                @Override
                public Path next() {
                    final int index = cursor.getAndIncrement();
                    if (index >= children.size()) {
                        throw new java.util.NoSuchElementException();
                    }
                    return children.get(index);
                }
            };
        }

        @Override
        public void close() {
            spent.set(true);
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    @Override
    public void createDirectory(final Path dir, final FileAttribute<?>... attrs) {
        throw new ReadOnlyFileSystemException();
    }

    @Override
    public void delete(final Path path) {
        throw new ReadOnlyFileSystemException();
    }

    @Override
    public void copy(final Path source, final Path target, final CopyOption... options) {
        throw new ReadOnlyFileSystemException();
    }

    @Override
    public void move(final Path source, final Path target, final CopyOption... options) {
        throw new ReadOnlyFileSystemException();
    }

    @Override
    public void setAttribute(final Path path, final String attribute, final Object value,
            final LinkOption... options) {
        throw new ReadOnlyFileSystemException();
    }

    // -------------------------------------------------------------------------------------------------------------

    @Override
    public boolean isSameFile(final Path path, final Path path2) {
        final var vfsPath = check(path);
        vfsPath.getFileSystem().ensureOpen();
        // A path of another filesystem is answered, not rejected, as FileSystemProvider#isSameFile requires
        if (!(path2 instanceof final VfsPath vfsPath2)) {
            return false;
        }
        return vfsPath.toAbsolutePath().normalize().equals(vfsPath2.toAbsolutePath().normalize());
    }

    @Override
    public boolean isHidden(final Path path) {
        check(path).getFileSystem().ensureOpen();
        return false;
    }

    @Override
    public FileStore getFileStore(final Path path) {
        final var fileSystem = check(path).getFileSystem();
        fileSystem.ensureOpen();
        return fileSystem.fileStore();
    }

    @Override
    public void checkAccess(final Path path, final AccessMode... modes) throws IOException {
        final var vfsPath = check(path);
        for (final var mode : modes) {
            if (mode == AccessMode.WRITE || mode == AccessMode.EXECUTE) {
                throw new AccessDeniedException(path.toString(), null,
                        "A virtual filesystem is read-only and holds no executable files");
            }
        }
        if (!vfsPath.getFileSystem().exists(vfsPath.entryName())) {
            throw new NoSuchFileException(path.toString());
        }
    }

    @Override
    public <V extends FileAttributeView> @Nullable V getFileAttributeView(final Path path, final Class<V> type,
            final LinkOption... options) {
        if (type != BasicFileAttributeView.class) {
            return null;
        }
        final var vfsPath = check(path);
        return type.cast(new BasicFileAttributeView() {
            @Override
            public String name() {
                return "basic";
            }

            @Override
            public BasicFileAttributes readAttributes() throws IOException {
                return attributesOf(vfsPath);
            }

            @Override
            public void setTimes(final @Nullable FileTime lastModifiedTime, final @Nullable FileTime lastAccessTime,
                    final @Nullable FileTime createTime) {
                throw new ReadOnlyFileSystemException();
            }
        });
    }

    @Override
    public <A extends BasicFileAttributes> A readAttributes(final Path path, final Class<A> type,
            final LinkOption... options) throws IOException {
        if (type != BasicFileAttributes.class) {
            throw new UnsupportedOperationException("Unsupported file attributes type: " + type.getName());
        }
        return type.cast(attributesOf(check(path)));
    }

    @Override
    public Map<String, Object> readAttributes(final Path path, final String attributes, final LinkOption... options)
            throws IOException {
        final var colonIdx = attributes.indexOf(':');
        final var view = colonIdx < 0 ? "basic" : attributes.substring(0, colonIdx);
        if (!"basic".equals(view)) {
            throw new UnsupportedOperationException("Unsupported file attribute view: " + view);
        }
        final var requested = attributes.substring(colonIdx + 1);
        final var attrs = attributesOf(check(path));
        final var names = "*".equals(requested) ? ATTRIBUTE_NAMES : List.of(requested.split(","));
        final Map<String, Object> selected = new LinkedHashMap<>();
        for (final var name : names) {
            // Only the attributes that were asked for are read, because reading the size of a module entry means
            // reading the whole entry
            selected.put(name, attributeOf(attrs, name));
        }
        return selected;
    }

    /** The names of the attributes of the {@code "basic"} view, in the order the default provider lists them. */
    private static final List<String> ATTRIBUTE_NAMES = List.of("lastModifiedTime", "lastAccessTime",
            "creationTime", "size", "isRegularFile", "isDirectory", "isSymbolicLink", "isOther", "fileKey");

    /**
     * Read one named attribute of the {@code "basic"} view.
     *
     * @param attrs
     *            the attributes of the file.
     * @param name
     *            the name of the attribute.
     * @return the value of the attribute.
     * @throws IllegalArgumentException
     *             if the {@code "basic"} view has no attribute of that name.
     */
    private static @Nullable Object attributeOf(final BasicFileAttributes attrs, final String name) {
        return switch (name) {
        case "lastModifiedTime" -> attrs.lastModifiedTime();
        case "lastAccessTime" -> attrs.lastAccessTime();
        case "creationTime" -> attrs.creationTime();
        case "size" -> attrs.size();
        case "isRegularFile" -> attrs.isRegularFile();
        case "isDirectory" -> attrs.isDirectory();
        case "isSymbolicLink" -> attrs.isSymbolicLink();
        case "isOther" -> attrs.isOther();
        case "fileKey" -> attrs.fileKey();
        default -> throw new IllegalArgumentException("Unknown file attribute: " + name);
        };
    }

    /**
     * Read the attributes of a path.
     *
     * @param path
     *            the path.
     * @return the attributes.
     * @throws NoSuchFileException
     *             if the path names nothing in the filesystem.
     * @throws IOException
     *             if the entries of the root could not be listed.
     */
    private static BasicFileAttributes attributesOf(final VfsPath path) throws IOException {
        final var name = path.entryName();
        final var fileSystem = path.getFileSystem();
        final var entry = fileSystem.entry(name);
        if (entry == null) {
            if (!fileSystem.isDirectory(name)) {
                throw new NoSuchFileException(path.toString());
            }
            return new VfsFileAttributes(null);
        }
        return new VfsFileAttributes(entry);
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * The attributes of one file or directory of a virtual filesystem.
     *
     * @param entry
     *            the entry, or null if this is a directory synthesized from the names of the entries below it.
     */
    private record VfsFileAttributes(@Nullable VfsEntry entry) implements BasicFileAttributes {
        @Override
        public FileTime lastModifiedTime() {
            // A directory of a virtual filesystem has no modification time of its own, and neither does a module
            // entry, both of which report the epoch. A time before the epoch is negative, and is a real time that
            // a file of a directory root can carry, so it is reported as it is rather than clamped to the epoch.
            return FileTime.fromMillis(entry == null ? 0L : entry.getLastModifiedMillis());
        }

        @Override
        public FileTime lastAccessTime() {
            return lastModifiedTime();
        }

        @Override
        public FileTime creationTime() {
            return lastModifiedTime();
        }

        @Override
        public boolean isRegularFile() {
            return entry != null;
        }

        @Override
        public boolean isDirectory() {
            return entry == null;
        }

        @Override
        public boolean isSymbolicLink() {
            return false;
        }

        @Override
        public boolean isOther() {
            return false;
        }

        @Override
        public long size() {
            if (entry == null) {
                return 0L;
            }
            final var length = entry.getLength();
            if (length >= 0) {
                return length;
            }
            // A module entry does not know its length without reading it. This is only paid by a caller that
            // actually asks for the size, not by every walk of the filesystem.
            try (var content = entry.read()) {
                final var byteBuffer = content.getByteBuffer();
                return byteBuffer == null ? 0L : byteBuffer.remaining();
            } catch (final IOException e) {
                throw new IOError(e);
            }
        }

        @Override
        public @Nullable Object fileKey() {
            return entry;
        }
    }

    /**
     * A read-only {@link FileChannel} over the content of one {@link VfsEntry}, so that an entry can be read by
     * code that asks for a {@link FileChannel} rather than for a {@link SeekableByteChannel}.
     */
    private static final class VfsFileChannel extends FileChannel {
        /** The channel that holds the content, closed when this channel is closed. */
        private final VfsRandomAccessChannel content;

        /** The read position, which is allowed to be beyond the end of the content. */
        private long position;

        /**
         * Constructor.
         *
         * @param content
         *            the channel that holds the content of the entry.
         */
        VfsFileChannel(final VfsRandomAccessChannel content) {
            this.content = content;
        }

        @Override
        public int read(final ByteBuffer dst) throws IOException {
            final var numBytes = content.read(dst, position);
            if (numBytes > 0) {
                position += numBytes;
            }
            return numBytes;
        }

        @Override
        public long read(final ByteBuffer[] dsts, final int offset, final int length) throws IOException {
            Objects.checkFromIndexSize(offset, length, dsts.length);
            var total = 0L;
            for (var i = offset; i < offset + length; i++) {
                final var numBytes = read(dsts[i]);
                if (numBytes < 0) {
                    // Only end-of-file before anything was read is reported as end-of-file, which is what a
                    // scattering read of a file does
                    return total == 0 ? -1 : total;
                }
                total += numBytes;
            }
            return total;
        }

        @Override
        public int read(final ByteBuffer dst, final long fromPosition) throws IOException {
            if (fromPosition < 0) {
                throw new IllegalArgumentException("Negative position: " + fromPosition);
            }
            return content.read(dst, fromPosition);
        }

        @Override
        public int write(final ByteBuffer src) {
            throw new NonWritableChannelException();
        }

        @Override
        public long write(final ByteBuffer[] srcs, final int offset, final int length) {
            throw new NonWritableChannelException();
        }

        @Override
        public int write(final ByteBuffer src, final long atPosition) {
            throw new NonWritableChannelException();
        }

        @Override
        public long position() throws IOException {
            checkOpen();
            return position;
        }

        @Override
        public FileChannel position(final long newPosition) throws IOException {
            checkOpen();
            if (newPosition < 0) {
                throw new IllegalArgumentException("Negative position: " + newPosition);
            }
            position = newPosition;
            return this;
        }

        @Override
        public long size() throws IOException {
            return content.size();
        }

        @Override
        public FileChannel truncate(final long size) {
            throw new NonWritableChannelException();
        }

        @Override
        public void force(final boolean metaData) throws IOException {
            // Nothing to flush, since nothing can be written, but a closed channel still has to be reported
            checkOpen();
        }

        @Override
        public long transferTo(final long fromPosition, final long count, final WritableByteChannel target)
                throws IOException {
            if (fromPosition < 0 || count < 0) {
                throw new IllegalArgumentException("Negative position or count");
            }
            checkOpen();
            if (!target.isOpen()) {
                throw new ClosedChannelException();
            }
            final var remaining = size() - fromPosition;
            if (remaining <= 0) {
                return 0;
            }
            final var buf = ByteBuffer.allocate((int) Math.min(count, remaining));
            final var numBytes = content.read(buf, fromPosition);
            if (numBytes <= 0) {
                return 0;
            }
            buf.flip();
            // A partial write is reported as such, rather than looped over, which is what a file channel does when
            // the target is a non-blocking channel that will not take the whole buffer
            return target.write(buf);
        }

        @Override
        public long transferFrom(final ReadableByteChannel src, final long atPosition, final long count) {
            throw new NonWritableChannelException();
        }

        @Override
        public MappedByteBuffer map(final MapMode mode, final long fromPosition, final long size) {
            throw new UnsupportedOperationException(
                    "An entry of an archive has no region of a file of its own that could be mapped");
        }

        @Override
        public FileLock lock(final long fromPosition, final long size, final boolean shared) {
            throw new UnsupportedOperationException("This read-only filesystem does not support file locking");
        }

        @Override
        public @Nullable FileLock tryLock(final long fromPosition, final long size, final boolean shared) {
            throw new UnsupportedOperationException("This read-only filesystem does not support file locking");
        }

        /**
         * Check that this channel is still open.
         *
         * @throws ClosedChannelException
         *             if it is not.
         */
        private void checkOpen() throws IOException {
            if (!content.isOpen()) {
                throw new ClosedChannelException();
            }
        }

        @Override
        protected void implCloseChannel() throws IOException {
            content.close();
        }
    }

    /**
     * A read-only {@link SeekableByteChannel} over the content of one {@link VfsEntry} that can also be read at an
     * absolute position, without moving the position of the channel, so that {@link VfsFileChannel} can be built
     * over it.
     */
    private static final class VfsRandomAccessChannel implements SeekableByteChannel {
        /** The content of the entry, released when this channel is closed. */
        private final VfsEntry.RandomAccessContent content;

        /**
         * The read position, which is allowed to be beyond the end of the content, where reads return end-of-file.
         */
        private long position;

        /** Whether this channel is still open. */
        private final AtomicBoolean open = new AtomicBoolean(true);

        /**
         * Constructor.
         *
         * @param content
         *            the content of the entry.
         */
        VfsRandomAccessChannel(final VfsEntry.RandomAccessContent content) {
            this.content = content;
        }

        /**
         * Check that this channel is still open.
         *
         * @throws ClosedChannelException
         *             if it is not.
         */
        private void checkOpen() throws IOException {
            if (!open.get()) {
                throw new ClosedChannelException();
            }
        }

        @Override
        public synchronized int read(final ByteBuffer dst) throws IOException {
            final var numBytes = read(dst, position);
            if (numBytes > 0) {
                position += numBytes;
            }
            return numBytes;
        }

        /**
         * Read from a given position, without moving the position of the channel. This is safe to do from several
         * threads at once, which is what {@link FileChannel} promises: the reader keeps state of its own between
         * reads, and is not safe to use from more than one thread at a time, so every read through this channel is
         * serialized. There is one reader per open channel, so this does not serialize threads that are reading
         * different files, or even the same file through channels of their own.
         *
         * @param dst
         *            the buffer to read into.
         * @param fromPosition
         *            the position to read from, which is allowed to be beyond the end of the content.
         * @return the number of bytes read, or -1 at end of file.
         * @throws IOException
         *             if the content could not be read.
         */
        synchronized int read(final ByteBuffer dst, final long fromPosition) throws IOException {
            checkOpen();
            final var numBytes = dst.remaining();
            if (numBytes == 0) {
                return 0;
            }
            // The end of the content is where the reader says it is, rather than being checked against a length
            // first: a deflated entry or a module resource does not know its own length until it has been read to
            // the end, and reading a header should not have to inflate or stream the whole of the entry to find
            // out where it ends

            // The reader writes at an index and leaves the destination's position and limit alone, so the position
            // is advanced here over what it wrote, which is what the contract of this method asks for
            final var dstStart = dst.position();
            final var numBytesRead = Math.max(content.reader().read(fromPosition, dst, dstStart, numBytes), 0);
            dst.position(dstStart + numBytesRead);
            // The reader returns -1, which Math#max above turned into 0, once the read starts at or past the end
            // of the content
            return numBytesRead == 0 ? -1 : numBytesRead;
        }

        @Override
        public int write(final ByteBuffer src) {
            throw new NonWritableChannelException();
        }

        @Override
        public synchronized long position() throws IOException {
            checkOpen();
            return position;
        }

        @Override
        public synchronized SeekableByteChannel position(final long newPosition) throws IOException {
            checkOpen();
            if (newPosition < 0) {
                throw new IllegalArgumentException("Negative position: " + newPosition);
            }
            // Seeking beyond the end is allowed, the position reads back as the one that was asked for, and reads
            // there return -1
            position = newPosition;
            return this;
        }

        @Override
        public long size() throws IOException {
            checkOpen();
            // For an entry that is inflated or streamed, this reads the whole of the content, since that is the
            // only way to find out how long it is. That is what Files#readAllBytes and Files#readString do first,
            // so those read the entry once from beginning to end, and then copy it out of the reader's buffer.
            return content.reader().length();
        }

        @Override
        public SeekableByteChannel truncate(final long size) {
            throw new NonWritableChannelException();
        }

        @Override
        public boolean isOpen() {
            return open.get();
        }

        @Override
        public void close() {
            if (open.compareAndSet(true, false)) {
                content.closeAction().run();
            }
        }
    }
}
