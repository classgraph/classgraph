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
 * Copyright (c) 2026 Luke Hutchison
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
package io.github.classgraph.base.internal.utils;

import java.io.UTFDataFormatException;

/**
 * String utilities.
 */
public final class StringUtils {
    /**
     * Lookup table of escape sequences for characters that cannot appear literally between double quotes.
     */
    private static final String[] CHAR_REPLACEMENTS = new String[256];

    static {
        for (var c = 0; c < 256; c++) {
            if (c == 32) {
                c = 127;
            }
            final var buf = new StringBuilder(6);
            appendUnicodeEscape((char) c, buf);
            CHAR_REPLACEMENTS[c] = buf.toString();
        }
        CHAR_REPLACEMENTS['"'] = "\\\"";
        CHAR_REPLACEMENTS['\\'] = "\\\\";
        CHAR_REPLACEMENTS['\n'] = "\\n";
        CHAR_REPLACEMENTS['\r'] = "\\r";
        CHAR_REPLACEMENTS['\t'] = "\\t";
        CHAR_REPLACEMENTS['\b'] = "\\b";
        CHAR_REPLACEMENTS['\f'] = "\\f";
    }

    /** Not instantiable. */
    private StringUtils() {
        // Cannot be constructed
    }

    /**
     * Append the {@code \}{@code uXXXX} escape sequence for a character to a buffer.
     *
     * @param chr
     *            The character to escape.
     * @param buf
     *            The buffer to append to.
     */
    private static void appendUnicodeEscape(final char chr, final StringBuilder buf) {
        buf.append("\\u");
        for (var shift = 12; shift >= 0; shift -= 4) {
            final var nibble = (chr >> shift) & 0xf;
            buf.append(nibble <= 9 ? (char) ('0' + nibble) : (char) ('a' + nibble - 10));
        }
    }

    /**
     * Escape a string for inclusion inside double quotes, using Java escape sequences for quotes, backslashes, and
     * non-printable-ASCII characters.
     *
     * @param unsafeStr
     *            The string to escape.
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
                appendUnicodeEscape(c, buf);
            } else {
                final var replacement = CHAR_REPLACEMENTS[c];
                if (replacement == null) {
                    buf.append(c);
                } else {
                    buf.append(replacement);
                }
            }
        }
        return buf.toString();
    }

    /**
     * Escape a character for inclusion inside single quotes, using Java escape sequences for single quotes,
     * backslashes, and non-printable-ASCII characters.
     *
     * @param unsafeChr
     *            The character to escape.
     * @return The escaped character.
     */
    public static String escapeChar(final char unsafeChr) {
        if (unsafeChr == '\'') {
            return "\\'";
        } else if (unsafeChr == '"') {
            // A double quote has to be escaped between double quotes, but not between single quotes
            return "\"";
        } else if (unsafeChr > 0xff) {
            final var buf = new StringBuilder(6);
            appendUnicodeEscape(unsafeChr, buf);
            return buf.toString();
        } else {
            final var replacement = CHAR_REPLACEMENTS[unsafeChr];
            return replacement == null ? String.valueOf(unsafeChr) : replacement;
        }
    }

    /**
     * Append a constant value to a buffer formatted as a Java source literal.
     *
     * @param val
     *            The value.
     * @param buf
     *            The buffer to append to.
     */
    public static void appendSourceLiteral(final Object val, final StringBuilder buf) {
        if (val instanceof final String str) {
            buf.append('"').append(escapeString(str)).append('"');
        } else if (val instanceof final Character chr) {
            buf.append('\'').append(escapeChar(chr)).append('\'');
        } else if (val instanceof final Long longVal) {
            // A long literal outside the int range needs an 'L' suffix
            buf.append(longVal.longValue()).append('L');
        } else if (val instanceof final Float floatVal) {
            if (floatVal.isNaN()) {
                buf.append("Float.NaN");
            } else if (floatVal.isInfinite()) {
                buf.append(floatVal > 0 ? "Float.POSITIVE_INFINITY" : "Float.NEGATIVE_INFINITY");
            } else {
                buf.append(floatVal.floatValue()).append('f');
            }
        } else if (val instanceof final Double doubleVal) {
            if (doubleVal.isNaN()) {
                buf.append("Double.NaN");
            } else if (doubleVal.isInfinite()) {
                buf.append(doubleVal > 0 ? "Double.POSITIVE_INFINITY" : "Double.NEGATIVE_INFINITY");
            } else {
                buf.append(doubleVal.doubleValue());
            }
        } else {
            buf.append(val);
        }
    }

