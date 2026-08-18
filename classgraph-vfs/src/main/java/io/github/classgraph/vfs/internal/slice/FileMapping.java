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
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;

import io.github.classgraph.base.LogNode;
import io.github.classgraph.base.internal.utils.VersionFinder;
import org.jspecify.annotations.Nullable;

/**
 * A memory mapping of a whole file, owned by the toplevel {@link Slice} that mapped it. Sub-slices of that slice
 * share the mapping by duplicating {@link #byteBuffer}, and must not unmap it.
 *
 * <p>
 * The file is mapped using a {@code java.lang.foreign.Arena}, so that it can be unmapped by closing the arena.
 * Arenas were only finalized in JDK 22, so on JDK 17 to 21 files are not memory mapped at all, and are read through
 * the {@link FileChannel} API instead -- see {@link #map(FileChannel, long, Object, LogNode)}.
 */
// #939
final class FileMapping {
    /** The mapped {@link ByteBuffer}, covering the whole file. */
    final ByteBuffer byteBuffer;

    /**
     * The {@code java.lang.foreign.Arena} that was used to map the file, or null once {@link #unmap()} has closed
     * it. Typed as {@link Object}, since ClassGraph needs to compile and run on JDK 17+.
     */
    private @Nullable Object arena;

    /**
     * Constructor.
     *
     * @param byteBuffer
     *            the mapped byte buffer
     * @param arena
     *            the arena that was used to map the file
     */
    private FileMapping(final ByteBuffer byteBuffer, final Object arena) {
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
     * @return the mapping, or null if the file could not be mapped, in which case the caller has to read the file
     *         through the {@link FileChannel} API instead. Always null on JDK older than 22.
     */
    static @Nullable FileMapping map(final FileChannel fileChannel, final long fileLength, final Object file,
            final @Nullable LogNode log) {
        // (A file larger than MAX_BUFFER_SIZE cannot be mapped to a single ByteBuffer)
        if (fileLength > Slice.MAX_BUFFER_SIZE) {
            return null;
        }
        if (VersionFinder.JAVA_MAJOR_VERSION < 22) {
            // Before JDK 22 there is no way to unmap a file that is safe to call while another thread might still
            // be reading the mapped ByteBuffer: Unsafe::invokeCleaner frees the address range immediately, so a
            // concurrent read of a buffer that aliases it crashes the JVM with SIGSEGV, and the alternative of
            // leaving the mapping for the garbage collector to unmap keeps the file locked open on Windows.
            // Read through the FileChannel API instead, which throws ClosedChannelException when the channel is
            // closed mid-read.
            return null;
        }
        // Mapping a file is the only thing that makes ClassGraph allocate off-heap memory, and it is not freed
        // until the reading session is closed, which can happen long after the file was mapped -- so load the
        // classes needed to free it now, while the classloader that loaded ClassGraph is certainly still alive
        // #331
        OffHeapMemory.warmUpDirectByteBufferClosing();
        final var arena = OffHeapMemory.openArena();
        if (arena == null) {
            // The arena API should be available on this JDK, but could not be invoked reflectively -- read the
            // file through the FileChannel API instead
            return null;
        }
        ByteBuffer byteBuffer = null;
        try {
            // Try mapping the file (some operating systems throw OutOfMemoryError if the file can't be mapped,
            // some throw IOException)
            byteBuffer = OffHeapMemory.mapFileUsingArena(arena, fileChannel, 0L, fileLength);
        } catch (IOException | OutOfMemoryError e) {
            // Try running garbage collection, then try mapping the file again. (Garbage collection is what can free
            // address space here: a mapping whose ByteBuffer is unreachable is unmapped by its Cleaner once the
            // reference is cleared, which is a phantom-reference mechanism, not finalization.)
            System.gc();
            try {
                byteBuffer = OffHeapMemory.mapFileUsingArena(arena, fileChannel, 0L, fileLength);
            } catch (IOException | OutOfMemoryError e2) {
                if (log != null) {
                    log.log("File " + file + " cannot be memory mapped: " + e2 + " (reading the file instead)");
                }
                // Fall through -- the file will be read through the FileChannel API instead
            }
        }
        if (byteBuffer == null) {
            // The arena ended up not being used to map the file -- close it again
            OffHeapMemory.closeArena(arena, log);
            return null;
        }
        return new FileMapping(byteBuffer, arena);
    }

    /**
     * Unmap the file. Must be called only once, and only by the toplevel {@link Slice} that owns the mapping, and
     * only once {@link #byteBuffer} and all its duplicates are no longer in use by any thread. Reading a buffer
     * that aliases an unmapped file throws {@link IllegalStateException}, which the readers translate into
     * {@link IOException}.
     */
    void unmap() {
        final var arenaCurr = arena;
        if (arenaCurr != null) {
            // Unmap the ByteBuffer by closing the arena that was used to map it (#939)
            OffHeapMemory.closeArena(arenaCurr, /* log = */ null);
            arena = null;
        }
    }
}
