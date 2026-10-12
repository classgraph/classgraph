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
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;

import io.github.classgraph.base.LogNode;
import io.github.classgraph.base.internal.path.FastPathResolver;
import io.github.classgraph.base.internal.path.FileUtils;
import io.github.classgraph.vfs.Vfs;
import io.github.classgraph.vfs.internal.StoragePaths;
import io.github.classgraph.vfs.internal.slice.PathSlice;
import io.github.classgraph.vfs.internal.slice.Slice;
import org.jspecify.annotations.Nullable;

/**
 * A physical zipfile, backed by a {@link File} (which may be mmap'd using a {@link FileChannel}), a {@link Path},
 * or a byte array in RAM.
 *
 * <p>
 * A zipfile that had to be spilled to a temporary file -- a deflated nested jarfile too large to inflate into RAM,
 * or a jarfile downloaded from a URL that was too large to buffer -- owns that temporary file through its
 * {@link PathSlice}, and the {@link io.github.classgraph.vfs.VfsRoot} that opened the zipfile owns the zipfile in
 * turn, so closing that root deletes the file.
 */
public final class PhysicalZipFile {
    /** The {@link Path} backing this {@link PhysicalZipFile}, if any. */
    private final @Nullable Path path;

    /** The {@link File} backing this {@link PhysicalZipFile}, if any. */
    private final @Nullable File file;

    /** The path to the zipfile. */
    private final String pathStr;

    /** The {@link Slice} for the zipfile. */
    final Slice slice;

    /**
     * The number of bytes that the deflated jarfiles nested within this zipfile may still inflate to: this
     * zipfile's own, if it is a toplevel zipfile, or else that of the zipfile it was inflated out of.
     */
    final NestedJarInflationAllowance nestedJarInflationAllowance;

    /**
     * The number of bytes to give back to {@link #nestedJarInflationAllowance} when this zipfile is closed: its
     * length, if it was inflated out of another zipfile, or else zero. Set to zero once given back.
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
        this.pathStr = StoragePaths.pathStr(path);
        this.slice = new PathSlice(path, vfs, log);
        this.nestedJarInflationAllowance = new NestedJarInflationAllowance(pathStr, slice.sliceLength);
        this.bytesToGiveBack = new AtomicLong();
    }

    /**
     * Construct a {@link PhysicalZipFile} by reading from the {@link InputStream} to an array in RAM, or spill to
     * disk if the {@link InputStream} is too long.
     *
     * @param inputStream
     *            the input stream. Read to its end, but not closed -- the caller retains ownership of it.
     * @param inputStreamLengthHint
     *            the number of bytes to read from inputStream, or -1 if unknown.
     * @param pathStr
     *            the source URL the InputStream was opened from, or the zip entry path of this entry in the parent
     *            zipfile
     * @param vfs
     *            the {@link Vfs} that is opening this jarfile
     * @param log
     *            the log node, or null to skip logging
     * @throws IOException
     *             if an I/O exception occurs.
     */
    PhysicalZipFile(final InputStream inputStream, final long inputStreamLengthHint, final String pathStr,
            final Vfs vfs, final @Nullable LogNode log) throws IOException {
        this(inputStream, inputStreamLengthHint, pathStr, /* inflatedOutOf = */ null, vfs, log);
    }

    /**
     * Construct a {@link PhysicalZipFile} by reading from the {@link InputStream} to an array in RAM, or spill to
     * disk if the {@link InputStream} is too long.
     *
     * @param inputStream
     *            the input stream. Read to its end, but not closed -- the caller retains ownership of it.
     * @param inputStreamLengthHint
     *            the number of bytes to read from inputStream, or -1 if unknown.
     * @param pathStr
     *            the source URL the InputStream was opened from, or the zip entry path of this entry in the parent
     *            zipfile
     * @param inflatedOutOf
     *            for a deflated nested jarfile, the allowance of the zipfile it is inflated out of, which
     *            inputStream has taken each byte read from it from, and which this zipfile shares, and gives its
     *            length back to when it is closed; else null.
     * @param vfs
     *            the {@link Vfs} that is opening this jarfile
     * @param log
     *            the log node, or null to skip logging
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
     * Release this zipfile: close the slice it is read through, releasing the file handle and the memory mapping
     * behind it, and delete the temporary file it was extracted to, if it was extracted to one. A zipfile inflated
     * out of another gives its length back to {@link #nestedJarInflationAllowance}, once. Called by the root that
     * owns this zipfile -- the one it was opened for, handed it in a {@link JarOpener.OpenedJar} -- when that root
     * is closed. Nothing else closes it: the jarfiles that read through it, including any stored nested jarfile
     * read in place as a byte range of it, do not own it.
     *
     * @throws IOException
     *             if the slice could not be closed.
     */
    public void close() throws IOException {
        nestedJarInflationAllowance.giveBack(bytesToGiveBack.getAndSet(0L));
        slice.close();
    }

    /**
     * Release this zipfile, because nothing will ever be able to reach it: the operation that opened it failed.
     * Without this, the file handle, memory mapping and temporary file behind it would not be released even when
     * the {@link Vfs} is closed, since the {@link Vfs} releases only what the roots it opened own, and no root owns
     * this zipfile.
     *
     * @param failure
     *            the failure that stopped this zipfile from being handed over, for any failure to release it to be
     *            recorded within.
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
     * @return the {@link File} this zipfile was opened from, or the temporary file it was spilled to if it was read
     *         from a stream that was too long to hold in RAM -- or null if it was opened from a {@link Path}, or
     *         read from a stream into RAM.
     */
    public @Nullable File getFile() {
        return file;
    }

    /**
     * Get the path or name of this zipfile, which log messages and entry paths use. This does not identify the
     * zipfile: two zipfiles read from streams under the same name, such as the same nested jar of two different
     * outer jars, have the same name.
     *
     * @return the path of the file or {@link Path} this zipfile was opened from, or, if it was read from a stream,
     *         the name it was read under: the URL it was downloaded from, or the name of the zip entry it was
     *         inflated from. (A temporary file that a stream was spilled to does not change this name.)
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
