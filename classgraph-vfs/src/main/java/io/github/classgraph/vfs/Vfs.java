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
import java.net.URL;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import io.github.classgraph.base.LogNode;
import io.github.classgraph.base.internal.concurrency.InterruptionChecker;
import io.github.classgraph.base.internal.concurrency.SingletonMap.NewInstanceException;
import io.github.classgraph.base.internal.concurrency.SingletonMap.NullSingletonException;
import io.github.classgraph.base.internal.concurrency.SingletonMap;
import io.github.classgraph.base.internal.path.FastPathResolver;
import io.github.classgraph.base.internal.path.FileUtils;
import io.github.classgraph.base.internal.path.PathSyntax;
import io.github.classgraph.base.internal.utils.Assert;
import io.github.classgraph.vfs.internal.VfsSession;
import io.github.classgraph.vfs.internal.zip.JarOpener;
import io.github.classgraph.vfs.internal.zip.LogicalZipFile;
import org.jspecify.annotations.Nullable;

/**
 * A virtual filesystem: opens a directory, a jarfile, or a module, however it is named -- by path string, by
 * {@link File}, by {@link Path}, by {@link URI}, by {@link URL}, by {@link ModuleReference}, as an
 * {@link InputStream}, or as a byte array -- and gives back the same {@link VfsRoot} interface for all of them.
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
 * The entries of a root are named the same way whichever kind of root it is -- relative to the root, with
 * {@code '/'} as the separator -- and are read through the same {@link VfsEntry} methods, as an
 * {@link InputStream}, a {@link java.nio.channels.ReadableByteChannel}, a {@link java.nio.ByteBuffer}, a byte array
 * or a string. So code that reads a jarfile reads a directory or a module without changing a line.
 *
 * <p>
 * A nested jarfile is read in place, without being extracted to disk, unless it is stored deflated rather than
 * uncompressed and is too large to inflate into RAM -- only then is it spilled to a temporary file, which is
 * deleted when this {@link Vfs} is closed.
 *
 * <p>
 * A {@link Vfs} caches every root it opens, so opening the same path twice returns the same {@link VfsRoot}, and a
 * jarfile that encloses several nested jarfiles is only read once. Iterating the {@link Vfs} gives back the roots
 * it has cached, and {@link #evict(VfsRoot)} takes one back out of the cache without stopping it working for
 * anything still holding it. The cache, the open file handles and the temporary files are all released by
 * {@link #close()}, which invalidates every {@link VfsRoot} and {@link VfsEntry} it handed out, so a {@link Vfs}
 * should be held open for as long as its entries are being read.
 *
 * <p>
 * Every method is safe to call from multiple threads at once. Two threads that ask for the same path at the same
 * time get back the same {@link VfsRoot}, and the jarfile behind it is only read once. How storage is read is set
 * by the {@link VfsSpec} this {@link Vfs} was constructed with, whose settings are meant to be chosen before
 * anything is opened but are safe to change from any thread, and {@link #verbose()}, which only turns on logging,
 * is synchronized like every other method. {@link #close()} takes effect the moment it is called, so a thread that
 * calls any other method after that -- even while the close is still running -- gets an {@link IOException} from an
 * {@code open} method, or an {@link IllegalStateException} from {@link #verbose()}, rather than a root backed by
 * storage that is being released.
 */
public final class Vfs implements AutoCloseable, Iterable<VfsRoot> {
    /**
     * The session that owns the resources the opened roots are backed by, and that tracks whether this is closed.
     */
    private final VfsSession session;

    /**
     * The roots that have been opened from a path, keyed by every path that names each -- the path as it was
     * opened, its canonical form, and the path the root reports itself at -- so that one directory or jarfile named
     * several ways is opened once. Two threads that ask for the same path at the same time build the root only
     * once, since a lookup for a key whose root is still being built blocks until it is ready. The enclosing
     * jarfiles of a nested jarfile are opened through this same cache, one frame of re-entry per {@code "!"}
     * section, so each of them is a root in its own right, cached under its own path. Initialized in the
     * constructor, since building a root needs this {@link Vfs}.
     */
    private final SingletonMap<String, VfsRoot, IOException> rootsByPath;

    /** The roots that have been opened from a {@link ModuleReference}, keyed by that module. */
    private final Map<ModuleReference, VfsRoot> rootsByModule = new ConcurrentHashMap<>();

    /**
     * Every root this {@link Vfs} has opened and not yet closed -- including one opened from an {@link InputStream}
     * or a byte array, which the path cache does not hold -- so that {@link #close()} can close each of them. A
     * root removes itself from this set when it is closed.
     */
    private final Set<VfsRoot> openRoots = ConcurrentHashMap.newKeySet();

    /** The log node that {@link #verbose()} turns on, or null if not logging. */
    private volatile @Nullable LogNode log;

    /** Constructor, using the default value of every setting -- see {@link VfsSpec}. */
    public Vfs() {
        this(new VfsSpec(), new InterruptionChecker());
    }

