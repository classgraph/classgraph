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
import java.lang.ref.PhantomReference;
import java.lang.ref.ReferenceQueue;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel.MapMode;
import java.nio.channels.FileChannel;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.classgraph.base.LogNode;
import io.github.classgraph.base.internal.reflection.ReflectionUtils;
import io.github.classgraph.base.internal.utils.VersionFinder;
import org.jspecify.annotations.Nullable;

/**
 * Allocation, memory-mapping and freeing of off-heap memory.
 *
 * <p>
 * This is done through the {@code java.lang.foreign.Arena} API, which was finalized in JDK 22: buffers are
 * allocated from a shared arena, and closing the arena frees or unmaps all of them at once. The API is reached by
 * reflection, since ClassGraph compiles against JDK 17. On JDK 17 to 21 the API is unavailable (or not final), so
 * {@link #openArena()} returns null, and no off-heap memory is allocated at all -- there is no way to free it again
 * on those JDK versions that is safe to call while another thread may still be reading the buffer.
 *
 * <p>
 * A file can still be memory mapped without an arena on those JDK versions, since a mapping is not allocated memory
 * that has to be freed: it is released once the garbage collector finds every view of it unreachable.
 * {@link #freeUnreachableBuffers()} asks for that to happen, and waits until it has.
 */
public final class OffHeapMemory {
    /**
     * Constructor.
     */
    private OffHeapMemory() {
        // Cannot be constructed
    }

    // -------------------------------------------------------------------------------------------------------------

    // TODO: once ClassGraph's minimum supported JDK version is 22 or later, the arena methods below can open
    // and close arenas, and allocate and memory-map ByteBuffers, by calling the java.lang.foreign API directly
    // rather than through reflection.

    /**
     * The fully-qualified name of the JDK 22+ {@code java.lang.foreign.Arena} interface.
     */
    private static final String ARENA_CLASS_NAME = "java.lang.foreign.Arena";

    /**
     * Open a new shared {@code java.lang.foreign.Arena} (JDK 22+), which can be used to allocate direct
     * {@link ByteBuffer}s ({@link #allocateDirectByteBufferUsingArena(Object, long)}) and to memory-map files to
     * {@link ByteBuffer}s ({@link #mapFileUsingArena(Object, FileChannel, long, long)}). Closing the arena
     * ({@link #closeArena(Object, LogNode)}) frees or unmaps all {@link ByteBuffer}s obtained from it, in place of
     * the terminally-deprecated {@code Unsafe::invokeCleaner} method.
     *
     * @return a new shared {@code Arena} instance, or null if the arena API is not available (JDK older than 22).
     */
    // #939
    public static @Nullable Object openArena() {
        if (VersionFinder.JAVA_MAJOR_VERSION < 22) {
            // The java.lang.foreign API was only finalized in JDK 22 (the preview versions of the API in JDK 19-21
            // cannot be invoked reflectively without --enable-preview)
            return null;
        }
        final Class<?> arenaClass = ReflectionUtils.classForNameOrNull(ARENA_CLASS_NAME);
        if (arenaClass == null) {
            return null;
        }
        // Invoke Arena.ofShared() -- a shared arena is needed rather than a confined arena, since the ByteBuffers
        // obtained from the arena may be read and closed by multiple threads
        return ReflectionUtils.invokeStaticMethod(/* throwException = */ false, arenaClass, "ofShared");
    }

    /**
     * Allocate a direct {@link ByteBuffer} using a shared arena (JDK 22+). The buffer is freed by closing the
     * arena.
     *
     * @param arena
     *            an arena obtained from {@link #openArena()}.
     * @param size
     *            the number of bytes to allocate.
     * @return the allocated {@link ByteBuffer}, or null if the buffer could not be allocated.
     */
    public static @Nullable ByteBuffer allocateDirectByteBufferUsingArena(final Object arena, final long size) {
        // Invoke arena.allocate(size).asByteBuffer()
        final var memorySegment = ReflectionUtils.invokeMethod(/* throwException = */ false, arena, "allocate",
                long.class, size);
        return memorySegment == null ? null
                : (ByteBuffer) ReflectionUtils.invokeMethod(/* throwException = */ false, memorySegment,
                        "asByteBuffer");
    }

