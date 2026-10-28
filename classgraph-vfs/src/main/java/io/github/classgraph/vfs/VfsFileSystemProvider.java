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
import java.nio.file.DirectoryIteratorException;
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
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

import io.github.classgraph.base.internal.path.FastPathResolver;
import io.github.classgraph.base.internal.path.URLPaths;
import io.github.classgraph.base.internal.utils.Assert;

/**
 * {@link FileSystemProvider} for read-only {@link VfsFileSystem} views of a {@link VfsRoot}, registered under the
 * {@code "cgvfs:"} URI scheme.
 *
 * <p>
 * When {@code classgraph-vfs} is loaded by the system class loader, this provider is discovered automatically via
 * {@link java.util.ServiceLoader}; {@link #isInstalled()} reports whether the {@code "cgvfs:"} scheme is installed
 * in {@link FileSystemProvider#installedProviders()}.
 *
 * <p>
 * A {@code "cgvfs:"} URI consists of {@code "cgvfs:"} followed by any path or URL string accepted by
 * {@link Vfs#open(String)} (or {@code "jrt:/<module>"} for a module), using {@code "!/"} to separate nested
 * jarfiles, package roots, or entry paths:
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
 * {@link #newFileSystem(URI, Map)} and {@link #getFileSystem(URI)} interpret the entire URI as the root path, while
 * {@link #getPath(URI)} resolves the longest prefix matching an open registered filesystem and treats the remainder
 * after {@code "!/"} as the path within that filesystem.
 */
public final class VfsFileSystemProvider extends FileSystemProvider {
    /**
     * Fallback provider instance used when the {@code "cgvfs:"} scheme is not installed via
     * {@link java.util.ServiceLoader}.
     */
    private static final VfsFileSystemProvider INSTANCE = new VfsFileSystemProvider();

    /** Open filesystems created via {@link #newFileSystem(URI, Map)}, keyed by normalized path. */
    private static final Map<String, VfsFileSystem> FILESYSTEMS_BY_PATH = new ConcurrentHashMap<>();

    /** Lazy holder for the provider instance installed in {@link FileSystemProvider#installedProviders()}. */
    private static final class Installed {
        /** The installed provider instance, or {@code null} if not installed. */
        static final @Nullable VfsFileSystemProvider PROVIDER = find();

        /** Constructor. */
        private Installed() {
        }

        /**
         * Find the installed {@link VfsFileSystemProvider} instance.
         *
         * @return the installed instance, or {@code null} if none.
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
     * Public constructor required for {@link java.util.ServiceLoader}. Callers should obtain filesystems via
     * {@link java.nio.file.FileSystems} or {@link VfsRoot#asFileSystem()}.
     */
    public VfsFileSystemProvider() {
    }

    /**
     * Return the canonical provider instance (the installed provider if present, otherwise {@link #INSTANCE}).
     *
     * @return the provider instance.
     */
    static VfsFileSystemProvider instance() {
        final var installed = Installed.PROVIDER;
        return installed != null ? installed : INSTANCE;
    }

    /**
     * Return whether this provider is registered in {@link FileSystemProvider#installedProviders()} so that
     * {@code "cgvfs:"} URIs can be resolved through {@link java.nio.file.FileSystems} and {@link Path#of(URI)}.
     *
     * @return {@code true} if the {@code "cgvfs:"} scheme is installed.
     */
    public static boolean isInstalled() {
        return Installed.PROVIDER != null;
    }

    /**
     * Validate and cast {@code path} to {@link VfsPath}.
     *
     * @param path
     *            the path to check.
     * @return {@code path} as a {@link VfsPath}.
     * @throws NullPointerException
     *             if {@code path} is {@code null}.
     * @throws ProviderMismatchException
     *             if {@code path} is not a {@link VfsPath}.
     */
    private static VfsPath check(final Path path) {
        Assert.notNull(path, "path");
        if (!(path instanceof final VfsPath vfsPath)) {
            throw new ProviderMismatchException("Not a path of a virtual filesystem: " + path);
        }
        return vfsPath;
    }

