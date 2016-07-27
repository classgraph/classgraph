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
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.Queue;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.lukehutch.fastclasspathscanner.FastClasspathScanner;

public abstract class LoggedThread<T> implements Callable<T> {
    protected ThreadLog log = new ThreadLog();

    @Override
    public T call() throws Exception {
        try {
            return doWork();
        } catch (final Throwable e) {
            log.flush();
            if (FastClasspathScanner.verbose) {
                log.log("Thread " + Thread.currentThread().getName() + " threw " + e);
            }
            throw e;
        } finally {
            log.flush();
        }
    }

    public abstract T doWork() throws Exception;

    private static class ThreadLogEntry implements Comparable<ThreadLogEntry> {
        private final int indentLevel;
        private final Date time;
        private final String msg;
        private final String sortKey;
        private final String stackTrace;
        private final long elapsedTimeNanos;
        private static final SimpleDateFormat dateTimeFormatter = new SimpleDateFormat("yyyy-MM-dd'T'HH:mmX");
        private static final DecimalFormat nanoFormatter = new DecimalFormat("0.000000");

        public ThreadLogEntry(final String sortKey, final int indentLevel, final String msg,
                final long elapsedTimeNanos, final Throwable exception) {
            this.indentLevel = indentLevel;
            this.msg = msg;
            this.sortKey = sortKey;
            this.time = Calendar.getInstance().getTime();
            this.elapsedTimeNanos = elapsedTimeNanos;
            if (exception != null) {
                final StringWriter writer = new StringWriter();
                exception.printStackTrace(new PrintWriter(writer));
                stackTrace = writer.toString();
            } else {
                stackTrace = null;
            }
        }

        private void appendLogLine(final String line, final StringBuilder buf) {
            synchronized (dateTimeFormatter) {
                buf.append(dateTimeFormatter.format(time));
            }
            buf.append('\t');
            buf.append(FastClasspathScanner.class.getSimpleName());
            buf.append('\t');
            final int numIndentChars = 2 * indentLevel;
            for (int i = 0; i < numIndentChars - 1; i++) {
                buf.append('-');
            }
            if (numIndentChars > 0) {
                buf.append(" ");
            }
            buf.append(line);
        }

        @Override
        public String toString() {
            final StringBuilder buf = new StringBuilder();
            appendLogLine(msg, buf);
            if (elapsedTimeNanos >= 0L) {
                buf.append(" in ");
                buf.append(nanoFormatter.format(elapsedTimeNanos * 1e-9));
                buf.append(" sec");
            }
            if (stackTrace != null) {
                buf.append(" -- stacktrace:");
                final String[] parts = stackTrace.split("\n");
                for (int i = 0; i < parts.length; i++) {
                    buf.append('\n');
                    appendLogLine(parts[i], buf);
                }
            }
            return buf.toString();
        }

        @Override
        public int compareTo(final ThreadLogEntry o) {
            return this.sortKey.compareTo(o.sortKey);
        }
    }

    /**
     * Class for accumulating ordered log entries from threads, for later writing to the log without interleaving.
     */
    public static class ThreadLog implements AutoCloseable {
        private static AtomicBoolean versionLogged = new AtomicBoolean(false);
        private final Queue<ThreadLogEntry> logEntries = new ConcurrentLinkedQueue<>();

        public void log(final String sortKey, final int indentLevel, final String msg, final long elapsedTimeNanos,
                final Throwable e) {
            logEntries.add(new ThreadLogEntry(sortKey, indentLevel, msg, elapsedTimeNanos, e));
        }

        public void log(final String sortKey, final int indentLevel, final String msg,
                final long elapsedTimeNanos) {
            logEntries.add(new ThreadLogEntry(sortKey, indentLevel, msg, elapsedTimeNanos, null));
        }

        public void log(final String sortKey, final int indentLevel, final String msg, final Throwable e) {
            logEntries.add(new ThreadLogEntry(sortKey, indentLevel, msg, -1L, e));
        }

        public void log(final String sortKey, final int indentLevel, final String msg) {
            logEntries.add(new ThreadLogEntry(sortKey, indentLevel, msg, -1L, null));
        }

        public void log(final String sortKey, final String msg, final long elapsedTimeNanos, final Throwable e) {
            logEntries.add(new ThreadLogEntry(sortKey, 0, msg, elapsedTimeNanos, e));
        }

        public void log(final String sortKey, final String msg, final long elapsedTimeNanos) {
            logEntries.add(new ThreadLogEntry(sortKey, 0, msg, elapsedTimeNanos, null));
        }

        public void log(final String sortKey, final String msg, final Throwable e) {
            logEntries.add(new ThreadLogEntry(sortKey, 0, msg, -1L, e));
        }

        public void log(final String sortKey, final String msg) {
            logEntries.add(new ThreadLogEntry(sortKey, 0, msg, -1L, null));
        }

        public void log(final int indentLevel, final String msg, final long elapsedTimeNanos, final Throwable e) {
            logEntries.add(new ThreadLogEntry("", indentLevel, msg, elapsedTimeNanos, e));
        }

        public void log(final int indentLevel, final String msg, final long elapsedTimeNanos) {
            logEntries.add(new ThreadLogEntry("", indentLevel, msg, elapsedTimeNanos, null));
        }

        public void log(final int indentLevel, final String msg, final Throwable e) {
            logEntries.add(new ThreadLogEntry("", indentLevel, msg, -1L, e));
        }

        public void log(final int indentLevel, final String msg) {
            logEntries.add(new ThreadLogEntry("", indentLevel, msg, -1L, null));
        }

        public void log(final String msg, final long elapsedTimeNanos, final Throwable e) {
            logEntries.add(new ThreadLogEntry("", 0, msg, elapsedTimeNanos, e));
        }

        public void log(final String msg, final long elapsedTimeNanos) {
            logEntries.add(new ThreadLogEntry("", 0, msg, elapsedTimeNanos, null));
        }

        public void log(final String msg, final Throwable e) {
            logEntries.add(new ThreadLogEntry("", 0, msg, -1L, e));
        }

        public void log(final String msg) {
            logEntries.add(new ThreadLogEntry("", 0, msg, -1L, null));
        }

        private synchronized void flush() {
            if (!logEntries.isEmpty()) {
                final StringBuilder buf = new StringBuilder();
                if (versionLogged.compareAndSet(false, true)) {
                    if (FastClasspathScanner.verbose) {
                        // Log the version before the first log entry
                        buf.append(new ThreadLogEntry("", 0,
                                "FastClasspathScanner version " + FastClasspathScanner.getVersion(), -1L, null)
                                        .toString());
                        buf.append('\n');
                    }
                }
                final ArrayList<ThreadLogEntry> entriesSorted = new ArrayList<>();
                for (ThreadLogEntry logEntry; (logEntry = logEntries.poll()) != null;) {
                    entriesSorted.add(logEntry);
                }
                Collections.sort(entriesSorted);
                for (final ThreadLogEntry logEntry : entriesSorted) {
                    buf.append(logEntry.toString());
                    buf.append('\n');
                }
                System.err.print(buf.toString());
                System.err.flush();
            }
        }

        @Override
        public void close() {
            flush();
        }
    }
}
