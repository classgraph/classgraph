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

import java.lang.foreign.Arena;
import java.lang.ref.PhantomReference;
import java.lang.ref.ReferenceQueue;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.classgraph.base.LogNode;
import org.jspecify.annotations.Nullable;

/**
 * Freeing of off-heap memory.
 *
 * <p>
 * Off-heap memory is allocated, and files are memory mapped, through a shared {@link Arena}, and closing the arena
 * frees or unmaps all of it at once, and makes a thread that is still reading it throw
 * {@link IllegalStateException} rather than read memory that is no longer there. The garbage collector cannot
 * release memory in a shared arena: only closing the arena does. {@link #freeUnreachableBuffers()} is for the
 * memory that the garbage collector does release, which is any mapping that something other than an arena made.
 */
final class OffHeapMemory {
    /** Not instantiable. */
    private OffHeapMemory() {
        // Cannot be constructed
    }

    /**
     * Close an arena, freeing any memory allocated from it and unmapping any files mapped with it. The memory must
     * no longer be in use by any thread.
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
            // IllegalStateException, if the arena has already been closed, or if another thread is accessing
            // memory in it at this moment
            if (log != null) {
                log.log("Could not close arena: " + e);
            }
            return false;
        }
    }

    /** True once {@link #warmUpDirectByteBufferClosing()} has run. */
    private static final AtomicBoolean warmedUp = new AtomicBoolean(false);

    /**
     * Load the classes needed to free or unmap a direct {@link java.nio.ByteBuffer}, by allocating a small direct
     * {@link java.nio.ByteBuffer} and immediately freeing it again.
     *
     * <p>
     * Freeing a direct {@link java.nio.ByteBuffer} happens when the root that mapped the file is closed, which may
     * be long after the file was read, and possibly from a shutdown hook or a container's teardown code, by which
     * time the classloader that loaded ClassGraph may no longer be able to load anything -- one report had a Maven
     * plugin's Plexus {@code ClassRealm} already closed, so the lambda class implementing the buffer-freeing code
     * could not be defined, and closing threw {@link NoClassDefFoundError}. Loading those classes up front, while
     * the classloader is certainly still alive, means closing needs no classes that are not already loaded.
     */
    // #331
    static void warmUpDirectByteBufferClosing() {
        if (!warmedUp.getAndSet(true)) {
            // Direct ByteBuffers are freed by closing the arena that allocated them
            final var arena = Arena.ofShared();
            arena.allocate(32).asByteBuffer();
            closeArena(arena, /* log = */ null);
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * The longest {@link #freeUnreachableBuffers()} waits for the garbage collector to process the references that
     * a collection found, in milliseconds.
     */
    private static final int REFERENCE_PROCESSING_TIMEOUT_MILLIS = 100;

    /**
     * Ask the garbage collector to run, and wait for the references that the collection found to be processed, so
     * that a mapping that nothing can reach any more is more likely to have been released by the time this returns.
     *
     * <p>
     * This is best effort, and cannot be made reliable: a file is unmapped while the reference to its mapped buffer
     * is processed, and nothing can observe that a particular reference has been processed. That is why ClassGraph
     * maps a file in an arena and unmaps it explicitly rather than leaving it to this -- this is only for what an
     * explicit unmapping cannot reach: an address range that has to be freed before a mapping can be retried, a
     * mapping whose arena would not close, and a temporary file that Windows would not let ClassGraph delete. A JVM
     * started with {@code -XX:+DisableExplicitGC} ignores the request to collect altogether, in which case this
     * returns once the wait times out, having done nothing.
     */
    // #939
    static void freeUnreachableBuffers() {
        // System.gc() returns once the collection itself is over, which is before the references that the
        // collection found have been processed. A phantom reference to an object that the same collection finds
        // unreachable is enqueued while those references are processed, so waiting for it to be enqueued waits for
        // most of that processing -- but the order within one batch of references is arbitrary, so this is a wait
        // that sometimes helps rather than a guarantee. (Measured over 300 rounds of mapping eight files and
        // dropping every reference to them, on JDK 8, 17, 21 and 26: a file was still mapped when System.gc()
        // returned in between 5 and all 300 of the rounds, depending on the JDK and the machine, and waiting for
        // reference processing first moved that in both directions. Two more collections and 100ms cleared every
        // straggler in every run but one, so the collector does get there -- just not by the time the request to
        // collect returns, which is when a caller waiting to delete or overwrite the file needs it gone.)
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
