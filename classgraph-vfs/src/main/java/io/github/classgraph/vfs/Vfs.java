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
import java.io.Closeable;
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
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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
 * try (Vfs vfs = new Vfs()) {
 *     VfsRoot root = vfs.open("outer.jar!/lib/inner.jar");
 *     for (VfsEntry entry : root.getEntries()) {
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
 * The {@code open} methods are safe to call from multiple threads at once: two threads that ask for the same path
 * at the same time get the same {@link VfsRoot}, and only one of them does the work of reading it. The
 * configuration methods are not thread-safe, and are intended to be called before the first call to {@code open}.
 */
public class Vfs implements Closeable {
    /** Everything this virtual filesystem is configured with. */
    private final VfsScanSpec vfsScanSpec = new VfsScanSpec();

    /** The handler that opens jarfiles and owns the resources they are backed by. */
    private final NestedJarHandler nestedJarHandler;

    /** The roots that have been opened from a path, keyed by the path they were opened from. */
    private final Map<String, VfsRoot> rootsByPath = new ConcurrentHashMap<>();

    /** The roots that have been opened from a {@link ModuleReference}, keyed by that module. */
    private final Map<ModuleReference, VfsRoot> rootsByModule = new ConcurrentHashMap<>();

    /** The log node, or null if not logging. */
    private @Nullable LogNode log;

    /** True once {@link #close()} has been called. */
    private volatile boolean closed;

    /** Constructor. */
    public Vfs() {
        this.nestedJarHandler = new NestedJarHandler(vfsScanSpec, new InterruptionChecker());
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
     */
    public Vfs verbose() {
        if (log == null) {
            log = new LogNode();
        }
        return this;
    }

    /**
     * Do not open jarfiles nested within other jarfiles, so that a path containing {@code "!/"} can only name a
     * package root within a jarfile, not a jarfile within a jarfile.
     *
     * @return this (for method chaining).
     */
    public Vfs disableNestedJars() {
        vfsScanSpec.scanNestedJars = false;
        return this;
    }

    /**
     * Report every version of a multi-release jarfile's entries, rather than only the newest version of each entry
     * that this JVM can run.
     *
     * @return this (for method chaining).
     */
    public Vfs enableMultiReleaseVersions() {
        vfsScanSpec.enableMultiReleaseVersions = true;
        return this;
    }

    /**
     * Allow jarfiles to be opened from URLs with the given scheme, as well as from the local filesystem. The
     * {@code file:} and {@code jar:} schemes are always allowed.
     *
     * <p>
     * A jarfile read from a URL is downloaded in full before its entries can be read, since a zipfile's central
     * directory is at the end of the file.
     *
     * @param scheme
     *            the URL scheme to allow, e.g. {@code "https"}. The scheme name only, without the trailing
     *            {@code ':'}.
     * @return this (for method chaining).
     * @throws IllegalArgumentException
     *             if {@code scheme} is shorter than two characters (a one-character scheme cannot be told apart
     *             from a Windows drive letter), or is not a valid URL scheme.
     */
    public Vfs enableURLScheme(final String scheme) {
        vfsScanSpec.enableURLScheme(scheme);
        return this;
    }

    /**
     * Set the number of bytes of a jarfile that may be held in RAM before it is spilled to a temporary file on
     * disk. This only applies to jarfiles that cannot be read in place: a nested jarfile that is stored deflated
     * rather than uncompressed, a jarfile downloaded from a URL, and a jarfile read from an {@link InputStream}.
     *
     * <p>
     * The default is 64MB, i.e. writing to disk is avoided wherever possible.
     *
     * @param maxBufferedJarRAMSize
     *            the maximum number of bytes to hold in RAM.
     * @return this (for method chaining).
     * @throws IllegalArgumentException
     *             if {@code maxBufferedJarRAMSize} is negative.
     */
    public Vfs maxBufferedJarRAMSize(final int maxBufferedJarRAMSize) {
        if (maxBufferedJarRAMSize < 0) {
            throw new IllegalArgumentException("maxBufferedJarRAMSize cannot be negative");
        }
        vfsScanSpec.maxBufferedJarRAMSize = maxBufferedJarRAMSize;
        return this;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Open a directory or a jarfile named by a path.
     *
     * <p>
     * The path may name a directory or a jarfile in the local filesystem, or a URL with an allowed scheme (see
     * {@link #enableURLScheme(String)}). A jarfile nested within another jarfile is named by separating the
     * enclosing jarfile from the nested one with {@code "!/"}, to any depth, e.g.
     * {@code "outer.jar!/lib/inner.jar"}. A trailing {@code "!/"} section that does not name a nested jarfile names
     * a package root within the jarfile instead, e.g. {@code "spring-boot-app.jar!/BOOT-INF/classes"}, in which
     * case only the entries under that root are reported, with the root stripped from their names.
     *
     * @param path
     *            the path to open.
     * @return the opened root.
     * @throws IOException
     *             if the path could not be opened or read, or if this {@link Vfs} has been closed.
     */
    public VfsRoot open(final String path) throws IOException {
        Assert.notNull(path, "path");
        checkNotClosed(path);
        // computeIfAbsent is not used, because the mapping function must not itself open other jarfiles (the
        // enclosing jarfiles of a nested one are opened on the way to it, which would be a recursive update)
        final var alreadyOpened = rootsByPath.get(path);
        if (alreadyOpened != null) {
            return alreadyOpened;
        }
        final var logNode = log == null ? null : log.log("Opening " + path);
        final var root = openUncached(path, logNode);
        final var openedByAnotherThread = rootsByPath.putIfAbsent(path, root);
        return openedByAnotherThread == null ? root : openedByAnotherThread;
    }

    /**
     * Open a directory or a jarfile named by a path, without consulting or updating the cache of opened roots.
     *
     * @param path
     *            the path to open.
     * @param logNode
     *            the log node, or null to skip logging.
     * @return the opened root.
     * @throws IOException
     *             if the path could not be opened or read.
     */
    private VfsRoot openUncached(final String path, final @Nullable LogNode logNode) throws IOException {
        // Strip any "jar:" and "file:" prefix, and normalize the path separators
        final var resolvedPath = FastPathResolver.resolve(path);
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
                    .get(path, logNode);
            return new ArchiveRoot(this, logicalZipFileAndPackageRoot.getKey(),
                    logicalZipFileAndPackageRoot.getValue());
        } catch (final NullSingletonException | NewInstanceException e) {
            // Chain the cause, as well as naming it in the message -- otherwise the reason the path could not be
            // opened is not reachable from the stack trace
            final var cause = e.getCause() == null ? e : e.getCause();
            throw new IOException("Could not open " + path + " : " + cause, cause);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while opening " + path);
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
        final var openedByAnotherThread = rootsByPath.putIfAbsent(key, root);
        return openedByAnotherThread == null ? root : openedByAnotherThread;
    }

    /**
     * Open a directory or a jarfile named by a {@link URI}.
     *
     * @param uri
     *            the {@link URI} to open. A {@code "jar:"} or {@code "file:"} URI names something in the local
     *            filesystem; any other scheme has to be enabled with {@link #enableURLScheme(String)} first.
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
     *            filesystem; any other scheme has to be enabled with {@link #enableURLScheme(String)} first.
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
        checkNotClosed(moduleReference.descriptor().name());
        // Constructing a ModuleRoot does no I/O, so it is safe to build it inside the map's mapping function
        return rootsByModule.computeIfAbsent(moduleReference, ref -> new ModuleRoot(this, ref));
    }

    /**
     * Open a jarfile read from an {@link InputStream}. The stream is read to the end, into RAM or into a temporary
     * file if it is longer than {@link #maxBufferedJarRAMSize(int)}, since a zipfile's central directory is at the
     * end of the file and so cannot be reached by reading forwards.
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
            return new ArchiveRoot(this, nestedJarHandler.openJarFromInputStream(inputStream,
                    /* inputStreamLengthHint = */ -1L, name, logNode), /* packageRoot = */ "");
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
            return new ArchiveRoot(this, nestedJarHandler.openJarFromInputStream(new ByteArrayInputStream(jarBytes),
                    jarBytes.length, name, logNode), /* packageRoot = */ "");
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
        if (closed) {
            throw new IOException("Cannot read " + what + " after the Vfs has been closed");
        }
    }

    /**
     * Returns whether this {@link Vfs} has been closed.
     *
     * @return true if this {@link Vfs} has been closed.
     */
    boolean isClosed() {
        return closed;
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
        closed = true;
        rootsByPath.clear();
        rootsByModule.clear();
        nestedJarHandler.close(log);
        final var logCurr = log;
        if (logCurr != null) {
            logCurr.flush();
        }
    }
}
