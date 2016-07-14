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

    private static class ThreadLogEntry {
        private final int indentLevel;
        private final Date time;
        private final String msg;
        private final long elapsedTimeNanos;
        private final SimpleDateFormat dateTimeFormatter = new SimpleDateFormat("yyyy-MM-dd'T'HH:mmX");
        private final DecimalFormat nanoFormatter = new DecimalFormat("0.000000");

        public ThreadLogEntry(final int indentLevel, final String msg, final long elapsedTimeNanos) {
            this.indentLevel = indentLevel;
            this.msg = msg;
            this.time = Calendar.getInstance().getTime();
            this.elapsedTimeNanos = elapsedTimeNanos;
        }

        public ThreadLogEntry(final int indentLevel, final String msg) {
            this(indentLevel, msg, -1L);
        }

        @Override
        public String toString() {
            final StringBuilder buf = new StringBuilder();
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
            buf.append(msg);
            if (elapsedTimeNanos >= 0L) {
                buf.append(" in ");
                buf.append(nanoFormatter.format(elapsedTimeNanos * 1e-9));
                buf.append(" sec");
            }
            return buf.toString();
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
        if (!logEntries.isEmpty()) {
            final StringBuilder buf = new StringBuilder();
            if (versionLogged.compareAndSet(false, true)) {
                if (FastClasspathScanner.verbose) {
                    // Log the version before the first log entry
                    buf.append(new ThreadLogEntry(0,
                            "FastClasspathScanner version " + FastClasspathScanner.getVersion()).toString());
                    buf.append('\n');
                }
            }
            for (ThreadLogEntry logEntry; (logEntry = logEntries.poll()) != null;) {
                buf.append(logEntry.toString());
                buf.append('\n');
            }
            System.err.print(buf.toString());
            System.err.flush();
            logEntries.clear();
        }
    }
}
