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
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileChannel.MapMode;
import java.util.concurrent.atomic.AtomicBoolean;

import org.jspecify.annotations.Nullable;

import io.github.classgraph.base.LogNode;
import io.github.classgraph.base.internal.reflection.ReflectionUtils;
import io.github.classgraph.base.internal.utils.VersionFinder;

/**
 * Reflective allocation, memory-mapping, and unmapping of off-heap memory via {@code java.lang.foreign.Arena} (JDK
 * 22+) or {@code sun.misc.Unsafe#invokeCleaner} (JDK 17–21).
 */
final class OffHeapMemory {
    /** Not instantiable. */
    private OffHeapMemory() {
        // Cannot be constructed
    }

    // -------------------------------------------------------------------------------------------------------------

    // TODO: once ClassGraph's minimum supported JDK version is 22 or later, the reflective lookup of
    // Unsafe::invokeCleaner below can be deleted, since a mapping is then always made in an arena and unmapped by
    // closing it.

    /** The {@code Unsafe#invokeCleaner(ByteBuffer)} method, or null if unavailable. */
    private static @Nullable Method invokeCleanerMethod;

    /** The {@code sun.misc.Unsafe} singleton, or null if unavailable. */
    private static @Nullable Object theUnsafe;

    /** True once {@link #lookupInvokeCleanerMethod()} has run. */
    private static volatile boolean initialized;

    /** Look up {@code Unsafe#invokeCleaner(ByteBuffer)} and the {@code theUnsafe} singleton. */
    private static void lookupInvokeCleanerMethod() {
        try {
            // A JVM with no sun.misc.Unsafe leaves the fields null -- closeDirectByteBufferImpl() then logs and
            // returns false
            final var unsafeClass = ReflectionUtils.classForNameOrNull("sun.misc.Unsafe");
            if (unsafeClass == null) {
                return;
            }
            final var theUnsafeField = unsafeClass.getDeclaredField("theUnsafe");
            theUnsafeField.setAccessible(true);
            theUnsafe = theUnsafeField.get(null);
            invokeCleanerMethod = unsafeClass.getMethod("invokeCleaner", ByteBuffer.class);
            invokeCleanerMethod.setAccessible(true);
        } catch (final ReflectiveOperationException | LinkageError | SecurityException e) {
            // Ignore -- closeDirectByteBuffer() returns false, and the mapping is left to the garbage collector. (A
            // SecurityManager that denies RuntimePermission("accessClassInPackage.sun.misc") or
            // ReflectPermission("suppressAccessChecks") ends up here too.)
            theUnsafe = null;
            invokeCleanerMethod = null;
        }
    }

    /**
     * Unmap a memory-mapped {@link ByteBuffer} using {@code Unsafe#invokeCleaner}.
     *
     * @param byteBuffer
     *            the buffer to unmap
     * @param log
     *            the log node, or null to skip logging
     * @return true if the buffer was unmapped
     */
    private static boolean closeDirectByteBufferImpl(final ByteBuffer byteBuffer, final @Nullable LogNode log) {
        final var unsafe = theUnsafe;
        final var invokeCleaner = invokeCleanerMethod;
        if (unsafe == null || invokeCleaner == null) {
            if (log != null) {
                log.log("Could not unmap ByteBuffer: sun.misc.Unsafe is not available");
            }
            return false;
        }
        try {
            invokeCleaner.invoke(unsafe, byteBuffer);
            return true;
        } catch (final InvocationTargetException e) {
            // invokeCleaner throws IllegalArgumentException if the buffer is a duplicate or a slice of the mapping,
            // not the mapping itself. Method.invoke wraps whatever it throws. An Error, such as OutOfMemoryError,
            // is not caught, as on the rest of the path that closes a root
            if (e.getCause() instanceof final Error error) {
                throw error;
            }
            if (log != null && !(e.getCause() instanceof IllegalArgumentException)) {
                log.log("Could not unmap ByteBuffer: " + e.getCause());
            }
            return false;
        } catch (final ReflectiveOperationException | SecurityException e) {
            if (log != null) {
                log.log("Could not unmap ByteBuffer: " + e);
            }
            return false;
        }
    }

    /**
     * Unmap a direct {@link ByteBuffer} mapped without an arena using {@code Unsafe#invokeCleaner}. Must only be
     * called on the root mapped buffer once all views of the mapping have been released.
     *
     * @param byteBuffer
     *            the mapped {@link ByteBuffer} to unmap.
     * @param log
     *            the log node, or null to skip logging
     * @return true if the buffer was unmapped.
     */
    // #939
    static boolean closeDirectByteBuffer(final ByteBuffer byteBuffer, final @Nullable LogNode log) {
        if (!byteBuffer.isDirect()) {
            // A heap ByteBuffer has nothing to unmap
            return false;
        }
        ensureInvokeCleanerMethodLookedUp();
        return closeDirectByteBufferImpl(byteBuffer, log);
    }

    /**
     * Check whether {@code Unsafe#invokeCleaner} is available to unmap direct {@link ByteBuffer}s.
     *
     * @return true if {@code Unsafe#invokeCleaner} can be called.
     */
    static boolean canInvokeCleaner() {
        ensureInvokeCleanerMethodLookedUp();
        return theUnsafe != null && invokeCleanerMethod != null;
    }

