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
import java.util.concurrent.atomic.AtomicInteger;

import org.jspecify.annotations.Nullable;

import io.github.classgraph.base.LogNode;
import io.github.classgraph.base.internal.utils.VersionFinder;

/**
 * A read-only memory mapping of a whole file, owned and released by the toplevel {@link PathSlice} that mapped it.
 */
// #939
final class FileMapping {
    /** The mapped {@link ByteBuffer} covering the whole file. */
    final ByteBuffer byteBuffer;

    /** The number of active views of this mapping that must be released before the file can be unmapped. */
    private final AtomicInteger openViews = new AtomicInteger();

    /** True once the owning slice has closed. */
    private volatile boolean released;

    /** True once the file has been unmapped (guarded by {@code this}). */
    private boolean unmapped;

    /** Action to run once the file has been unmapped, or null if none (guarded by {@code this}). */
    // #939
    private @Nullable Runnable onUnmapped;

    /** The {@code java.lang.foreign.Arena} used to map the file (JDK 22+), or null if none or closed. */
    private @Nullable Object arena;

    /**
     * Constructor.
     *
     * @param byteBuffer
     *            the mapped byte buffer
     * @param arena
     *            the arena used to map the file, or null if mapped without an arena
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
     * @return the mapping, or null if the file exceeds {@link Slice#MAX_BUFFER_SIZE}, the {@link FileChannel} does
     *         not support memory mapping, or mapping failed.
     */
    static @Nullable FileMapping map(final FileChannel fileChannel, final long fileLength, final Object file,
            final @Nullable LogNode log) {
        // (A file larger than MAX_BUFFER_SIZE cannot be mapped to a single ByteBuffer)
        if (fileLength > Slice.MAX_BUFFER_SIZE) {
            return null;
        }
        // A mapping is not released until the root that mapped the file is closed, which can happen long after the
        // file was mapped -- so load the classes needed to release it now, while the classloader that loaded
        // ClassGraph is certainly still alive
        // #331
        OffHeapMemory.warmUpDirectByteBufferClosing();
        Object arena = null;
        if (VersionFinder.JAVA_MAJOR_VERSION >= 22) {
            arena = OffHeapMemory.openArena();
            if (arena == null) {
                // The arena API should be available on this JDK, but could not be invoked reflectively -- read
                // the file through the FileChannel API instead
                return null;
            }
        } else if (!OffHeapMemory.canInvokeCleaner()) {
            // Without an arena the only way to unmap the file is Unsafe::invokeCleaner, which cannot be called
            // here, so the file would stay mapped until the garbage collector found it unreachable -- read the
            // file through the FileChannel API instead
            if (log != null) {
                log.log("File " + file + " is not memory mapped, since it could not be unmapped again: "
                        + "sun.misc.Unsafe::invokeCleaner is not available (reading the file instead)");
            }
            return null;
        }
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
        } catch (IOException | OutOfMemoryError e) {
            // Retry after GC, which may free unrelated heap or cleaner-managed mappings made elsewhere in the
            // process. Cleaner-managed mappings are released during reference processing, after collection.
            // On JDK 22+, ClassGraph's own mappings use shared arenas: GC cannot release those, even when their
            // Java objects become unreachable. They must be released explicitly by closing their owning arena.
            // On older JDKs, unreachable legacy mappings can also be released by their cleaner.
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
     *             if the file could not be mapped
     */
    private static @Nullable ByteBuffer mapWholeFile(final @Nullable Object arena, final FileChannel fileChannel,
            final long fileLength) throws IOException {
        return arena == null ? fileChannel.map(FileChannel.MapMode.READ_ONLY, 0L, fileLength)
                : OffHeapMemory.mapFileUsingArena(arena, fileChannel, 0L, fileLength);
    }

    /**
     * Acquire a view of this mapping so that the file is not unmapped while the view is open. Must be paired with
     * {@link #releaseView()}.
     *
     * @return true if the view was acquired, or false if the mapping has already been released.
     */
    // #939
    boolean acquireView() {
        // Increment first and read released afterwards, while unmap() sets released first and reads the count
        // afterwards, so that of two threads racing here at least one sees what the other did: either this one
        // sees the mapping released and takes no view, or unmap() sees the view and leaves the file mapped. What
        // cannot happen is the unsafe outcome, where the file is unmapped while this caller can still read it.
        openViews.incrementAndGet();
        if (released) {
            releaseView();
            return false;
        }
        return true;
    }

    /** Release a view acquired by {@link #acquireView()}, unmapping the file if it was the last open view. */
    // #939
    void releaseView() {
        if (openViews.decrementAndGet() == 0 && released && unmapIfNoViewIsOpen()) {
            // The owning slice closed while this view was open, so releasing it is the last chance to unmap the
            // file -- nothing else will look at this mapping again -- and therefore also the last chance to do
            // whatever the owning slice left waiting for the file to be unmapped
            runOnUnmapped();
        }
    }

    /**
     * Run the given action once the file has been unmapped, or immediately if already unmapped.
     *
     * @param action
     *            the action to run once the file has been unmapped.
     */
    // #939
    void runWhenUnmapped(final Runnable action) {
        final boolean fileIsUnmapped;
        synchronized (this) {
            // The last view can be released between unmap() reporting the file still mapped and this call
            fileIsUnmapped = unmapped;
            if (!fileIsUnmapped) {
                onUnmapped = action;
            }
        }
        if (fileIsUnmapped) {
            action.run();
        }
    }

    /** Run the action registered by {@link #runWhenUnmapped(Runnable)}, if any, and clear it. */
    // #939
    private void runOnUnmapped() {
        final Runnable action;
        synchronized (this) {
            action = onUnmapped;
            onUnmapped = null;
        }
        if (action != null) {
            action.run();
        }
    }

    /**
     * Release the memory mapping of the file. Called once by the owning toplevel {@link PathSlice} on close.
     *
     * @return true if the file was successfully unmapped.
     */
    // #939
    boolean unmap() {
        released = true;
        final var arenaCurr = arena;
        if (arenaCurr != null) {
            arena = null;
            // Nothing but closing the arena can unmap a buffer that an arena mapped, since Unsafe::invokeCleaner
            // has no cleaner to invoke on such a buffer -- so rule out the fallback below whether or not the
            // arena closes, rather than leaving a later releaseView() to try a method that cannot work
            synchronized (this) {
                unmapped = true;
            }
            // An arena that will not close leaves the file mapped, which the caller has to be told about: on
            // Windows a mapped file cannot be deleted or overwritten
            return OffHeapMemory.closeArena(arenaCurr, /* log = */ null);
        }
        return unmapIfNoViewIsOpen();
    }

    /**
     * Unmap the file if the owning slice has closed and no view of the mapping is open.
     *
     * @return true if the file has been unmapped.
     */
    private synchronized boolean unmapIfNoViewIsOpen() {
        if (openViews.get() != 0) {
            // A view of the mapping is still open, so releasing it is what will unmap the file
            return false;
        }
        if (!unmapped) {
            // Synchronized, so that two threads racing here cannot both unmap the file
            unmapped = OffHeapMemory.closeDirectByteBuffer(byteBuffer, /* log = */ null);
        }
        return unmapped;
    }
}
