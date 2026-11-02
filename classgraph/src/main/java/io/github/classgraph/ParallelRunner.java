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
package io.github.classgraph;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.function.IntConsumer;

/**
 * Runs tasks on the worker threads of a scan, and waits for them all to finish.
 */
@FunctionalInterface
interface ParallelRunner {
    /** Runs every task on the calling thread. */
    ParallelRunner SERIAL = tasks -> {
        for (final Runnable task : tasks) {
            task.run();
        }
    };

    /**
     * Run the tasks, and wait for them all to finish.
     *
     * @param tasks
     *            the tasks, which may run in any order, on any thread
     * @throws InterruptedException
     *             if the thread was interrupted
     * @throws ExecutionException
     *             if a task threw an exception
     */
    void runAll(List<Runnable> tasks) throws InterruptedException, ExecutionException;

    /**
     * Call an action once for each index from 0 to {@code size - 1}, with the indices split into contiguous ranges,
     * a few for each thread, so that one task covers many indices.
     *
     * @param size
     *            the number of indices
     * @param action
     *            the action, which may be called for the indices in any order, on any thread
     * @throws InterruptedException
     *             if the thread was interrupted
     * @throws ExecutionException
     *             if the action threw an exception
     */
    default void forEachIndex(final int size, final IntConsumer action)
            throws InterruptedException, ExecutionException {
        // Several ranges per thread, so a thread that finishes early can take another range
        final var numRanges = Math.min(size, 64);
        final List<Runnable> tasks = new ArrayList<>(numRanges);
        for (var r = 0; r < numRanges; r++) {
            final var start = (int) ((long) size * r / numRanges);
            final var end = (int) ((long) size * (r + 1) / numRanges);
            tasks.add(() -> {
                for (var i = start; i < end; i++) {
                    action.accept(i);
                }
            });
        }
        runAll(tasks);
    }
}
