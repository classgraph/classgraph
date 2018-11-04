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

import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.DecimalFormat;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.classgraph.ClassGraph;

/**
 * A tree-structured threadsafe log that allows you to add log entries in arbitrary order, and have the output
 * retain a sane order. The order may also be made deterministic by specifying a sort key for log entries.
 */
public class LogNode {
    /**
     * The timestamp at which the log node was created (relative to some arbitrary system timepoint).
     */
    private final long timeStampNano = System.nanoTime();

    /** The timestamp at which the log node was created, in epoch millis. */
    private final long timeStampMillis = System.currentTimeMillis();

    /** The log message. */
    private final String msg;

    /** The stacktrace, if this log entry was due to an exception. */
    private String stackTrace;

    /** The time between when this log entry was created and addElapsedTime() was called. */
    private long elapsedTimeNanos;

    /** The parent LogNode. */
    private LogNode parent;

    /** The child nodes of this log node. */
    private final Map<String, LogNode> children = new ConcurrentSkipListMap<>();

    /** The sort key prefix for deterministic ordering of log entries. */
    private String sortKeyPrefix;

    /** The sort key suffix for this log entry, used to make sort keys unique. */
    private static AtomicInteger sortKeyUniqueSuffix = new AtomicInteger(0);

    /** The date/time formatter (not threadsafe). */
    private static final SimpleDateFormat dateTimeFormatter = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZZ");

    /** The elapsed time formatter. */
    private static final DecimalFormat nanoFormatter = new DecimalFormat("0.000000");

    /**
     * If this is set to true, log entries are output in realtime, as well as added to the LogNode tree. This can
     * help debug situations where log info is never shown, e.g. deadlocks, or where you need to show the log info
     * right up to the point where you hit a breakpoint.
     */
    public static boolean LOG_IN_REALTIME = false;

    /**
     * Create a non-toplevel log node. The order may also be made deterministic by specifying a sort key for log
     * entries.
     */
    private LogNode(final String sortKey, final String msg, final long elapsedTimeNanos,
            final Throwable exception) {
        this.sortKeyPrefix = sortKey;
        this.msg = msg;
        this.elapsedTimeNanos = elapsedTimeNanos;
        if (exception != null) {
            final StringWriter writer = new StringWriter();
            exception.printStackTrace(new PrintWriter(writer));
            stackTrace = writer.toString();
        } else {
            stackTrace = null;
        }
        if (LOG_IN_REALTIME) {
            System.err.println(toString());
        }
    }

    /** Create a toplevel log node. */
    public LogNode() {
        this("", "", /* elapsedTimeNanos = */ -1L, /* exception = */ null);
        this.log("ClassGraph version " + VersionFinder.getVersion());
        JarUtils.logJavaInfo(this);
    }

    /** Append a line to the log output, indenting this log entry according to tree structure. */
    private void appendLine(final String timeStampStr, final int indentLevel, final String line,
            final StringBuilder buf) {
        buf.append(timeStampStr);
        buf.append('\t');
        buf.append(ClassGraph.class.getSimpleName());
        buf.append('\t');
        final int numDashes = 2 * (indentLevel - 1);
        for (int i = 0; i < numDashes; i++) {
            buf.append('-');
        }
        if (numDashes > 0) {
            buf.append(' ');
        }
        buf.append(line);
        buf.append('\n');
    }

    /** Recursively build the log output. */
    private void toString(final int indentLevel, final StringBuilder buf) {
        final Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(timeStampMillis);
        final String timeStampStr;
        synchronized (dateTimeFormatter) {
            timeStampStr = dateTimeFormatter.format(cal.getTime());
        }

        if (msg != null && !msg.isEmpty()) {
            appendLine(timeStampStr, indentLevel,
                    elapsedTimeNanos > 0L
                            ? msg + " (took " + nanoFormatter.format(elapsedTimeNanos * 1e-9) + " sec)" //
                            : msg,
                    buf);
        }
        if (stackTrace != null && !stackTrace.isEmpty()) {
            final String[] parts = stackTrace.split("\n");
            for (String part : parts) {
                appendLine(timeStampStr, indentLevel, part, buf);
            }
        }

        for (final Entry<String, LogNode> ent : children.entrySet()) {
            final LogNode child = ent.getValue();
            child.toString(indentLevel + 1, buf);
        }
    }

    /** Build the log output. Call this on the toplevel log node. */
    @Override
    public String toString() {
        // DateTimeFormatter is not threadsafe
        synchronized (dateTimeFormatter) {
            final StringBuilder buf = new StringBuilder();
            toString(0, buf);
            return buf.toString();
        }
    }

    /**
     * Call this once the work corresponding with a given log entry has completed if you want to show the time taken
     * after the log entry.
     */
    public void addElapsedTime() {
        elapsedTimeNanos = System.nanoTime() - timeStampNano;
    }

