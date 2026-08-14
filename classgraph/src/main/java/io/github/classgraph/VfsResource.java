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
package io.github.classgraph;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Objects;
import java.util.Set;

import io.github.classgraph.base.internal.utils.ProxyingInputStream;
import io.github.classgraph.vfs.CloseableByteBuffer;
import io.github.classgraph.vfs.VfsEntry;
import io.github.classgraph.vfs.internal.slice.reader.ClassfileReader;
import org.jspecify.annotations.Nullable;

/**
 * A {@link Resource} that is read through the virtual filesystem, so that a file in a directory, an entry of a
 * jarfile and a resource in a module are all read the same way. Each kind of classpath element subclasses this only
 * to say how the resource is named or located within it, since that is all that differs between them.
 */
class VfsResource extends Resource {
    /** The entry in the virtual filesystem that this resource is read from. */
    final VfsEntry entry;

    /** The path of the resource relative to the package root. */
    private final String path;

    /** The buffer this resource was read into, or null if it has not been read into a buffer. */
    private @Nullable CloseableByteBuffer closeableByteBuffer;

    /**
     * Constructor.
     *
     * @param classpathElement
     *            the classpath element this resource was found in.
     * @param entry
     *            the entry in the virtual filesystem that this resource is read from.
     * @param path
     *            the path of the resource relative to the package root.
     */
    VfsResource(final ClasspathElement classpathElement, final VfsEntry entry, final String path) {
        super(classpathElement, entry.getLength());
        this.entry = entry;
        this.path = path;
    }

    // -------------------------------------------------------------------------------------------------------------

    @Override
    public String getPath() {
        return path;
    }

    @Override
    public long getLastModifiedMillis() {
        return entry.getLastModifiedTimeMillis();
    }

    @Override
    public @Nullable Set<PosixFilePermission> getPosixFilePermissions() {
        return entry.getPosixFilePermissions();
    }

    // -------------------------------------------------------------------------------------------------------------

    @Override
    public InputStream open() throws IOException {
        checkCanOpen();
        try {
            final Resource thisResource = this;
            inputStream = new ProxyingInputStream(entry.open()) {
                /** True once the resource has been closed, so that it is only closed once. */
                private boolean closedResource;

                @Override
                public void close() throws IOException {
                    try {
                        super.close();
                    } finally {
                        // Closing the stream closes the resource it was opened on. Closing the stream a second time
                        // must not close the resource again, since by then the resource may have been reopened.
                        if (!closedResource) {
                            closedResource = true;
                            thisResource.close();
                        }
                    }
                }
            };
            length = entry.getLength();
            return inputStream;

        } catch (final IOException e) {
            // Leave the resource closed if it could not be opened, so that opening it can be tried again, and so
            // that anything the entry checked out in order to open it is handed back
            close();
            throw e;
        }
    }

    @Override
    public ByteBuffer read() throws IOException {
        checkCanOpen();
        try {
            final var closeableBuffer = entry.read();
            closeableByteBuffer = closeableBuffer;
            // The buffer may belong to whatever produced it, and is only valid until this resource is closed
            final var buffer = Objects.requireNonNull(closeableBuffer.getByteBuffer());
            byteBuffer = buffer;
            length = buffer.remaining();
            return buffer;

        } catch (final IOException e) {
            close();
            throw e;
        }
    }

    @Override
    public byte[] load() throws IOException {
        checkCanOpen();
        try (Resource res = this) { // Close this after use
            final var byteArray = entry.load();
            res.length = byteArray.length;
            return byteArray;
        }
    }

    @Override
    ClassfileReader openClassfile() throws IOException {
        checkCanOpen();
        try {
            final var classfileReader = entry.openClassfileReader(this);
            length = entry.getLength();
            return classfileReader;

        } catch (final IOException e) {
            close();
            throw e;
        }
    }

    @Override
    public void close() {
        if (markClosed()) {
            byteBuffer = null;
            final var closeableBuffer = closeableByteBuffer;
            if (closeableBuffer != null) {
                closeableByteBuffer = null;
                // Releases the buffer, and hands back anything the entry checked out in order to read it
                closeableBuffer.close();
            }

            // Close inputStream
            super.close();
        }
    }
}
