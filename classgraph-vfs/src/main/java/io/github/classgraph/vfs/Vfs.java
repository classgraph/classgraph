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

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.module.ModuleReference;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.FileSystem;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.Inflater;

import org.jetbrains.annotations.Unmodifiable;
import org.jspecify.annotations.Nullable;

import io.github.classgraph.base.LogNode;
import io.github.classgraph.base.internal.concurrency.InterruptionChecker;
import io.github.classgraph.base.internal.concurrency.SingletonMap;
import io.github.classgraph.base.internal.concurrency.SingletonMap.NewInstanceException;
import io.github.classgraph.base.internal.concurrency.SingletonMap.NullSingletonException;
import io.github.classgraph.base.internal.path.FastPathResolver;
import io.github.classgraph.base.internal.path.FileUtils;
import io.github.classgraph.base.internal.path.PathSyntax;
import io.github.classgraph.base.internal.utils.Assert;
import io.github.classgraph.vfs.internal.recycler.RecycledInflaterInputStream;
import io.github.classgraph.vfs.internal.recycler.Recycler;
import io.github.classgraph.vfs.internal.zip.JarOpener;

/**
 * A virtual filesystem providing unified read access to directories, jarfiles, and modules specified by path
 * string, {@link File}, {@link Path}, {@link URI}, {@link URL}, {@link ModuleReference}, {@link InputStream}, or
 * byte array.
 *
 * <pre>
 * try (Vfs vfs = new Vfs()) {
 *     VfsRoot root = vfs.open("outer.jar!/lib/inner.jar");
 *     for (VfsEntry entry : root) {
 *         byte[] content = entry.load();
 *         // ...
 *     }
 * }
 * </pre>
 *
 * <p>
 * Entries within any {@link VfsRoot} use root-relative paths with {@code '/'} separators and can be read as an
 * {@link InputStream}, {@link java.nio.channels.ReadableByteChannel}, {@link java.nio.ByteBuffer}, byte array, or
 * string. Stored nested jarfiles are read in place without extracting to disk; deflated nested jarfiles exceeding
 * {@link VfsSpec#getMaxBufferedJarRAMSize()} are inflated to a temporary file that is deleted when the root or this
 * {@link Vfs} is closed.
 *
 * <p>
 * Opened roots (except those opened from an {@link InputStream} or byte array) are cached by canonical path or
 * {@link ModuleReference}, so opening the same location multiple times returns the same {@link VfsRoot}. Iterating
 * this {@link Vfs} yields the currently cached roots, and {@link #evict(VfsRoot)} removes a root from the cache
 * without closing it. {@link #close()} closes all opened roots, streams, channels, and buffers, and releases all
 * underlying file handles, memory mappings, and temporary files.
 *
 * <p>
 * All methods on {@link Vfs} are thread-safe. Concurrent calls to open the same path share the resulting
 * {@link VfsRoot}, waiting up to {@link VfsSpec#getOpenWaitTimeout()} for progress.
 */
public final class Vfs implements AutoCloseable, Iterable<VfsRoot> {
    /** Immutable copy of the configuration settings for this {@link Vfs}. */
    private final VfsSpec vfsSpec;

    /** Shared interruption checker for operations on this {@link Vfs}. */
    private final InterruptionChecker interruptionChecker;

    /** True once {@link #close()} has been called. */
    private final AtomicBoolean closed = new AtomicBoolean(false);

    /** Pool of {@link Inflater} instances used to read deflated zip entries. */
    private final Recycler<Inflater, RuntimeException> inflaterRecycler = RecycledInflaterInputStream
            .newInflaterRecycler();

    /** Cache of opened roots keyed by path string or {@link PathKey}. */
    private final SingletonMap<Object, VfsRoot> rootsByPath = new SingletonMap<>(closed);

    /**
     * Cache key for a {@link Path} in a non-default {@link FileSystem}.
     *
     * @param fileSystem
     *            the source filesystem, compared by identity.
     * @param uri
     *            the path's URI.
     */
    private record PathKey(FileSystem fileSystem, URI uri) {
        @Override
        public boolean equals(final @Nullable Object other) {
            return other instanceof PathKey key && fileSystem == key.fileSystem && uri.equals(key.uri);
        }

        @Override
        public int hashCode() {
            return 31 * System.identityHashCode(fileSystem) + uri.hashCode();
        }

        @Override
        public String toString() {
            return uri.toString();
        }
    }

    /** Cache of roots opened from a {@link ModuleReference}. */
    private final Map<ModuleReference, VfsRoot> rootsByModule = new ConcurrentHashMap<>();

    /** All currently open roots owned by this {@link Vfs}. */
    private final Set<VfsRoot> openRoots = ConcurrentHashMap.newKeySet();

    /** Verbose log node enabled by {@link #verbose()}, or {@code null} if logging is disabled. */
    private volatile @Nullable LogNode log;

    /** Construct a {@link Vfs} with default {@link VfsSpec} settings. */
    public Vfs() {
        this(new VfsSpec(), new InterruptionChecker());
    }

