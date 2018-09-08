/*
 * This file is part of ClassGraph.
 *
 * Author: Luke Hutchison (luke.hutch@gmail.com)
 *
 * Hosted at: https://github.com/classgraph/classgraph
 *
 * --
 *
 * The MIT License (MIT)
 *
 * Copyright (c) 2018 Luke Hutchison
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
package io.github.classgraph.utils;

import java.util.ArrayList;
import java.util.concurrent.Future;

/**
 * An AutoCloseable list of {@code Future<Void>} items that can be used in a try-with-resources block. When close()
 * is called on this list, all items' {@code get()} methods are called, implementing a completion barrier.
 */
class AutoCloseableFutureListWithCompletionBarrier extends ArrayList<Future<Void>> implements AutoCloseable {
    private final LogNode log;

    AutoCloseableFutureListWithCompletionBarrier(final int size, final LogNode log) {
        super(size);
        this.log = log;
    }

    /** Completion barrier. */
    @Override
    public void close() {
        for (final Future<Void> future : this) {
            try {
                future.get();
            } catch (final Exception e) {
                if (log != null) {
                    log.log("Exception while waiting for future result", e);
                }
            }
        }
    }
}
