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
 * Copyright (c) 2019 Luke Hutchison
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
package nonapi.io.github.classgraph.concurrency;

import java.util.ArrayList;
import java.util.concurrent.Future;

import nonapi.io.github.classgraph.utils.LogNode;

/**
 * An AutoCloseable list of {@code Future<Void>} items that can be used in a try-with-resources block. When close()
 * is called on this list, all items' {@code get()} methods are called, implementing a completion barrier.
 */
class AutoCloseableFutureListWithCompletionBarrier extends ArrayList<Future<Void>> implements AutoCloseable {

    /** The log. */
    private transient final LogNode log;

    /**
     * Constructor.
     *
     * @param size
     *            the size
     * @param log
     *            the log
     */
    AutoCloseableFutureListWithCompletionBarrier(final int size, final LogNode log) {
        super(size);
        this.log = log;
    }

    /* (non-Javadoc)
     * @see java.util.ArrayList#equals(java.lang.Object)
     */
    // Override needed to remove Scrutinizer warning, since log field does not need to be compared in equals()
    @Override
    public boolean equals(final Object o) {
        return super.equals(o);
    }

    /* (non-Javadoc)
     * @see java.util.ArrayList#hashCode()
     */
    @Override
    public int hashCode() {
        return super.hashCode();
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
