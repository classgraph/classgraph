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

/**
 * File utilities.
 */
public final class StringUtils {
    /**
     * Lookup table of escape sequences for characters that cannot appear literally
     * between double quotes.
     */
    private static final String[] CHAR_REPLACEMENTS = new String[256];

    static {
        for (var c = 0; c < 256; c++) {
            if (c == 32) {
                c = 127;
            }
            final var nibble1 = c >> 4;
            final var hexDigit1 = nibble1 <= 9 ? (char) ('0' + nibble1) : (char) ('A' + nibble1 - 10);
            final var nibble0 = c & 0xf;
            final var hexDigit0 = nibble0 <= 9 ? (char) ('0' + nibble0) : (char) ('A' + nibble0 - 10);
            CHAR_REPLACEMENTS[c] = "\\u00" + hexDigit1 + "" + hexDigit0;
        }
        CHAR_REPLACEMENTS['"'] = "\\\"";
        CHAR_REPLACEMENTS['\\'] = "\\\\";
        CHAR_REPLACEMENTS['\n'] = "\\n";
        CHAR_REPLACEMENTS['\r'] = "\\r";
        CHAR_REPLACEMENTS['\t'] = "\\t";
        CHAR_REPLACEMENTS['\b'] = "\\b";
        CHAR_REPLACEMENTS['\f'] = "\\f";
    }

    /**
     * Constructor.
     */
    private StringUtils() {
        // Cannot be constructed
    }

    /**
     * Escape a string so that it can be shown surrounded by double quotes, using
     * Java escape sequences for quotes, backslashes, control characters, and any
     * character outside the Latin-1 range.
     *
     * @param unsafeStr The string to escape.
     * @return The escaped string.
     */
    public static String escapeString(final String unsafeStr) {
        // Fast path
        var needsEscaping = false;
        for (int i = 0, n = unsafeStr.length(); i < n; i++) {
            final var c = unsafeStr.charAt(i);
            if (c > 0xff || CHAR_REPLACEMENTS[c] != null) {
                needsEscaping = true;
                break;
            }
        }
        if (!needsEscaping) {
            return unsafeStr;
        }
        // Slow path
        final StringBuilder buf = new StringBuilder(unsafeStr.length() * 2);
        for (int i = 0, n = unsafeStr.length(); i < n; i++) {
            final var c = unsafeStr.charAt(i);
            if (c > 0xff) {
                buf.append("\\u");
                final var nibble3 = (c & 0xf000) >> 12;
                buf.append(nibble3 <= 9 ? (char) ('0' + nibble3) : (char) ('A' + nibble3 - 10));
                final var nibble2 = (c & 0xf00) >> 8;
                buf.append(nibble2 <= 9 ? (char) ('0' + nibble2) : (char) ('A' + nibble2 - 10));
                final var nibble1 = (c & 0xf0) >> 4;
                buf.append(nibble1 <= 9 ? (char) ('0' + nibble1) : (char) ('A' + nibble1 - 10));
                final var nibble0 = c & 0xf;
                buf.append(nibble0 <= 9 ? (char) ('0' + nibble0) : (char) ('A' + nibble0 - 10));
            } else {
                final var replacement = CHAR_REPLACEMENTS[c];
                buf.append(replacement == null ? String.valueOf(c) : replacement);
            }
        }
        return buf.toString();
    }

