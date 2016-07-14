package io.github.lukehutch.fastclasspathscanner.utils;

public class Join {
    /** A replacement for Java 8's String.join(). */
    public static String join(String sep, Iterable<?> iterable) {
        StringBuilder buf = new StringBuilder();
        boolean first = true;
        for (Object item : iterable) {
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
    public static String join(String sep, Object[] array) {
        StringBuilder buf = new StringBuilder();
        boolean first = true;
        for (Object item : array) {
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
