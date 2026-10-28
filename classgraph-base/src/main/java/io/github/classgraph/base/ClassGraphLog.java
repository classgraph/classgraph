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
package io.github.classgraph.base;

import org.jspecify.annotations.Nullable;

/**
 * A node in the hierarchical verbose log written when {@code ClassGraph#verbose()} is enabled.
 *
 * <p>
 * Each {@code log} call appends a child entry and returns the child node so sub-entries can be nested beneath it.
 * Entries may be recorded concurrently from multiple threads and are sorted and emitted when the log is flushed at
 * the end of a scan.
 *
 * <p>
 * Log references passed to callbacks such as {@code io.github.classgraph.classpath.ClassLoaderHandler} are
 * {@code null} when verbose logging is disabled, so callers must check for {@code null} before logging.
 */
public interface ClassGraphLog {
    /**
     * Add an entry to the log.
     *
     * @param msg
     *            the message.
     * @return the child log node for adding sub-entries.
     */
    ClassGraphLog log(@Nullable String msg);

    /**
     * Add an entry to the log with the stack trace of a {@link Throwable} beneath it.
     *
     * @param msg
     *            the message.
     * @param e
     *            the {@link Throwable} that was thrown.
     * @return the child log node for adding sub-entries.
     */
    ClassGraphLog log(@Nullable String msg, Throwable e);
}
