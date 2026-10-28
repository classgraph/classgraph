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
package io.github.classgraph.vfs.internal.zip;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;

import org.jspecify.annotations.Nullable;

import io.github.classgraph.base.LogNode;
import io.github.classgraph.base.internal.path.FastPathResolver;
import io.github.classgraph.base.internal.path.FileUtils;
import io.github.classgraph.vfs.Vfs;
import io.github.classgraph.vfs.internal.slice.PathSlice;
import io.github.classgraph.vfs.internal.slice.Slice;

/**
 * A physical zipfile backed by a {@link File}, a {@link Path}, or an in-memory byte array (via {@link Slice}).
 */
public final class PhysicalZipFile {
    /** The {@link Path} backing this {@link PhysicalZipFile}, if any. */
    private final @Nullable Path path;

    /** The {@link File} backing this {@link PhysicalZipFile}, if any. */
    private final @Nullable File file;

    /** The path or display name of the zipfile. */
    private final String pathStr;

    /** The {@link Slice} for the zipfile. */
    final Slice slice;

    /**
     * The inflation allowance shared by this zipfile and all deflated nested jarfiles inflated from it.
     */
    final NestedJarInflationAllowance nestedJarInflationAllowance;

    /**
     * The number of bytes to return to {@link #nestedJarInflationAllowance} when this zipfile is closed (non-zero
     * only if this zipfile was inflated from an enclosing zipfile).
     */
    private final AtomicLong bytesToGiveBack;

    /**
     * Construct a {@link PhysicalZipFile} from a file on disk.
     *
     * @param file
     *            the file
     * @param vfs
     *            the {@link Vfs} that is opening this jarfile
     * @param log
     *            the log node, or null to skip logging
     * @throws IOException
     *             if an I/O exception occurs.
     */
    PhysicalZipFile(final File file, final Vfs vfs, final @Nullable LogNode log) throws IOException {
        this.path = null;
        this.file = file;
        this.pathStr = FastPathResolver.resolve(FileUtils.currDirPath(), file.getPath());
        this.slice = new PathSlice(file, vfs, log);
        this.nestedJarInflationAllowance = new NestedJarInflationAllowance(pathStr, slice.sliceLength);
        this.bytesToGiveBack = new AtomicLong();
    }

    /**
     * Construct a {@link PhysicalZipFile} from a {@link Path}.
     *
     * @param path
     *            the path
     * @param vfs
     *            the {@link Vfs} that is opening this jarfile
     * @param log
     *            the log node, or null to skip logging
     * @throws IOException
     *             if an I/O exception occurs.
     */
    PhysicalZipFile(final Path path, final Vfs vfs, final @Nullable LogNode log) throws IOException {
        this.path = path;
        this.file = null;
        this.pathStr = FileUtils.pathStr(path);
        this.slice = new PathSlice(path, vfs, log);
        this.nestedJarInflationAllowance = new NestedJarInflationAllowance(pathStr, slice.sliceLength);
        this.bytesToGiveBack = new AtomicLong();
    }

    /**
     * Construct a {@link PhysicalZipFile} by reading from {@code inputStream} into an in-memory byte array, or
     * spilling to a temporary file if it exceeds the configured RAM buffer size.
     *
     * @param inputStream
     *            the input stream (read to EOF, not closed by this constructor).
     * @param inputStreamLengthHint
     *            the expected stream length in bytes, or -1 if unknown.
     * @param pathStr
     *            the source URL or zip entry path from which {@code inputStream} was opened.
     * @param vfs
     *            the {@link Vfs} opening this jarfile.
     * @param log
     *            the log node, or null to skip logging.
     * @throws IOException
     *             if an I/O exception occurs.
     */
    PhysicalZipFile(final InputStream inputStream, final long inputStreamLengthHint, final String pathStr,
            final Vfs vfs, final @Nullable LogNode log) throws IOException {
        this(inputStream, inputStreamLengthHint, pathStr, /* inflatedOutOf = */ null, vfs, log);
    }

