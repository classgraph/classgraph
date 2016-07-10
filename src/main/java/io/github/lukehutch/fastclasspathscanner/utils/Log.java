package io.github.lukehutch.fastclasspathscanner.utils;

import java.text.DecimalFormat;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.lukehutch.fastclasspathscanner.FastClasspathScanner;

public class Log {

    /** Class for accumulating ordered log entries from sub-threads, for later writing to the log. */
    public static class DeferredLog {
        private static class DeferredLogEntry {
            final int indentLevel;
            final String msg;
            final long elapsedTimeNanos;

            public DeferredLogEntry(final int indentLevel, final String msg, final long elapsedTimeNanos) {
                this.indentLevel = indentLevel;
                this.msg = msg;
                this.elapsedTimeNanos = elapsedTimeNanos;
            }

            public DeferredLogEntry(final int indentLevel, final String msg) {
                this.indentLevel = indentLevel;
                this.msg = msg;
                this.elapsedTimeNanos = -1L;
            }

            public void sendToLog() {
                if (elapsedTimeNanos < 0L) {
                    Log.log(indentLevel, msg);
                } else {
                    Log.log(indentLevel, msg, elapsedTimeNanos);
                }
            }
        }

        private final Queue<DeferredLogEntry> logEntries = new ConcurrentLinkedQueue<>();

        private static Object lock = new Object();

        public void log(final int indentLevel, final String msg) {
            logEntries.add(new DeferredLogEntry(indentLevel, msg));
        }

        public void log(final String msg) {
            logEntries.add(new DeferredLogEntry(0, msg));
        }

        public void log(final int indentLevel, final String msg, final long elapsedTimeNanos) {
            logEntries.add(new DeferredLogEntry(indentLevel, msg, elapsedTimeNanos));
        }

        public void log(final String msg, final long elapsedTimeNanos) {
            logEntries.add(new DeferredLogEntry(0, msg, elapsedTimeNanos));
        }

        public void flush() {
            if (!logEntries.isEmpty()) {
                synchronized (lock) {
                    for (DeferredLogEntry entry; (entry = logEntries.poll()) != null;) {
                        entry.sendToLog();
                    }
                    Log.flush();
                }
            }
        }
    }

    private static DateTimeFormatter dateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mmX")
            .withZone(ZoneOffset.UTC);

    private static DecimalFormat nanoFormatter = new DecimalFormat("0.000000");

    private static AtomicBoolean versionLogged = new AtomicBoolean(false);

    private Log() {
    }

    private static String indent(final int indentLevel, final String msg) {
        final int numIndentChars = 2 * indentLevel;
        final StringBuilder buf = new StringBuilder(msg.length() + numIndentChars);
        for (int i = 0; i < numIndentChars - 1; i++) {
            buf.append('-');
        }
        if (numIndentChars > 0) {
            buf.append(" ");
        }
        buf.append(msg);
        return buf.toString();
    }

    public static void log(final int indentLevel, final String msg) {
        if (versionLogged.compareAndSet(false, true)) {
            // Log the version before the first log entry
            log("FastClasspathScanner version " + FastClasspathScanner.getVersion());
        }
        System.err.println(dateTimeFormatter.format(Instant.now()) + "\t"
                + FastClasspathScanner.class.getSimpleName() + "\t" + indent(indentLevel, msg));
    }

    public static void log(final String msg) {
        log(0, msg);
    }

    public static void log(final int indentLevel, final String msg, final long elapsedTimeNanos) {
        // DecimalFormat is not guaranteed to be threadsafe, so we have to use synchronized here
        // (DateTimeFormatter is threadsafe though)
        String elapsed;
        synchronized (nanoFormatter) {
            elapsed = nanoFormatter.format(elapsedTimeNanos * 1e-9);
        }
        log(indent(indentLevel, msg) + " in " + elapsed + " sec");
    }

    public static void log(final String msg, final long elapsedTimeNanos) {
        log(0, msg, elapsedTimeNanos);
    }

    public static void flush() {
        System.err.flush();
    }
}
