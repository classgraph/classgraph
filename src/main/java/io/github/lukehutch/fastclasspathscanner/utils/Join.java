package io.github.lukehutch.fastclasspathscanner.utils;

public class Join {
    /** A replacement for Java 8's String.join(). */
    public static String join(final String sep, final Iterable<?> iterable) {
        final StringBuilder buf = new StringBuilder();
        boolean first = true;
        for (final Object item : iterable) {
            if (first) {
                first = false;
            } else {
                buf.append(sep);
            }
            buf.append(item);
        }
        return buf.toString();
    }

    /** A replacement for Java 8's String.join(). */
    public static String join(final String sep, final Object[] array) {
        final StringBuilder buf = new StringBuilder();
        boolean first = true;
        for (final Object item : array) {
            if (first) {
                first = false;
            } else {
                buf.append(sep);
            }
            buf.append(item);
        }
        return buf.toString();
    }
}