    /**
     * Look up the file {@link VfsEntry} for {@code path}.
     *
     * @param path
     *            the path.
     * @return the entry.
     * @throws FileSystemException
     *             if {@code path} names a directory.
     * @throws NoSuchFileException
     *             if {@code path} does not exist.
     * @throws IOException
     *             if an I/O error occurs.
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
     * Verify that {@code options} contains only read options.
     *
     * @param options
     *            the open options to validate.
     * @throws ReadOnlyFileSystemException
     *             if any write option is present.
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
     * Extract and decode the root path from a {@code "cgvfs:"} {@link URI}.
     *
     * @param uri
     *            the {@code "cgvfs:"} URI.
     * @return the decoded root path string.
     * @throws IllegalArgumentException
     *             if {@code uri} is not a valid {@code "cgvfs:"} URI.
     */
    private static String pathOf(final URI uri) {
        return rootPathOf(rawPathOf(uri));
    }

    /**
     * Return the raw (un-decoded) scheme-specific part of a {@code "cgvfs:"} {@link URI}.
     *
     * @param uri
     *            the {@code "cgvfs:"} URI.
     * @return the raw scheme-specific part.
     * @throws IllegalArgumentException
     *             if {@code uri} is not a valid {@code "cgvfs:"} URI or has a fragment.
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
     * Decode the root path portion of a {@code "cgvfs:"} URI into the form expected by {@link Vfs#open(String)}.
     *
     * @param rawPath
     *            the raw root path from the URI.
     * @return the decoded root path.
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

    /** Pattern matching a percent-encoded backslash ({@code %5C} or {@code %5c}). */
    private static final Pattern ESCAPED_BACKSLASH = Pattern.compile("%5[cC]");

    /**
     * Decode the entry path portion of a {@code "cgvfs:"} URI.
     *
     * @param rawEntryName
     *            the raw entry path from the URI.
     * @return the decoded entry path.
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
     * Create a {@link Vfs} configured from {@code env} and open a root using {@code opener}.
     *
     * @param env
     *            the environment map (may contain {@code "vfsSpec"}).
     * @param opener
     *            the callback to open the root.
     * @return the opened {@link VfsRoot}.
     * @throws IOException
     *             if the root cannot be opened.
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
     * Wrap {@code root} in an owning {@link VfsFileSystem} and register it under {@code requestedPath}.
     *
     * @param root
     *            the opened root.
     * @param requestedPath
     *            the path by which the root was requested.
     * @return the registered {@link FileSystem}.
     * @throws FileSystemAlreadyExistsException
     *             if a filesystem is already registered for {@code requestedPath}.
     * @throws IOException
     *             if closing the filesystem after a registration conflict fails.
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
     * Extract the module name from a {@code "jrt:/<module>"} path, or return {@code null} if not a module path.
     *
     * @param path
     *            the path string.
     * @return the module name, or {@code null}.
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
     * Return the path representation of {@code root} for use in a {@code "cgvfs:"} URI ({@code "jrt:/<module>"} for
     * a {@link ModuleRoot}, or {@link VfsRoot#reportedPath()} otherwise).
     *
     * @param root
     *            the root.
     * @return the {@code "cgvfs:"} root path string.
     */
    static String cgvfsPathOf(final VfsRoot root) {
        return root instanceof ModuleRoot ? "jrt:/" + root.getPath() : root.reportedPath();
    }