    /**
     * Read a modified UTF-8 string from a byte array.
     *
     * @param arr
     *            The array to read the string from.
     * @param startOffset
     *            The start offset of the string within the array.
     * @param numBytes
     *            The number of bytes of the modified UTF-8 encoding of the string.
     * @return The decoded string.
     * @throws UTFDataFormatException
     *             If the bytes are not valid modified UTF-8.
     * @throws IllegalArgumentException
     *             If {@code startOffset} or {@code numBytes} is negative, or the range is out of bounds.
     */
    public static String readStringModifiedUtf8(final byte[] arr, final int startOffset, final int numBytes)
            throws UTFDataFormatException {
        // Compare by subtraction rather than addition, so that a large startOffset plus a large numBytes cannot
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
            chars[charIdx++] = (char) c;
        }
        while (byteIdx < numBytes) {
            final var leadByteIdx = byteIdx;
            final var c = arr[startOffset + byteIdx] & 0xff;
            switch (c >> 4) {
            case 0, 1, 2, 3, 4, 5, 6, 7 -> {
                byteIdx++;
                chars[charIdx++] = (char) c;
            }
            case 12, 13 -> {
                byteIdx += 2;
                if (byteIdx > numBytes) {
                    throw malformed(leadByteIdx);
                }
                final int c2 = arr[startOffset + byteIdx - 1];
                if ((c2 & 0xc0) != 0x80) {
                    throw malformed(leadByteIdx);
                }
                chars[charIdx++] = (char) (((c & 0x1f) << 6) | (c2 & 0x3f));
            }
            case 14 -> {
                byteIdx += 3;
                if (byteIdx > numBytes) {
                    throw malformed(leadByteIdx);
                }
                final int c2 = arr[startOffset + byteIdx - 2];
                final int c3 = arr[startOffset + byteIdx - 1];
                if ((c2 & 0xc0) != 0x80 || (c3 & 0xc0) != 0x80) {
                    throw malformed(leadByteIdx);
                }
                chars[charIdx++] = (char) (((c & 0x0f) << 12) | ((c2 & 0x3f) << 6) | (c3 & 0x3f));
            }
            default -> throw malformed(leadByteIdx);
            }
        }
        // The char array is one char per byte, so it is longer than the string whenever a multi-byte sequence was
        // decoded
        return charIdx == numBytes ? new String(chars) : new String(chars, 0, charIdx);
    }

    /**
     * Construct a {@link UTFDataFormatException} for a malformed modified UTF-8 sequence starting at
     * {@code byteIdx}.
     *
     * @param byteIdx
     *            the byte index of the malformed character relative to the start of the string.
     * @return the exception.
     */
    private static UTFDataFormatException malformed(final int byteIdx) {
        return new UTFDataFormatException("Malformed modified UTF-8 around byte " + byteIdx);
    }

    /**
     * Normalize a classfile type descriptor by optionally replacing {@code '/'} with {@code '.'} and stripping a
     * leading {@code 'L'} and trailing {@code ';'}.
     *
     * @param typeDescriptor
     *            The type descriptor.
     * @param replaceSlashWithDot
     *            If {@code true}, replace {@code '/'} with {@code '.'}.
     * @param stripLSemicolon
     *            If {@code true}, strip the leading {@code 'L'} and trailing {@code ';'}.
     * @return The normalized type descriptor.
     * @throws IllegalArgumentException
     *             If {@code stripLSemicolon} is {@code true} and the string does not start with {@code 'L'} and end
     *             with {@code ';'}.
     */
    public static String normalizeTypeDescriptor(final String typeDescriptor, final boolean replaceSlashWithDot,
            final boolean stripLSemicolon) throws IllegalArgumentException {
        // String#replace returns the string itself if it contains no '/', so a string that needs no replacement is
        // not copied
        final var replaced = replaceSlashWithDot ? typeDescriptor.replace('/', '.') : typeDescriptor;
        if (!stripLSemicolon) {
            return replaced;
        }
        if (replaced.length() < 2 || replaced.charAt(0) != 'L' || replaced.charAt(replaced.length() - 1) != ';') {
            throw new IllegalArgumentException(
                    "Expected string to start with 'L' and end with ';', got \"" + replaced + "\"");
        }
        return replaced.substring(1, replaced.length() - 1);
    }

    /**
     * Append the string representations of the elements of an {@link Iterable} to a buffer, separated by a
     * separator string. (Unlike {@link String#join}, the elements may be of any type, and may be null.)
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
     * Join the string representations of the elements of an {@link Iterable}, separated by a separator string.
     * (Unlike {@link String#join}, the elements may be of any type, and may be null.)
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
}
