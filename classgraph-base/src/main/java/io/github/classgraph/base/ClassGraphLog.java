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
 * A node of the verbose log that ClassGraph writes when {@code ClassGraph#verbose()} is enabled.
 *
 * <p>
 * The log is a tree, not a flat sequence of lines: each entry can have sub-entries, which are indented beneath it
 * in the output. Adding an entry therefore returns the node that sub-entries of that entry are added to.
 *
 * <p>
 * The log is written in an arbitrary order by many threads at once, and only sorted into a sane order when it is
 * flushed at the end of a scan, so a message is not printed at the moment it is logged.
 *
 * <p>
 * A log node is only handed to code that ClassGraph calls out to, such as a
 * {@code io.github.classgraph.classpath.ClassLoaderHandler}. The node is null when verbose logging is switched off,
 * so every use of it has to be guarded with a null check.
 */
public interface ClassGraphLog {
    /**
     * Add an entry to the log.
     *
     * @param msg
     *            the message.
     * @return the log node that sub-entries of this entry can be added to.
     */
    ClassGraphLog log(@Nullable String msg);

    /**
     * Add an entry to the log, with the stacktrace of a {@link Throwable} beneath it.
     *
     * @param msg
     *            the message.
     * @param e
     *            the {@link Throwable} that was thrown.
     * @return the log node that sub-entries of this entry can be added to.
     */
    ClassGraphLog log(@Nullable String msg, Throwable e);
}
