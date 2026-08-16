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
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.classgraph.base.internal.concurrency.InterruptionChecker;
import io.github.classgraph.base.internal.concurrency.SingletonMap.NewInstanceException;
import io.github.classgraph.base.internal.concurrency.SingletonMap.NullSingletonException;
import io.github.classgraph.base.internal.utils.Assert;
import io.github.classgraph.base.internal.utils.FastPathResolver;
import io.github.classgraph.base.internal.utils.JarUtils;
import io.github.classgraph.base.internal.utils.LogNode;
import io.github.classgraph.vfs.internal.ScanResources;
import io.github.classgraph.vfs.internal.spec.VfsScanSpec;
import io.github.classgraph.vfs.internal.zip.NestedJarHandler;
import org.jspecify.annotations.Nullable;

/**
 * A virtual filesystem: opens a directory, a jarfile, or a module, however it is named -- by path string, by
 * {@link File}, by {@link Path}, by {@link URI}, by {@link URL}, by {@link ModuleReference}, as an
 * {@link InputStream}, or as a byte array -- and gives back the same {@link VfsRoot} interface for all of them.
 *
 * <pre>
 * try (Vfs vfs = new Vfs(); VfsRoot root = vfs.open("outer.jar!/lib/inner.jar")) {
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
 * jarfile that encloses several nested jarfiles is only read once. The cache, the open file handles and the
 * temporary files are all released by {@link #close()}, which invalidates every {@link VfsRoot} and
 * {@link VfsEntry} it handed out, so a {@link Vfs} should be held open for as long as its entries are being read.
 *
 * <p>
 * Every method is safe to call from multiple threads at once. Two threads that ask for the same path at the same
 * time get back the same {@link VfsRoot}, and the jarfile behind it is only read once. How storage is read is fixed
 * at construction, so there are no settings for one thread to change while another is reading, and
 * {@link #verbose()}, which only turns on logging, is synchronized like every other method. {@link #close()} takes
 * effect the moment it is called, so a thread that calls any other method after that -- even while the close is
 * still running -- gets an {@link IOException} from an {@code open} method, or an {@link IllegalStateException}
 * from {@link #verbose()}, rather than a root backed by storage that is being released.
 */
public class Vfs implements AutoCloseable {
    /** The default value of the {@code enableNestedJars} constructor parameter. */
    public static final boolean DEFAULT_ENABLE_NESTED_JARS = VfsScanSpec.DEFAULT_ENABLE_NESTED_JARS;

    /** The default value of the {@code enableMultiReleaseVersions} constructor parameter. */
    public static final boolean DEFAULT_ENABLE_MULTI_RELEASE_VERSIONS = //
            VfsScanSpec.DEFAULT_ENABLE_MULTI_RELEASE_VERSIONS;

    /** The default value of the {@code maxBufferedJarRAMSize} constructor parameter, in bytes. */
    public static final int DEFAULT_MAX_BUFFERED_JAR_RAM_SIZE = VfsScanSpec.DEFAULT_MAX_BUFFERED_JAR_RAM_SIZE;

    /** The handler that opens jarfiles and owns the resources they are backed by. */
    private final NestedJarHandler nestedJarHandler;

    /** The roots that have been opened from a path, keyed by the path they were opened from. */
    private final Map<String, VfsRoot> rootsByPath = new ConcurrentHashMap<>();

    /** The roots that have been opened from a {@link ModuleReference}, keyed by that module. */
    private final Map<ModuleReference, VfsRoot> rootsByModule = new ConcurrentHashMap<>();

    /** The log node, or null if not logging. */
    private volatile @Nullable LogNode log;

    /** True once {@link #close()} has been called. */
    private final AtomicBoolean closed = new AtomicBoolean();

    /**
     * Constructor, using the default value of every option -- see {@link #Vfs(boolean, boolean, Collection, int)}.
     */
    public Vfs() {
        this(new VfsScanSpec(), new InterruptionChecker(), /* log = */ null);
    }

