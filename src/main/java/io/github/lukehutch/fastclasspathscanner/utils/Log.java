package io.github.lukehutch.fastclasspathscanner.utils;

import java.text.DecimalFormat;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import io.github.lukehutch.fastclasspathscanner.FastClasspathScanner;

public class Log {

    /** Class for accumulating ordered log entries from sub-threads, for later writing to the log. */
    public static class DeferredLog {
        private static class DeferredLogEntry {
            final int indentLevel;
            final String msg;
            final long elapsedTimeNanos;

            public DeferredLogEntry(int indentLevel, String msg, long elapsedTimeNanos) {
                this.indentLevel = indentLevel;
                this.msg = msg;
                this.elapsedTimeNanos = elapsedTimeNanos;
            }

            public DeferredLogEntry(int indentLevel, String msg) {
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

        private List<DeferredLogEntry> logEntries = new ArrayList<>();
        
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

        public void flushSynchronized() {
            if (!logEntries.isEmpty()) {
                synchronized (lock) {
                    for (DeferredLogEntry entry : logEntries) {
                        entry.sendToLog();
                    }
                    logEntries.clear();
                    Log.flush();
                }
            }
        }
    }

    private static DateTimeFormatter dateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mmX")
            .withZone(ZoneOffset.UTC);

    private static DecimalFormat nanoFormatter = new DecimalFormat("0.000000");

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
        System.err
                .println(dateTimeFormatter.format(Instant.now()) + "\t" + FastClasspathScanner.class.getSimpleName()
                        + "\t" + indent(indentLevel, msg) + " in " + elapsed + " sec");
    }

    public static void log(final String msg, final long elapsedTimeNanos) {
        log(0, msg, elapsedTimeNanos);
    }

    public static void flush() {
        System.err.flush();
    }
}
