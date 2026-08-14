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
import java.util.HashSet;
import java.util.Set;

import io.github.classgraph.base.internal.utils.URLPathEncoder;
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
    public String getName() {
        return name;
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
                    + URLPathEncoder.encodePath(zipEntry.entryName));
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
    public long getLastModifiedTimeMillis() {
        return zipEntry.getLastModifiedTimeMillis();
    }

    @Override
    public @Nullable Set<PosixFilePermission> getPosixFilePermissions() {
        final var fileAttributes = zipEntry.fileAttributes;
        if (fileAttributes == 0) {
            // Zip entries written by tools that do not record Unix mode bits have zero file attributes
            return null;
        }
        final Set<PosixFilePermission> permissions = new HashSet<>();
        for (var i = 0; i < POSIX_FILE_PERMISSION_BITS.length; i++) {
            if ((fileAttributes & (0400 >> i)) != 0) {
                permissions.add(POSIX_FILE_PERMISSION_BITS[i]);
            }
        }
        return permissions;
    }

    @Override
    public FastZipEntry getZipEntry() {
        return zipEntry;
    }

    // -------------------------------------------------------------------------------------------------------------

    @Override
    public InputStream open() throws IOException {
        return zipEntry.getSlice().open();
    }

    @Override
    public CloseableByteBuffer read() throws IOException {
        // The slice of a zip entry is a sub-slice of the zipfile, and owns no resources of its own, so there is
        // nothing to release when the buffer is closed -- the zipfile is released when the Vfs is closed
        return new CloseableByteBuffer(zipEntry.getSlice().read(), () -> {
            // Nothing to release
        });
    }

    @Override
    public byte[] load() throws IOException {
        return zipEntry.getSlice().load();
    }
}
