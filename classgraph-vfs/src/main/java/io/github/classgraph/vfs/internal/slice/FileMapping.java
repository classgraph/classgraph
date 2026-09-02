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
 * the file is mapped through {@link FileChannel#map} instead, and unmapping it frees the address range whether or
 * not anything is still reading it. So on those JDK versions the file is unmapped once the owning slice has closed
 * <i>and</i> every view of the mapping that the caller could still read has been released -- see {@link #unmap()},
 * {@link #acquireView()} and {@link #releaseView()}.
 */
// #939
final class FileMapping {
    /** The mapped {@link ByteBuffer}, covering the whole file. */
    final ByteBuffer byteBuffer;

    /**
     * The number of views of the mapping that the caller could still read, and that therefore have to be released
     * before the file can be unmapped. Only views that outlive the call that produced them are counted: a buffer
     * handed to the caller of {@code VfsEntry#read()} is one, whereas a read that copies bytes out of the mapping
     * and returns is over before it returns.
     */
    private final AtomicInteger openViews = new AtomicInteger();

    /** True once the owning slice has closed, after which no new view of this mapping is handed out. */
    private volatile boolean released;

    /** True once the file has been unmapped. Read and written only while holding the lock on this object. */
    private boolean unmapped;

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
            // Try running garbage collection, then try mapping the file again. (Garbage collection is what can free
            // address space here: a mapping whose ByteBuffer is unreachable is unmapped by its Cleaner once the
            // reference is cleared, which is a phantom-reference mechanism, not finalization. The collection has to
            // be waited for, since the unmapping happens after the collection itself is over.)
            OffHeapMemory.freeUnreachableBuffers();
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
     * Take a view of this mapping, so that the file is not unmapped while the caller can still read the buffer it
     * was handed. Every view has to be released again by {@link #releaseView()}.
     *
     * @return true if a view was taken, or false if the mapping has already been released, in which case there is
     *         nothing left to read and nothing to release.
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

    /** Release a view taken by {@link #acquireView()}, unmapping the file if it was the last one. */
    // #939
    void releaseView() {
        if (openViews.decrementAndGet() == 0 && released) {
            // The owning slice closed while this view was open, so releasing it is the last chance to unmap the
            // file -- nothing else will look at this mapping again
            unmapIfNoViewIsOpen();
        }
    }

    /**
     * Release the memory mapping of the file. Called only by the toplevel {@link Slice} that owns the mapping, as
     * it closes.
     *
     * <p>
     * On JDK 22 and later this unmaps the file by closing the arena that mapped it, and does so even if another
     * thread is still reading it: that read throws {@link IllegalStateException}, which the readers translate into
     * {@link IOException}.
     *
     * <p>
     * Below JDK 22 there is no arena, and the only method that can unmap a file, {@code Unsafe::invokeCleaner},
     * frees the address range whether or not anything is still reading it -- a thread that reads one byte
     * afterwards takes a SIGSEGV that kills the JVM. So the file is unmapped here only if no view of the mapping is
     * open; if one is, the last {@link #releaseView()} unmaps it instead. A read that is already in flight on
     * another thread when the owning slice is closed is not covered by either: every reader checks that the file is
     * still mapped before it reads, but a close that lands between that check and the read itself can still pull
     * the memory out from under it. Closing a {@link io.github.classgraph.vfs.Vfs} while another thread is reading
     * through it is a use-after-close either way, and is documented as one.
     *
     * @return true if the file has been unmapped by the time this returns, or false if it is left mapped -- either
     *         until the garbage collector finds every view of it unreachable, or, if the arena would not close, for
     *         the rest of the life of the JVM.
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
     * Unmap the file, if the owning slice has closed and no view of the mapping is open.
     *
     * @return true if the file has been unmapped by the time this returns.
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
