package io.github.lukehutch.fastclasspathscanner.utils;

import java.text.DecimalFormat;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.lukehutch.fastclasspathscanner.FastClasspathScanner;

/** Class for accumulating ordered log entries from threads, for later writing to the log without interleaving. */
public class ThreadLog {
    private static AtomicBoolean versionLogged = new AtomicBoolean(false);
    private final Queue<ThreadLogEntry> logEntries = new ConcurrentLinkedQueue<>();
    private SimpleDateFormat dateTimeFormatter = new SimpleDateFormat("yyyy-MM-dd'T'HH:mmX");
    private DecimalFormat nanoFormatter = new DecimalFormat("0.000000");

    private static class ThreadLogEntry {
        private final int indentLevel;
        private final Date time;
        private final String msg;
        private final long elapsedTimeNanos;

        public ThreadLogEntry(final int indentLevel, final String msg, final long elapsedTimeNanos) {
            this.indentLevel = indentLevel;
            this.msg = msg;
            this.time = Calendar.getInstance().getTime();
            this.elapsedTimeNanos = elapsedTimeNanos;
        }

        public ThreadLogEntry(final int indentLevel, final String msg) {
            this(indentLevel, msg, -1L);
        }
    }

    public void log(final int indentLevel, final String msg) {
        logEntries.add(new ThreadLogEntry(indentLevel, msg));
    }

    public void log(final String msg) {
        logEntries.add(new ThreadLogEntry(0, msg));
    }

    public void log(final int indentLevel, final String msg, final long elapsedTimeNanos) {
        logEntries.add(new ThreadLogEntry(indentLevel, msg, elapsedTimeNanos));
    }

    public void log(final String msg, final long elapsedTimeNanos) {
        logEntries.add(new ThreadLogEntry(0, msg, elapsedTimeNanos));
    }

    public void flush() {
        if (versionLogged.compareAndSet(false, true)) {
            if (FastClasspathScanner.verbose) {
                // Log the version before the first log entry
                log("FastClasspathScanner version " + FastClasspathScanner.getVersion());
            }
        }
        if (!logEntries.isEmpty()) {
            // SimpleDateFormatter is not threadsafe => lock
            synchronized (dateTimeFormatter) {
                StringBuilder buf = new StringBuilder();
                for (ThreadLogEntry logEntry : logEntries) {
                    buf.append(dateTimeFormatter.format(logEntry.time));
                    buf.append('\t');
                    buf.append(FastClasspathScanner.class.getSimpleName());
                    buf.append('\t');
                    final int numIndentChars = 2 * logEntry.indentLevel;
                    for (int i = 0; i < numIndentChars - 1; i++) {
                        buf.append('-');
                    }
                    if (numIndentChars > 0) {
                        buf.append(" ");
                    }
                    buf.append(logEntry.msg);
                    if (logEntry.elapsedTimeNanos >= 0L) {
                        buf.append(" in ");
                        buf.append(nanoFormatter.format(logEntry.elapsedTimeNanos * 1e-9));
                    }
                    buf.append('\n');
                }
                System.err.println(buf.toString());
                System.err.flush();
                logEntries.clear();
            }
        }
    }
}
