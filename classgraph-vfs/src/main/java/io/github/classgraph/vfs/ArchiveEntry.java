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
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

import io.github.classgraph.base.internal.path.URLPaths;
import io.github.classgraph.vfs.internal.zip.FastZipEntry;
import org.jspecify.annotations.Nullable;

/** One entry of a zipfile or jarfile. */
final class ArchiveEntry extends VfsEntry {
    /** The zip entry. */
    private final FastZipEntry zipEntry;

    /** The name of this entry, relative to the archive's package root. */
    private final String name;

    /**
     * The POSIX file permissions, in the order of the Unix mode bits, from {@code 0400} down to {@code 0001}.
     */
    private static final PosixFilePermission[] POSIX_FILE_PERMISSION_BITS = { PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE, PosixFilePermission.GROUP_READ,
            PosixFilePermission.GROUP_WRITE, PosixFilePermission.GROUP_EXECUTE, PosixFilePermission.OTHERS_READ,
            PosixFilePermission.OTHERS_WRITE, PosixFilePermission.OTHERS_EXECUTE };

    /**
     * Constructor.
     *
     * @param root
     *            the archive this entry was read from.
     * @param zipEntry
     *            the zip entry.
     * @param name
     *            the name of this entry, relative to the archive's package root.
     */
    ArchiveEntry(final ArchiveRoot root, final FastZipEntry zipEntry, final String name) {
        super(root);
        this.zipEntry = zipEntry;
        this.name = name;
    }

    // -------------------------------------------------------------------------------------------------------------

    @Override
    public String getPathFromRoot() {
        return name;
    }

    @Override
    public String getRawPathFromRoot() {
        return zipEntry.entryName;
    }

    @Override
    public String getPath() {
        return zipEntry.getPath();
    }

    @Override
    public URI getURI() {
        final var rootURIStr = getRoot().getURI().toString();
        try {
            // A jarfile nested within another jarfile already has a "jar:" URI, and must not be given a second one
            return new URI((rootURIStr.startsWith("jar:") ? "" : "jar:") + rootURIStr + "!/"
                    + URLPaths.encodePath(zipEntry.entryName));
        } catch (final URISyntaxException e) {
            throw new IllegalStateException("Could not form URI for " + getPath() + " : " + e, e);
        }
    }

    @Override
    public long getLength() {
        return zipEntry.uncompressedSize;
    }

    @Override
    public long getCompressedSize() {
        return zipEntry.compressedSize;
    }

    @Override
    public long getLastModifiedMillis() {
        return zipEntry.getLastModifiedMillis();
    }

    @Override
    public @Nullable Set<PosixFilePermission> getPosixFilePermissions() {
        final var fileAttributes = zipEntry.fileAttributes;
        if (fileAttributes == 0) {
            // Zip entries written by tools that do not record Unix mode bits have zero file attributes
            return null;
        }
        // An EnumSet iterates in PosixFilePermission declaration order, i.e. owner then group then others, and
        // read then write then execute within each
        final Set<PosixFilePermission> permissions = EnumSet.noneOf(PosixFilePermission.class);
        for (var i = 0; i < POSIX_FILE_PERMISSION_BITS.length; i++) {
            if ((fileAttributes & (0400 >> i)) != 0) {
                permissions.add(POSIX_FILE_PERMISSION_BITS[i]);
            }
        }
        return Collections.unmodifiableSet(permissions);
    }

    // -------------------------------------------------------------------------------------------------------------

    @Override
    public InputStream open() throws IOException {
        getRoot().checkNotClosed(getPath());
        return zipEntry.getSlice().open();
    }

    @Override
    public CloseableByteBuffer read() throws IOException {
        getRoot().checkNotClosed(getPath());
        // The slice of a zip entry is a sub-slice of the zipfile, and owns no resources of its own -- the zipfile
        // is released when the Vfs is closed. But if the zipfile is memory-mapped, the buffer returned here is a
        // view of that mapping, so the mapping has to be held open until the caller closes the wrapper
        // #939
        final var slice = zipEntry.getSlice();
        final var releaseMappingView = slice.acquireMappingView();
        try {
            return new CloseableByteBuffer(slice.read(), releaseMappingView);
        } catch (final IOException | RuntimeException | Error e) {
            // The caller never sees the buffer if this throws, so nothing else would release the view
            releaseMappingView.run();
            throw e;
        }
    }

    @Override
    public byte[] load() throws IOException {
        getRoot().checkNotClosed(getPath());
        return zipEntry.getSlice().load();
    }
}
