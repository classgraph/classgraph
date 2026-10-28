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

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import org.jspecify.annotations.Nullable;

import io.github.classgraph.base.internal.utils.VersionFinder;

/**
 * A thread-safe hierarchical log tree used by ClassGraph for verbose logging.
 *
 * <p>
 * Calling any {@code log} method appends a child node and returns it so sub-entries can be nested under it. Sibling
 * entries are ordered by insertion order, or by sort key if one is provided. Calling {@link #flush()} on the root
 * node writes the formatted tree to the {@code io.github.classgraph.ClassGraph} {@link Logger} at {@code INFO}
 * level.
 */
public final class LogNode implements ClassGraphLog {
    // Turn off log4j2 message lookups (CVE-2021-44228) for log4j 2.10-2.14.1 if ClassGraph's java.util.logging
    // output is routed to log4j. This only takes effect if LogNode is initialized (verbose or realtime logging)
    // before log4j initializes, and it never overrides a value the application has set for this system property.
    // https://blog.cloudflare.com/inside-the-log4j2-vulnerability-cve-2021-44228/
    static {
        try {
            System.getProperties().putIfAbsent("log4j2.formatMsgNoLookups", "true");
        } catch (final SecurityException e) {
            // Ignore -- if the system properties cannot be read or written, the mitigation cannot be applied, but
            // this must not throw ExceptionInInitializerError, which would make LogNode unusable
        }
    }

    /** The name of the logger to which verbose log output is written. */
    private static final String LOGGER_NAME = "io.github.classgraph.ClassGraph";

    /** The tag prefix for log entries. */
    private static final String LOG_ENTRY_TAG = "ClassGraph";

    /** The logger. */
    private static final Logger log = Logger.getLogger(LOGGER_NAME);

    /** The creation timestamp in nanoseconds ({@link System#nanoTime()}). */
    private final long timeStampNano = System.nanoTime();

    /** The creation timestamp in epoch milliseconds. */
    private final long timeStampMillis = System.currentTimeMillis();

    /** The log message, or {@code null} if none. */
    private final @Nullable String msg;

    /** The formatted stack trace, if an exception was logged. */
    private final @Nullable String stackTrace;

    /** The elapsed time in nanoseconds recorded by {@link #addElapsedTime()}. */
    private long elapsedTimeNanos;

    /** The parent log node, or {@code null} if this is the root node. */
    private @Nullable LogNode parent;

    /** The child nodes of this log node, keyed by sort key. */
    private final Map<String, LogNode> children = new ConcurrentSkipListMap<>();

    /** The sort key prefix for deterministic ordering of log entries. */
    private final String sortKeyPrefix;

    /** Counter used to assign a unique suffix to each sort key. */
    private static final AtomicInteger sortKeyUniqueSuffix = new AtomicInteger(0);

