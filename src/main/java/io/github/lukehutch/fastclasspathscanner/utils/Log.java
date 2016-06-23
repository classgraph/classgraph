package io.github.lukehutch.fastclasspathscanner.utils;

import java.text.DecimalFormat;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

import io.github.lukehutch.fastclasspathscanner.FastClasspathScanner;

public class Log {
    private static DateTimeFormatter dateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mmX")
            .withZone(ZoneOffset.UTC);

    private static DecimalFormat nanoFormatter = new DecimalFormat("0.000000");

    private Log() {
    }

    private static String indent(String msg, int indentLevel) {
        int numIndentChars = 2 * indentLevel;
        StringBuilder buf = new StringBuilder(msg.length() + numIndentChars);
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
                + FastClasspathScanner.class.getSimpleName() + "\t" + indent(msg, indentLevel));
    }

    public static void log(final String msg) {
        log(0, msg);
    }

    public static void log(final int indentLevel, final String msg, final long elapsedTimeNanos) {
        System.err.println(dateTimeFormatter.format(Instant.now()) + "\t"
                + FastClasspathScanner.class.getSimpleName() + "\t" + indent(msg, indentLevel) + " in "
                + nanoFormatter.format(elapsedTimeNanos * 1e-9) + " sec");
    }

    public static void log(final String msg, final long elapsedTimeNanos) {
        log(0, msg, elapsedTimeNanos);
    }
}