    /** Look up {@code Unsafe#invokeCleaner(ByteBuffer)} if not yet initialized. */
    private static void ensureInvokeCleanerMethodLookedUp() {
        // Double-checked locking, so that two threads calling this for the first time concurrently cannot both run
        // the lookup and race on the static fields it assigns
        if (!initialized) {
            synchronized (OffHeapMemory.class) {
                if (!initialized) {
                    lookupInvokeCleanerMethod();
                    initialized = true;
                }
            }
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    // TODO: once ClassGraph's minimum supported JDK version is 22 or later, the arena methods below can open
    // and close arenas, and allocate and memory-map ByteBuffers, by calling the java.lang.foreign API directly
    // rather than through reflection.

    /** The fully-qualified name of the JDK 22+ {@code java.lang.foreign.Arena} interface. */
    private static final String ARENA_CLASS_NAME = "java.lang.foreign.Arena";

    /**
     * Open a new shared {@code java.lang.foreign.Arena} on JDK 22+.
     *
     * @return a new shared {@code Arena} instance, or null if unavailable (JDK older than 22).
     */
    // #939
    static @Nullable Object openArena() {
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
     * Allocate a direct {@link ByteBuffer} using a shared arena (JDK 22+).
     *
     * @param arena
     *            an arena obtained from {@link #openArena()}.
     * @param size
     *            the number of bytes to allocate.
     * @return the allocated {@link ByteBuffer}, or null if allocation failed.
     */
    static @Nullable ByteBuffer allocateDirectByteBufferUsingArena(final Object arena, final long size) {
        // Invoke arena.allocate(size).asByteBuffer()
        final var memorySegment = ReflectionUtils.invokeMethod(/* throwException = */ false, arena, "allocate",
                long.class, size);
        return memorySegment == null ? null
                : (ByteBuffer) ReflectionUtils.invokeMethod(/* throwException = */ false, memorySegment,
                        "asByteBuffer");
    }

    /**
     * Memory-map a region of a {@link FileChannel} to a read-only {@link ByteBuffer} using a shared arena (JDK
     * 22+).
     *
     * @param arena
     *            an arena obtained from {@link #openArena()}.
     * @param fileChannel
     *            the file channel to map.
     * @param position
     *            the start position within the file.
     * @param size
     *            the size of the region to map (up to {@link Slice#MAX_BUFFER_SIZE}).
     * @return the mapped {@link ByteBuffer}, or null if the arena mapping API could not be invoked reflectively.
     * @throws IOException
     *             if mapping the file failed with an I/O error.
     * @throws UnsupportedOperationException
     *             if the file channel does not support memory mapping.
     */
    static @Nullable ByteBuffer mapFileUsingArena(final Object arena, final FileChannel fileChannel,
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
            // after running garbage collection. Also unwrap UnsupportedOperationException, which a FileChannel
            // that does not support mapping throws, so that the caller can log why the file was not mapped
            for (Throwable t = e; t != null; t = t.getCause()) {
                if (t instanceof final IOException ioException) {
                    throw ioException;
                } else if (t instanceof final OutOfMemoryError outOfMemoryError) {
                    throw outOfMemoryError;
                } else if (t instanceof final UnsupportedOperationException unsupportedOperationException) {
                    throw unsupportedOperationException;
                }
            }
            // The reflective invocation itself failed -- the caller will fall back to the FileChannel API
            return null;
        }
    }

    /**
     * Close an arena obtained from {@link #openArena()}, freeing any buffers allocated from it and unmapping any
     * files mapped with it.
     *
     * @param arena
     *            the arena to close.
     * @param log
     *            the log node, or null to skip logging
     * @return true if the arena was closed.
     */
    static boolean closeArena(final Object arena, final @Nullable LogNode log) {
        try {
            ReflectionUtils.invokeMethod(/* throwException = */ true, arena, "close");
            return true;
        } catch (final Exception e) {
            // ReflectionUtils wraps what close() threw. An Error, such as NoClassDefFoundError, is not caught, as
            // on the rest of the path that closes a root
            @Nullable
            Throwable cause = e.getCause();
            while (cause instanceof final InvocationTargetException invocationTargetException) {
                cause = invocationTargetException.getCause();
            }
            if (cause instanceof final Error error) {
                throw error;
            }
            if (log != null) {
                log.log("Could not close arena: " + e);
            }
            return false;
        }
    }

    /** True once {@link #warmUpDirectByteBufferClosing()} has run. */
    private static final AtomicBoolean warmedUp = new AtomicBoolean(false);

    /**
     * Preload the classes required to free or unmap a direct {@link ByteBuffer} so that closing does not need to
     * load classes if the classloader has already been closed.
     */
    // #331
    static void warmUpDirectByteBufferClosing() {
        if (!warmedUp.getAndSet(true)) {
            final var arena = openArena();
            if (arena != null) {
                // Direct ByteBuffers are freed by closing the arena that allocated them
                allocateDirectByteBufferUsingArena(arena, 32);
                closeArena(arena, /* log = */ null);
            } else {
                // Below JDK 22 a file is unmapped by Unsafe::invokeCleaner, which closeDirectByteBuffer looks up
                // reflectively -- sun.misc.Unsafe and that lookup are what has to be resolved ahead of time
                closeDirectByteBuffer(ByteBuffer.allocateDirect(32), /* log = */ null);
            }
        }
    }
}