    /**
     * Constructor, setting every option. The options cannot be changed afterwards, since a {@link Vfs} is meant to
     * be shared between threads.
     *
     * @param enableNestedJars
     *            whether to open jarfiles nested within other jarfiles, so that a path containing {@code "!/"} can
     *            name a jarfile within a jarfile, and not just a package root within a jarfile. Default:
     *            {@value #DEFAULT_ENABLE_NESTED_JARS}.
     * @param enableMultiReleaseVersions
     *            whether to report every version of a multi-release jarfile's entries, rather than only the newest
     *            version of each entry that this JVM can run. Default:
     *            {@value #DEFAULT_ENABLE_MULTI_RELEASE_VERSIONS}.
     * @param urlSchemes
     *            the URL schemes that jarfiles may be opened from, as well as from the local filesystem, e.g.
     *            {@code Set.of("https")}. Scheme names only, without the trailing {@code ':'}. The {@code file:}
     *            and {@code jar:} schemes are always allowed. A jarfile read from a URL is downloaded in full
     *            before its entries can be read, since a zipfile's central directory is at the end of the file. May
     *            be null or empty, which allows no scheme beyond the two that are always allowed, which is the
     *            default.
     * @param maxBufferedJarRAMSize
     *            the number of bytes of a jarfile that may be held in RAM before it is spilled to a temporary file
     *            on disk. This only applies to jarfiles that cannot be read in place: a nested jarfile that is
     *            stored deflated rather than uncompressed, a jarfile downloaded from a URL, and a jarfile read from
     *            an {@link InputStream}. Default: {@value #DEFAULT_MAX_BUFFERED_JAR_RAM_SIZE} bytes (64MB), i.e.
     *            writing to disk is avoided wherever possible.
     * @throws IllegalArgumentException
     *             if any of {@code urlSchemes} is shorter than two characters (a one-character scheme cannot be
     *             told apart from a Windows drive letter) or is not a valid URL scheme, or if
     *             {@code maxBufferedJarRAMSize} is negative.
     */
    public Vfs(final boolean enableNestedJars, final boolean enableMultiReleaseVersions,
            final @Nullable Collection<String> urlSchemes, final int maxBufferedJarRAMSize) {
        this(newVfsScanSpec(enableNestedJars, enableMultiReleaseVersions, urlSchemes, maxBufferedJarRAMSize),
                new InterruptionChecker(), /* log = */ null);
    }

    /**
     * Build the settings for the public constructor that sets every option, validating them before anything is
     * constructed.
     *
     * @param enableNestedJars
     *            whether to open jarfiles nested within other jarfiles.
     * @param enableMultiReleaseVersions
     *            whether to report every version of a multi-release jarfile's entries.
     * @param urlSchemes
     *            the URL schemes that jarfiles may be opened from, or null for none.
     * @param maxBufferedJarRAMSize
     *            the number of bytes of a jarfile that may be held in RAM before it is spilled to disk.
     * @return the settings.
     */
    private static VfsScanSpec newVfsScanSpec(final boolean enableNestedJars,
            final boolean enableMultiReleaseVersions, final @Nullable Collection<String> urlSchemes,
            final int maxBufferedJarRAMSize) {
        if (maxBufferedJarRAMSize < 0) {
            throw new IllegalArgumentException("maxBufferedJarRAMSize cannot be negative");
        }
        final var vfsScanSpec = new VfsScanSpec();
        vfsScanSpec.enableNestedJars = enableNestedJars;
        vfsScanSpec.enableMultiReleaseVersions = enableMultiReleaseVersions;
        if (urlSchemes != null) {
            for (final var scheme : urlSchemes) {
                vfsScanSpec.enableURLScheme(scheme);
            }
        }
        vfsScanSpec.maxBufferedJarRAMSize = maxBufferedJarRAMSize;
        return vfsScanSpec;
    }