    /**
     * Construct a {@link PhysicalZipFile} by reading from {@code inputStream} into an in-memory byte array, or
     * spilling to a temporary file if it exceeds the configured RAM buffer size.
     *
     * @param inputStream
     *            the input stream (read to EOF, not closed by this constructor).
     * @param inputStreamLengthHint
     *            the expected stream length in bytes, or -1 if unknown.
     * @param pathStr
     *            the source URL or zip entry path from which {@code inputStream} was opened.
     * @param inflatedOutOf
     *            for a deflated nested jarfile, the enclosing zipfile's {@link NestedJarInflationAllowance} to
     *            which this zipfile's length will be returned on {@link #close()}; or null for a toplevel zipfile.
     * @param vfs
     *            the {@link Vfs} opening this jarfile.
     * @param log
     *            the log node, or null to skip logging.
     * @throws IOException
     *             if an I/O exception occurs.
     */
    PhysicalZipFile(final InputStream inputStream, final long inputStreamLengthHint, final String pathStr,
            final @Nullable NestedJarInflationAllowance inflatedOutOf, final Vfs vfs, final @Nullable LogNode log)
            throws IOException {
        this.path = null;
        this.pathStr = pathStr;
        // Try downloading the InputStream to a byte array. If this succeeds, this will result in an ArraySlice. If
        // it fails, the InputStream will be spilled to disk, resulting in a PathSlice over the temporary file.
        this.slice = Slice.fromInputStream(inputStream, /* tempFileBaseName = */ pathStr, inputStreamLengthHint,
                vfs, log);
        this.file = this.slice instanceof final PathSlice pathSlice ? pathSlice.getFile() : null;
        if (inflatedOutOf == null) {
            this.nestedJarInflationAllowance = new NestedJarInflationAllowance(pathStr, slice.sliceLength);
            this.bytesToGiveBack = new AtomicLong();
        } else {
            this.nestedJarInflationAllowance = inflatedOutOf;
            this.bytesToGiveBack = new AtomicLong(slice.sliceLength);
        }
    }

    /**
     * Close the backing {@link Slice} (releasing any file channel, memory mapping, or temporary file) and return
     * any inflated bytes to {@link #nestedJarInflationAllowance}.
     *
     * @throws IOException
     *             if the slice could not be closed.
     */
    public void close() throws IOException {
        nestedJarInflationAllowance.giveBack(bytesToGiveBack.getAndSet(0L));
        slice.close();
    }

    /**
     * Close this zipfile when opening fails before ownership can be transferred to a caller, adding any close
     * exception as a suppressed exception on {@code failure}.
     *
     * @param failure
     *            the exception that prevented opening from completing.
     */
    void releaseUnreachable(final Throwable failure) {
        try {
            close();
        } catch (final IOException | RuntimeException | Error e) {
            failure.addSuppressed(e);
        }
    }

    /**
     * Get the {@link Path} this zipfile was opened from.
     *
     * @return the {@link Path} this zipfile was opened from, or null if it was opened from a {@link File}, or read
     *         from a stream.
     */
    public @Nullable Path getPath() {
        return path;
    }

    /**
     * Get the {@link File} this zipfile is read from.
     *
     * @return the {@link File} this zipfile was opened from, the temporary file it was spilled to, or null if
     *         opened from a {@link Path} or buffered in RAM.
     */
    public @Nullable File getFile() {
        return file;
    }

    /**
     * Get the path or display name of this zipfile.
     *
     * @return the file path, source URL, or nested zip entry name under which this zipfile was opened.
     */
    public String getPathString() {
        return pathStr;
    }

    /**
     * Get the length of this zipfile.
     *
     * @return the length of this zipfile, in bytes.
     */
    public long length() {
        return slice.sliceLength;
    }

    @Override
    public String toString() {
        return pathStr;
    }
}