    /**
     * Reads the "modified UTF8" format defined in the Java classfile spec,
     * optionally replacing '/' with '.', and optionally removing the prefix "L" and
     * the suffix ";".
     *
     * @param arr                 the array to read the string from
     * @param startOffset         The start offset of the string within the array.
     * @param numBytes            The number of bytes of the UTF8 encoding of the
     *                            string.
     * @param replaceSlashWithDot If true, replace '/' with '.'.
     * @param stripLSemicolon     If true, string final ';' character.
     * @return The string.
     * @throws IllegalArgumentException If string could not be parsed.
     */
    public static String readString(final byte[] arr, final int startOffset, final int numBytes,
            final boolean replaceSlashWithDot, final boolean stripLSemicolon) throws IllegalArgumentException {
        // Compare by subtraction rather than addition, so that a large startOffset plus
        // a large numBytes cannot
        // overflow int and slip past the range check
        if (startOffset < 0 || numBytes < 0 || numBytes > arr.length - startOffset) {
            throw new IllegalArgumentException("offset or numBytes out of range");
        }
        final var chars = new char[numBytes];
        var byteIdx = 0;
        var charIdx = 0;
        for (; byteIdx < numBytes; byteIdx++) {
            final var c = arr[startOffset + byteIdx] & 0xff;
            if (c > 127) {
                break;
            }
            chars[charIdx++] = (char) (replaceSlashWithDot && c == '/' ? '.' : c);
        }
        while (byteIdx < numBytes) {
            final var c = arr[startOffset + byteIdx] & 0xff;
            switch (c >> 4) {
            case 0, 1, 2, 3, 4, 5, 6, 7 -> {
                byteIdx++;
                chars[charIdx++] = (char) (replaceSlashWithDot && c == '/' ? '.' : c);
            }
            case 12, 13 -> {
                byteIdx += 2;
                if (byteIdx > numBytes) {
                    throw new IllegalArgumentException("Bad modified UTF8");
                }
                final int c2 = arr[startOffset + byteIdx - 1];
                if ((c2 & 0xc0) != 0x80) {
                    throw new IllegalArgumentException("Bad modified UTF8");
                }
                final var c3 = ((c & 0x1f) << 6) | (c2 & 0x3f);
                chars[charIdx++] = (char) (replaceSlashWithDot && c3 == '/' ? '.' : c3);
            }
            case 14 -> {
                byteIdx += 3;
                if (byteIdx > numBytes) {
                    throw new IllegalArgumentException("Bad modified UTF8");
                }
                final int c2 = arr[startOffset + byteIdx - 2];
                final int c3 = arr[startOffset + byteIdx - 1];
                if ((c2 & 0xc0) != 0x80 || (c3 & 0xc0) != 0x80) {
                    throw new IllegalArgumentException("Bad modified UTF8");
                }
                final var c4 = ((c & 0x0f) << 12) | ((c2 & 0x3f) << 6) | (c3 & 0x3f);
                chars[charIdx++] = (char) (replaceSlashWithDot && c4 == '/' ? '.' : c4);
            }
            default -> throw new IllegalArgumentException("Bad modified UTF8");
            }
        }
        if (charIdx == numBytes && !stripLSemicolon) {
            return new String(chars);
        } else {
            if (stripLSemicolon) {
                if (charIdx < 2 || chars[0] != 'L' || chars[charIdx - 1] != ';') {
                    throw new IllegalArgumentException(
                            "Expected string to start with 'L' and end with ';', got \"" + new String(chars) + "\"");
                }
                return new String(chars, 1, charIdx - 2);
            } else {
                return new String(chars, 0, charIdx);
            }
        }
    }

    /**
     * Append the string representations of the elements of an {@link Iterable} to a
     * buffer, separated by a separator string. (Unlike {@link String#join}, the
     * elements may be of any type, and may be null.)
     * 
     * @param buf            The buffer to append to.
     * @param addAtBeginning The token to add at the beginning of the string.
     * @param sep            The separator string.
     * @param addAtEnd       The token to add at the end of the string.
     * @param iterable       The {@link Iterable} to join.
     */
    public static void join(final StringBuilder buf, final String addAtBeginning, final String sep,
            final String addAtEnd, final Iterable<?> iterable) {
        if (!addAtBeginning.isEmpty()) {
            buf.append(addAtBeginning);
        }
        var first = true;
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
     * Join the string representations of the elements of an {@link Iterable},
     * separated by a separator string. (Unlike {@link String#join}, the elements
     * may be of any type, and may be null.)
     * 
     * @param sep      The separator string.
     * @param iterable The {@link Iterable} to join.
     * @return The string representation of the joined elements.
     */
    public static String join(final String sep, final Iterable<?> iterable) {
        final StringBuilder buf = new StringBuilder();
        join(buf, "", sep, "", iterable);
        return buf.toString();
    }

    /**
     * Join the string representations of the given items, separated by a separator
     * string. (Unlike {@link String#join}, the items may be of any type.)
     * 
     * @param sep   The separator string.
     * @param items The items to join.
     * @return The string representation of the joined items.
     */
    public static String join(final String sep, final Object... items) {
        final StringBuilder buf = new StringBuilder();
        var first = true;
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