    /**
     * Construct a {@code "cgvfs:"} {@link URI} for {@code entryName} within {@code root}.
     *
     * @param root
     *            the root.
     * @param entryName
     *            the root-relative entry path without a leading {@code '/'}, or {@code ""} for the root itself.
     * @return the {@code "cgvfs:"} {@link URI}.
     * @throws URISyntaxException
     *             if the URI cannot be constructed.
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
     * Register {@code fileSystem} in {@link #FILESYSTEMS_BY_PATH} under all lookup keys for {@code requestedPath}.
     *
     * @param fileSystem
     *            the filesystem to register.
     * @param requestedPath
     *            the path by which the filesystem was requested.
     * @throws FileSystemAlreadyExistsException
     *             if a filesystem is already registered under any of the keys.
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
     * Remove {@code fileSystem} from {@link #FILESYSTEMS_BY_PATH}.
     *
     * @param fileSystem
     *            the filesystem to unregister.
     * @param requestedPath
     *            the path by which the filesystem was requested.
     */
    static void unregister(final VfsFileSystem fileSystem, final String requestedPath) {
        for (final var key : keysOf(fileSystem, requestedPath)) {
            FILESYSTEMS_BY_PATH.remove(key, fileSystem);
        }
    }

    /**
     * Return the registry lookup keys for {@code fileSystem} opened via {@code requestedPath}.
     *
     * @param fileSystem
     *            the filesystem.
     * @param requestedPath
     *            the requested path.
     * @return the distinct lookup keys.
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
     * Opens a read-only filesystem for a {@code "cgvfs:"} URI naming a directory, jarfile, nested jarfile, package
     * root, or module ({@code "cgvfs:jrt:/<module>"}). Closing the returned filesystem unregisters it and closes
     * its underlying {@link Vfs}.
     *
     * <p>
     * Recognized {@code env} options:
     * <ul>
     * <li>{@code "vfsSpec"}: a {@link VfsSpec} (defensively copied) used to configure the underlying
     * {@link Vfs}.</li>
     * <li>{@code "layer"}: the {@link ModuleLayer} used to resolve a {@code "jrt:/<module>"} URI (defaults to
     * {@link ModuleLayer#boot()}).</li>
     * </ul>
     * Unrecognized keys in {@code env} are ignored.
     *
     * @throws FileSystemAlreadyExistsException
     *             if a filesystem created via this method is already open for the same root.
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
     * Opens a new, unregistered read-only filesystem for a directory {@link Path}. Non-directory paths throw
     * {@link UnsupportedOperationException} so that {@link java.nio.file.FileSystems#newFileSystem(Path)} delegates
     * archive files to the JDK's {@code zipfs} provider; to open an archive with this provider, use a
     * {@code cgvfs:} URI or {@link Vfs#open(Path)} followed by {@link VfsRoot#asFileSystem()}.
     *
     * @throws UnsupportedOperationException
     *             if {@code path} is not a directory.
     * @throws IOException
     *             if the directory cannot be opened.
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
     * Returns an open filesystem previously created via {@link #newFileSystem(URI, Map)}. Filesystems created via
     * {@link #newFileSystem(Path, Map)} or {@link VfsRoot#asFileSystem()} are not registered and cannot be looked
     * up here.
     *
     * @throws FileSystemNotFoundException
     *             if no registered filesystem is open at {@code uri}.
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
     * Matches the longest prefix of {@code uri} corresponding to an open filesystem registered via
     * {@link #newFileSystem(URI, Map)}, and resolves the remaining path after {@code "!/"} within that filesystem.
     *
     * @throws FileSystemNotFoundException
     *             if no prefix of {@code uri} matches an open registered filesystem.
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
     * Returns a read-only {@link FileChannel}; write operations throw {@link NonWritableChannelException}, and
     * memory mapping and file locking throw {@link UnsupportedOperationException}.
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

    /** A {@link DirectoryStream} over a pre-collected list of child paths with lazy filtering. */
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

