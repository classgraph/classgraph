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
package io.github.classgraph.vfs.internal.slice;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;

import org.jspecify.annotations.Nullable;

import io.github.classgraph.base.LogNode;

/**
 * A read-only memory mapping of a whole file, owned and released by the toplevel {@link PathSlice} that mapped it.
 */
// #331
// #939
final class FileMapping {
    /** The mapped {@link ByteBuffer} covering the whole file. */
    final ByteBuffer byteBuffer;

    /** The arena used to map the file. */
    private final Arena arena;

    /**
     * Constructor.
     *
     * @param byteBuffer
     *            the mapped byte buffer
     * @param arena
     *            the arena used to map the file
     */
    private FileMapping(final ByteBuffer byteBuffer, final Arena arena) {
        this.byteBuffer = byteBuffer;
        this.arena = arena;
    }

    /**
     * Memory-map a whole file.
     *
     * @param fileChannel
     *            the {@link FileChannel} of the file to map
     * @param fileLength
     *            the length of the file
     * @param file
     *            the file being mapped, for logging
     * @param log
     *            the log node, or null to skip logging
     * @return the mapping, or null if the file exceeds {@link Slice#MAX_BUFFER_SIZE}, the {@link FileChannel} does
     *         not support memory mapping, or mapping failed.
     */
    static @Nullable FileMapping map(final FileChannel fileChannel, final long fileLength, final Object file,
            final @Nullable LogNode log) {
        // (A file larger than MAX_BUFFER_SIZE cannot be mapped to a single ByteBuffer)
        if (fileLength > Slice.MAX_BUFFER_SIZE) {
            return null;
        }
        // A shared arena rather than a confined one, since the mapping may be read and closed by multiple threads
        final var arena = Arena.ofShared();
        ByteBuffer byteBuffer = null;
        try {
            // Try mapping the file (some operating systems throw OutOfMemoryError if the file can't be mapped,
            // some throw IOException)
            byteBuffer = mapWholeFile(arena, fileChannel, fileLength);
        } catch (final UnsupportedOperationException e) {
            // A FileChannel does not have to support memory mapping at all -- the channel of a file in a
            // filesystem other than the default one usually does not. Retrying after garbage collection cannot
            // help here, since nothing about the channel will have changed
            if (log != null) {
                log.log("File " + file + " cannot be memory mapped: " + e + " (reading the file instead)");
            }
        } catch (IOException | OutOfMemoryError _) {
            // Retry after GC, which may free unrelated heap or cleaner-managed mappings made elsewhere in the
            // process. Cleaner-managed mappings are released during reference processing, after collection.
            // ClassGraph's own mappings use shared arenas: GC cannot release those, even when their Java objects
            // become unreachable. They must be released explicitly by closing their owning arena.
            PathSlice.freeUnreachableBuffers();
            try {
                byteBuffer = mapWholeFile(arena, fileChannel, fileLength);
            } catch (IOException | OutOfMemoryError e2) {
                if (log != null) {
                    log.log("File " + file + " cannot be memory mapped: " + e2 + " (reading the file instead)");
                }
                // Fall through -- the file will be read through the FileChannel API instead
            }
        }
        if (byteBuffer == null) {
            // The arena ended up not being used to map the file -- close it again
            closeArena(arena, log);
            return null;
        }
        return new FileMapping(byteBuffer, arena);
    }

    /**
     * Map a whole file through an arena.
     *
     * @param arena
     *            the {@link Arena} to map the file through
     * @param fileChannel
     *            the {@link FileChannel} of the file to map
     * @param fileLength
     *            the length of the file
     * @return the mapped byte buffer
     * @throws IOException
     *             if the file could not be mapped
     */
    private static ByteBuffer mapWholeFile(final Arena arena, final FileChannel fileChannel, final long fileLength)
            throws IOException {
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, 0L, fileLength, arena).asByteBuffer();
    }

    /**
     * Release the memory mapping of the file. Called once by the owning toplevel {@link PathSlice} on close.
     *
     * @return true if the file was successfully unmapped.
     */
    // #939
    boolean unmap() {
        return closeArena(arena, /* log = */ null);
    }

    /**
     * Close an arena, unmapping any file mapped with it.
     *
     * @param arena
     *            the arena to close
     * @param log
     *            the log node, or null to skip logging
     * @return true if the arena was successfully closed
     */
    // #939
    static boolean closeArena(final Arena arena, final @Nullable LogNode log) {
        try {
            arena.close();
            return true;
        } catch (final RuntimeException e) {
            // IllegalStateException, if the arena has already been closed, or if an I/O operation on a buffer in
            // the arena is in progress (such as a channel write from the buffer), which holds the arena open
            if (log != null) {
                log.log("Could not close arena: " + e);
            }
            return false;
        }
    }
}
