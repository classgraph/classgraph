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
import java.lang.module.ModuleReader;
import java.net.URI;

import io.github.classgraph.base.internal.utils.ProxyingInputStream;
import io.github.classgraph.vfs.internal.module.ModuleReaderUtils;

/**
 * One resource of a module. Reading it acquires a {@link ModuleReader} from the module's recycler, and hands the
 * reader back to the recycler once the stream or buffer that was read from it has been closed.
 */
final class ModuleEntry extends VfsEntry {
    /** The path of the resource within the module. */
    private final String name;

    /**
     * Constructor.
     *
     * @param root
     *            the module this entry was read from.
     * @param name
     *            the path of the resource within the module.
     */
    ModuleEntry(final ModuleRoot root, final String name) {
        super(root);
        this.name = name;
    }

    // -------------------------------------------------------------------------------------------------------------

    @Override
    public ModuleRoot getRoot() {
        return (ModuleRoot) super.getRoot();
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public String getPath() {
        // This is the notation the JDK itself uses to name something within a module, e.g. "java.base/java/lang"
        return getRoot().getPath() + "/" + name;
    }

    @Override
    public URI getURI() {
        try {
            final var recycler = getRoot().moduleReaderRecycler();
            final var reader = recycler.acquire();
            try {
                return ModuleReaderUtils.find(reader, name);
            } finally {
                recycler.recycle(reader);
            }
        } catch (final IOException | SecurityException e) {
            throw new IllegalStateException("Could not form URI for " + getPath() + " : " + e, e);
        }
    }

    @Override
    public long getLength() {
        // A ModuleReader cannot report the length of a resource without reading it
        return -1L;
    }

    @Override
    public long getLastModifiedMillis() {
        // A ModuleReader cannot report the modification time of a resource
        return 0L;
    }

    // -------------------------------------------------------------------------------------------------------------

    @Override
    public InputStream open() throws IOException {
        final var recycler = getRoot().moduleReaderRecycler();
        final var reader = recycler.acquire();
        final InputStream inputStream;
        try {
            inputStream = ModuleReaderUtils.open(reader, name);
        } catch (final IOException e) {
            // Recycle the reader rather than leaving it checked out, so that opening this entry can be tried again
            recycler.recycle(reader);
            throw e;
        } catch (final SecurityException e) {
            recycler.recycle(reader);
            throw new IOException("Could not open " + getPath(), e);
        }
        return new ProxyingInputStream(inputStream) {
            /** True once the {@link ModuleReader} has been recycled, so that it is only recycled once. */
            private boolean recycled;

            @Override
            public void close() throws IOException {
                try {
                    super.close();
                } finally {
                    if (!recycled) {
                        recycled = true;
                        recycler.recycle(reader);
                    }
                }
            }
        };
    }

    @Override
    public CloseableByteBuffer read() throws IOException {
        final var recycler = getRoot().moduleReaderRecycler();
        final var reader = recycler.acquire();
        try {
            final var byteBuffer = ModuleReaderUtils.read(reader, name);
            // The buffer belongs to the ModuleReader and has to be handed back to it, so the caller gets a read-only
            // view of it, and closing that view is what releases the buffer and recycles the reader
            return new CloseableByteBuffer(byteBuffer.asReadOnlyBuffer(), () -> {
                reader.release(byteBuffer);
                recycler.recycle(reader);
            });
        } catch (final IOException e) {
            // Recycle the reader rather than leaving it checked out, so that reading this entry can be tried again
            recycler.recycle(reader);
            throw e;
        } catch (final SecurityException | OutOfMemoryError e) {
            recycler.recycle(reader);
            throw new IOException("Could not read " + getPath(), e);
        }
    }

    @Override
    public byte[] load() throws IOException {
        final var recycler = getRoot().moduleReaderRecycler();
        final var reader = recycler.acquire();
        try {
            final var byteBuffer = ModuleReaderUtils.read(reader, name);
            try {
                // The buffer belongs to the ModuleReader, which reclaims it below, so the content has to be copied
                // out rather than aliased
                final var byteArray = new byte[byteBuffer.remaining()];
                byteBuffer.get(byteArray);
                return byteArray;
            } finally {
                reader.release(byteBuffer);
            }
        } catch (final SecurityException | OutOfMemoryError e) {
            throw new IOException("Could not read " + getPath(), e);
        } finally {
            recycler.recycle(reader);
        }
    }
}
