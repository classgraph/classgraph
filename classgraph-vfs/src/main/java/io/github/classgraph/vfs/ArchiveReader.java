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

import java.io.Closeable;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import io.github.classgraph.base.internal.concurrency.InterruptionChecker;
import io.github.classgraph.base.internal.concurrency.SingletonMap.NewInstanceException;
import io.github.classgraph.base.internal.concurrency.SingletonMap.NullSingletonException;
import io.github.classgraph.base.internal.utils.Assert;
import io.github.classgraph.base.internal.utils.LogNode;
import io.github.classgraph.vfs.internal.spec.VfsScanSpec;
import io.github.classgraph.vfs.internal.zip.NestedJarHandler;
import org.jspecify.annotations.Nullable;

/**
 * Opens jarfiles for reading, including jarfiles nested inside other jarfiles to any depth.
 *
 * <pre>
 * try (ArchiveReader reader = new ArchiveReader()) {
 *     Archive archive = reader.open("outer.jar!/lib/inner.jar");
 *     for (ArchiveEntry entry : archive.getEntries()) {
 *         try (InputStream inputStream = entry.open()) {
 *             // ...
 *         }
 *     }
 * }
 * </pre>
 *
 * <p>
 * A nested jarfile is read in place, without being extracted to disk, unless it is stored deflated rather than
 * uncompressed and is too large to inflate into RAM -- only then is it spilled to a temporary file, which is
 * deleted when this reader is closed.
 *
 * <p>
 * A reader caches every jarfile it opens, so opening the same path twice returns the same {@link Archive}, and a
 * jarfile that encloses several nested jarfiles is only read once. The cache, the open file handles and the
 * temporary files are all released by {@link #close()}, which invalidates every {@link Archive} and
 * {@link ArchiveEntry} the reader handed out, so a reader should be held open for as long as its entries are being
 * read.
 *
 * <p>
 * {@link #open(String)} is safe to call from multiple threads at once: two threads that ask for the same path at
 * the same time get the same {@link Archive}, and only one of them does the work of reading it. The configuration
 * methods are not thread-safe, and are intended to be called before the first call to {@link #open(String)}.
 */
public class ArchiveReader implements Closeable {
    /** Everything the archive reader is configured with. */
    private final VfsScanSpec vfsScanSpec = new VfsScanSpec();

    /** The handler that opens jarfiles and owns the resources they are backed by. */
    private final NestedJarHandler nestedJarHandler;

    /** The archives that have been opened, keyed by the path they were opened from. */
    private final Map<String, Archive> archives = new ConcurrentHashMap<>();

    /** The log node, or null if not logging. */
    private @Nullable LogNode log;

    /** Constructor. */
    public ArchiveReader() {
        this.nestedJarHandler = new NestedJarHandler(vfsScanSpec, new InterruptionChecker());
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Log what is read to the {@code io.github.classgraph.ClassGraph} logger, at {@code INFO} level. This is
     * intended for working out why a jarfile is not being read as expected, and is not a stable output format.
     *
     * <p>
     * The log is written when this reader is closed.
     *
     * @return this (for method chaining).
     */
    public ArchiveReader verbose() {
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
    public ArchiveReader disableNestedJars() {
        vfsScanSpec.scanNestedJars = false;
        return this;
    }

    /**
     * Report every version of a multi-release jarfile's entries, rather than only the newest version of each entry
     * that this JVM can run.
     *
     * @return this (for method chaining).
     */
    public ArchiveReader enableMultiReleaseVersions() {
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
    public ArchiveReader enableURLScheme(final String scheme) {
        vfsScanSpec.enableURLScheme(scheme);
        return this;
    }

    /**
     * Set the number of bytes of a jarfile that may be held in RAM before it is spilled to a temporary file on
     * disk. This only applies to jarfiles that cannot be read in place: a nested jarfile that is stored deflated
     * rather than uncompressed, and a jarfile downloaded from a URL.
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
    public ArchiveReader maxBufferedJarRAMSize(final int maxBufferedJarRAMSize) {
        if (maxBufferedJarRAMSize < 0) {
            throw new IllegalArgumentException("maxBufferedJarRAMSize cannot be negative");
        }
        vfsScanSpec.maxBufferedJarRAMSize = maxBufferedJarRAMSize;
        return this;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Open a jarfile.
     *
     * <p>
     * The path may name a jarfile in the local filesystem, or a URL with an allowed scheme (see
     * {@link #enableURLScheme(String)}). A jarfile nested within another jarfile is named by separating the
     * enclosing jarfile from the nested one with {@code "!/"}, to any depth, e.g.
     * {@code "outer.jar!/lib/inner.jar"}. A trailing {@code "!/"} section that does not name a nested jarfile names
     * a package root within the jarfile instead, e.g. {@code "spring-boot-app.jar!/BOOT-INF/classes"}, in which
     * case only the entries under that root are reported, with the root stripped from their names.
     *
     * @param path
     *            the path of the jarfile to open.
     * @return the opened jarfile.
     * @throws IOException
     *             if the jarfile could not be opened or read, or if this reader has been closed.
     */
    public Archive open(final String path) throws IOException {
        Assert.notNull(path, "path");
        // computeIfAbsent is not used, because the mapping function must not itself open other jarfiles (the
        // enclosing jarfiles of a nested one are opened on the way to it, which would be a recursive update)
        final var alreadyOpened = archives.get(path);
        if (alreadyOpened != null) {
            return alreadyOpened;
        }
        final var logNode = log == null ? null : log.log("Opening " + path);
        try {
            // The nested jar handler caches the logical zipfile, so two threads that race here open it only once,
            // and both get the same LogicalZipFile back
            final var logicalZipFileAndPackageRoot = nestedJarHandler.nestedPathToLogicalZipFileAndPackageRootMap()
                    .get(path, logNode);
            final var archive = new Archive(logicalZipFileAndPackageRoot.getKey(),
                    logicalZipFileAndPackageRoot.getValue());
            final var openedByAnotherThread = archives.putIfAbsent(path, archive);
            return openedByAnotherThread == null ? archive : openedByAnotherThread;
        } catch (final NullSingletonException | NewInstanceException e) {
            throw new IOException("Could not open " + path + " : " + (e.getCause() == null ? e : e.getCause()));
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while opening " + path);
        } catch (final NullPointerException e) {
            // The nested jar handler's caches are set to null by close()
            throw new IOException("Cannot open " + path + " after the ArchiveReader has been closed");
        }
    }

    /**
     * Close every jarfile that was opened by this reader, release the file handles and memory mappings that back
     * them, and delete any temporary files that were created. Every {@link Archive} and {@link ArchiveEntry} this
     * reader handed out is invalidated, and any {@link java.io.InputStream} still being read from one of them will
     * stop returning data.
     *
     * <p>
     * Closing an already-closed reader has no effect.
     */
    @Override
    public void close() {
        archives.clear();
        nestedJarHandler.close(log);
        final var logCurr = log;
        if (logCurr != null) {
            logCurr.flush();
        }
    }
}