    /**
     * Constructor for the other ClassGraph modules, which configure the virtual filesystem through a
     * {@link VfsScanSpec} built from their own API rather than through the methods of this class, and which share
     * an {@link InterruptionChecker} and a {@link LogNode} with the rest of a scan.
     *
     * <p>
     * The parameter types are in packages that are only exported to those modules, so this constructor cannot be
     * called from anywhere else, and it is not part of the API.
     *
     * @param vfsScanSpec
     *            everything the virtual filesystem is configured with.
     * @param interruptionChecker
     *            the interruption checker to share with the rest of the scan.
     * @param log
     *            the log node, or null to not log.
     * @hidden
     */
    public Vfs(final VfsScanSpec vfsScanSpec, final InterruptionChecker interruptionChecker,
            final @Nullable LogNode log) {
        // The settings are held by the handler, which is what reads with them
        this.nestedJarHandler = new NestedJarHandler(vfsScanSpec, interruptionChecker);
        this.log = log;
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
     * The path may name a directory or a jarfile in the local filesystem, or a URL with a scheme that this
     * {@link Vfs} was constructed with. A jarfile nested within another jarfile is named by separating the
     * enclosing jarfile from the nested one with {@code "!/"}, to any depth, e.g.
     * {@code "outer.jar!/lib/inner.jar"}. A trailing {@code "!/"} section that does not name a nested jarfile names
     * a package root within the jarfile instead, e.g. {@code "spring-boot-app.jar!/BOOT-INF/classes"}, in which
     * case only the entries under that root are reported, with the root stripped from their names.
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
     * Open a directory or a jarfile named by a path, logging to the given log node rather than to the one this
     * {@link Vfs} was given. This is for the other ClassGraph modules, which nest what the virtual filesystem logs
     * under the part of the scan log that it belongs to, and is not part of the API.
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
     * @hidden
     */
    public VfsRoot open(final String path, final @Nullable LogNode logNode) throws IOException {
        Assert.notNull(path, "path");
        checkNotClosed(path);
        // The resolved path is the cache key, not the path as it was written: the same directory or jarfile can be
        // named as a plain path, as a "file:" or "jar:" URL, with a trailing separator, or -- on Windows -- with
        // backslashes rather than forward slashes, and all of those name one thing that is opened once
        final var resolvedPath = FastPathResolver.resolve(path);
        // computeIfAbsent is not used, because the mapping function must not itself open other jarfiles (the
        // enclosing jarfiles of a nested one are opened on the way to it, which would be a recursive update)
        final var alreadyOpened = rootsByPath.get(resolvedPath);
        if (alreadyOpened != null) {
            return alreadyOpened;
        }
        final var root = openUncached(resolvedPath, logNode == null ? null : logNode.log("Opening " + path));
        final var alreadyOpenedUnderReportedPath = openedUnderReportedPath(root, resolvedPath);
        if (alreadyOpenedUnderReportedPath != null) {
            // The path names something that is already open under another name, so the root just opened is a second
            // view of it, and is dropped in favour of the one already open. (It is not closed: it was never handed
            // out, it holds nothing that needs releasing, and closing it would evict the root it duplicates from
            // the cache of the paths that root was opened from.)
            return cacheRoot(rootsByPath, resolvedPath, alreadyOpenedUnderReportedPath, path);
        }
        final var cachedRoot = cacheRoot(rootsByPath, resolvedPath, root, path);
        cacheRootUnderReportedPath(cachedRoot, resolvedPath);
        return cachedRoot;
    }

    /**
     * Return the root that is already open at the path a root that has just been opened reports itself at, if there
     * is one. A root is named by the canonical path of the directory or jarfile that backs it, which is not always
     * the path it was opened from, so two paths that reach the same thing -- through a symlink, or, on Windows,
     * through an 8.3 short name -- open one root under two names, and the second one to be opened is redundant.
     *
     * @param root
     *            the root that has just been opened.
     * @param openedFrom
     *            the resolved path the root was opened from.
     * @return the root already open at the path {@code root} reports itself at, or null if there is none, or if
     *         {@code root} reports itself at the path it was opened from.
     */
    private @Nullable VfsRoot openedUnderReportedPath(final VfsRoot root, final String openedFrom) {
        final var reportedPath = root.reportedPath();
        return reportedPath.equals(openedFrom) ? null : rootsByPath.get(reportedPath);
    }

    /**
     * Cache a root under the path it reports itself at, as well as under the path it was opened from. A root is
     * named by the canonical path of the directory or jarfile that backs it, which is not always the path it was
     * opened from: a symlink, or -- on Windows -- a path written in 8.3 short form, reaches the same file under
     * another name. Without this, handing the path a root reports back to {@link #open(String)} would read the same
     * directory or jarfile a second time.
     *
     * @param root
     *            the root, already cached under the path it was opened from.
     * @param openedFrom
     *            the resolved path the root was opened from.
     */
    private void cacheRootUnderReportedPath(final VfsRoot root, final String openedFrom) {
        final var reportedPath = root.reportedPath();
        if (!reportedPath.equals(openedFrom)) {
            rootsByPath.putIfAbsent(reportedPath, root);
            if (closed.get()) {
                // close() cleared the caches between the two keys being added, so it did not see this one -- take
                // it back out again, rather than leaving a root in the cache of a closed Vfs
                rootsByPath.remove(reportedPath, root);
            }
        }
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
        final var cachedRoot = openedByAnotherThread == null ? root : openedByAnotherThread;
        if (closed.get()) {
            // close() cleared the caches while this root was being opened, so it did not see this root -- take it
            // back out again, rather than leaving a root in the cache of a closed Vfs
            cache.remove(key, cachedRoot);
        }
        return discardIfClosed(what, cachedRoot);
    }

    /**
     * Return a root that has just been opened, unless this {@link Vfs} was closed while it was being opened, in
     * which case close the root and throw, rather than handing back a root that is backed by storage that has
     * already been released.
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
        if (closed.get()) {
            root.close();
            throw new IOException("Cannot read " + what + " after the Vfs has been closed");
        }
        return root;
    }

    /**
     * Open a directory or a jarfile named by a path, without consulting or updating the cache of opened roots.
     *
     * @param resolvedPath
     *            the path to open, in the form {@link FastPathResolver#resolve(String)} returns it, so that the
     *            nested jar handler is keyed by the same spelling of the path that the cache of opened roots is.
     * @param logNode
     *            the log node, or null to skip logging.
     * @return the opened root.
     * @throws IOException
     *             if the path could not be opened or read.
     */
    private VfsRoot openUncached(final String resolvedPath, final @Nullable LogNode logNode) throws IOException {
        // A path with a "!/" section in it names something within a jarfile, and a path with a URL scheme names a
        // jarfile to download, so neither can be a directory
        if (JarUtils.lastIndexOfNestedJarSeparator(resolvedPath) < 0
                && !JarUtils.URL_SCHEME_PATTERN.matcher(resolvedPath).matches()) {
            Path dir;
            try {
                dir = Path.of(resolvedPath);
            } catch (final InvalidPathException e) {
                // A path that this filesystem cannot represent is not a directory, so try opening it as a jarfile,
                // which reports the failure with the reason it could not be read
                dir = null;
            }
            if (dir != null && Files.isDirectory(dir)) {
                return new DirRoot(this, dir);
            }
        }
        try {
            // The nested jar handler caches the logical zipfile, so two threads that race here open it only once,
            // and both get the same LogicalZipFile back
            final var logicalZipFileAndPackageRoot = nestedJarHandler.nestedPathToLogicalZipFileAndPackageRootMap()
                    .get(resolvedPath, logNode);
            return new ArchiveRoot(this, logicalZipFileAndPackageRoot.getKey(),
                    logicalZipFileAndPackageRoot.getValue());
        } catch (final NullSingletonException | NewInstanceException e) {
            // Chain the cause, as well as naming it in the message -- otherwise the reason the path could not be
            // opened is not reachable from the stack trace
            final var cause = e.getCause() == null ? e : e.getCause();
            throw new IOException("Could not open " + resolvedPath + " : " + cause, cause);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while opening " + resolvedPath);
        }
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
        final var alreadyOpened = rootsByPath.get(key);
        if (alreadyOpened != null) {
            return alreadyOpened;
        }
        final var logNode = log == null ? null : log.log("Opening " + key);
        final VfsRoot root;
        if (Files.isDirectory(path)) {
            root = new DirRoot(this, path);
        } else {
            try {
                root = new ArchiveRoot(this, nestedJarHandler.openJarFromPath(path, logNode), "");
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while opening " + key);
            }
        }
        return cacheRoot(rootsByPath, key, root, key);
    }

    /**
     * Open a directory or a jarfile named by a {@link URI}.
     *
     * @param uri
     *            the {@link URI} to open. A {@code "jar:"} or {@code "file:"} URI names something in the local
     *            filesystem; any other scheme has to be one this {@link Vfs} was constructed with.
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
     *            filesystem; any other scheme has to be one this {@link Vfs} was constructed with.
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
        return cacheRoot(rootsByModule, moduleReference, new ModuleRoot(this, moduleReference), moduleName);
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
            return discardIfClosed(name, new ArchiveRoot(this, nestedJarHandler.openJarFromInputStream(inputStream,
                    /* inputStreamLengthHint = */ -1L, name, logNode), /* packageRoot = */ ""));
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while reading " + name);
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
            return discardIfClosed(name, new ArchiveRoot(this, nestedJarHandler.openJarFromInputStream(
                    new ByteArrayInputStream(jarBytes), jarBytes.length, name, logNode), /* packageRoot = */ ""));
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while reading " + name);
        }
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
        if (closed.get()) {
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
        if (closed.get()) {
            throw new IllegalStateException("Cannot configure a Vfs after it has been closed");
        }
    }

    /**
     * Returns whether this {@link Vfs} has been closed.
     *
     * @return true if this {@link Vfs} has been closed.
     */
    boolean isClosed() {
        return closed.get();
    }

    /**
     * Returns the resources that the roots opened by this {@link Vfs} are backed by.
     *
     * @return the resources.
     */
    ScanResources scanResources() {
        return nestedJarHandler.scanResources;
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
     * Remove a root from the cache, so that opening the path it was opened from builds a new root. Called by
     * {@link VfsRoot#close()}.
     *
     * @param root
     *            the root that was closed.
     */
    void rootClosed(final VfsRoot root) {
        if (!closed.get()) {
            // A root can be cached under more than one key, e.g. under both the path it was opened from and the
            // canonical path of the file that turned out to back it
            rootsByPath.values().removeIf(cachedRoot -> cachedRoot == root);
            rootsByModule.values().removeIf(cachedRoot -> cachedRoot == root);
        }
        // If this Vfs is already closing, the caches are cleared wholesale by close(), so there is nothing to do
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
        return !closed.get() && nestedJarHandler.scanResources.hasTempFiles();
    }

    /**
     * Close every root that was opened by this {@link Vfs}, release the file handles and memory mappings that back
     * them, and delete any temporary files that were created. Every {@link VfsRoot} and {@link VfsEntry} that was
     * handed out is invalidated, and any {@link InputStream} still being read from one of them will stop returning
     * data.
     *
     * <p>
     * Closing an already-closed {@link Vfs} has no effect.
     */
    @Override
    public void close() {
        final var logCurr = log;
        // Only the thread that performs the close flushes the log, so that a second thread cannot print a
        // half-written log while the first is still adding entries to it
        if (doClose(logCurr) && logCurr != null) {
            logCurr.flush();
        }
    }

    /**
     * Close this {@link Vfs}, logging to the given log node rather than to the one it was given. This is for the
     * other ClassGraph modules, which nest what the virtual filesystem logs under the part of the scan log that it
     * belongs to, and is not part of the API.
     *
     * @param logNode
     *            the log node, or null to not log.
     * @hidden
     */
    public void close(final @Nullable LogNode logNode) {
        doClose(logNode);
    }

    /**
     * Close this {@link Vfs}, if it is not already closed.
     *
     * @param logNode
     *            the log node, or null to not log.
     * @return true if this call closed the {@link Vfs}, or false if it was already closed by an earlier or
     *         concurrent call.
     */
    private boolean doClose(final @Nullable LogNode logNode) {
        // Mark this Vfs as closed before closing the roots, so that each root knows the caches are about to be
        // cleared wholesale and does not remove itself from them one at a time. The flag is set atomically, so that
        // a second call (or a concurrent one) returns rather than closing the same roots twice, and so that a
        // thread calling any other method the moment a close starts is turned away.
        if (closed.getAndSet(true)) {
            return false;
        }
        for (final var root : rootsByPath.values()) {
            root.close();
        }
        for (final var root : rootsByModule.values()) {
            root.close();
        }
        rootsByPath.clear();
        rootsByModule.clear();
        nestedJarHandler.close(logNode);
        return true;
    }
}
