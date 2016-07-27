/*
 * This file is part of FastClasspathScanner.
 * 
 * Author: Luke Hutchison
 * 
 * Hosted at: https://github.com/lukehutch/fast-classpath-scanner
 * 
 * --
 *
 * The MIT License (MIT)
 *
 * Copyright (c) 2016 Luke Hutchison
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
package io.github.lukehutch.fastclasspathscanner.utils;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.DecimalFormat;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.lukehutch.fastclasspathscanner.FastClasspathScanner;

public class LogNode {
    private final long timeStamp = System.nanoTime();
    private final String msg;
    private String stackTrace;
    private long elapsedTimeNanos;
    private final Map<String, LogNode> children = new ConcurrentSkipListMap<>();
    private String sortKeyPrefix = "";
    private static AtomicInteger sortKeyUniqueSuffix = new AtomicInteger(0);
    public boolean verbose;

    private static final SimpleDateFormat dateTimeFormatter = new SimpleDateFormat("yyyy-MM-dd'T'HH:mmX");
    private static final DecimalFormat nanoFormatter = new DecimalFormat("0.000000");

    private LogNode(final String sortKeyPrefix, final String msg, final long elapsedTimeNanos,
            final Throwable exception) {
        this.sortKeyPrefix = sortKeyPrefix;
        this.msg = msg;
        this.elapsedTimeNanos = elapsedTimeNanos;
        if (exception != null) {
            final StringWriter writer = new StringWriter();
            exception.printStackTrace(new PrintWriter(writer));
            stackTrace = writer.toString();
        } else {
            stackTrace = null;
        }
    }

    public LogNode() {
        this("", "", /* elapsedTimeNanos = */ -1L, /* exception = */ null);
    }

    private void appendLine(final String timeStampStr, final int indentLevel, final String line,
            final StringBuilder buf) {
        buf.append(timeStampStr);
        buf.append('\t');
        buf.append(FastClasspathScanner.class.getSimpleName());
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

    private void toString(final int indentLevel, final StringBuilder buf) {
        final Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(timeStamp * 1_000_000);
        final String timeStampStr = dateTimeFormatter.format(cal.getTime());

        final String logMsg = indentLevel == 0 ? "FastClasspathScanner version " + FastClasspathScanner.getVersion()
                : elapsedTimeNanos > 0L ? msg + " (took " + nanoFormatter.format(elapsedTimeNanos * 1e-9) + " sec)"
                        : msg;
        appendLine(timeStampStr, indentLevel, logMsg, buf);

        if (stackTrace != null) {
            buf.append(" -- stacktrace:");
            final String[] parts = stackTrace.split("\n");
            for (int i = 0; i < parts.length; i++) {
                buf.append('\n');
                appendLine(timeStampStr, indentLevel, parts[i], buf);
            }
        }

        for (final Entry<String, LogNode> ent : children.entrySet()) {
            final String key = ent.getKey();
            final LogNode child = ent.getValue();
            child.toString(indentLevel + 1, buf);
            // Remove the child node once it has been written, although double-check first to see if additional
            // log entries have been added to the child, as a soft protection against race conditions. In general
            // though, the toplevel toString() method should only be called after all worker threads have stopped. 
            if (child.children.isEmpty()) {
                children.remove(key);
            }
        }
    }

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
     * Call this once the work corresponding with a given log entry has completed to show the elapsed time after the
     * log entry.
     */
    public void addElapsedTime() {
        elapsedTimeNanos = System.nanoTime() - timeStamp;
    }

    private LogNode addChild(final String sortKey, final String msg, final long elapsedTimeNanos,
            final Throwable exception) {
        final String newSortKey = sortKeyPrefix + String.format("-%09d", sortKeyUniqueSuffix.getAndIncrement())
                + sortKey;
        final LogNode newChild = new LogNode(newSortKey, msg, elapsedTimeNanos, exception);
        // Make the sort key unique, so that log entries are not clobbered if keys are reused;
        // increment unique suffix with each new log entry, so that ties are broken in chronological order.
        children.put(newSortKey, newChild);
        return newChild;
    }

    public LogNode log(final String sortKey, final String msg, final long elapsedTimeNanos, final Throwable e) {
        return addChild(sortKey, msg, elapsedTimeNanos, e);
    }

    public LogNode log(final String sortKey, final String msg, final long elapsedTimeNanos) {
        return addChild(sortKey, msg, elapsedTimeNanos, null);
    }

    public LogNode log(final String sortKey, final String msg, final Throwable e) {
        return addChild(sortKey, msg, -1L, e);
    }

    public LogNode log(final String sortKey, final String msg) {
        return addChild(sortKey, msg, -1L, null);
    }

    public LogNode log(final String msg, final long elapsedTimeNanos, final Throwable e) {
        return addChild("", msg, elapsedTimeNanos, e);
    }

    public LogNode log(final String msg, final long elapsedTimeNanos) {
        return addChild("", msg, elapsedTimeNanos, null);
    }

    public LogNode log(final String msg, final Throwable e) {
        return addChild("", msg, -1L, e);
    }

    public LogNode log(final String msg) {
        return addChild("", msg, -1L, null);
    }

    public void flush() {
        System.err.print(this.toString());
    }
}
