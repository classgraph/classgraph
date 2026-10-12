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
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.channels.FileChannel;
import java.nio.channels.NonWritableChannelException;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.AccessDeniedException;
import java.nio.file.AccessMode;
import java.nio.file.CopyOption;
import java.nio.file.DirectoryStream;
import java.nio.file.DirectoryIteratorException;
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
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

import io.github.classgraph.base.internal.path.FastPathResolver;
import io.github.classgraph.base.internal.path.URLPaths;
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
    /**
     * The provider that {@link VfsFileSystem#provider()} returns when the {@code "cgvfs:"} scheme is not installed.
     * When it is installed, the installed instance is returned instead; see {@link #instance()}.
     */
    private static final VfsFileSystemProvider INSTANCE = new VfsFileSystemProvider();

    /**
     * The filesystems created from a URI by {@link #newFileSystem(URI, Map)}, keyed by every path they can be named
     * by. No other filesystem is held here: not one created from a {@link Path} by
     * {@link #newFileSystem(Path, Map)}, and not one created by {@link VfsRoot#asFileSystem()}. Each is removed
     * when it is closed, or when its root or the {@link Vfs} behind it is closed. This is static rather than
     * per-instance because more than one instance can exist: the one {@link java.util.ServiceLoader} constructs,
     * {@link #INSTANCE}, and any a caller constructs directly.
     */
    private static final Map<String, VfsFileSystem> FILESYSTEMS_BY_PATH = new ConcurrentHashMap<>();

    /**
     * Holds the installed instance of this class, looked up on first use. This is a separate class so that the
     * lookup is not run by the initialization of {@link VfsFileSystemProvider}, or by its constructor: both can run
     * while {@link FileSystemProvider#installedProviders()} is still loading the providers, and that method fails
     * if it is called again before the loading finishes.
     */
    private static final class Installed {
        /** The installed instance, or null if the {@code "cgvfs:"} scheme is not installed. */
        static final @Nullable VfsFileSystemProvider PROVIDER = find();

        /**
         * Constructor.
         */
        private Installed() {
        }

        /**
         * Find the installed instance.
         *
         * @return the installed instance, or null if there is none.
         */
        private static @Nullable VfsFileSystemProvider find() {
            for (final var provider : FileSystemProvider.installedProviders()) {
                if (provider instanceof final VfsFileSystemProvider vfsProvider) {
                    return vfsProvider;
                }
            }
            return null;
        }
    }

    /**
     * Constructor.
     *
     * <p>
     * This is public only because {@link java.util.ServiceLoader} has to be able to call it. Use
     * {@link java.nio.file.FileSystems} to reach the provider, or {@link Vfs} and {@link VfsRoot#asFileSystem()} to
     * bypass it. Constructing one directly gives a provider that shares the filesystem registry with the installed
     * one, so it behaves the same, but a filesystem it creates reports the installed provider (or a private
     * instance, if the scheme is not installed) from {@link FileSystem#provider()}, not the provider that was
     * constructed.
     */
    public VfsFileSystemProvider() {
    }

    /**
     * Returns the provider that every {@link VfsFileSystem} reports as its own: the installed instance if the
     * {@code "cgvfs:"} scheme is installed, so that code which compares a path's provider with the installed one by
     * identity accepts it, or else {@link #INSTANCE}.
     *
     * @return the provider.
     */
    static VfsFileSystemProvider instance() {
        final var installed = Installed.PROVIDER;
        return installed != null ? installed : INSTANCE;
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
        return Installed.PROVIDER != null;
    }

    /**
     * Cast a path to a {@link VfsPath}.
     *
     * @param path
     *            the path.
     * @return the path, as a {@link VfsPath}.
     * @throws NullPointerException
     *             if the path is null.
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
        final var fileSystem = path.vfsFileSystem();
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
        return rootPathOf(rawPathOf(uri));
    }

    /**
     * Returns the scheme-specific part of a {@code "cgvfs:"} URI, with its percent encoding as it is written.
     *
     * @param uri
     *            the URI.
     * @return the scheme-specific part of the URI, not decoded.
     * @throws IllegalArgumentException
     *             if the URI is not a {@code "cgvfs:"} URI, or names nothing.
     */
    private static String rawPathOf(final URI uri) {
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
        return rawPath;
    }

    /**
     * Decode the part of a {@code "cgvfs:"} URI that names a root into the path {@link Vfs#open(String)} takes,
     * decoding its percent encoding exactly once.
     *
     * @param rawPath
     *            the part of the URI that names the root, with its percent encoding as it is written.
     * @return the path of the root.
     */
    private static String rootPathOf(final String rawPath) {
        if (!URLPaths.startsWithURLScheme(rawPath)) {
            // A path with no scheme is decoded here, since Vfs#open does not decode a path at all: a path is not
            // percent-encoded. It is decoded by ClassGraph's rule, which never produces a separator, rather than by
            // URI#getSchemeSpecificPart's, so "%2F" and "%5C" stay as they are written. FastPathResolver#resolve
            // reads a backslash as a separator on every platform, so decoding "%5C" would introduce a separator
            // that was never in the path, and name a different file than the same path string handed to Vfs#open.
            // That is what made a Coursier cache path unscannable in #255
            return FastPathResolver.decodePercentEncoding(rawPath);
        }
        if (FastPathResolver.resolveDecodesPercentEncoding(rawPath)) {
            // A path written with a "file:" or "jar:" scheme is left alone, since FastPathResolver decodes that one
            // itself when Vfs#open resolves it: decoding it here as well would look for a file whose name
            // contains the three characters "%20" under a name with a space in it instead
            return rawPath;
        }
        // A URL that names something fetched over http, https or another scheme is kept exactly as it is written,
        // since that is the URL Vfs#open fetches: decoding "my%20lib.jar" would request "/my lib.jar", which is
        // a different resource. Only what follows its first "!/" is decoded, since that is a path within an
        // archive, which neither FastPathResolver#resolve nor Vfs#open decodes
        final var separatorIdx = rawPath.indexOf("!/");
        return separatorIdx < 0 ? rawPath
                : rawPath.substring(0, separatorIdx)
                        + FastPathResolver.decodePercentEncoding(rawPath.substring(separatorIdx));
    }

    /** A percent-encoded backslash, in either case. */
    private static final Pattern ESCAPED_BACKSLASH = Pattern.compile("%5[cC]");

    /**
     * Decode the part of a {@code "cgvfs:"} URI that names a path within a filesystem, decoding its percent
     * encoding exactly once.
     *
     * <p>
     * Unlike the path of a root, this is never resolved by {@link Vfs#resolvePath(String)}: it is split on
     * {@code '/'} alone, so a backslash is an ordinary character of a name, and {@code "%5C"} is decoded to one.
     * {@code "%2F"} is still kept as it is written, since a slash would be a separator.
     *
     * @param rawEntryName
     *            the path within the filesystem, with its percent encoding as it is written.
     * @return the decoded path.
     */
    private static String entryNameOf(final String rawEntryName) {
        final var parts = ESCAPED_BACKSLASH.split(rawEntryName, -1);
        final var buf = new StringBuilder(rawEntryName.length());
        for (var i = 0; i < parts.length; i++) {
            if (i > 0) {
                buf.append('\\');
            }
            buf.append(FastPathResolver.decodePercentEncoding(parts[i]));
        }
        return buf.toString();
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
     *            the env map, which may hold a {@link VfsSpec} under {@code "vfsSpec"}. The {@link VfsSpec} is
     *            copied when the {@link Vfs} is created, so changing it afterward does not change the filesystem.
     *            Any other key is ignored, since a caller that also drives zipfs passes the keys zipfs takes.
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
     * registered. The filesystem is taken back out of the registry when it is closed, or when its root or that
     * {@link Vfs} is closed.
     *
     * @param root
     *            the opened root.
     * @param requestedPath
     *            the path the caller named the root by.
     * @return the filesystem view of the root.
     * @throws FileSystemAlreadyExistsException
     *             if a filesystem is already open at that path.
     * @throws IOException
     *             if cleanup of a failed registration cannot close an opened handle.
     */
    private static FileSystem registeredFileSystemOf(final VfsRoot root, final String requestedPath)
            throws IOException {
        // The Vfs was created by openRoot, and this filesystem is the only reference the caller is given to it, so
        // the filesystem owns it, and closing the filesystem closes it
        final var fileSystem = root.asOwningFileSystem(requestedPath);
        var registered = false;
        try {
            register(fileSystem, requestedPath);
            // No public method of the filesystem hands out its root or Vfs, but code that reaches them anyway, such
            // as by reflection, can close them without closing this filesystem. That must not leave a dead
            // filesystem registered, which getFileSystem would return, and which would make every newFileSystem at
            // this path throw until the dead filesystem was closed. This removes only the registration: the
            // filesystem's own close still closes the Vfs it owns
            root.addUnregistration(() -> unregister(fileSystem, requestedPath));
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
     * Returns how a root is written in a {@code "cgvfs:"} URI, so that {@link #newFileSystem(URI, Map)} opens that
     * root again. This is the path the root reports itself at, except for a module, which is written
     * {@code "jrt:/<module>"}: that is the only spelling {@link #moduleNameOf(String)} reads as a module, and the
     * bare module name that a module reports itself at names a directory relative to the working directory.
     *
     * <p>
     * {@code "jrt:/<module>"} is resolved in the layer that the {@code "layer"} option names, by default the boot
     * layer. So once the filesystem of a module of another layer is closed, its URI opens the module of that name
     * in the layer given to the new call, or fails if that layer has none, but it never opens a directory.
     *
     * @param root
     *            the root.
     * @return the path of the root in a {@code "cgvfs:"} URI.
     */
    static String cgvfsPathOf(final VfsRoot root) {
        return root instanceof ModuleRoot ? "jrt:/" + root.getPath() : root.reportedPath();
    }

    /**
     * Returns the {@code "cgvfs:"} URI of a path within the filesystem of a root, quoted so that
     * {@link #getPath(URI)} decodes each part of it back to what it was.
     *
     * @param root
     *            the root.
     * @param entryName
     *            the path within the root, without a leading {@code '/'}, or the empty string for the root itself.
     * @return the URI.
     * @throws URISyntaxException
     *             if the path cannot be written as a URI.
     */
    static URI cgvfsUriOf(final VfsRoot root, final String entryName) throws URISyntaxException {
        final var rootPath = cgvfsPathOf(root);
        final var path = rootPath + (entryName.isEmpty() ? "" : "!/" + entryName);
        if (!URLPaths.startsWithURLScheme(rootPath) || FastPathResolver.resolveDecodesPercentEncoding(rootPath)) {
            // This URI constructor quotes the characters that a URI cannot hold, and every '%', so a path with a
            // space in it gives a URI with "%20" in it, which is decoded back to the space when the URI is read.
            // The separators are not quoted, since the path of a root is already resolved, so they are forward
            // slashes even on Windows: a separator is written as a separator, never as "%2F"
            return new URI(SCHEME, path, /* fragment = */ null);
        }
        // A root fetched over a URL is written with the URL as it is, since rootPathOf keeps it as it is written:
        // only the characters a URI cannot hold are quoted, and an escape that is already written is kept. What
        // follows the URL's first "!/" is a path within an archive, which is quoted as a path is, and decoded
        final var separatorIdx = path.indexOf("!/");
        final var url = separatorIdx < 0 ? path : path.substring(0, separatorIdx);
        final var pathInArchive = separatorIdx < 0 ? ""
                : new URI(/* scheme = */ null, /* host = */ null, "/" + path.substring(separatorIdx),
                        /* fragment = */ null).getRawPath().substring(1);
        return new URI(SCHEME + ":" + URLPaths.encodeURL(url) + pathInArchive);
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
     *            the path the caller named it by, which need not be the path its root is written as in a
     *            {@code "cgvfs:"} URI -- an alias through a symlink, or a {@code "file:"} URL of the same jarfile,
     *            resolve to the same root.
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
     * Returns the registry keys of a filesystem: the path it was named by, and the path its root is written as in a
     * {@code "cgvfs:"} URI, which differ when it was named through a symlink, by URL, or by a Windows short name.
     *
     * @param fileSystem
     *            the filesystem.
     * @param requestedPath
     *            the path the caller named it by.
     * @return the keys, without duplicates.
     */
    private static Collection<String> keysOf(final VfsFileSystem fileSystem, final String requestedPath) {
        final var keys = new LinkedHashSet<String>(4);
        keys.add(Vfs.resolvePath(requestedPath));
        // Not the path the root reports itself at, which for a module is its bare name: that would claim the key
        // of a relative directory of the same name, and getFileSystem and getPath would return the module's
        // filesystem for that directory
        keys.add(cgvfsPathOf(fileSystem.getRoot()));
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
     * {@link ModuleLayer#boot()}. The {@link VfsSpec} is copied when the filesystem is created, so changing it
     * afterward does not change the filesystem. Any other key is ignored rather than rejected, so that a caller
     * which drives both this provider and zipfs from one env map does not have to strip the keys zipfs takes.
     *
     * <p>
     * A module's filesystem is registered under {@code "jrt:/<module>"}, whatever layer the module was resolved in,
     * so only one module of a given name can be open through this method at a time: opening the module of the same
     * name in another layer throws {@link FileSystemAlreadyExistsException} until the first is closed, and
     * {@link #getFileSystem(URI)} returns whichever one is open. To read two modules of the same name at once, open
     * each with {@link Vfs#openModule(String, ModuleLayer)} and read it through {@link VfsRoot#asFileSystem()}.
     *
     * @throws FileSystemAlreadyExistsException
     *             if a filesystem created by this method is already open at that path, or, for a module, if one is
     *             open for a module of the same name in any layer.
     */
    @Override
    public FileSystem newFileSystem(final URI uri, final Map<String, ?> env) throws IOException {
        final var path = pathOf(uri);
        // Turn away a path that already has a filesystem open before opening anything, as zipfs does: code written
        // for zipfs calls this and falls back to getFileSystem when it throws, and opening the root first would
        // download a remote jarfile, or extract a nested one, again on every such call only to discard it.
        // register() checks again, which catches a second open that races this one, and a filesystem already open
        // under another name of the same root
        final var key = Vfs.resolvePath(path);
        if (FILESYSTEMS_BY_PATH.containsKey(key)) {
            throw new FileSystemAlreadyExistsException(
                    "A filesystem is already open at " + key + "; close it before opening another");
        }
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
     * The path must name a directory, of any filesystem, not only of the default one. Every other path -- a
     * jarfile, any other file, or a path where nothing exists yet -- is declined with
     * {@link UnsupportedOperationException}. That is what the contract of this method asks of a provider that does
     * not handle a path, and it matters beyond this provider: {@link java.nio.file.FileSystems#newFileSystem(Path)}
     * tries each installed provider in turn, moving on to the next only when one throws
     * {@link UnsupportedOperationException}, and the order in which it tries them is not defined -- with
     * classgraph-vfs on the module path, this provider comes before the JDK's zipfs. Taking a jarfile here would
     * hand the caller this provider's read-only filesystem where they expected a zipfs one, and failing a path that
     * does not exist would stop zipfs from creating a zipfile there with {@code Map.of("create", "true")}. To open
     * an archive through this provider, call {@link Vfs#open(Path)} and then {@link VfsRoot#asFileSystem()}, or
     * name it with a {@code cgvfs:} URI.
     *
     * <p>
     * This does leave one difference from a JVM without this provider installed:
     * {@link java.nio.file.FileSystems#newFileSystem(Path)} over a directory returns a filesystem here, where it
     * would otherwise throw {@link java.nio.file.ProviderNotFoundException}, since no built-in provider reads a
     * directory as a filesystem.
     *
     * <p>
     * The returned filesystem owns the {@link Vfs} this creates to open the path, so closing the filesystem
     * releases what reading through it took. The env map is read the same way {@link #newFileSystem(URI, Map)}
     * reads it.
     *
     * <p>
     * Each call returns a new filesystem, independent of any other open at the same directory, and
     * {@link #getFileSystem(URI)} and {@link #getPath(URI)} do not find it. This is what the javadoc of
     * {@link FileSystemProvider#newFileSystem(Path, Map)} and {@link FileSystemProvider#getFileSystem(URI)}
     * specifies, and what zipfs does for a zipfile.
     *
     * @throws UnsupportedOperationException
     *             if the path does not name a directory.
     * @throws IOException
     *             if the directory could not be read.
     */
    @Override
    public FileSystem newFileSystem(final Path path, final Map<String, ?> env) throws IOException {
        Assert.notNull(path, "path");
        if (!Files.isDirectory(path)) {
            throw new UnsupportedOperationException("Not a directory: " + path);
        }
        final var root = openRoot(env, vfs -> vfs.open(path));
        // Not registered: FileSystemProvider#getFileSystem must not return a filesystem created from a Path, and
        // zipfs does not register one either. The filesystem still owns the Vfs that openRoot created
        return root.asOwningFileSystem(/* registeredPath = */ null);
    }

    /**
     * {@inheritDoc}
     *
     * <p>
     * Only a filesystem created by {@link #newFileSystem(URI, Map)} can be looked up by URI. A filesystem created
     * by {@link VfsRoot#asFileSystem()} is not registered under its path, since the {@link Vfs} that opened the
     * root belongs to the caller, and two of them can have the same path open at once. Nor is a filesystem created
     * by {@link #newFileSystem(Path, Map)}, which {@link FileSystemProvider#getFileSystem(URI)} must not return,
     * and of which any number can be open at one directory.
     *
     * @throws FileSystemNotFoundException
     *             if no filesystem created from a URI is open at that path.
     */
    @Override
    public FileSystem getFileSystem(final URI uri) {
        final var path = pathOf(uri);
        final var fileSystem = FILESYSTEMS_BY_PATH.get(Vfs.resolvePath(path));
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
        // Split before decoding, and decode each part by its own rule: the prefix names a root, which is decoded
        // as newFileSystem decodes it, and the rest names a path within that root's filesystem, which nothing
        // else decodes. An escaped "%21/" is not a separator, since the split is made before it is decoded
        final var rawPath = rawPathOf(uri);
        // Longest prefix first, so that the path of a nested jarfile is read against the nested jarfile's own
        // filesystem rather than against the enclosing jarfile that also has a filesystem open. The whole path is
        // tried before any prefix of it, so a URI that names a filesystem exactly is that filesystem's root
        var separatorIdx = rawPath.length();
        while (separatorIdx > 0) {
            final var prefix = rawPath.substring(0, separatorIdx);
            final var fileSystem = FILESYSTEMS_BY_PATH.get(Vfs.resolvePath(rootPathOf(prefix)));
            if (fileSystem != null) {
                final var entryName = separatorIdx == rawPath.length() ? ""
                        : entryNameOf(rawPath.substring(separatorIdx + "!/".length()));
                return fileSystem.getPath("/" + entryName);
            }
            separatorIdx = rawPath.lastIndexOf("!/", separatorIdx - 1);
        }
        throw new FileSystemNotFoundException("No filesystem is open at " + rootPathOf(rawPath)
                + ", nor at any prefix of it; create one with FileSystems#newFileSystem");
    }

    // -------------------------------------------------------------------------------------------------------------

    @Override
    public InputStream newInputStream(final Path path, final OpenOption... options) throws IOException {
        // Arrays.asList rather than Set.of, because a repeated open option is accepted, not rejected
        checkReadOnly(Arrays.asList(options));
        final var vfsPath = check(path);
        return vfsPath.vfsFileSystem().openInputStream(entryOf(vfsPath));
    }

    @Override
    public SeekableByteChannel newByteChannel(final Path path, final Set<? extends OpenOption> options,
            final FileAttribute<?>... attrs) throws IOException {
        checkReadOnly(options);
        if (attrs.length != 0) {
            throw new UnsupportedOperationException(
                    "File attributes are not supported by this read-only filesystem");
        }
        final var vfsPath = check(path);
        return VfsFileChannel.open(entryOf(vfsPath), vfsPath.vfsFileSystem());
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
        final var vfsPath = check(path);
        return VfsFileChannel.open(entryOf(vfsPath), vfsPath.vfsFileSystem());
    }

    @Override
    public DirectoryStream<Path> newDirectoryStream(final Path dir, final Filter<? super Path> filter)
            throws IOException {
        final var dirPath = check(dir);
        Assert.notNull(filter, "filter");
        final var fileSystem = dirPath.vfsFileSystem();
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
            children.add(child);
        }
        return fileSystem.register(new VfsDirectoryStream(children, filter, fileSystem));
    }

    /**
     * A {@link DirectoryStream} over a list of child paths that was built up front, so nothing is held open. It
     * hands out its iterator once, as {@link DirectoryStream#iterator()} requires, and that iterator reports the
     * end of the directory once the stream is closed, after any accepted child already buffered by hasNext().
     * Filtering is lazy, and checked filter failures are reported at the iterator boundary.
     */
    private static final class VfsDirectoryStream implements DirectoryStream<Path> {
        /** The children of the directory. */
        private final List<Path> children;

        /** The caller's filter, invoked only when the iterator needs another child. */
        private final Filter<? super Path> filter;

        /** The filesystem view that tracks this stream. */
        private final VfsFileSystem owner;

        /** Whether {@link #iterator()} has been called. */
        private final AtomicBoolean iteratorReturned = new AtomicBoolean();

        /** Whether this stream has been closed. */
        private volatile boolean closed;

        /**
         * Constructor.
         *
         * @param children
         *            the children of the directory.
         * @param filter
         *            the filter applied during iteration.
         * @param owner
         *            the filesystem view.
         */
        VfsDirectoryStream(final List<Path> children, final Filter<? super Path> filter,
                final VfsFileSystem owner) {
            this.children = children;
            this.filter = filter;
            this.owner = owner;
        }

        @Override
        public Iterator<Path> iterator() {
            if (closed || !iteratorReturned.compareAndSet(false, true)) {
                throw new IllegalStateException("The iterator has already been returned, or the stream was closed");
            }
            // Synchronize both filtering and consumption, so shared iterators cannot filter or return a child
            // twice. Closing never discards an element already promised by hasNext().
            return new Iterator<>() {
                private int cursor;

                private @Nullable Path buffered;

                @Override
                public synchronized boolean hasNext() {
                    if (buffered != null) {
                        return true;
                    }
                    while (!closed && cursor < children.size()) {
                        final var child = children.get(cursor++);
                        try {
                            if (filter.accept(child)) {
                                buffered = child;
                                return true;
                            }
                        } catch (final IOException e) {
                            throw new DirectoryIteratorException(e);
                        }
                    }
                    return false;
                }

                @Override
                public synchronized Path next() {
                    if (!hasNext()) {
                        throw new NoSuchElementException();
                    }
                    final var next = Objects.requireNonNull(buffered);
                    buffered = null;
                    return next;
                }
            };
        }

        @Override
        public void close() {
            closed = true;
            owner.unregister(this);
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
        Assert.notNull(path2, "path2");
        vfsPath.vfsFileSystem().ensureOpen();
        // A path of another filesystem is answered, not rejected, as FileSystemProvider#isSameFile requires
        if (!(path2 instanceof final VfsPath vfsPath2)) {
            return false;
        }
        return vfsPath.toAbsolutePath().normalize().equals(vfsPath2.toAbsolutePath().normalize());
    }

    @Override
    public boolean isHidden(final Path path) {
        check(path).vfsFileSystem().ensureOpen();
        return false;
    }

    @Override
    public FileStore getFileStore(final Path path) {
        final var fileSystem = check(path).vfsFileSystem();
        fileSystem.ensureOpen();
        return fileSystem.fileStore();
    }

    @Override
    public void checkAccess(final Path path, final AccessMode... modes) throws IOException {
        final var vfsPath = check(path);
        // Reject a null mode before answering for any other mode, as the JDK's providers do
        for (final var mode : modes) {
            Objects.requireNonNull(mode, "access mode must not be null");
        }
        for (final var mode : modes) {
            if (mode == AccessMode.WRITE || mode == AccessMode.EXECUTE) {
                throw new AccessDeniedException(path.toString(), null,
                        "A virtual filesystem is read-only and holds no executable files");
            }
        }
        final var fileSystem = vfsPath.vfsFileSystem();
        final var name = vfsPath.entryName();
        final var entry = fileSystem.entry(name);
        if (entry == null) {
            if (!fileSystem.isDirectory(name)) {
                throw new NoSuchFileException(path.toString());
            }
        } else if (modes.length > 0 && !entry.isReadable()) {
            // A file of a directory root is listed whether or not it can be read, so it exists, but it is not
            // readable, just as it is not through the default filesystem. Asked for no mode, this checks only that
            // the file exists
            throw new AccessDeniedException(path.toString());
        }
    }

    @Override
    public <V extends FileAttributeView> @Nullable V getFileAttributeView(final Path path, final Class<V> type,
            final LinkOption... options) {
        Assert.notNull(path, "path");
        Assert.notNull(type, "type");
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
        try {
            for (final var name : names) {
                // Only the attributes that were asked for are read, because reading the size of a module entry
                // means reading the whole entry
                selected.put(name, attributeOf(attrs, name));
            }
        } catch (final UncheckedIOException e) {
            // The size of a module entry could not be read. This method can throw the IOException itself.
            throw e.getCause();
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
        final var fileSystem = path.vfsFileSystem();
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

        /**
         * Returns the size of the file.
         *
         * <p>
         * A module entry does not know its length, so it is read in full to find it, each time this is called,
         * which needs its root to be open.
         *
         * @return the size of the file in bytes, or 0 for a directory.
         * @throws UncheckedIOException
         *             if the size of a module entry could not be read, because the entry could not be read or its
         *             root was closed. This is an exception, not an {@link java.io.IOError}, so that a handler for
         *             exceptions catches it: {@link java.nio.file.Files#size(Path)} calls this method directly, and
         *             passes on whatever it throws.
         */
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
                throw new UncheckedIOException(e);
            }
        }

        @Override
        public @Nullable Object fileKey() {
            // No key, as zipfs reports, and as a directory here already reports. The entry would hand out its root
            // and the Vfs behind it, which a filesystem created by the provider owns and keeps from the caller. A
            // key can be added later without breaking callers, since the contract already allows null.
            return null;
        }
    }
}
