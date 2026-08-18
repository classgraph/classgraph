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
 * A memory mapping of a whole file, owned by the toplevel {@link Slice} that mapped it. Every slice of that file
 * reads through {@link #byteBuffer}, and only the owning slice may release it.
 *
 * <p>
 * On JDK 22 and later the file is mapped through a {@code java.lang.foreign.Arena}, so that closing the arena
 * unmaps the file the moment the owning slice is closed. Arenas were only finalized in JDK 22, so on JDK 17 to 21
 * the file is mapped through {@link FileChannel#map} instead, and is unmapped by the JDK's own cleaner once the
 * mapped buffer and every view of it have become unreachable -- see {@link #unmap()}.
 */
// #939
final class FileMapping {
    /** The mapped {@link ByteBuffer}, covering the whole file. */
    final ByteBuffer byteBuffer;

    /**
     * The {@code java.lang.foreign.Arena} that was used to map the file, or null if the file was mapped without an
     * arena (on a JDK older than 22), or once {@link #unmap()} has closed it. Typed as {@link Object}, since
     * ClassGraph needs to compile and run on JDK 17+.
     */
    private @Nullable Object arena;

    /**
     * Constructor.
     *
     * @param byteBuffer
     *            the mapped byte buffer
     * @param arena
     *            the arena that was used to map the file, or null if the file was mapped without one
     */
    private FileMapping(final ByteBuffer byteBuffer, final @Nullable Object arena) {
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
     *         through the {@link FileChannel} API instead.
     */
    static @Nullable FileMapping map(final FileChannel fileChannel, final long fileLength, final Object file,
            final @Nullable LogNode log) {
        // (A file larger than MAX_BUFFER_SIZE cannot be mapped to a single ByteBuffer)
        if (fileLength > Slice.MAX_BUFFER_SIZE) {
            return null;
        }
        Object arena = null;
        if (VersionFinder.JAVA_MAJOR_VERSION >= 22) {
            // Mapping a file through an arena is the only thing that makes ClassGraph allocate off-heap memory,
            // and it is not freed until the reading session is closed, which can happen long after the file was
            // mapped -- so load the classes needed to free it now, while the classloader that loaded ClassGraph
            // is certainly still alive
            // #331
            OffHeapMemory.warmUpDirectByteBufferClosing();
            arena = OffHeapMemory.openArena();
            if (arena == null) {
                // The arena API should be available on this JDK, but could not be invoked reflectively -- read
                // the file through the FileChannel API instead
                return null;
            }
        }
        ByteBuffer byteBuffer = null;
        try {
            // Try mapping the file (some operating systems throw OutOfMemoryError if the file can't be mapped,
            // some throw IOException)
            byteBuffer = mapWholeFile(arena, fileChannel, fileLength);
        } catch (IOException | OutOfMemoryError e) {
            // Try running garbage collection, then try mapping the file again. (Garbage collection is what can free
            // address space here: a mapping whose ByteBuffer is unreachable is unmapped by its Cleaner once the
            // reference is cleared, which is a phantom-reference mechanism, not finalization.)
            System.gc();
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
            if (arena != null) {
                // The arena ended up not being used to map the file -- close it again
                OffHeapMemory.closeArena(arena, log);
            }
            return null;
        }
        return new FileMapping(byteBuffer, arena);
    }

    /**
     * Map a whole file, through an arena if there is one.
     *
     * @param arena
     *            the {@code java.lang.foreign.Arena} to map the file through, or null to map the file through
     *            {@link FileChannel#map} instead
     * @param fileChannel
     *            the {@link FileChannel} of the file to map
     * @param fileLength
     *            the length of the file
     * @return the mapped byte buffer, or null if an arena was given but its methods could not be invoked
     *         reflectively
     * @throws IOException
     *             if the file could not be mapped (mapping may succeed if it is retried after garbage collection)
     */
    private static @Nullable ByteBuffer mapWholeFile(final @Nullable Object arena, final FileChannel fileChannel,
            final long fileLength) throws IOException {
        return arena == null ? fileChannel.map(FileChannel.MapMode.READ_ONLY, 0L, fileLength)
                : OffHeapMemory.mapFileUsingArena(arena, fileChannel, 0L, fileLength);
    }

    /**
     * Release the memory mapping of the file. Must be called only once, and only by the toplevel {@link Slice} that
     * owns the mapping.
     *
     * <p>
     * On JDK 22 and later this unmaps the file, by closing the arena that mapped it, so it must not be called until
     * {@link #byteBuffer} and every view of it are no longer in use by any thread: reading a view of an unmapped
     * file throws {@link IllegalStateException}, which the readers translate into {@link IOException}.
     *
     * <p>
     * Below JDK 22 there is no arena, and nothing can unmap the file on demand -- the only method that could,
     * {@code Unsafe::invokeCleaner}, frees the address range whether or not another thread is reading it, and a
     * thread that reads one byte afterwards takes a SIGSEGV that kills the JVM. The JDK's own cleaner unmaps the
     * file instead, once the mapped buffer and every view of it have become unreachable, which is why the owning
     * slice drops its reference to the buffer as it closes, and why no slice keeps a view of the mapping of its
     * own.
     */
    // #939
    void unmap() {
        final var arenaCurr = arena;
        if (arenaCurr != null) {
            // Unmap the ByteBuffer by closing the arena that was used to map it
            OffHeapMemory.closeArena(arenaCurr, /* log = */ null);
            arena = null;
        }
    }
}
