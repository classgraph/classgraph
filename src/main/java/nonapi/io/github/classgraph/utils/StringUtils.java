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
     * Constructor.
     */
    private StringUtils() {
        // Cannot be constructed
    }

    /**
     * Reads the "modified UTF8" format defined in the Java classfile spec, optionally replacing '/' with '.', and
     * optionally removing the prefix "L" and the suffix ";".
     *
     * @param arr
     *            the array to read the string from
     * @param startOffset
     *            The start offset of the string within the array.
     * @param numBytes
     *            The number of bytes of the UTF8 encoding of the string.
     * @param replaceSlashWithDot
     *            If true, replace '/' with '.'.
     * @param stripLSemicolon
     *            If true, string final ';' character.
     * @return The string.
     * @throws IllegalArgumentException
     *             If string could not be parsed.
     */
    public static String readString(final byte[] arr, final int startOffset, final int numBytes,
            final boolean replaceSlashWithDot, final boolean stripLSemicolon) throws IllegalArgumentException {
        if (startOffset < 0L || numBytes < 0 || startOffset + numBytes > arr.length) {
            throw new IllegalArgumentException("offset or numBytes out of range");
        }
        final char[] chars = new char[numBytes];
        int byteIdx = 0;
        int charIdx = 0;
        for (; byteIdx < numBytes; byteIdx++) {
            final int c = arr[startOffset + byteIdx] & 0xff;
            if (c > 127) {
                break;
            }
            chars[charIdx++] = (char) (replaceSlashWithDot && c == '/' ? '.' : c);
        }
        while (byteIdx < numBytes) {
            final int c = arr[startOffset + byteIdx] & 0xff;
            switch (c >> 4) {
            case 0:
            case 1:
            case 2:
            case 3:
            case 4:
            case 5:
            case 6:
            case 7: {
                byteIdx++;
                chars[charIdx++] = (char) (replaceSlashWithDot && c == '/' ? '.' : c);
                break;
            }
            case 12:
            case 13: {
                byteIdx += 2;
                if (byteIdx > numBytes) {
                    throw new IllegalArgumentException("Bad modified UTF8");
                }
                final int c2 = arr[startOffset + byteIdx - 1];
                if ((c2 & 0xc0) != 0x80) {
                    throw new IllegalArgumentException("Bad modified UTF8");
                }
                final int c3 = ((c & 0x1f) << 6) | (c2 & 0x3f);
                chars[charIdx++] = (char) (replaceSlashWithDot && c3 == '/' ? '.' : c3);
                break;
            }
            case 14: {
                byteIdx += 3;
                if (byteIdx > numBytes) {
                    throw new IllegalArgumentException("Bad modified UTF8");
                }
                final int c2 = arr[startOffset + byteIdx - 2];
                final int c3 = arr[startOffset + byteIdx - 1];
                if ((c2 & 0xc0) != 0x80 || (c3 & 0xc0) != 0x80) {
                    throw new IllegalArgumentException("Bad modified UTF8");
                }
                final int c4 = ((c & 0x0f) << 12) | ((c2 & 0x3f) << 6) | (c3 & 0x3f);
                chars[charIdx++] = (char) (replaceSlashWithDot && c4 == '/' ? '.' : c4);
                break;
            }
            default:
                throw new IllegalArgumentException("Bad modified UTF8");
            }
        }
        if (charIdx == numBytes && !stripLSemicolon) {
            return new String(chars);
        } else {
            if (stripLSemicolon) {
                if (charIdx < 2 || chars[0] != 'L' || chars[charIdx - 1] != ';') {
                    throw new IllegalArgumentException("Expected string to start with 'L' and end with ';', got \""
                            + new String(chars) + "\"");
                }
                return new String(chars, 1, charIdx - 2);
            } else {
                return new String(chars, 0, charIdx);
            }
        }
    }

}
