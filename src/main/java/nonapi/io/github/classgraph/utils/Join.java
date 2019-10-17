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
 * Copyright (c) 2019 Luke Hutchison
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
package nonapi.io.github.classgraph.utils;

/** A replacement for Java 8's String.join() that will allow compilation on Java 7. */
public final class Join {

    /**
     * Constructor.
     */
    private Join() {
        // Cannot be constructed
    }

    /**
     * A replacement for Java 8's String.join().
     * 
     * @param buf
     *            The buffer to append to.
     * @param addAtBeginning
     *            The token to add at the beginning of the string.
     * @param sep
     *            The separator string.
     * @param addAtEnd
     *            The token to add at the end of the string.
     * @param iterable
     *            The {@link Iterable} to join.
     */
    public static void join(final StringBuilder buf, final String addAtBeginning, final String sep,
            final String addAtEnd, final Iterable<?> iterable) {
        if (!addAtBeginning.isEmpty()) {
            buf.append(addAtBeginning);
        }
        boolean first = true;
        for (final Object item : iterable) {
            if (first) {
                first = false;
            } else {
                buf.append(sep);
            }
            buf.append(item == null ? "null" : item.toString());
        }
        if (!addAtEnd.isEmpty()) {
            buf.append(addAtEnd);
        }
    }

    /**
     * A replacement for Java 8's String.join().
     * 
     * @param sep
     *            The separator string.
     * @param iterable
     *            The {@link Iterable} to join.
     * @return The string representation of the joined elements.
     */
    public static String join(final String sep, final Iterable<?> iterable) {
        final StringBuilder buf = new StringBuilder();
        join(buf, "", sep, "", iterable);
        return buf.toString();
    }

    /**
     * A replacement for Java 8's String.join().
     * 
     * @param sep
     *            The separator string.
     * @param items
     *            The items to join.
     * @return The string representation of the joined items.
     */
    public static String join(final String sep, final Object... items) {
        final StringBuilder buf = new StringBuilder();
        boolean first = true;
        for (final Object item : items) {
            if (first) {
                first = false;
            } else {
                buf.append(sep);
            }
            buf.append(item.toString());
        }
        return buf.toString();
    }
}