    /**
     * {@inheritDoc}
     *
     * <p>
     * Directory-backed paths delegate to {@link Files#isSameFile(Path, Path)} on their underlying paths; archive
     * and module paths compare their owning {@link VfsRoot} identity and normalized entry path.
     */
    @Override
    public boolean isSameFile(final Path path, final Path path2) throws IOException {
        final var vfsPath = check(path);
        Assert.notNull(path2, "path2");
        vfsPath.vfsFileSystem().ensureOpen();
        // A path of another filesystem is answered, not rejected, as FileSystemProvider#isSameFile requires
        if (!(path2 instanceof final VfsPath vfsPath2)) {
            return false;
        }
        final var fileSystem = vfsPath.vfsFileSystem();
        final var fileSystem2 = vfsPath2.vfsFileSystem();
        fileSystem2.ensureOpen();
        if (vfsPath.equals(vfsPath2)) {
            // Exact equality is the documented existence-check shortcut; normalized aliases are not equal paths.
            return true;
        }
        checkAccess(vfsPath);
        checkAccess(vfsPath2);
        if (fileSystem.getRoot() instanceof DirRoot dirRoot && fileSystem2.getRoot() instanceof DirRoot dirRoot2) {
            return Files.isSameFile(dirRoot.getNioPath().resolve(vfsPath.entryName()),
                    dirRoot2.getNioPath().resolve(vfsPath2.entryName()));
        }
        return fileSystem.getRoot() == fileSystem2.getRoot() && vfsPath.entryName().equals(vfsPath2.entryName());
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
        final var linkOptions = options.clone();
        return type.cast(new BasicFileAttributeView() {
            @Override
            public String name() {
                return "basic";
            }

            @Override
            public BasicFileAttributes readAttributes() throws IOException {
                return attributesOf(vfsPath, linkOptions);
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
        return type.cast(attributesOf(check(path), options));
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
        final var attrs = attributesOf(check(path), options);
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
     * @param options
     *            whether to follow symbolic links.
     * @return the attributes.
     * @throws NoSuchFileException
     *             if the path names nothing in the filesystem.
     * @throws IOException
     *             if the entries of the root could not be listed.
     */
    private static BasicFileAttributes attributesOf(final VfsPath path, final LinkOption... options)
            throws IOException {
        for (final var option : options) {
            Objects.requireNonNull(option, "link option must not be null");
        }
        final var name = path.entryName();
        final var fileSystem = path.vfsFileSystem();
        fileSystem.ensureOpen();
        if (fileSystem.getRoot() instanceof DirRoot dirRoot) {
            final var nativePath = dirRoot.pathForNio(name);
            if (nativePath == null) {
                throw new NoSuchFileException(path.toString());
            }
            // Native attributes preserve empty-directory metadata, symbolic links and file keys. In particular,
            // Files.walk must see a link with NOFOLLOW_LINKS and detect cycles with FOLLOW_LINKS.
            return Files.readAttributes(nativePath, BasicFileAttributes.class, options);
        }
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

    /** Basic file attributes for an archive or module entry (or synthesized directory). */
    private static final class VfsFileAttributes implements BasicFileAttributes {
        /** The entry, or {@code null} for a synthesized directory. */
        private final @Nullable VfsEntry entry;

        /** Cached size in bytes for entries with unknown length, or {@code -1} if not yet computed. */
        private long computedSize = -1L;

        /**
         * Constructor.
         *
         * @param entry
         *            the entry, or {@code null} for a directory.
         */
        VfsFileAttributes(final @Nullable VfsEntry entry) {
            this.entry = entry;
        }

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
         * Return the size of the file in bytes, or {@code 0} for a directory. For module entries whose length is
         * not known up front, the entry stream is read once to count its bytes.
         *
         * @return the file size in bytes.
         * @throws UncheckedIOException
         *             if streaming a module entry to determine its size fails.
         */
        @Override
        public synchronized long size() {
            if (entry == null) {
                return 0L;
            }
            final var length = entry.getLength();
            if (length >= 0) {
                return length;
            }
            // A module entry does not know its length without reading it. This is only paid by a caller that
            // actually asks for the size, not by every walk of the filesystem.
            if (computedSize >= 0) {
                return computedSize;
            }
            final long size;
            try (var stream = entry.open()) {
                final var buffer = new byte[16 * 1024];
                var count = 0L;
                int numRead;
                while ((numRead = stream.read(buffer)) != -1) {
                    count += numRead;
                }
                size = count;
            } catch (final IOException e) {
                throw new UncheckedIOException(e);
            }
            // Do not cache a failed read or close, so a caller can retry a transient failure.
            computedSize = size;
            return size;
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
