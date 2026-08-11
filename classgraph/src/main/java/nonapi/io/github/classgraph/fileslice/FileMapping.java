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
package nonapi.io.github.classgraph.fileslice;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileChannel.MapMode;

import nonapi.io.github.classgraph.utils.FileUtils;
import nonapi.io.github.classgraph.utils.LogNode;
import nonapi.io.github.classgraph.utils.VersionFinder;
import org.jspecify.annotations.Nullable;

/**
 * A memory mapping of a whole file, owned by the toplevel {@link Slice} that mapped it. Sub-slices of that slice
 * share the mapping by duplicating {@link #byteBuffer}, and must not unmap it.
 *
 * <p>
 * On JDK 22+ the file is mapped using a {@code java.lang.foreign.Arena}, so that it can be unmapped by closing the
 * arena, rather than by calling the terminally-deprecated {@code Unsafe::invokeCleaner} method.
 */
// #939
final class FileMapping {
    /** The mapped {@link ByteBuffer}, covering the whole file. */
    final ByteBuffer byteBuffer;

    /**
     * The {@code java.lang.foreign.Arena} (JDK 22+) that was used to map the file, or null if the file was mapped
     * with {@link FileChannel#map(MapMode, long, long)} on an older JDK. Typed as {@link Object}, since ClassGraph
     * needs to compile and run on JDK 17+.
     */
    private @Nullable Object arena;

    /**
     * Constructor.
     *
     * @param byteBuffer
     *            the mapped byte buffer
     * @param arena
     *            the arena that was used to map the file, or null if it was mapped without an arena
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
     * @param scanResources
     *            the resources owned by the scan
     * @param file
     *            the file being mapped, for logging
     * @param log
     *            the log node, or null to skip logging
     * @return the mapping, or null if the file could not be mapped, in which case the caller has to read the file
     *         through the {@link FileChannel} API instead.
     */
    static @Nullable FileMapping map(final FileChannel fileChannel, final long fileLength,
            final ScanResources scanResources, final Object file, final @Nullable LogNode log) {
        // (A file larger than MAX_BUFFER_SIZE cannot be mapped to a single ByteBuffer)
        if (fileLength > FileUtils.MAX_BUFFER_SIZE) {
            return null;
        }
        // (openArena returns null on JDK older than 22)
        final var arena = FileUtils.openArena(scanResources.reflectionUtils);
        ByteBuffer byteBuffer = null;
        try {
            // Try mapping the file (some operating systems throw OutOfMemoryError if the file can't be mapped,
            // some throw IOException)
            byteBuffer = mapFile(arena, fileChannel, fileLength, scanResources);
        } catch (IOException | OutOfMemoryError e) {
            // Try running garbage collection, then try mapping the file again. (Garbage collection is what can free
            // address space here: a mapping whose ByteBuffer is unreachable is unmapped by its Cleaner once the
            // reference is cleared, which is a phantom-reference mechanism, not finalization.)
            System.gc();
            try {
                byteBuffer = mapFile(arena, fileChannel, fileLength, scanResources);
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
                FileUtils.closeArena(arena, scanResources.reflectionUtils, log);
            }
            return null;
        }
        return new FileMapping(byteBuffer, arena);
    }

    /**
     * Map the whole file, using the arena if one was opened.
     *
     * @param arena
     *            the arena to map the file with, or null if the arena API is unavailable (JDK older than 22)
     * @param fileChannel
     *            the {@link FileChannel} of the file to map
     * @param fileLength
     *            the length of the file
     * @param scanResources
     *            the resources owned by the scan
     * @return the mapped byte buffer, or null if the file cannot be mapped by this JDK version or by the
     *         {@link FileChannel} implementation of the filesystem the file is stored in.
     * @throws IOException
     *             if an I/O exception occurred while mapping the file (mapping may succeed if retried after garbage
     *             collection).
     */
    private static @Nullable ByteBuffer mapFile(final @Nullable Object arena, final FileChannel fileChannel,
            final long fileLength, final ScanResources scanResources) throws IOException {
        if (arena != null) {
            return FileUtils.mapFileUsingArena(arena, fileChannel, 0L, fileLength, scanResources.reflectionUtils);
        }
        if (VersionFinder.JAVA_MAJOR_VERSION >= 22) {
            // An arena could not be opened, even though the arena API should be available -- don't fall back to
            // FileChannel#map, since the resulting MappedByteBuffer could only be unmapped by the garbage collector
            // (Unsafe::invokeCleaner is not used on JDK 22+, see #939) -- read through the FileChannel API instead
            return null;
        }
        try {
            return fileChannel.map(MapMode.READ_ONLY, 0L, fileLength);
        } catch (final UnsupportedOperationException e) {
            // The FileChannel of a non-default filesystem provider need not support memory mapping
            return null;
        }
    }

    /**
     * Unmap the file. Must be called only once, and only by the toplevel {@link Slice} that owns the mapping, and
     * only once {@link #byteBuffer} and all its duplicates are no longer in use by any thread.
     *
     * @param scanResources
     *            the resources owned by the scan
     */
    void unmap(final ScanResources scanResources) {
        final var arenaCurr = arena;
        if (arenaCurr != null) {
            // JDK 22+: unmap the ByteBuffer by closing the arena that was used to map it (#939)
            FileUtils.closeArena(arenaCurr, scanResources.reflectionUtils, /* log = */ null);
            arena = null;
        } else {
            FileUtils.closeDirectByteBuffer(byteBuffer, scanResources.reflectionUtils, /* log = */ null);
        }
    }
}
