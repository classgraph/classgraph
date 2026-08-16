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
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileChannel.MapMode;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.classgraph.base.internal.log.LogNode;
import io.github.classgraph.base.internal.reflection.ReflectionUtils;
import io.github.classgraph.base.internal.utils.VersionFinder;
import org.jspecify.annotations.Nullable;

/**
 * Allocation, memory-mapping and freeing of off-heap memory.
 *
 * <p>
 * On JDK 22 and later this is done through the {@code java.lang.foreign.Arena} API: buffers are allocated from a
 * shared arena, and closing the arena frees or unmaps all of them at once. On JDK 17 to 21 that API is not
 * available (or not final), so buffers are freed individually by {@code Unsafe#invokeCleaner(ByteBuffer)}. Both
 * APIs are reached by reflection, since ClassGraph compiles against JDK 17.
 */
public final class OffHeapMemory {
    /** The Unsafe.invokeCleaner() method. */
    private static @Nullable Method cleanerCleanMethod;

    /** The Unsafe object. */
    private static @Nullable Object theUnsafe;

    /**
     * True if the reflective handles above have been initialized. Volatile, and only ever assigned while holding
     * the lock on {@link OffHeapMemory}, so that the double-checked locking in {@link #closeDirectByteBuffer} is
     * correctly synchronized: a thread that reads true here is guaranteed to see the fully-initialized handles.
     */
    private static volatile boolean initialized;

    /**
     * Constructor.
     */
    private OffHeapMemory() {
        // Cannot be constructed
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Look up {@code Unsafe#invokeCleaner(ByteBuffer)} and the {@code theUnsafe} singleton.
     */
    private static void lookupCleanMethod() {
        if (VersionFinder.JAVA_MAJOR_VERSION < 22) {
            // Unsafe::invokeCleaner is terminally deprecated, and JDK 24+ reports: "A terminally deprecated method
            // in sun.misc.Unsafe has been called" if it is used. On JDK 22+, direct ByteBuffers are allocated and
            // memory-mapped using the java.lang.foreign.Arena API instead, and they are freed/unmapped by closing
            // the arena that created them, so the cleaner method is only needed on JDK 17-21. See:
            // https://github.com/classgraph/classgraph/issues/899 and:
            // https://github.com/classgraph/classgraph/issues/939
            try {
                // A JVM with no sun.misc.Unsafe throws ClassNotFoundException or LinkageError here, which is
                // caught below, leaving the fields null -- closeDirectByteBufferImpl() then logs and returns false
                final var unsafeClass = Class.forName("sun.misc.Unsafe");
                final var theUnsafeField = unsafeClass.getDeclaredField("theUnsafe");
                theUnsafeField.setAccessible(true);
                theUnsafe = theUnsafeField.get(null);
                cleanerCleanMethod = unsafeClass.getMethod("invokeCleaner", ByteBuffer.class);
                cleanerCleanMethod.setAccessible(true);
            } catch (final SecurityException e) {
                throw new RuntimeException(
                        "You need to grant classgraph RuntimePermission(\"accessClassInPackage.sun.misc\") "
                                + "and ReflectPermission(\"suppressAccessChecks\")",
                        e);
            } catch (final ReflectiveOperationException | LinkageError ex) {
                // Ignore
            }
        }
    }

    /**
     * Close a direct byte buffer.
     *
     * @param byteBuffer
     *            the byte buffer
     * @param log
     *            the log node, or null to skip logging
     * @return true if successful
     */
    private static boolean closeDirectByteBufferImpl(final ByteBuffer byteBuffer, final @Nullable LogNode log) {
        if (!byteBuffer.isDirect()) {
            // Nothing to do
            return true;
        }
        try {
            if (VersionFinder.JAVA_MAJOR_VERSION < 22) {
                if (theUnsafe == null) {
                    if (log != null) {
                        log.log("Could not unmap ByteBuffer, theUnsafe == null");
                    }
                    return false;
                }
                if (cleanerCleanMethod == null) {
                    if (log != null) {
                        log.log("Could not unmap ByteBuffer, cleanMethod == null");
                    }
                    return false;
                }
                try {
                    cleanerCleanMethod.invoke(theUnsafe, byteBuffer);
                    return true;
                } catch (final IllegalArgumentException e) {
                    // Buffer is a duplicate or slice
                    return false;
                }
            } else {
                // JDK 22+: direct ByteBuffers are allocated or memory-mapped using the java.lang.foreign.Arena API,
                // and they are freed/unmapped by closing the arena that created them (see FileSlice#close()),
                // rather than by calling the terminally-deprecated Unsafe::invokeCleaner method (#939). A
                // ByteBuffer that was not created from an arena cannot be closed explicitly, so return false here.
                return false;
            }
        } catch (final ReflectiveOperationException | SecurityException e) {
            if (log != null) {
                log.log("Could not unmap ByteBuffer: " + e);
            }
            return false;
        }
    }

    /**
     * Close a {@code DirectByteBuffer} -- in particular, will unmap a {@link MappedByteBuffer}.
     *
     * @param byteBuffer
     *            The {@link ByteBuffer} to close/unmap.
     * @param log
     *            The log.
     * @return True if the byteBuffer was closed/unmapped.
     */
    public static boolean closeDirectByteBuffer(final ByteBuffer byteBuffer, final @Nullable LogNode log) {
        if (byteBuffer != null && byteBuffer.isDirect()) {
            // Double-checked locking, so that two threads calling this for the first time concurrently cannot both
            // run the lookup and race on the static fields it assigns
            if (!initialized) {
                synchronized (OffHeapMemory.class) {
                    if (!initialized) {
                        lookupCleanMethod();
                        initialized = true;
                    }
                }
            }
            return closeDirectByteBufferImpl(byteBuffer, log);
        } else {
            // Nothing to unmap
            return false;
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    // TODO: once ClassGraph's minimum supported JDK version is 22 or later, the Unsafe reflection code above
    // (lookupCleanMethod and closeDirectByteBufferImpl) can be removed, and the arena methods below can open
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
     * ({@link #closeArena(Object, LogNode)}) frees or unmaps all {@link ByteBuffer}s obtained from it, which on JDK
     * 22+ replaces the use of the terminally-deprecated {@code Unsafe::invokeCleaner} method.
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
                // On JDK 22+, direct ByteBuffers are freed by closing the arena that allocated them
                allocateDirectByteBufferUsingArena(arena, 32);
                closeArena(arena, /* log = */ null);
            } else {
                // On JDK 17-21, buffers are freed by Unsafe::invokeCleaner, which closeDirectByteBuffer looks
                // up reflectively -- sun.misc.Unsafe and that lookup are what needs to be resolved ahead of time
                closeDirectByteBuffer(ByteBuffer.allocateDirect(32), /* log = */ null);
            }
        }
    }
}
