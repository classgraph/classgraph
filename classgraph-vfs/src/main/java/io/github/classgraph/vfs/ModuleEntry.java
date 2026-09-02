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
import java.util.concurrent.atomic.AtomicBoolean;

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
    public String getPathFromRoot() {
        return name;
    }

    @Override
    public String getPath() {
        // This is the notation the JDK itself uses to name something within a module, e.g. "java.base/java/lang"
        return getRoot().getPath() + "/" + name;
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
        InputStream inputStream = null;
        var handedOffToCaller = false;
        try {
            inputStream = ModuleReaderUtils.open(reader, name);
            final var proxyingInputStream = new ProxyingInputStream(inputStream) {
                /** True once the {@link ModuleReader} has been recycled, so that it is only recycled once. */
                private final AtomicBoolean recycled = new AtomicBoolean();

                @Override
                public void close() throws IOException {
                    try {
                        super.close();
                    } finally {
                        // Two threads closing this stream at once must not hand the same reader back to the
                        // recycler twice, which would let two threads read through it at the same time
                        if (!recycled.getAndSet(true)) {
                            recycler.recycle(reader);
                        }
                    }
                }
            };
            handedOffToCaller = true;
            return proxyingInputStream;
        } catch (final SecurityException e) {
            throw new IOException("Could not open " + getPath(), e);
        } finally {
            if (!handedOffToCaller) {
                // Only the stream that the caller never got recycles the reader, so close the stream and recycle
                // the reader here rather than leaving them checked out, so that opening this entry can be tried
                // again
                try {
                    if (inputStream != null) {
                        inputStream.close();
                    }
                } catch (final IOException e) {
                    // Nothing can be done about a stream that cannot be closed, and the caller never saw it
                } finally {
                    recycler.recycle(reader);
                }
            }
        }
    }

    @Override
    public CloseableByteBuffer read() throws IOException {
        final var recycler = getRoot().moduleReaderRecycler();
        final var reader = recycler.acquire();
        var handedOffToCaller = false;
        try {
            final var byteBuffer = ModuleReaderUtils.read(reader, name);
            try {
                // The buffer belongs to the ModuleReader and has to be handed back to it, so the caller gets a
                // read-only view of it, and closing that view is what releases the buffer and recycles the reader
                final var closeableByteBuffer = new CloseableByteBuffer(byteBuffer.asReadOnlyBuffer(), () -> {
                    try {
                        reader.release(byteBuffer);
                    } finally {
                        // ModuleReader#release is allowed to throw, and CloseableByteBuffer#close swallows what
                        // the close action throws, so without this the reader would stay checked out of the
                        // recycler forever rather than being handed back for the next read
                        recycler.recycle(reader);
                    }
                }, getRoot());
                // The wrapper now owns the buffer and the reader, so the recovery paths below must not release
                // them as well
                handedOffToCaller = true;
                if (!getRoot().trackOpenHandle(closeableByteBuffer)) {
                    closeableByteBuffer.close();
                    throw new IOException("Cannot read " + getPath() + " after the root has been closed");
                }
                return closeableByteBuffer;
            } finally {
                if (!handedOffToCaller) {
                    // Only the view that the caller never got releases the buffer, so hand it back here
                    reader.release(byteBuffer);
                }
            }
        } catch (final SecurityException | OutOfMemoryError e) {
            throw new IOException("Could not read " + getPath(), e);
        } finally {
            if (!handedOffToCaller) {
                // Recycle the reader rather than leaving it checked out, so that reading this entry can be tried
                // again
                recycler.recycle(reader);
            }
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