    /**
     * Constructor, taking the settings to read storage with.
     *
     * <p>
     * The {@link VfsSpec} is held, not copied, and each setting is read where it is needed, so a setting should be
     * changed before this {@link Vfs} opens anything -- a setting changed while entries are being read takes effect
     * for some of them and not others. Changing one is safe from any thread, since every setting is held in a
     * volatile field.
     *
     * <pre>
     * try (Vfs vfs = new Vfs(new VfsSpec().disableMultiReleaseVersions().setMaxBufferedJarRAMSize(65536))) {
     *     // ...
     * }
     * </pre>
     *
     * @param vfsSpec
     *            the settings to read storage with.
     */
    public Vfs(final VfsSpec vfsSpec) {
        this(vfsSpec, new InterruptionChecker());
    }

    /**
     * Constructor for the other ClassGraph modules, which share an {@link InterruptionChecker} with the rest of a
     * scan. {@link InterruptionChecker} is in a package that is only exported to those modules, so no other module
     * can call this constructor, and it is not part of the API.
     *
     * @param vfsSpec
     *            the settings to read storage with.
     * @param interruptionChecker
     *            the interruption checker to share with the rest of the scan.
     * @hidden
     */
    public Vfs(final VfsSpec vfsSpec, final InterruptionChecker interruptionChecker) {
        this.session = new VfsSession(vfsSpec, interruptionChecker);
        this.rootsByPath = new SingletonMap<>(session.closedFlag()) {
            @Override
            public VfsRoot newInstance(final String resolvedPath, final @Nullable LogNode logNode)
                    throws IOException, InterruptedException {
                return openRootUncached(resolvedPath, logNode);
            }
        };
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Log what is read to the {@code io.github.classgraph.ClassGraph} logger, at {@code INFO} level. This is
     * intended for working out why something is not being read as expected, and is not a stable output format.
     *
     * <p>
     * The log is written when this {@link Vfs} is closed.
     *
     * @return this (for method chaining).
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
     * Open a directory or a jarfile named by a path.
     *
     * <p>
     * The path may name a directory or a jarfile in the local filesystem, or a URL with any scheme the JVM has a
     * handler for, other than one denied by {@link VfsSpec#disableURLScheme(String)}. A jarfile nested within
     * another jarfile is named by separating the enclosing jarfile from the nested one with {@code "!/"}, to any
     * depth, e.g. {@code "outer.jar!/lib/inner.jar"}. A trailing {@code "!/"} section that does not name a nested
     * jarfile names a package root within the jarfile instead, e.g.
     * {@code "spring-boot-app.jar!/BOOT-INF/classes"}, in which case only the entries under that root are reported,
     * with the root stripped from their names.
     *
     * <p>
     * A directory or jarfile is opened once however it is named, so the same {@link VfsRoot} is returned for a
     * plain path, for the {@code "file:"} or {@code "jar:"} URL of the same thing, for a path that reaches it
     * through a symlink, and, on Windows, for a path written with backslashes rather than forward slashes, or one
     * that names a directory by its 8.3 short name. The root reports itself at the canonical path of the directory
     * or jarfile that backs it, whichever of those names it was opened by.
     *
     * @param path
     *            the path to open.
     * @return the opened root.
     * @throws IOException
     *             if the path could not be opened or read, or if this {@link Vfs} has been closed.
     */
    public VfsRoot open(final String path) throws IOException {
        return open(path, log);
    }

    /**
     * Open a directory or a jarfile named by a path, logging to the given log node rather than to the one
     * {@link #verbose()} turned on. This is for callers that write a log of their own, and want what the virtual
     * filesystem logs nested under the part of it that it belongs to.
     *
     * <p>
     * A path is only opened once, so only the first call for a given path logs anything.
     *
     * @param path
     *            the path to open.
     * @param logNode
     *            the log node to log to, or null to not log.
     * @return the opened root.
     * @throws IOException
     *             if the path could not be opened or read, or if this {@link Vfs} has been closed.
     */
    public VfsRoot open(final String path, final @Nullable LogNode logNode) throws IOException {
        Assert.notNull(path, "path");
        checkNotClosed(path);
        // The resolved path is the cache key, not the path as it was written: the same directory or jarfile can be
        // named as a plain path, as a "file:" or "jar:" URL, with a trailing separator, or -- on Windows -- with
        // backslashes rather than forward slashes, and all of those name one thing that is opened once
        final var resolvedPath = FastPathResolver.resolve(path);
        try {
            return openThroughCache(resolvedPath, logNode, /* factory = */ null);
        } catch (final InterruptedException e) {
            // Route the interruption through the shared interruption checker, so that every other thread reading
            // through this Vfs stops too, and chain the cause, so that the reason the open did not complete is
            // reachable from the stack trace
            session.interruptionChecker().interrupt();
            throw new IOException("Interrupted while opening " + resolvedPath, e);
        }
    }

    /**
     * Look a key up in {@link #rootsByPath}, building the root if no thread has opened that key yet, and return the
     * root open at that key. {@link InterruptedException} is deliberately let through raw rather than being wrapped
     * in an {@link IOException} here: a build that opens the jarfile enclosing a nested one re-enters this method,
     * and wrapping the interruption in the inner frame would leave the outer frame reporting the cancellation of
     * the calling thread as a failure of the path it was opening. Only the public {@code open} methods convert it.
     *
     * @param key
     *            the cache key: a path in the form {@link FastPathResolver#resolve(String)} returns, or the URI of
     *            a {@link Path} in a non-default filesystem.
     * @param logNode
     *            the log node that a build logs to, or null to skip logging.
     * @param factory
     *            builds the root if no thread has opened that key yet, or null to build with
     *            {@link #openRootUncached(String, LogNode)}.
     * @return the root open at that key.
     * @throws IOException
     *             if the key could not be opened or read, or if this {@link Vfs} has been closed.
     * @throws InterruptedException
     *             if the thread was interrupted.
     */
    private VfsRoot openThroughCache(final String key, final @Nullable LogNode logNode,
            final SingletonMap.@Nullable NewInstanceFactory<VfsRoot, IOException> factory)
            throws IOException, InterruptedException {
        for (;;) {
            final VfsRoot root;
            try {
                root = rootsByPath.get(key, logNode, factory);
            } catch (final NullSingletonException | NewInstanceException e) {
                // A failed open is not remembered: the cache would otherwise turn every later attempt at the same
                // path away with the first failure, so a path that failed once could never be opened again through
                // this Vfs -- not after whatever stopped it has been put right, and not by another route that
                // reaches the same thing without the step that failed
                rootsByPath.discard(key);
                // Chain the cause, as well as naming it in the message -- otherwise the reason the path could not
                // be opened is not reachable from the stack trace
                final var cause = e.getCause() == null ? e : e.getCause();
                throw new IOException("Could not open " + key + " : " + cause, cause);
            }
            // The path the root reports itself at -- its canonical path -- may differ from the key it was asked
            // for by, e.g. when a multi-release jarfile's versioned entry was named by its unversioned name, and
            // recording it as an alias makes a later open by that spelling a cache hit rather than a rebuild
            registerAlias(root, key);
            if (!root.isClosed()) {
                return root;
            }
            // The cached root is closed. If this Vfs was closed while the root was being built, close() may have
            // drained the set of open roots before this root was added to it, so close the root here (closing a
            // root twice is harmless), and turn the caller away
            if (isClosed()) {
                root.close();
            }
            checkNotClosed(key);
            // The root was closed by hand while this Vfs stayed open: discard the stale cache entry, unless
            // another thread already replaced it, and retry, so that the caller gets a live root back
            rootsByPath.discard(key, root);
        }
    }

    /**
     * Look a key up in {@link #rootsByPath}, building the root with {@link #openRootUncached(String, LogNode)} if
     * no thread has opened that key yet. This is how one frame of {@link #openRootUncached(String, LogNode)}
     * re-enters the cache to open the jarfile enclosing a nested one, or to open a non-canonical path under its
     * canonical form; the cache allows the re-entry, since each frame locks only its own key.
     *
     * @param key
     *            the cache key, in the form {@link FastPathResolver#resolve(String)} returns.
     * @param logNode
     *            the log node that a build logs to, or null to skip logging.
     * @return the root open at that key.
     * @throws IOException
     *             if the key could not be opened or read, or if this {@link Vfs} has been closed.
     * @throws InterruptedException
     *             if the thread was interrupted.
     */
    private VfsRoot openReentrant(final String key, final @Nullable LogNode logNode)
            throws IOException, InterruptedException {
        return openThroughCache(key, logNode, /* factory = */ null);
    }

    /**
     * Record the path a root reports itself at as another key it can be found by in {@link #rootsByPath}, unless
     * that is the key it was just found by, or another root already holds that key. The root is handed the action
     * that takes the alias back out of the cache, so that closing the root removes it.
     *
     * @param root
     *            the root that was just opened or found in the cache.
     * @param openedKey
     *            the key it was opened or found by.
     */
    private void registerAlias(final VfsRoot root, final String openedKey) {
        final var reportedPath = root.reportedPath();
        // putIfAbsent refuses silently once this Vfs is closed, which is correct here: the caches of a closed Vfs
        // stay empty
        if (!reportedPath.equals(openedKey) && rootsByPath.putIfAbsent(reportedPath, root)) {
            root.addUnregistration(() -> rootsByPath.discard(reportedPath, root));
        }
    }

    /**
     * Hand a root that {@link #openRootUncached(String, LogNode)} is about to return the action that takes the key
     * it is being built under back out of {@link #rootsByPath}, so that closing the root removes it from the cache.
     * The cache entry itself is written by the cache once the build returns, so a root closed in the narrow window
     * between this registration and that write leaves a stale entry behind, which
     * {@link #openThroughCache(String, LogNode, SingletonMap.NewInstanceFactory)} discards and retries when it is
     * next looked up.
     *
     * @param key
     *            the key the root is being built under.
     * @param root
     *            the root being returned for that key.
     * @return the root.
     */
    private VfsRoot recordPathKey(final String key, final VfsRoot root) {
        root.addUnregistration(() -> rootsByPath.discard(key, root));
        return root;
    }

    /**
     * Build a root within a container root: a package root view of the container's jarfile, or the root of a
     * jarfile nested within it. The child registers itself with the container, so that closing the container closes
     * the child; if the container was closed while the child was being built, the registration may have come too
     * late for the container's close to see, so the child is closed here instead, and the caller -- always a build
     * running under {@link #openThroughCache(String, LogNode, SingletonMap.NewInstanceFactory)} -- then sees a
     * closed root, discards it, and retries or turns its caller away. The closed root is still returned rather than
     * an exception being thrown, since the cache would record a thrown build as a permanent failure of the path,
     * and a race with a close of the container is not one.
     *
     * @param container
     *            the root the child is being built within.
     * @param zipFile
     *            the jarfile the child reads.
     * @param packageRoot
     *            the package root within that jarfile, or the empty string for the whole jarfile.
     * @return the child root.
     */
    private ArchiveRoot newChildRoot(final ArchiveRoot container, final LogicalZipFile zipFile,
            final String packageRoot) {
        final var child = adopt(new ArchiveRoot(this, container, zipFile, packageRoot));
        if (container.isClosed()) {
            child.close();
        }
        return child;
    }

    /**
     * Record a root that was just built as open, so that {@link #close()} closes it, and hand the root the action
     * that takes it back out of the set of open roots, so that closing the root removes it. Every root this
     * {@link Vfs} builds passes through here, whether or not it is then cached under a path. The caller must still
     * make the closed check that discards the root if this {@link Vfs} was closed while it was being built --
     * {@link #discardIfClosed(String, VfsRoot)} does both.
     *
     * @param <R>
     *            the type of the root.
     * @param root
     *            the root that was just built.
     * @return the root.
     */
    private <R extends VfsRoot> R adopt(final R root) {
        openRoots.add(root);
        root.addUnregistration(() -> openRoots.remove(root));
        return root;
    }

    /**
     * Add a root that has just been opened to a cache of opened roots, unless another thread opened the same path
     * first, in which case the root that thread cached is returned and the one passed in is discarded.
     *
     * @param <K>
     *            the type of the cache key.
     * @param cache
     *            the cache to add the root to.
     * @param key
     *            the key to cache the root under.
     * @param root
     *            the root that was just opened.
     * @param what
     *            what was opened, for the exception message.
     * @return the cached root.
     * @throws IOException
     *             if this {@link Vfs} was closed while the root was being opened.
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
     * Return a root that has just been opened, unless this {@link Vfs} was closed while it was being opened, in
     * which case close the root and throw, rather than handing back a root that is backed by storage that has
     * already been released. Closing the root also takes it back out of the set of open roots and any cache it was
     * put in, since {@link #close()} may have passed over all of those before the root was recorded in them.
     *
     * @param what
     *            what was opened, for the exception message.
     * @param root
     *            the root that was just opened.
     * @return the root.
     * @throws IOException
     *             if this {@link Vfs} was closed while the root was being opened.
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
     * Open a directory or a jarfile named by a path, when no root is cached under that path yet. This is only
     * called by {@link #rootsByPath}, which holds the key while this runs, so a path is only built once however
     * many threads ask for it at the same time.
     *
     * <p>
     * A path that names something within a jarfile -- {@code "outer.jar!/lib/inner.jar"} -- is opened one
     * {@code "!/"} section at a time: the path up to the last separator is opened through the cache, which opens
     * the section before that, and so on down to the jarfile in the local filesystem, and each of those enclosing
     * jarfiles is a root in its own right, cached under its own path. A path that is not the canonical spelling of
     * what it names -- one that reaches a jarfile through a symlink, or that names a nested jarfile through a
     * non-canonical spelling of the enclosing one -- is opened by re-entering the cache under the canonical
     * spelling, so that both spellings share one root.
     *
     * @param resolvedPath
     *            the path to open, in the form {@link FastPathResolver#resolve(String)} returns it.
     * @param outerLog
     *            the log node to log under, or null to skip logging.
     * @return the opened root.
     * @throws IOException
     *             if the path could not be opened or read.
     * @throws InterruptedException
     *             if the thread was interrupted.
     */
    private VfsRoot openRootUncached(final String resolvedPath, final @Nullable LogNode outerLog)
            throws IOException, InterruptedException {
        final var logNode = outerLog == null ? null : outerLog.log("Opening " + resolvedPath);
        final var lastPlingIdx = PathSyntax.lastIndexOfNestedJarSeparator(resolvedPath);
        if (lastPlingIdx < 0) {
            // A path with a URL scheme names a jarfile to download, so it cannot be a directory
            if (!PathSyntax.hasURLScheme(resolvedPath)) {
                Path dir;
                try {
                    dir = Path.of(resolvedPath);
                } catch (final InvalidPathException e) {
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
                File canonicalFile;
                try {
                    canonicalFile = FileUtils.canonicalize(new File(resolvedPath));
                } catch (final SecurityException e) {
                    throw new IOException("Path component " + resolvedPath + " could not be canonicalized: " + e,
                            e);
                }
                // This is the same spelling of the path that the opened jarfile reports itself at
                final var canonicalKey = FastPathResolver.resolve(FileUtils.currDirPath(), canonicalFile.getPath());
                if (!canonicalKey.equals(resolvedPath)) {
                    return recordPathKey(resolvedPath, openReentrant(canonicalKey, outerLog));
                }
                return recordPathKey(resolvedPath, adopt(new ArchiveRoot(this, /* container = */ null,
                        JarOpener.openJarFile(canonicalFile, session, logNode), /* packageRoot = */ "")));
            }
            return recordPathKey(resolvedPath, adopt(new ArchiveRoot(this, /* container = */ null,
                    JarOpener.openJarFromURL(resolvedPath, session, logNode), /* packageRoot = */ "")));
        }

        // The path names something within a jarfile: open the enclosing jarfile first, through the cache, so that
        // it is opened once however many nested jarfiles or package roots are read out of it
        final var parentPath = resolvedPath.substring(0, lastPlingIdx);
        // The separator itself is the "!", and sanitizing the rest strips the "/" that follows it
        final var childPath = PathSyntax.sanitizeEntryPath(resolvedPath.substring(lastPlingIdx + 1),
                /* removeInitialSlash = */ true, /* removeFinalSlash = */ true);
        final var parentRoot = openReentrant(parentPath, outerLog);
        if (!(parentRoot instanceof final ArchiveRoot parentArchive)) {
            // FastPathResolver only splits a path at a "!" that follows a jarfile, so this cannot normally happen
            throw new IOException("Path " + parentPath + " is not a jarfile, so " + resolvedPath
                    + " does not name anything within one");
        }
        // A path opened at a package root reports itself at that root, but the entry named after the "!" is looked
        // up in the whole jarfile, so read it through the root of the whole jarfile
        final var container = (ArchiveRoot) parentArchive.getContainerRoot();
        final var containerZipFile = container.zipFile();
        // The enclosing jarfile may have been reached by a non-canonical path, in which case the canonical
        // spelling of this path names the same thing and is where the root belongs
        final var canonicalChildKey = containerZipFile.getPath() + "!/" + childPath;
        if (!canonicalChildKey.equals(resolvedPath)) {
            return recordPathKey(resolvedPath, openReentrant(canonicalChildKey, outerLog));
        }
        final var childZipEntry = JarOpener.findEntry(containerZipFile, childPath);
        if (childZipEntry == null) {
            if (JarOpener.hasEntriesUnderDir(containerZipFile, childPath)) {
                // The path names a directory within the jarfile rather than a nested jarfile, so it is a package
                // root: the same jarfile, reporting only the entries under that directory
                if (logNode != null && !childPath.isEmpty()) {
                    logNode.log("Path " + childPath + " in jarfile " + containerZipFile
                            + " is a directory, not a file -- using as package root");
                }
                return recordPathKey(resolvedPath, newChildRoot(container, containerZipFile, childPath));
            }
            throw new IOException("Path " + childPath + " does not exist in jarfile " + containerZipFile);
        }
        if (!session.vfsSpec.isNestedJarsEnabled()) {
            throw new IOException("Nested jar scanning is disabled -- skipping nested jar " + resolvedPath);
        }
        return recordPathKey(resolvedPath,
                newChildRoot(container, JarOpener.openNestedJar(childZipEntry, logNode), /* packageRoot = */ ""));
    }

    /**
     * Open a directory or a jarfile named by a {@link File}.
     *
     * @param file
     *            the directory or jarfile to open.
     * @return the opened root.
     * @throws IOException
     *             if the file could not be opened or read, or if this {@link Vfs} has been closed.
     */
    public VfsRoot open(final File file) throws IOException {
        Assert.notNull(file, "file");
        return open(file.getPath());
    }

    /**
     * Open a directory or a jarfile named by a {@link Path}. The {@link Path} may be in a filesystem other than the
     * default one, e.g. in a zipfile mounted with {@link FileSystems#newFileSystem(Path, ClassLoader)}.
     *
     * @param path
     *            the directory or jarfile to open.
     * @return the opened root.
     * @throws IOException
     *             if the path could not be opened or read, or if this {@link Vfs} has been closed.
     */
    public VfsRoot open(final Path path) throws IOException {
        Assert.notNull(path, "path");
        if (path.getFileSystem() == FileSystems.getDefault()) {
            // A path in the default filesystem can name a jarfile nested within another jarfile, which only the
            // string form of the path can express
            return open(path.toString());
        }
        // A path in another filesystem is keyed by its URI, since its string form is only meaningful within that
        // filesystem, and could collide with a path in the default one
        final var key = path.toUri().toString();
        checkNotClosed(key);
        // Read the log node once, since it is volatile and the factory below may run on another thread
        final var logCurr = log;
        try {
            // The path itself is what is opened, so the cache is given a factory that opens it, rather than the
            // default one, which would take the URI apart again and look for a file of that name
            return openThroughCache(key, logCurr, () -> {
                final var logNode = logCurr == null ? null : logCurr.log("Opening " + key);
                return recordPathKey(key,
                        Files.isDirectory(path) ? adopt(new DirRoot(this, path))
                                : adopt(new ArchiveRoot(this, /* container = */ null,
                                        JarOpener.openJarFromPath(path, session, logNode),
                                        /* packageRoot = */ "")));
            });
        } catch (final InterruptedException e) {
            session.interruptionChecker().interrupt();
            throw new IOException("Interrupted while opening " + key, e);
        }
    }

    /**
     * Open a directory or a jarfile named by a {@link URI}.
     *
     * @param uri
     *            the {@link URI} to open. A {@code "jar:"} or {@code "file:"} URI names something in the local
     *            filesystem; any other scheme is opened if the JVM has a handler for it and
     *            {@link VfsSpec#disableURLScheme(String)} has not denied it.
     * @return the opened root.
     * @throws IOException
     *             if the {@link URI} could not be opened or read, or if this {@link Vfs} has been closed.
     */
    public VfsRoot open(final URI uri) throws IOException {
        Assert.notNull(uri, "uri");
        return open(uri.toString());
    }

    /**
     * Open a directory or a jarfile named by a {@link URL}.
     *
     * @param url
     *            the {@link URL} to open. A {@code "jar:"} or {@code "file:"} URL names something in the local
     *            filesystem; any other scheme is opened if the JVM has a handler for it and
     *            {@link VfsSpec#disableURLScheme(String)} has not denied it.
     * @return the opened root.
     * @throws IOException
     *             if the {@link URL} could not be opened or read, or if this {@link Vfs} has been closed.
     */
    public VfsRoot open(final URL url) throws IOException {
        Assert.notNull(url, "url");
        return open(url.toString());
    }

    /**
     * Open a module.
     *
     * @param moduleReference
     *            the module to open.
     * @return the opened root.
     * @throws IOException
     *             if this {@link Vfs} has been closed. The module itself is not opened until its entries are listed
     *             or read.
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
     * Open a module of the boot {@link ModuleLayer} by name, e.g. {@code "java.logging"}.
     *
     * <p>
     * This reads a module that the JVM has already resolved, whether it came from the runtime image, from the
     * module path, or from an automatic module on the classpath.
     *
     * @param moduleName
     *            the name of the module to open.
     * @return the opened root.
     * @throws FileSystemNotFoundException
     *             if the boot layer has no module of that name.
     * @throws IOException
     *             if this {@link Vfs} has been closed. The module itself is not opened until its entries are listed
     *             or read.
     */
    public VfsRoot openModule(final String moduleName) throws IOException {
        return openModule(moduleName, ModuleLayer.boot());
    }

    /**
     * Open a module of a given {@link ModuleLayer} by name.
     *
     * <p>
     * The layer's own modules are searched first, then the modules of its parent layers, breadth-first, since a
     * layer can see the modules of the layers it was built on top of. The first module found with the given name
     * wins, which is the module a class loaded from that layer would resolve to.
     *
     * @param moduleName
     *            the name of the module to open.
     * @param layer
     *            the layer to resolve the name in.
     * @return the opened root.
     * @throws FileSystemNotFoundException
     *             if neither the layer nor any of its ancestors has a module of that name.
     * @throws IOException
     *             if this {@link Vfs} has been closed. The module itself is not opened until its entries are listed
     *             or read.
     */
    public VfsRoot openModule(final String moduleName, final ModuleLayer layer) throws IOException {
        Assert.notNull(moduleName, "moduleName");
        Assert.notNull(layer, "layer");
        checkNotClosed(moduleName);
        final var moduleReference = findModule(moduleName, layer);
        if (moduleReference == null) {
            throw new FileSystemNotFoundException("No module named " + moduleName + " in the module layer");
        }
        return open(moduleReference);
    }

    /**
     * Find a module by name in a layer, or, failing that, in the layer's ancestors.
     *
     * @param moduleName
     *            the name of the module to find.
     * @param layer
     *            the layer to search.
     * @return the module, or null if no layer reachable from the given one has a module of that name.
     */
    private static @Nullable ModuleReference findModule(final String moduleName, final ModuleLayer layer) {
        // Breadth-first, so that a module of the layer itself shadows one of the same name in an ancestor, and so
        // that a diamond of layers does not search the shared ancestor more than once
        final var toSearch = new ArrayDeque<ModuleLayer>();
        final var alreadySearched = Collections.newSetFromMap(new IdentityHashMap<ModuleLayer, Boolean>());
        toSearch.add(layer);
        alreadySearched.add(layer);
        while (!toSearch.isEmpty()) {
            final var currLayer = toSearch.remove();
            for (final var resolvedModule : currLayer.configuration().modules()) {
                if (resolvedModule.name().equals(moduleName)) {
                    return resolvedModule.reference();
                }
            }
            for (final var parent : currLayer.parents()) {
                if (alreadySearched.add(parent)) {
                    toSearch.add(parent);
                }
            }
        }
        return null;
    }

    /**
     * Open a jarfile read from an {@link InputStream}. The stream is read to the end, into RAM or into a temporary
     * file if it is longer than the maximum buffered jar RAM size this {@link Vfs} was constructed with, since a
     * zipfile's central directory is at the end of the file and so cannot be reached by reading forwards.
     *
     * <p>
     * Unlike the other {@code open} methods, this one does not cache what it opens, since each call reads a
     * different stream.
     *
     * @param inputStream
     *            the stream to read the jarfile from. The caller retains ownership of the stream, and this method
     *            does not close it.
     * @param name
     *            a name for the jarfile, which is used in log messages and in the paths of its entries.
     * @return the opened root.
     * @throws IOException
     *             if the jarfile could not be read, or if this {@link Vfs} has been closed.
     */
    public VfsRoot open(final InputStream inputStream, final String name) throws IOException {
        Assert.notNull(inputStream, "inputStream");
        Assert.notNull(name, "name");
        checkNotClosed(name);
        final var logNode = log == null ? null : log.log("Reading " + name + " from an InputStream");
        try {
            return discardIfClosed(name,
                    adopt(new ArchiveRoot(
                            this, /* container = */ null, JarOpener.openJarFromInputStream(inputStream,
                                    /* inputStreamLengthHint = */ -1L, name, session, logNode),
                            /* packageRoot = */ "")));
        } catch (final InterruptedException e) {
            session.interruptionChecker().interrupt();
            throw new IOException("Interrupted while reading " + name, e);
        }
    }

    /**
     * Open a jarfile held in a byte array.
     *
     * <p>
     * Unlike the other {@code open} methods, this one does not cache what it opens, since each call may be given
     * different bytes.
     *
     * @param jarBytes
     *            the bytes of the jarfile. The caller retains ownership of the array, and this method does not
     *            modify it.
     * @param name
     *            a name for the jarfile, which is used in log messages and in the paths of its entries.
     * @return the opened root.
     * @throws IOException
     *             if the jarfile could not be read, or if this {@link Vfs} has been closed.
     */
    public VfsRoot open(final byte[] jarBytes, final String name) throws IOException {
        Assert.notNull(jarBytes, "jarBytes");
        Assert.notNull(name, "name");
        checkNotClosed(name);
        final var logNode = log == null ? null : log.log("Reading " + name + " from a byte array");
        try {
            return discardIfClosed(name,
                    adopt(new ArchiveRoot(this, /* container = */ null,
                            JarOpener.openJarFromInputStream(new ByteArrayInputStream(jarBytes), jarBytes.length,
                                    name, session, logNode),
                            /* packageRoot = */ "")));
        } catch (final InterruptedException e) {
            session.interruptionChecker().interrupt();
            throw new IOException("Interrupted while reading " + name, e);
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Returns an iterator over the roots this {@link Vfs} has cached, so that the roots can be iterated directly,
     * just as the entries of a root can:
     *
     * <pre>
     * for (VfsRoot root : vfs) {
     *     System.out.println(root.getPath());
     * }
     * </pre>
     *
     * <p>
     * The roots come back sorted by the path they report themselves at, and each root is reported once, however
     * many paths it was opened by. A root read from an {@link InputStream} or from a byte array is not included,
     * since there is no path that names it, so it is not cached: reading the same bytes again builds a new root. A
     * jarfile that was only opened because a nested jarfile or a package root within it was asked for is included,
     * since it is a root in its own right, cached under its own path.
     *
     * <p>
     * The iterator is a snapshot taken when this method is called, so a root opened or evicted by another thread
     * afterwards does not change what it reports. A root that another thread is still opening is not included,
     * since it is not open yet. A closed {@link Vfs} has nothing cached, so iterating one reports nothing.
     *
     * @return an iterator over the roots this {@link Vfs} has cached.
     */
    @Override
    public Iterator<VfsRoot> iterator() {
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
     * Remove a root from the cache of this {@link Vfs}, so that the memory its list of entries occupies can be
     * reclaimed once nothing is using the root any more, and so that opening the same path again builds a new root.
     *
     * <p>
     * This does not stop the root working. Anything still holding it -- another thread, or another part of the
     * program that opened the same path and got the same root back -- goes on reading through it exactly as before,
     * and it becomes garbage only once the last of them lets go of it. Whatever the root itself owns is released
     * when the root is closed, which {@link #close()} does to every root this {@link Vfs} has opened, evicted or
     * not; to release a root's resources now rather than then, close the root instead of evicting it -- see
     * {@link VfsRoot#close()}.
     *
     * <p>
     * There is no need to evict anything unless a long-lived {@link Vfs} opens a great many roots, since a
     * {@link Vfs} holds every root it has opened until it is closed. Evicting a root that this {@link Vfs} does not
     * have cached, because it was already evicted or was opened from a stream or a byte array, has no effect.
     *
     * @param root
     *            the root to remove from the cache.
     */
    public void evict(final VfsRoot root) {
        if (!session.isClosed()) {
            // A root can be cached under more than one key, e.g. under both the path it was opened from and the
            // canonical path of the file that turned out to back it
            rootsByPath.discardValue(root);
            rootsByModule.values().removeIf(cachedRoot -> cachedRoot == root);
        }
        // If this Vfs is already closing, the caches are cleared wholesale by close(), so there is nothing to do
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Throw an {@link IOException} if this {@link Vfs} has been closed.
     *
     * @param what
     *            what was being read, for the error message.
     * @throws IOException
     *             if this {@link Vfs} has been closed.
     */
    void checkNotClosed(final String what) throws IOException {
        if (session.isClosed()) {
            throw new IOException("Cannot read " + what + " after the Vfs has been closed");
        }
    }

    /**
     * Throw an {@link IllegalStateException} if this {@link Vfs} has been closed. This is for the configuration
     * methods, which cannot throw a checked exception.
     *
     * @throws IllegalStateException
     *             if this {@link Vfs} has been closed.
     */
    private void checkOpen() {
        if (session.isClosed()) {
            throw new IllegalStateException("Cannot configure a Vfs after it has been closed");
        }
    }

    /**
     * Returns whether this {@link Vfs} has been closed.
     *
     * @return true if this {@link Vfs} has been closed.
     */
    boolean isClosed() {
        return session.isClosed();
    }

    /**
     * Returns the session that the roots opened by this {@link Vfs} are backed by.
     *
     * @return the session.
     */
    VfsSession session() {
        return session;
    }

    /**
     * Returns the log node.
     *
     * @return the log node, or null if not logging.
     */
    @Nullable
    LogNode log() {
        return log;
    }

    /**
     * Check whether anything read through this {@link Vfs} had to be extracted to a temporary file. A nested
     * jarfile that is compressed, or that is too large to buffer in RAM, is extracted to a temporary file, which is
     * deleted when this {@link Vfs} is closed.
     *
     * @return true if at least one temporary file was created and has not yet been deleted, or false if none was
     *         created, or if this {@link Vfs} has been closed.
     */
    public boolean hasTempFiles() {
        // The temporary files are deleted by close(), so a closed Vfs has none
        return !session.isClosed() && session.hasTempFiles();
    }

    /**
     * Release the file handles and memory mappings that back the roots opened by this {@link Vfs}, and delete any
     * temporary files that were created. Every {@link VfsRoot} and {@link VfsEntry} that was handed out is
     * invalidated: reading one of them, or an {@link InputStream} that was already open on one of them, throws
     * {@link IOException}, and reading through a {@link java.nio.file.Path} of one of them throws
     * {@link java.nio.file.ClosedFileSystemException}. A read that was in flight in another thread when this was
     * called fails the same way, rather than returning content that was read from a file that has since been
     * released. A {@link java.nio.ByteBuffer} that {@link VfsEntry#read()} handed out must not be read after this
     * either -- see {@link CloseableByteBuffer}.
     *
     * <p>
     * Every file that was memory mapped is unmapped before this returns, except one that a
     * {@link CloseableByteBuffer} the caller has not closed yet is still a view of, which stays mapped until the
     * last such buffer is closed. Windows refuses to delete, rename or overwrite a file while it is mapped, so a
     * close that returned with the files it had mapped still mapped would leave them locked. On Windows, if a file
     * was left mapped, this also asks the garbage collector to run, which is the only other thing that can unmap
     * one; nothing is asked for on any other operating system, where a mapped file can be deleted or replaced
     * anyway.
     *
     * <p>
     * Closing an already-closed {@link Vfs} has no effect.
     */
    @Override
    public void close() {
        // Only the thread that performs the close flushes the log, so that a second thread cannot print a
        // half-written log while the first is still adding entries to it
        doClose(log, /* flushLogAfterwards = */ true);
    }

    /**
     * Close this {@link Vfs}, logging to the given log node rather than to the one {@link #verbose()} turned on.
     * This is for callers that write a log of their own, and want what the virtual filesystem logs nested under the
     * part of it that it belongs to.
     *
     * <p>
     * Unlike {@link #close()}, this does not flush the log node afterwards, since the caller owns the log tree that
     * it belongs to and flushes it when the whole of it has been written.
     *
     * @param logNode
     *            the log node, or null to not log.
     */
    public void close(final @Nullable LogNode logNode) {
        doClose(logNode, /* flushLogAfterwards = */ false);
    }

    /**
     * Close this {@link Vfs}, if it is not already closed.
     *
     * @param logNode
     *            the log node, or null to not log.
     * @param flushLogAfterwards
     *            true to flush the log node once the close is complete, which only the caller that owns the log
     *            node does.
     */
    private void doClose(final @Nullable LogNode logNode, final boolean flushLogAfterwards) {
        // The session is marked closed atomically, so that a second call (or a concurrent one) returns rather than
        // releasing the same resources twice, and so that a thread calling any other method the moment a close
        // starts is turned away. It is marked before anything is released, since it is what every root checks
        // before reading, so a thread that is midway through a read cannot get at storage that is being released
        // out from under it.
        if (!session.beginClose()) {
            return;
        }
        try {
            try {
                // The caches have to be dropped before the resources behind them are released, so that nothing can
                // be handed a root that is about to be closed
                rootsByPath.clear();
                rootsByModule.clear();
                // Close every root this Vfs opened, releasing what each root itself owns: a module root's pooled
                // readers are closed here, before the session teardown below releases the file handles, memory
                // mappings and temporary files that the roots were read through. VfsRoot#close never throws, so
                // one root that fails to release does not stop the rest from being closed
                final var rootsToClose = new ArrayList<>(openRoots);
                openRoots.clear();
                for (final var root : rootsToClose) {
                    root.close(logNode);
                }
            } finally {
                // The session teardown is what releases every file handle, memory mapping and temporary file that
                // the roots were read through, so it runs even if dropping the caches failed
                session.close(logNode);
            }
        } finally {
            if (flushLogAfterwards && logNode != null) {
                logNode.flush();
            }
        }
    }
}