    /** Timestamp formatter using the system time zone and {@link Locale#US}. */
    private static final DateTimeFormatter dateTimeFormatter = DateTimeFormatter
            .ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US).withZone(ZoneId.systemDefault());

    /** Whether log entries are emitted immediately as well as added to the tree. */
    private final boolean logInRealtime;

    /**
     * Create a non-root log node.
     *
     * @param sortKey
     *            the sort key
     * @param msg
     *            the log message
     * @param elapsedTimeNanos
     *            the elapsed time in nanoseconds
     * @param exception
     *            the exception that was thrown, or {@code null}
     * @param logInRealtime
     *            whether to write each log entry immediately as it is added
     */
    private LogNode(final String sortKey, final @Nullable String msg, final long elapsedTimeNanos,
            final @Nullable Throwable exception, final boolean logInRealtime) {
        this.sortKeyPrefix = sortKey;
        this.msg = msg;
        this.elapsedTimeNanos = elapsedTimeNanos;
        this.logInRealtime = logInRealtime;
        if (exception != null) {
            final StringWriter writer = new StringWriter();
            exception.printStackTrace(new PrintWriter(writer));
            stackTrace = writer.toString();
        } else {
            stackTrace = null;
        }
    }

    /** Create a root log node whose entries are written when {@link #flush()} is called. */
    public LogNode() {
        this(false);
    }

    /**
     * Create a root log node.
     *
     * @param logInRealtime
     *            if {@code true}, log entries are also written immediately as they are added rather than only when
     *            {@link #flush()} is called.
     */
    public LogNode(final boolean logInRealtime) {
        this("", "", /* elapsedTimeNanos = */ -1L, /* exception = */ null, logInRealtime);
        this.log("ClassGraph version "
                + VersionFinder.getVersion(LogNode.class, "io.github.classgraph", "classgraph-base"));
        logJavaInfo();
    }

    /** Log OS and Java runtime information. */
    private void logJavaInfo() {
        log("Operating system: " + VersionFinder.getProperty("os.name") + " "
                + VersionFinder.getProperty("os.version") + " " + VersionFinder.getProperty("os.arch"));
        log("Java version: " + VersionFinder.getProperty("java.version") + " / "
                + VersionFinder.getProperty("java.runtime.version") + " ("
                + VersionFinder.getProperty("java.vendor") + ")");
        log("Java home: " + VersionFinder.getProperty("java.home"));
    }

    /**
     * Append a line to the log output, indenting this log entry according to tree structure.
     *
     * @param timeStampStr
     *            the timestamp string
     * @param indentLevel
     *            the indent level
     * @param line
     *            the line to log
     * @param buf
     *            the buffer to append to
     */
    private static void appendLine(final String timeStampStr, final int indentLevel, final String line,
            final StringBuilder buf) {
        buf.append(timeStampStr);
        buf.append('\t');
        buf.append(LOG_ENTRY_TAG);
        buf.append('\t');
        final var numDashes = 2 * (indentLevel - 1);
        for (var i = 0; i < numDashes; i++) {
            buf.append('-');
        }
        if (numDashes > 0) {
            buf.append(' ');
        }
        buf.append(line);
        buf.append('\n');
    }

    /**
     * Recursively build the log output.
     *
     * @param indentLevel
     *            the indent level
     * @param buf
     *            the buffer to append to
     */
    private void toString(final int indentLevel, final StringBuilder buf) {
        final var timeStampStr = dateTimeFormatter.format(Instant.ofEpochMilli(timeStampMillis));
        if (msg != null && !msg.isEmpty()) {
            // Locale.US, so that the decimal separator is not a comma in some locales
            appendLine(timeStampStr, indentLevel,
                    elapsedTimeNanos > 0L
                            ? msg + " (took " + String.format(Locale.US, "%.6f", elapsedTimeNanos * 1e-9) + " sec)"
                            : msg,
                    buf);
        }
        if (stackTrace != null && !stackTrace.isEmpty()) {
            // A stack trace is written by PrintWriter#println, which ends each line with the platform line
            // separator, so on Windows the lines are separated by "\r\n" rather than by "\n"
            final var parts = stackTrace.split("\\r?\\n");
            for (final String part : parts) {
                appendLine(timeStampStr, indentLevel, part, buf);
            }
        }

        for (final LogNode child : children.values()) {
            child.toString(indentLevel + 1, buf);
        }
    }

    /**
     * Format this log tree as a string.
     *
     * @return the formatted log output
     */
    @Override
    public String toString() {
        final var buf = new StringBuilder();
        toString(0, buf);
        return buf.toString();
    }

    /** Record the time elapsed since this log entry was created. */
    public void addElapsedTime() {
        elapsedTimeNanos = System.nanoTime() - timeStampNano;
    }

    /**
     * Add a child log node.
     *
     * @param sortKey
     *            the sort key
     * @param msg
     *            the log message
     * @param elapsedTimeNanos
     *            the elapsed time in nanos
     * @param exception
     *            the exception that was thrown
     * @return the log node
     */
    private LogNode addChild(final String sortKey, final @Nullable String msg, final long elapsedTimeNanos,
            final @Nullable Throwable exception) {
        final var newSortKey = sortKeyPrefix + "\t" + sortKey + "\t"
                + String.format(Locale.US, "%09d", sortKeyUniqueSuffix.getAndIncrement());
        final LogNode newChild = new LogNode(newSortKey, msg, elapsedTimeNanos, exception, logInRealtime);
        newChild.parent = this;
        // Make the sort key unique, so that log entries are not clobbered if keys are reused; increment unique
        // suffix with each new log entry, so that ties are broken in chronological order.
        children.put(newSortKey, newChild);
        if (logInRealtime) {
            // Written here rather than in the constructor, since the depth of the entry is only known once it has
            // a parent
            var depth = 0;
            for (var node = newChild; node.parent != null; node = node.parent) {
                depth++;
            }
            final var buf = new StringBuilder();
            newChild.toString(depth, buf);
            log.info(buf.toString());
        }
        return newChild;
    }

    /**
     * Add a child log node for a message.
     *
     * @param sortKey
     *            the sort key
     * @param msg
     *            the log message
     * @param elapsedTimeNanos
     *            the elapsed time in nanos
     * @return the log node
     */
    private LogNode addChild(final String sortKey, final @Nullable String msg, final long elapsedTimeNanos) {
        return addChild(sortKey, msg, elapsedTimeNanos, null);
    }

    /**
     * Add a child log node for an exception.
     *
     * @param exception
     *            the exception that was thrown
     * @return the log node
     */
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
    public LogNode log(final String sortKey, final @Nullable String msg, final long elapsedTimeNanos,
            final Throwable e) {
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
    public LogNode log(final String sortKey, final @Nullable String msg, final long elapsedTimeNanos) {
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
    public LogNode log(final String sortKey, final @Nullable String msg, final Throwable e) {
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
    public LogNode log(final String sortKey, final @Nullable String msg) {
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
    public LogNode log(final @Nullable String msg, final long elapsedTimeNanos, final Throwable e) {
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
    public LogNode log(final @Nullable String msg, final long elapsedTimeNanos) {
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
    @Override
    public LogNode log(final @Nullable String msg, final Throwable e) {
        return addChild("", msg, -1L).addChild(e);
    }

    /**
     * Add a log entry.
     *
     * @param msg
     *            The message.
     * @return a child log node, which can be used to add sub-entries.
     */
    @Override
    public LogNode log(final @Nullable String msg) {
        return addChild("", msg, -1L);
    }

    /**
     * Add a series of log entries. Returns the last LogNode created.
     *
     * @param msgs
     *            The messages.
     * @return the last log node created, which can be used to add sub-entries, or null if {@code msgs} was empty.
     */
    public @Nullable LogNode log(final Collection<String> msgs) {
        LogNode last = null;
        for (final String m : msgs) {
            last = log(m);
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
     * Write the log tree to the {@code io.github.classgraph.ClassGraph} {@link Logger} at {@code INFO} level and
     * clear all child entries. Must only be called on the root log node after concurrent logging has completed.
     */
    public void flush() {
        if (parent != null) {
            throw new IllegalStateException("Only flush the toplevel LogNode");
        }
        if (!children.isEmpty()) {
            final var logOutput = this.toString();
            this.children.clear();
            log.info(logOutput);
        }
    }
}