    /** Add a child log node. */
    private LogNode addChild(final String sortKey, final String msg, final long elapsedTimeNanos,
            final Throwable exception) {
        final String newSortKey = sortKeyPrefix + "\t" + (sortKey == null ? "" : sortKey) + "\t"
                + String.format("%09d", sortKeyUniqueSuffix.getAndIncrement());
        final LogNode newChild = new LogNode(newSortKey, msg, elapsedTimeNanos, exception);
        newChild.parent = this;
        // Make the sort key unique, so that log entries are not clobbered if keys are reused; increment unique
        // suffix with each new log entry, so that ties are broken in chronological order.
        children.put(newSortKey, newChild);
        return newChild;
    }

    /** Add a child log node for a message. */
    private LogNode addChild(final String sortKey, final String msg, final long elapsedTimeNanos) {
        return addChild(sortKey, msg, elapsedTimeNanos, null);
    }

    /** Add a child log node for an exception. */
    private LogNode addChild(final Throwable exception) {
        return addChild("", "", -1L, exception);
    }

    /**
     * Add a log entry with sort key for deterministic ordering.
     * 
     * @param sortKey
     *            The sort key for the log entry.
     * @param msg
     *            The message.
     * @param elapsedTimeNanos
     *            The elapsed time.
     * @param e
     *            The {@link Throwable} that was thrown.
     * @return a child log node, which can be used to add sub-entries.
     */
    public LogNode log(final String sortKey, final String msg, final long elapsedTimeNanos, final Throwable e) {
        return addChild(sortKey, msg, elapsedTimeNanos).addChild(e);
    }

    /**
     * Add a log entry with sort key for deterministic ordering.
     * 
     * @param sortKey
     *            The sort key for the log entry.
     * @param msg
     *            The message.
     * @param elapsedTimeNanos
     *            The elapsed time.
     * @return a child log node, which can be used to add sub-entries.
     */
    public LogNode log(final String sortKey, final String msg, final long elapsedTimeNanos) {
        return addChild(sortKey, msg, elapsedTimeNanos);
    }

    /**
     * Add a log entry with sort key for deterministic ordering.
     * 
     * @param sortKey
     *            The sort key for the log entry.
     * @param msg
     *            The message.
     * @param e
     *            The {@link Throwable} that was thrown.
     * @return a child log node, which can be used to add sub-entries.
     */
    public LogNode log(final String sortKey, final String msg, final Throwable e) {
        return addChild(sortKey, msg, -1L).addChild(e);
    }

    /**
     * Add a log entry with sort key for deterministic ordering.
     * 
     * @param sortKey
     *            The sort key for the log entry.
     * @param msg
     *            The message.
     * @return a child log node, which can be used to add sub-entries.
     */
    public LogNode log(final String sortKey, final String msg) {
        return addChild(sortKey, msg, -1L);
    }

    /**
     * Add a log entry.
     * 
     * @param msg
     *            The message.
     * @param elapsedTimeNanos
     *            The elapsed time.
     * @param e
     *            The {@link Throwable} that was thrown.
     * @return a child log node, which can be used to add sub-entries.
     */
    public LogNode log(final String msg, final long elapsedTimeNanos, final Throwable e) {
        return addChild("", msg, elapsedTimeNanos).addChild(e);
    }

    /**
     * Add a log entry.
     *
     * @param msg
     *            The message.
     * @param elapsedTimeNanos
     *            The elapsed time.
     * @return a child log node, which can be used to add sub-entries.
     */
    public LogNode log(final String msg, final long elapsedTimeNanos) {
        return addChild("", msg, elapsedTimeNanos);
    }

    /**
     * Add a log entry.
     * 
     * @param msg
     *            The message.
     * @param e
     *            The {@link Throwable} that was thrown.
     * @return a child log node, which can be used to add sub-entries.
     */
    public LogNode log(final String msg, final Throwable e) {
        return addChild("", msg, -1L).addChild(e);
    }

    /**
     * Add a log entry.
     * 
     * @param msg
     *            The message.
     * @return a child log node, which can be used to add sub-entries.
     */
    public LogNode log(final String msg) {
        return addChild("", msg, -1L);
    }

    /**
     * Add a series of log entries. Returns the last LogNode created.
     * 
     * @param msgs
     *            The messages.
     * @return the last log node created, which can be used to add sub-entries.
     */
    public LogNode log(final List<String> msgs) {
        LogNode last = null;
        for (final String msg : msgs) {
            last = log(msg);
        }
        return last;
    }

    /**
     * Add a log entry.
     * 
     * @param e
     *            The {@link Throwable} that was thrown.
     * @return a child log node, which can be used to add sub-entries.
     */
    public LogNode log(final Throwable e) {
        return log("Exception thrown").addChild(e);
    }

    /**
     * Flush out the log to stderr, and clear the log contents. Only call this on the toplevel log node, when
     * threads do not have access to references of internal log nodes so that they cannot add more log entries
     * inside the tree, otherwise log entries may be lost.
     */
    public void flush() {
        if (parent != null) {
            throw new IllegalArgumentException("Only flush the toplevel LogNode");
        }
        final String logOutput = this.toString();
        this.children.clear();
        System.out.flush();
        System.err.print(logOutput);
        System.err.flush();
    }
}
