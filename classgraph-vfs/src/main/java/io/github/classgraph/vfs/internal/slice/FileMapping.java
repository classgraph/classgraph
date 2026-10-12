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

import io.github.classgraph.base.LogNode;
import org.jspecify.annotations.Nullable;

/**
 * A memory mapping of a whole file, owned by the toplevel {@link Slice} that mapped it. Every slice of that file
 * reads through {@link #byteBuffer}, and only the owning slice may release it.
 *
 * <p>
 * The file is mapped through a shared {@link Arena}, so that the owning slice can unmap the file as it closes, by
 * closing the arena, and a thread that reads the mapping after that throws {@link IllegalStateException} rather
 * than read memory that is no longer there. If another thread is in the middle of an access to the mapping when the
 * arena is closed, such as one bulk copy out of it, the close waits for that access to finish, the access then
 * throws {@link IllegalStateException} instead of returning, and only then is the file unmapped -- so a close can
 * take as long as that access takes. The garbage collector cannot release a mapping in a shared arena: only closing
 * the arena does.
 *
 * <p>
 * Unmapping needs no class that mapping did not already load: this class is loaded by {@link #map}, and closing an
 * arena loads nothing that opening it and mapping the file through it has not. That matters because the owning
 * slice may be closed from a shutdown hook or a container's teardown code, after the classloader that loaded
 * ClassGraph has been closed and can no longer load anything.
 */
// #331
// #939
final class FileMapping {
    /** The mapped {@link ByteBuffer}, covering the whole file. */
    final ByteBuffer byteBuffer;

    /** The arena that was used to map the file. */
    private final Arena arena;

    /**
     * Constructor.
     *
     * @param byteBuffer
     *            the mapped byte buffer
     * @param arena
     *            the arena that was used to map the file
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
     * @return the mapping, or null if the file could not be mapped -- because it is too long to map to a single
     *         {@link ByteBuffer}, because the {@link FileChannel} does not support mapping, or because the mapping
     *         failed -- in which case the caller has to read the file through the {@link FileChannel} API instead.
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
     *             if the file could not be mapped (mapping may succeed if it is retried after garbage collection)
     */
    private static ByteBuffer mapWholeFile(final Arena arena, final FileChannel fileChannel, final long fileLength)
            throws IOException {
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, 0L, fileLength, arena).asByteBuffer();
    }

    /**
     * Release the memory mapping of the file, by closing the arena that mapped it. Called only by the toplevel
     * {@link Slice} that owns the mapping, once, as it closes.
     *
     * <p>
     * This unmaps the file even if another thread is still reading it: if that thread is in the middle of an access
     * to the mapping, this waits for the access to finish, and the access then throws {@link IllegalStateException}
     * instead of returning, which the readers translate into {@link IOException}. Closing a
     * {@link io.github.classgraph.vfs.Vfs} while another thread is reading through it is a use-after-close either
     * way, and is documented as one.
     *
     * @return true if the file has been unmapped by the time this returns, or false if the arena would not close,
     *         which leaves the file mapped for the rest of the life of the JVM.
     */
    // #939
    boolean unmap() {
        return closeArena(arena, /* log = */ null);
    }

    /**
     * Close an arena, unmapping any file mapped with it. A thread that is still reading the mapping does not stop
     * the arena from closing: if it is in the middle of an access to the mapping, the close waits for that access
     * to finish, and the access then throws {@link IllegalStateException} instead of returning.
     *
     * @param arena
     *            the arena to close.
     * @param log
     *            the log node, or null to skip logging
     * @return true if the arena was successfully closed.
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