    /**
     * Construct a {@link Vfs} with the specified settings. The {@link VfsSpec} is defensively copied, so subsequent
     * modifications to {@code vfsSpec} do not affect this {@link Vfs}.
     *
     * <pre>
     * try (Vfs vfs = new Vfs(new VfsSpec().disableMultiReleaseVersions().setMaxBufferedJarRAMSize(65536))) {
     *     // ...
     * }
     * </pre>
     *
     * @param vfsSpec
     *            the settings to use.
     */
    public Vfs(final VfsSpec vfsSpec) {
        this(vfsSpec, new InterruptionChecker());
    }

    /**
     * Internal constructor sharing an {@link InterruptionChecker} across ClassGraph modules.
     *
     * @param vfsSpec
     *            the settings to use.
     * @param interruptionChecker
     *            the shared interruption checker.
     * @hidden
     */
    @SuppressWarnings("exports")
    public Vfs(final VfsSpec vfsSpec, final InterruptionChecker interruptionChecker) {
        Assert.notNull(vfsSpec, "vfsSpec");
        Assert.notNull(interruptionChecker, "interruptionChecker");
        this.vfsSpec = new VfsSpec(vfsSpec);
        this.interruptionChecker = interruptionChecker;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Enable verbose logging to the {@code io.github.classgraph.ClassGraph} logger at {@code INFO} level when this
     * {@link Vfs} is closed.
     *
     * @return {@code this} (for method chaining).
     * @throws IllegalStateException
     *             if this {@link Vfs} has been closed.
     */
    public synchronized Vfs verbose() {
        checkOpen();
        if (log == null) {
            log = new LogNode();
        }
        return this;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Open a directory or jarfile specified by path or URL string.
     *
     * <p>
     * The path may be a local filesystem path or a URL with any scheme supported by an installed URL stream handler
     * or {@link java.nio.file.spi.FileSystemProvider} (unless denied via {@link VfsSpec#denyURLScheme(String)}).
     * Nested jarfiles and package roots within a jarfile are separated using {@code "!/"} to any depth (e.g.
     * {@code "outer.jar!/lib/inner.jar"} or {@code "spring-boot-app.jar!/BOOT-INF/classes"}). Relative paths are
     * resolved against the current working directory, and equivalent paths (including symlinks,
     * {@code file:}/{@code
     * jar:} URLs, or Windows backslash/short paths) resolve to the same cached {@link VfsRoot}.
     *
     * @param path
     *            the path or URL string to open.
     * @return the opened {@link VfsRoot}.
     * @throws IOException
     *             if the path cannot be opened or read, or if this {@link Vfs} has been closed.
     */
    public VfsRoot open(final String path) throws IOException {
        return open(path, log);
    }

    /**
     * Open a directory or jarfile specified by path or URL string, logging to {@code logNode}.
     *
     * @param path
     *            the path or URL string to open.
     * @param logNode
     *            the log node to log to, or {@code null}.
     * @return the opened {@link VfsRoot}.
     * @throws IOException
     *             if the path cannot be opened or read, or if this {@link Vfs} has been closed.
     */
    public VfsRoot open(final String path, final @Nullable LogNode logNode) throws IOException {
        Assert.notNull(path, "path");
        checkNotClosed(path);
        // The resolved path is the cache key, not the path as it was written: the same directory or jarfile can be
        // named as a plain path, as a "file:" or "jar:" URL, with a trailing separator, or -- on Windows -- with
        // backslashes rather than forward slashes, and all of those name one thing that is opened once
        final var resolvedPath = resolvePath(path);
        try {
            return openThroughCache(resolvedPath, /* aliasReportedPath = */ true,
                    () -> openRootUncached(resolvedPath, logNode));
        } catch (final InterruptedException e) {
            // Route the interruption through the shared interruption checker, so that every other thread reading
            // through this Vfs stops too, and chain the cause, so that the reason the open did not complete is
            // reachable from the stack trace
            interruptionChecker.interrupt();
            throw new IOException("Interrupted while opening " + resolvedPath, e);
        }
    }

    /**
     * Normalize a path string into the key form used by {@link #open(String)}.
     *
     * @param path
     *            the path to resolve.
     * @return the resolved path string.
     */
    static String resolvePath(final String path) {
        return FastPathResolver.resolveFilePath(/* resolveBasePath = */ null, path);
    }

    /**
     * Look up or build a {@link VfsRoot} in {@link #rootsByPath} for {@code key}.
     *
     * @param key
     *            the cache key (a normalized path string or a {@link PathKey}).
     * @param aliasReportedPath
     *            whether to also register the root under its canonical {@link VfsRoot#reportedPath()}.
     * @param factory
     *            the factory used to create the root on cache miss.
     * @return the opened {@link VfsRoot}.
     * @throws IOException
     *             if the key cannot be opened, if waiting for another thread times out, or if this {@link Vfs} is
     *             closed.
     * @throws InterruptedException
     *             if the thread is interrupted.
     */
    private VfsRoot openThroughCache(final Object key, final boolean aliasReportedPath,
            final SingletonMap.NewInstanceFactory<VfsRoot> factory) throws IOException, InterruptedException {
        for (;;) {
            final VfsRoot root;
            try {
                // Give up waiting for another thread that is opening the same key once the timeout has passed with
                // no progress, since that thread may be stuck reading it
                root = rootsByPath.get(key, factory, interruptionChecker, vfsSpec.openWaitTimeoutNanos());
            } catch (final TimeoutException e) {
                final var timeout = new IOException(
                        "Timed out after " + TimeUnit.NANOSECONDS.toMillis(vfsSpec.openWaitTimeoutNanos())
                                + "ms with no progress, waiting for another thread to open " + key
                                + ". The other thread may be blocked reading it from a filesystem or network "
                                + "(VfsSpec#setOpenWaitTimeout(Duration) sets how long to wait, and a ClassGraph "
                                + "scan sets it to ClassGraph#setWorkerTimeout(Duration))",
                        e);
                // The caller may treat an IOException as a path that cannot be read, and skip it, but the path may
                // be fine. Record the timeout in the shared interruption checker too, so that a scan reading
                // through this Vfs fails at its next check rather than giving incomplete results, and so that the
                // other threads of the scan stop
                interruptionChecker.setExecutionException(new ExecutionException(timeout));
                throw timeout;
            } catch (final NullSingletonException | NewInstanceException e) {
                // A failed open is not remembered: the cache would otherwise turn every later attempt at the same
                // path away with the first failure, so a path that failed once could never be opened again through
                // this Vfs -- not after whatever stopped it has been put right, and not by another route that
                // reaches the same thing without the step that failed
                rootsByPath.discardFailed(key);
                // Chain the cause, as well as naming it in the message -- otherwise the reason the path could not
                // be opened is not reachable from the stack trace
                final var cause = e.getCause() == null ? e : e.getCause();
                throw new IOException("Could not open " + key + " : " + cause, cause);
            }
            // The path the root reports itself at -- its canonical path -- may differ from the key it was asked
            // for by, e.g. when a multi-release jarfile's versioned entry was named by its unversioned name, and
            // recording it as an alias makes a later open by that spelling a cache hit rather than a rebuild
            if (aliasReportedPath) {
                registerAlias(root, key);
            }
            if (!root.isClosed()) {
                return root;
            }
            // The cached root is closed. If this Vfs was closed while the root was being built, close() may have
            // drained the set of open roots before this root was added to it, so close the root here (closing a
            // root twice is harmless), and turn the caller away
            if (isClosed()) {
                root.close();
            }
            checkNotClosed(key.toString());
            // The root was closed by hand while this Vfs stayed open: discard the stale cache entry, unless
            // another thread already replaced it, and retry, so that the caller gets a live root back
            rootsByPath.discard(key, root);
        }
    }

    /**
     * Look up or build a {@link VfsRoot} in {@link #rootsByPath} using {@link #openRootUncached(String, LogNode)}.
     *
     * @param key
     *            the normalized path key.
     * @param logNode
     *            the log node, or {@code null}.
     * @return the opened {@link VfsRoot}.
     * @throws IOException
     *             if the key cannot be opened or if this {@link Vfs} is closed.
     * @throws InterruptedException
     *             if the thread is interrupted.
     */
    private VfsRoot openReentrant(final String key, final @Nullable LogNode logNode)
            throws IOException, InterruptedException {
        return openThroughCache(key, /* aliasReportedPath = */ true, () -> openRootUncached(key, logNode));
    }

    /**
     * Register {@code root}'s canonical reported path as an alias in {@link #rootsByPath}.
     *
     * @param root
     *            the opened root.
     * @param openedKey
     *            the key under which the root was opened.
     */
    private void registerAlias(final VfsRoot root, final Object openedKey) {
        final var nioPath = root.getNioPath();
        if (nioPath != null && nioPath.getFileSystem() != FileSystems.getDefault()) {
            return;
        }
        final var reportedPath = root.reportedPath();
        // putIfAbsent refuses silently once this Vfs is closed, which is correct here: the caches of a closed Vfs
        // stay empty
        if (!reportedPath.equals(openedKey) && rootsByPath.putIfAbsent(reportedPath, root)) {
            root.addUnregistration(() -> rootsByPath.discard(reportedPath, root));
        }
    }

    /**
     * Register a close callback on {@code root} to remove {@code key} from {@link #rootsByPath} when {@code root}
     * is closed.
     *
     * @param key
     *            the cache key.
     * @param root
     *            the root being returned for {@code key}.
     * @return {@code root}.
     */
    private VfsRoot recordPathKey(final Object key, final VfsRoot root) {
        root.addUnregistration(() -> rootsByPath.discard(key, root));
        return root;
    }

    /**
     * Register {@code child} as an open root and attach it as a child of {@code container}, closing {@code child}
     * immediately if {@code container} is already closed.
     *
     * @param container
     *            the enclosing archive root.
     * @param child
     *            the newly created child root.
     * @return {@code child}.
     */
    private ArchiveRoot adoptWithin(final ArchiveRoot container, final ArchiveRoot child) {
        adopt(child);
        // Added before the closed check, the order VfsRoot.close reverses: it marks the container closed before it
        // snapshots the children, so either the snapshot holds the child or the check below sees the close
        container.addChild(child);
        if (container.isClosed()) {
            child.close();
        }
        return child;
    }

    /**
     * Track {@code root} in {@link #openRoots} and register its removal callback on close.
     *
     * @param <R>
     *            the root type.
     * @param root
     *            the newly created root.
     * @return {@code root}.
     */
    private <R extends VfsRoot> R adopt(final R root) {
        openRoots.add(root);
        root.addUnregistration(() -> openRoots.remove(root));
        return root;
    }

    /**
     * Insert {@code root} into {@code cache} under {@code key}, or close {@code root} and return the existing root
     * if another thread cached one first.
     *
     * @param <K>
     *            the cache key type.
     * @param cache
     *            the target cache map.
     * @param key
     *            the cache key.
     * @param root
     *            the newly created root.
     * @param what
     *            description of the opened resource for exception messages.
     * @return the cached root.
     * @throws IOException
     *             if this {@link Vfs} was closed while opening the root.
     */
    private <K> VfsRoot cacheRoot(final Map<K, VfsRoot> cache, final K key, final VfsRoot root, final String what)
            throws IOException {
        final var openedByAnotherThread = cache.putIfAbsent(key, root);
        if (openedByAnotherThread != null) {
            // The root this thread built lost the race, and was never published, so nothing else can be holding
            // it: close it, so that anything it took while it was being built is released now rather than held
            // until this Vfs is closed
            root.close();
            return discardIfClosed(what, openedByAnotherThread);
        }
        root.addUnregistration(() -> cache.remove(key, root));
        return discardIfClosed(what, root);
    }

    /**
     * Close {@code root} and throw {@link IOException} if this {@link Vfs} has been closed, otherwise return
     * {@code root}.
     *
     * @param what
     *            description of the opened resource for exception messages.
     * @param root
     *            the newly created root.
     * @return {@code root}.
     * @throws IOException
     *             if this {@link Vfs} has been closed.
     */
    private VfsRoot discardIfClosed(final String what, final VfsRoot root) throws IOException {
        if (isClosed()) {
            // Closing a root twice is harmless, so it does not matter whether close() saw this root or not
            root.close();
        }
        checkNotClosed(what);
        return root;
    }

    /**
     * Open a directory, jarfile, nested jarfile, or package root for a normalized path on cache miss.
     *
     * @param resolvedPath
     *            the normalized path string from {@link #resolvePath(String)}.
     * @param outerLog
     *            the parent log node, or {@code null}.
     * @return the opened {@link VfsRoot}.
     * @throws IOException
     *             if the path cannot be opened or read.
     * @throws InterruptedException
     *             if the thread is interrupted.
     */
    private VfsRoot openRootUncached(final String resolvedPath, final @Nullable LogNode outerLog)
            throws IOException, InterruptedException {
        final var logNode = outerLog == null ? null : outerLog.log("Opening " + resolvedPath);
        final var lastPlingIdx = PathSyntax.lastIndexOfNestedJarSeparator(resolvedPath);
        if (lastPlingIdx < 0) {
            if (!PathSyntax.hasURLScheme(resolvedPath)) {
                Path dir;
                try {
                    dir = Path.of(resolvedPath);
                } catch (final InvalidPathException _) {
                    // A path that this filesystem cannot represent is not a directory, so try opening it as a
                    // jarfile, which reports the failure with the reason it could not be read
                    dir = null;
                }
                if (dir != null && Files.isDirectory(dir)) {
                    // A directory root names itself by the canonical path of the directory that backs it, which is
                    // what the path it was reached through has to be reconciled with when the two differ -- when
                    // the directory was reached through a symlink, or, on Windows, by an 8.3 short name. The root
                    // that was just built is not the one to keep in that case: the root belongs under the
                    // canonical path, where another thread may already have opened it. Nothing but the directory's
                    // own name is read to build one, so building one to find that name out costs a stat.
                    final var dirRoot = new DirRoot(this, dir);
                    final var canonicalKey = dirRoot.reportedPath();
                    if (!canonicalKey.equals(resolvedPath)) {
                        dirRoot.close();
                        return recordPathKey(resolvedPath, openReentrant(canonicalKey, outerLog));
                    }
                    return recordPathKey(resolvedPath, adopt(dirRoot));
                }
                // A jarfile in the local filesystem is opened under its canonical path, so that two paths that
                // reach the same jarfile -- one of them through a symlink, or, on Windows, by an 8.3 short name --
                // open it once
                final var canonicalFile = FileUtils.canonicalize(new File(resolvedPath));
                // This is the same spelling of the path that the opened jarfile reports itself at
                final var canonicalKey = FastPathResolver.resolve(FileUtils.currDirPath(), canonicalFile.getPath());
                if (!canonicalKey.equals(resolvedPath)) {
                    return recordPathKey(resolvedPath, openReentrant(canonicalKey, outerLog));
                }
                return recordPathKey(resolvedPath, adopt(new ArchiveRoot(this, /* container = */ null,
                        JarOpener.openJarFile(canonicalFile, this, logNode))));
            }
            // A path with a URL scheme names a directory if the filesystem provider installed for its scheme
            // serves one there, and a jarfile otherwise
            final var dirAtURL = directoryAtURL(resolvedPath);
            if (dirAtURL != null) {
                if (dirAtURL.getFileSystem() == FileSystems.getDefault()) {
                    // Open the directory under its local path, which is where open(Path) opens it too
                    return recordPathKey(resolvedPath, openReentrant(resolvePath(dirAtURL.toString()), outerLog));
                }
                // Open the directory under the key open(Path) opens it under, unless that is this path itself
                final var pathKey = new PathKey(dirAtURL.getFileSystem(), dirAtURL.toUri());
                return recordPathKey(resolvedPath, openInOtherFilesystem(dirAtURL, pathKey, outerLog));
            }
            return recordPathKey(resolvedPath, adopt(new ArchiveRoot(this, /* container = */ null,
                    JarOpener.openJarFromURL(resolvedPath, this, logNode))));
        }

        // The path names something within a jarfile: open the enclosing jarfile first, through the cache, so that
        // it is opened once however many nested jarfiles or package roots are read out of it
        final var parentPath = resolvedPath.substring(0, lastPlingIdx);
        // The separator itself is the "!", and sanitizing the rest strips the "/" that follows it. Any '!' in the
        // rest is part of an entry name, since the innermost separator is the last one
        final var childPath = PathSyntax.sanitizeRelativePath(resolvedPath.substring(lastPlingIdx + 1),
                /* removeFinalSlash = */ true);
        final var parentRoot = openReentrant(parentPath, outerLog);
        if (!(parentRoot instanceof final ArchiveRoot parentArchive)) {
            // FastPathResolver only splits a path at a "!" that follows a jarfile, so this cannot normally happen
            throw new IOException("Path " + parentPath + " is not a jarfile, so " + resolvedPath
                    + " does not name anything within one");
        }
        // A path opened at a package root reports itself at that root, but the entry named after the "!" is looked
        // up in the whole jarfile, so read it through the root of the whole jarfile
        final var container = parentArchive.getContainerRoot();
        final var containerZipFile = container.zipFile();
        // The enclosing jarfile may have been reached by a non-canonical path, in which case the canonical
        // spelling of this path names the same thing and is where the root belongs
        final var canonicalChildKey = containerZipFile.getPath() + "!/" + childPath;
        if (!canonicalChildKey.equals(resolvedPath)) {
            return recordPathKey(resolvedPath, openReentrant(canonicalChildKey, outerLog));
        }
        final var childZipEntry = containerZipFile.findEntry(childPath);
        if (childZipEntry == null) {
            if (containerZipFile.hasEntriesUnderDir(childPath)) {
                // The path names a directory within the jarfile rather than a nested jarfile, so it is a package
                // root: the same jarfile, reporting only the entries under that directory
                if (logNode != null && !childPath.isEmpty()) {
                    logNode.log("Path " + childPath + " in jarfile " + containerZipFile
                            + " is a directory, not a file -- using as package root");
                }
                return recordPathKey(resolvedPath,
                        adoptWithin(container, new ArchiveRoot(this, container, childPath)));
            }
            throw new IOException("Path " + childPath + " does not exist in jarfile " + containerZipFile);
        }
        if (!vfsSpec.isNestedJarsEnabled()) {
            throw new IOException("Nested jars are disabled -- cannot open nested jar " + resolvedPath);
        }
        return recordPathKey(resolvedPath, adoptWithin(container,
                new ArchiveRoot(this, container, containerZipFile.openNestedJar(childZipEntry, logNode))));
    }

    /**
     * Return the directory {@link Path} for a URL string if an installed
     * {@link java.nio.file.spi.FileSystemProvider} serves a directory at that URI.
     *
     * @param url
     *            the resolved URL string.
     * @return the directory {@link Path}, or {@code null} if not a directory served by an installed provider.
     */
    private @Nullable Path directoryAtURL(final String url) {
        final var scheme = url.substring(0, url.indexOf(':')).toLowerCase(Locale.ROOT);
        if ("http".equals(scheme) || "https".equals(scheme) || vfsSpec.getDeniedURLSchemes().contains(scheme)) {
            return null;
        }
        try {
            final var path = Path.of(new URI(url));
            return Files.isDirectory(path) ? path : null;
        } catch (final URISyntaxException | IllegalArgumentException | FileSystemNotFoundException _) {
            // The path is not a URI, no filesystem provider is installed for its scheme, the provider has no
            // filesystem open that holds it, or the provider rejected it. Opening it as a jarfile reports why it
            // could not be read
            return null;
        }
    }

    /**
     * Open a directory or jarfile specified by a {@link File}.
     *
     * @param file
     *            the directory or jarfile to open.
     * @return the opened {@link VfsRoot}.
     * @throws IOException
     *             if the file cannot be opened or read, or if this {@link Vfs} has been closed.
     */
    public VfsRoot open(final File file) throws IOException {
        Assert.notNull(file, "file");
        return open(file.getPath());
    }

    /**
     * Open a directory or jarfile specified by a {@link Path} in the default or any custom {@link FileSystem}.
     *
     * <p>
     * For paths in a non-default {@link FileSystem}, the source filesystem must remain open while the returned root
     * is used, and its URI scheme must not be denied by {@link VfsSpec#denyURLScheme(String)}.
     *
     * @param path
     *            the directory or jarfile to open.
     * @return the opened {@link VfsRoot}.
     * @throws IOException
     *             if the path cannot be opened or read, if its URI scheme is denied, or if this {@link Vfs} has
     *             been closed.
     */
    public VfsRoot open(final Path path) throws IOException {
        Assert.notNull(path, "path");
        if (path.getFileSystem() == FileSystems.getDefault()) {
            // A path in the default filesystem can name a jarfile nested within another jarfile, which only the
            // string form of the path can express
            return open(path.toString());
        }
        // A path in another filesystem is keyed by its URI, since its string form is only meaningful within that
        // filesystem, and could collide with a path in the default one. The key also holds the filesystem, since
        // two filesystems, such as two views of one directory or two mounts of one zipfile, report the same URI
        // for paths whose lifetimes differ
        final var uri = path.toUri().toString();
        final var key = new PathKey(path.getFileSystem(), path.toUri());
        checkNotClosed(uri);
        // A path in a filesystem whose scheme has been denied is refused, as it is when it is named by its URI.
        // The URI of a path in a mounted zipfile is a "jar:" URI, which resolving strips down to the location of
        // the zipfile, so what is checked is the scheme of the filesystem that holds the zipfile, if it has one
        final var resolvedKey = resolvePath(uri);
        if (PathSyntax.hasURLScheme(resolvedKey)) {
            JarOpener.checkURLSchemeNotDenied(resolvedKey, this);
        }
        try {
            return openInOtherFilesystem(path, key, log);
        } catch (final InterruptedException e) {
            interruptionChecker.interrupt();
            throw new IOException("Interrupted while opening " + key, e);
        }
    }

    /**
     * Open a directory or jarfile from a {@link Path} in a non-default {@link FileSystem} via {@link #rootsByPath}.
     *
     * @param path
     *            the directory or jarfile path.
     * @param key
     *            the cache key combining the source filesystem identity and path URI.
     * @param outerLog
     *            the parent log node, or {@code null}.
     * @return the opened {@link VfsRoot}.
     * @throws IOException
     *             if the path cannot be opened or read, or if this {@link Vfs} has been closed.
     * @throws InterruptedException
     *             if the thread is interrupted.
     */
    private VfsRoot openInOtherFilesystem(final Path path, final PathKey key, final @Nullable LogNode outerLog)
            throws IOException, InterruptedException {
        // Open the path itself, rather than taking its URI apart again and looking for a file of that name
        return openThroughCache(key, /* aliasReportedPath = */ false, () -> {
            final var logNode = outerLog == null ? null : outerLog.log("Opening " + key);
            return recordPathKey(key,
                    Files.isDirectory(path) ? adopt(new DirRoot(this, path))
                            : adopt(new ArchiveRoot(this, /* container = */ null,
                                    JarOpener.openJarFromPath(path, this, logNode))));
        });
    }

    /**
     * Open a directory or jarfile specified by a {@link URI}.
     *
     * @param uri
     *            the {@link URI} to open.
     * @return the opened {@link VfsRoot}.
     * @throws IOException
     *             if the {@link URI} cannot be opened or read, or if this {@link Vfs} has been closed.
     */
    public VfsRoot open(final URI uri) throws IOException {
        Assert.notNull(uri, "uri");
        return open(uri.toString());
    }

    /**
     * Open a directory or jarfile specified by a {@link URL}.
     *
     * @param url
     *            the {@link URL} to open.
     * @return the opened {@link VfsRoot}.
     * @throws IOException
     *             if the {@link URL} cannot be opened or read, or if this {@link Vfs} has been closed.
     */
    public VfsRoot open(final URL url) throws IOException {
        Assert.notNull(url, "url");
        return open(url.toString());
    }

    /**
     * Open a module from a {@link ModuleReference}.
     *
     * @param moduleReference
     *            the module reference to open.
     * @return the opened {@link VfsRoot}.
     * @throws IOException
     *             if this {@link Vfs} has been closed.
     */
    public VfsRoot open(final ModuleReference moduleReference) throws IOException {
        Assert.notNull(moduleReference, "moduleReference");
        final var moduleName = moduleReference.descriptor().name();
        checkNotClosed(moduleName);
        final var alreadyOpened = rootsByModule.get(moduleReference);
        if (alreadyOpened != null) {
            return alreadyOpened;
        }
        return cacheRoot(rootsByModule, moduleReference, adopt(new ModuleRoot(this, moduleReference)), moduleName);
    }

    /**
     * Open a module in the {@linkplain ModuleLayer#boot() boot layer} by name (e.g. {@code "java.logging"}).
     *
     * @param moduleName
     *            the module name.
     * @return the opened {@link VfsRoot}.
     * @throws NoSuchFileException
     *             if no module with {@code moduleName} exists in the boot layer.
     * @throws IOException
     *             if this {@link Vfs} has been closed.
     */
    public VfsRoot openModule(final String moduleName) throws IOException {
        return openModule(moduleName, ModuleLayer.boot());
    }

    /**
     * Open a module in the given {@link ModuleLayer} (or its parent layers, searched in
     * {@link java.lang.module.Configuration#findModule(String)} order) by name.
     *
     * @param moduleName
     *            the module name.
     * @param layer
     *            the module layer to search.
     * @return the opened {@link VfsRoot}.
     * @throws NoSuchFileException
     *             if no module with {@code moduleName} exists in {@code layer} or its parent layers.
     * @throws IOException
     *             if this {@link Vfs} has been closed.
     */
    public VfsRoot openModule(final String moduleName, final ModuleLayer layer) throws IOException {
        Assert.notNull(moduleName, "moduleName");
        Assert.notNull(layer, "layer");
        checkNotClosed(moduleName);
        final var resolvedModule = layer.configuration().findModule(moduleName);
        if (resolvedModule.isEmpty()) {
            throw new NoSuchFileException(moduleName, null,
                    "No module of that name in the module layer or its parents");
        }
        return open(resolvedModule.get().reference());
    }

    /**
     * Open a jarfile from an {@link InputStream}. The stream is buffered into RAM, or spilled to a temporary file
     * if larger than {@link VfsSpec#getMaxBufferedJarRAMSize()}. The returned root is not cached, and {@code
     * inputStream} is not closed by this method.
     *
     * @param inputStream
     *            the stream containing the jarfile bytes.
     * @param name
     *            the logical name for the jarfile, used in entry paths and log messages.
     * @return the opened {@link VfsRoot}.
     * @throws IOException
     *             if the jarfile cannot be read, or if this {@link Vfs} has been closed.
     */
    public VfsRoot open(final InputStream inputStream, final String name) throws IOException {
        Assert.notNull(inputStream, "inputStream");
        Assert.notNull(name, "name");
        checkNotClosed(name);
        final var logNode = log == null ? null : log.log("Reading " + name + " from an InputStream");
        return discardIfClosed(name, adopt(new ArchiveRoot(this, /* container = */ null, JarOpener
                .openJarFromInputStream(inputStream, /* inputStreamLengthHint = */ -1L, name, this, logNode))));
    }

    /**
     * Open a jarfile from a byte array. The returned root is not cached, and {@code jarBytes} is not modified.
     *
     * @param jarBytes
     *            the jarfile bytes.
     * @param name
     *            the logical name for the jarfile, used in entry paths and log messages.
     * @return the opened {@link VfsRoot}.
     * @throws IOException
     *             if the jarfile cannot be read, or if this {@link Vfs} has been closed.
     */
    public VfsRoot open(final byte[] jarBytes, final String name) throws IOException {
        Assert.notNull(jarBytes, "jarBytes");
        Assert.notNull(name, "name");
        checkNotClosed(name);
        final var logNode = log == null ? null : log.log("Reading " + name + " from a byte array");
        return discardIfClosed(name,
                adopt(new ArchiveRoot(this, /* container = */ null, JarOpener.openJarFromInputStream(
                        new ByteArrayInputStream(jarBytes), jarBytes.length, name, this, logNode))));
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Return an iterator over a snapshot of the cached {@link VfsRoot} instances in this {@link Vfs}, sorted by
     * their reported path. Roots opened from an {@link InputStream} or byte array are not cached and are not
     * included; enclosing jarfiles opened while resolving nested jarfiles or package roots are included. Returns an
     * empty iterator if this {@link Vfs} is closed.
     *
     * @return an unmodifiable iterator over the cached roots.
     */
    @Override
    public @Unmodifiable Iterator<VfsRoot> iterator() {
        if (isClosed()) {
            return Collections.emptyIterator();
        }
        // A root can be cached under more than one path, e.g. under both the path it was opened from and the
        // canonical path of the directory or jarfile that turned out to back it, so it can be reached twice here
        final Set<VfsRoot> distinctRoots = Collections.newSetFromMap(new IdentityHashMap<>());
        distinctRoots.addAll(rootsByPath.completedValues());
        distinctRoots.addAll(rootsByModule.values());
        final var roots = new ArrayList<>(distinctRoots);
        roots.sort(Comparator.comparing(VfsRoot::reportedPath));
        return Collections.unmodifiableList(roots).iterator();
    }

    /**
     * Remove {@code root} from this {@link Vfs}'s lookup caches so that subsequent {@code open} calls for the same
     * path or module create a new root. This does not close {@code root}; it remains open and tracked by this
     * {@link Vfs} until closed directly via {@link VfsRoot#close()} or when this {@link Vfs} is closed.
     *
     * @param root
     *            the root to evict from the cache.
     */
    public void evict(final VfsRoot root) {
        Assert.notNull(root, "root");
        if (!closed.get()) {
            // A root can be cached under more than one key, e.g. under both the path it was opened from and the
            // canonical path of the file that turned out to back it
            rootsByPath.discardValue(root);
            rootsByModule.values().removeIf(cachedRoot -> cachedRoot == root);
        }
        // If this Vfs is already closing, the caches are cleared wholesale by close(), so there is nothing to do
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Throw {@link IOException} if this {@link Vfs} has been closed.
     *
     * @param what
     *            description of the resource being read.
     * @throws IOException
     *             if this {@link Vfs} has been closed.
     */
    void checkNotClosed(final String what) throws IOException {
        if (closed.get()) {
            throw new IOException("Cannot read " + what + " after the Vfs has been closed");
        }
    }

    /**
     * Throw {@link IllegalStateException} if this {@link Vfs} has been closed.
     *
     * @throws IllegalStateException
     *             if this {@link Vfs} has been closed.
     */
    private void checkOpen() {
        if (closed.get()) {
            throw new IllegalStateException("Cannot configure a Vfs after it has been closed");
        }
    }

    /**
     * Return whether this {@link Vfs} has been closed.
     *
     * @return {@code true} if this {@link Vfs} has been closed.
     */
    public boolean isClosed() {
        return closed.get();
    }

    /**
     * Return the immutable {@link VfsSpec} configuration used by this {@link Vfs}.
     *
     * @return the immutable {@link VfsSpec}.
     */
    public VfsSpec getVfsSpec() {
        return vfsSpec;
    }

    /**
     * Return the shared {@link InterruptionChecker} for this {@link Vfs}.
     *
     * @return the interruption checker.
     * @hidden
     */
    @SuppressWarnings("exports")
    public InterruptionChecker interruptionChecker() {
        return interruptionChecker;
    }

    /**
     * Return the {@link Inflater} recycler for this {@link Vfs}.
     *
     * @return the inflater recycler.
     * @hidden
     */
    @SuppressWarnings("exports")
    public Recycler<Inflater, RuntimeException> inflaterRecycler() {
        return inflaterRecycler;
    }

    /**
     * Return the verbose log node, or {@code null} if logging is not enabled.
     *
     * @return the log node, or {@code null}.
     */
    @Nullable
    LogNode log() {
        return log;
    }

    /**
     * Close this {@link Vfs} and all {@link VfsRoot} instances it opened, closing any open streams, channels,
     * buffers, and filesystem views, releasing all file handles and memory mappings, and deleting any temporary
     * files. Subsequent reads from any root, entry, stream, or filesystem view opened by this {@link Vfs} will
     * fail. Calling this method on an already-closed {@link Vfs} has no effect.
     */
    @Override
    public void close() {
        // Only the thread that performs the close flushes the log, so that a second thread cannot print a
        // half-written log while the first is still adding entries to it
        doClose(log, /* flushLogAfterwards = */ true);
    }

    /**
     * Close this {@link Vfs}, logging cleanup messages to {@code logNode} without flushing it.
     *
     * @param logNode
     *            the log node to log to, or {@code null}.
     */
    public void close(final @Nullable LogNode logNode) {
        doClose(logNode, /* flushLogAfterwards = */ false);
    }

    /**
     * Close this {@link Vfs} if not already closed.
     *
     * @param logNode
     *            the log node to log to, or {@code null}.
     * @param flushLogAfterwards
     *            whether to flush {@code logNode} after closing.
     */
    private void doClose(final @Nullable LogNode logNode, final boolean flushLogAfterwards) {
        // Marked closed atomically, so that a second call (or a concurrent one) returns rather than releasing the
        // same resources twice, and so that a thread calling any other method the moment a close starts is turned
        // away. It is marked before anything is released, since it is what every root checks before reading, so a
        // thread that is midway through a read cannot get at storage that is being released out from under it.
        if (closed.getAndSet(true)) {
            return;
        }
        try {
            try {
                // The caches have to be dropped before the resources behind them are released, so that nothing can
                // be handed a root that is about to be closed
                rootsByPath.clear();
                rootsByModule.clear();
                // Close every root this Vfs opened, releasing what each root owns: its pooled module readers, and
                // the file handles, memory mappings and temporary files that its jarfile was read through.
                // VfsRoot#close throws no exception, so one root that fails to release does not stop the rest from
                // being closed
                final var rootsToClose = new ArrayList<>(openRoots);
                openRoots.clear();
                for (final var root : rootsToClose) {
                    root.close(logNode);
                }
            } finally {
                // The inflaters are borrowed on top of the slices the roots read through, so the pool is dropped
                // after the roots are closed. forceClose closes the pooled inflaters and any inflater still on
                // loan, and throws no exception. It runs in a finally block so that the inflaters are released even
                // if closing a root threw an Error
                inflaterRecycler.forceClose();
            }
            // An interruption during the close arrives as this thread's status, restored by the interrupted step
            // rather than recorded anywhere: asking the garbage collector to unmap a file that a slice could not
            // unmap reports one that way, being a static utility with no checker to reach. Record it in the shared
            // checker, so that the other threads that check it stop too: a worker that a failed scan left running
            // stops at its next check (once a scan has succeeded, nothing checks it). An interruption that another
            // thread recorded in the checker earlier is not raised on this thread: the thread closing a ScanResult
            // may have nothing to do with a thread that was interrupted while it read through the ScanResult, and
            // a failed scan must not interrupt the thread that called scan() unless the scan was interrupted
            if (Thread.currentThread().isInterrupted()) {
                interruptionChecker.interrupt();
            }
        } finally {
            if (flushLogAfterwards && logNode != null) {
                logNode.flush();
            }
        }
    }
}