    /**
     * Memory-map a region of a {@link FileChannel} to a read-only {@link ByteBuffer} using a shared arena (JDK
     * 22+). The buffer is unmapped by closing the arena.
     *
     * @param arena
     *            an arena obtained from {@link #openArena()}.
     * @param fileChannel
     *            the file channel to map.
     * @param position
     *            the position within the file at which the mapped region is to start.
     * @param size
     *            the size of the region to map (must not be larger than {@link Slice#MAX_BUFFER_SIZE}, since the
     *            mapped memory segment has to be projected to a single {@link ByteBuffer}).
     * @return the mapped {@link ByteBuffer}, or null if the arena-based mapping API could not be invoked
     *         reflectively.
     * @throws IOException
     *             if mapping the file failed with an I/O error (mapping may succeed if retried after garbage
     *             collection, see FileSlice).
     */
    public static @Nullable ByteBuffer mapFileUsingArena(final Object arena, final FileChannel fileChannel,
            final long position, final long size) throws IOException {
        final Class<?> arenaClass = ReflectionUtils.classForNameOrNull(ARENA_CLASS_NAME);
        if (arenaClass == null) {
            return null;
        }
        try {
            // Invoke fileChannel.map(MapMode.READ_ONLY, position, size, arena).asByteBuffer()
            final var memorySegment = ReflectionUtils.invokeMethod(/* throwException = */ true, fileChannel, "map",
                    new Class<?>[] { MapMode.class, long.class, long.class, arenaClass },
                    new Object[] { MapMode.READ_ONLY, position, size, arena });
            return memorySegment == null ? null
                    : (ByteBuffer) ReflectionUtils.invokeMethod(/* throwException = */ true, memorySegment,
                            "asByteBuffer");
        } catch (final Exception e) {
            // Mapping the file can fail with IOException or OutOfMemoryError, which the reflective method
            // invocation wraps in other exceptions -- unwrap and rethrow, so that the caller can retry mapping
            // after running garbage collection
            for (Throwable t = e; t != null; t = t.getCause()) {
                if (t instanceof final IOException ioException) {
                    throw ioException;
                } else if (t instanceof final OutOfMemoryError outOfMemoryError) {
                    throw outOfMemoryError;
                }
            }
            // The reflective invocation itself failed -- the caller will fall back to the FileChannel API
            return null;
        }
    }

    /**
     * Close an arena obtained from {@link #openArena()}, freeing any direct {@link ByteBuffer}s allocated from it
     * and unmapping any files mapped with it. The buffers must no longer be in use by any thread.
     *
     * @param arena
     *            the arena to close.
     * @param log
     *            the log node, or null to skip logging
     * @return true if the arena was successfully closed.
     */
    public static boolean closeArena(final Object arena, final @Nullable LogNode log) {
        try {
            ReflectionUtils.invokeMethod(/* throwException = */ true, arena, "close");
            return true;
        } catch (final Exception e) {
            if (log != null) {
                log.log("Could not close arena: " + e);
            }
            return false;
        }
    }

    /** True once {@link #warmUpDirectByteBufferClosing()} has run. */
    private static final AtomicBoolean warmedUp = new AtomicBoolean(false);

    /**
     * Load the classes needed to free or unmap a direct {@link ByteBuffer}, by allocating a small direct
     * {@link ByteBuffer} and immediately freeing it again.
     *
     * <p>
     * Freeing a direct {@link ByteBuffer} happens when a {@code ScanResult} is closed, which may be long after the
     * scan itself, and possibly from a shutdown hook or a container's teardown code, by which time the classloader
     * that loaded ClassGraph may no longer be able to load anything -- one report had a Maven plugin's Plexus
     * {@code ClassRealm} already closed, so the lambda class implementing the buffer-freeing code could not be
     * defined, and closing threw {@link NoClassDefFoundError}. Loading those classes up front, while the
     * classloader is certainly still alive, means closing needs no classes that are not already loaded.
     *
     */
    // #331
    public static void warmUpDirectByteBufferClosing() {
        if (!warmedUp.getAndSet(true)) {
            final var arena = openArena();
            if (arena != null) {
                // Direct ByteBuffers are freed by closing the arena that allocated them
                allocateDirectByteBufferUsingArena(arena, 32);
                closeArena(arena, /* log = */ null);
            }
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * The longest {@link #freeUnreachableBuffers()} waits for the garbage collector to process the references that
     * a collection found, in milliseconds.
     */
    private static final int REFERENCE_PROCESSING_TIMEOUT_MILLIS = 100;

    /**
     * Ask the garbage collector to run, and wait for it to process the references that the collection found, so
     * that by the time this returns, a file that was mapped without an arena and whose every view has become
     * unreachable has actually been unmapped.
     *
     * <p>
     * This is best effort: nothing can unmap a file on demand without an arena, and nothing can observe that the
     * collector has unmapped it. A JVM started with {@code -XX:+DisableExplicitGC} ignores the request to collect
     * altogether, in which case this returns once the wait times out, having done nothing.
     */
    // #939
    public static void freeUnreachableBuffers() {
        // System.gc() returns once the collection itself is over, which is before the references that the
        // collection found have been processed -- and a file is unmapped while the reference to its mapped buffer
        // is processed, not while the collection runs. A phantom reference to an object that the same collection
        // finds unreachable is enqueued during that same reference processing, so waiting for it to be enqueued
        // waits for the unmapping too. (Measured on JDK 8 and 17, mapping a file and dropping the reference to
        // it: without the wait the file was still mapped when System.gc() returned about one time in a hundred;
        // with the wait, it was never still mapped in 4000 tries.)
        final var collected = new ReferenceQueue<>();
        final var canary = new PhantomReference<>(new Object(), collected);
        System.gc();
        try {
            // Bounded, so that a JVM that ignores the request to collect cannot make this wait forever
            collected.remove(REFERENCE_PROCESSING_TIMEOUT_MILLIS);
        } catch (final InterruptedException e) {
            // Leave the files to be unmapped by a later collection, and let the caller see the interruption
            Thread.currentThread().interrupt();
        }
        // Keep the canary reachable until it has been waited for -- a phantom reference that has itself become
        // unreachable is never enqueued
        canary.clear();
    }
}
