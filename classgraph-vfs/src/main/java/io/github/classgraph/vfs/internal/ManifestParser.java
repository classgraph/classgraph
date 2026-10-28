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
package io.github.classgraph.vfs.internal;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

import org.jspecify.annotations.Nullable;

/**
 * A parser for the main section of a jarfile manifest ({@code META-INF/MANIFEST.MF}).
 */
public final class ManifestParser {
    /** The maximum manifest main section size that {@link #parse(InputStream)} will read, in bytes. */
    private static final int MAX_MAIN_SECTION_SIZE = 2 * 1024 * 1024;

    /** The initial buffer size for reading the main section. */
    private static final int INITIAL_BUFFER_SIZE = 1024;

    /** Not instantiable. */
    private ManifestParser() {
        // Cannot be constructed
    }

    /**
     * Parses the main section of a manifest, reading only up to the first blank line and ignoring any following
     * per-entry sections.
     *
     * @param manifestInputStream
     *            the manifest input stream (not closed by this method).
     * @return an immutable, case-insensitive map from attribute name to attribute value.
     * @throws IOException
     *             if the manifest could not be read, or if its main section exceeds {@value #MAX_MAIN_SECTION_SIZE}
     *             bytes.
     */
    public static Map<String, String> parse(final InputStream manifestInputStream) throws IOException {
        return parse(readMainSection(manifestInputStream));
    }

    /**
     * Reads the bytes of the main section of a manifest up to the first blank line.
     *
     * @param manifestInputStream
     *            the manifest input stream (not closed by this method).
     * @return the bytes of the main section, excluding the terminating blank line.
     * @throws IOException
     *             if the manifest could not be read, or if its main section exceeds {@value #MAX_MAIN_SECTION_SIZE}
     *             bytes.
     */
    private static byte[] readMainSection(final InputStream manifestInputStream) throws IOException {
        var buf = new byte[INITIAL_BUFFER_SIZE];
        var numBytesRead = 0;
        // The index of the start of the line the scan below has reached, and the index the scan has reached
        var lineStartIdx = 0;
        var scanIdx = 0;
        for (;;) {
            if (numBytesRead == buf.length) {
                if (buf.length == MAX_MAIN_SECTION_SIZE) {
                    throw new IOException(
                            "Manifest main section is larger than " + MAX_MAIN_SECTION_SIZE + " bytes");
                }
                buf = Arrays.copyOf(buf, (int) Math.min(buf.length * 2L, MAX_MAIN_SECTION_SIZE));
            }
            // The buffer has just been grown if it was full, so the read has room, and so returns at least one
            // byte, or -1 at the end of the stream, even from a stream that returns zero from a read (see
            // readAtLeastOne)
            final var numBytes = InputStreamUtils.readAtLeastOne(manifestInputStream, buf, numBytesRead,
                    buf.length - numBytesRead);
            final var atEndOfStream = numBytes < 0;
            if (!atEndOfStream) {
                numBytesRead += numBytes;
            }
            // A CR at the end of what has been read so far may turn out to be the first byte of a CRLF, so leave it
            // to the next read rather than scanning it as a line terminator in its own right
            final var scanEndIdx = !atEndOfStream && numBytesRead > 0 && buf[numBytesRead - 1] == (byte) '\r'
                    ? numBytesRead - 1
                    : numBytesRead;
            while (scanIdx < scanEndIdx) {
                if (!isLineTerminator(buf[scanIdx])) {
                    scanIdx++;
                } else if (scanIdx == lineStartIdx) {
                    // A blank line ends the main section
                    return Arrays.copyOf(buf, scanIdx);
                } else {
                    scanIdx = lineStartIdx = skipLineTerminator(buf, scanIdx, numBytesRead);
                }
            }
            if (atEndOfStream) {
                // The manifest has no blank line, so the whole of it is the main section
                return numBytesRead == buf.length ? buf : Arrays.copyOf(buf, numBytesRead);
            }
        }
    }

    /**
     * Parses the main section of a manifest (everything up to the first blank line).
     *
     * @param manifest
     *            the bytes of the manifest file.
     * @return an immutable, case-insensitive map from attribute name to attribute value.
     */
    public static Map<String, String> parse(final byte[] manifest) {
        final Map<String, String> attributes = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        final var len = manifest.length;
        var curr = 0;
        while (curr < len) {
            if (isLineTerminator(manifest[curr])) {
                // A blank line ends the main section
                break;
            }
            // Find the ':' that separates the attribute name from its value. It has to lie on this line -- a line
            // with no ':' is not an attribute, and is skipped.
            var colonIdx = -1;
            var lineEndIdx = len;
            for (var i = curr; i < len; i++) {
                if (isLineTerminator(manifest[i])) {
                    lineEndIdx = i;
                    break;
                } else if (manifest[i] == (byte) ':') {
                    colonIdx = i;
                    break;
                }
            }
            if (colonIdx < 0) {
                curr = skipLineTerminator(manifest, lineEndIdx);
                continue;
            }
            // A line whose name is not a valid attribute name, such as one with a space before the ':', is skipped
            // along with the lines that continue its value. (The JDK rejects the whole manifest instead, but the
            // rest of the manifest can still be read.)
            curr = readValue(manifest, colonIdx + 1, isValidName(manifest, curr, colonIdx) ? attributes : null,
                    new String(manifest, curr, colonIdx - curr, StandardCharsets.UTF_8));
        }
        return Collections.unmodifiableMap(attributes);
    }

    /**
     * Reads a manifest attribute value (including any continuation lines starting with a single space) and stores
     * it in {@code attributes}.
     *
     * @param manifest
     *            the bytes of the manifest file.
     * @param valueStartIdx
     *            the index of the first byte after the {@code ':'} separator.
     * @param attributes
     *            the map in which to store the attribute, or null to skip storing it.
     * @param name
     *            the attribute name.
     * @return the start index of the line following the attribute value.
     */
    private static int readValue(final byte[] manifest, final int valueStartIdx,
            final @Nullable Map<String, String> attributes, final String name) {
        final var len = manifest.length;
        // Skip the space that separates the ':' from the value. The jarfile specification requires exactly one,
        // but manifests in the wild are not always so careful.
        var segmentStartIdx = valueStartIdx;
        while (segmentStartIdx < len && manifest[segmentStartIdx] == (byte) ' ') {
            segmentStartIdx++;
        }
        var segmentEndIdx = segmentStartIdx;
        while (segmentEndIdx < len && !isLineTerminator(manifest[segmentEndIdx])) {
            segmentEndIdx++;
        }
        var nextLineIdx = skipLineTerminator(manifest, segmentEndIdx);
        String value;
        if (nextLineIdx >= len || manifest[nextLineIdx] != (byte) ' ') {
            // Fast path: the value is not continued on the following line
            value = new String(manifest, segmentStartIdx, segmentEndIdx - segmentStartIdx, StandardCharsets.UTF_8);
        } else {
            // The value is continued on one or more following lines. The bytes of a multi-byte UTF-8 character may
            // be split across a line break, so the segments have to be concatenated before they are decoded.
            final var buf = new ByteArrayOutputStream();
            buf.write(manifest, segmentStartIdx, segmentEndIdx - segmentStartIdx);
            while (nextLineIdx < len && manifest[nextLineIdx] == (byte) ' ') {
                segmentStartIdx = nextLineIdx + 1;
                segmentEndIdx = segmentStartIdx;
                while (segmentEndIdx < len && !isLineTerminator(manifest[segmentEndIdx])) {
                    segmentEndIdx++;
                }
                buf.write(manifest, segmentStartIdx, segmentEndIdx - segmentStartIdx);
                nextLineIdx = skipLineTerminator(manifest, segmentEndIdx);
            }
            value = buf.toString(StandardCharsets.UTF_8);
        }
        if (attributes != null) {
            attributes.put(name, value.endsWith(" ") ? value.trim() : value);
        }
        return nextLineIdx;
    }

    /**
     * Checks whether a manifest attribute name is valid (1 to 70 ASCII letters, digits, {@code '-'}, or
     * {@code '_'}).
     *
     * @param manifest
     *            the bytes of the manifest file.
     * @param startIdx
     *            the start index of the name.
     * @param endIdx
     *            the end index (exclusive) of the name.
     * @return true if the name is valid.
     */
    private static boolean isValidName(final byte[] manifest, final int startIdx, final int endIdx) {
        if (endIdx <= startIdx || endIdx - startIdx > 70) {
            return false;
        }
        for (var i = startIdx; i < endIdx; i++) {
            final var b = manifest[i];
            if (!(b >= 'a' && b <= 'z' || b >= 'A' && b <= 'Z' || b >= '0' && b <= '9' || b == '-' || b == '_')) {
                return false;
            }
        }
        return true;
    }

    /**
     * Checks whether a byte is a line terminator ({@code '\r'} or {@code '\n'}).
     *
     * @param b
     *            the byte to check.
     * @return true if the byte is CR or LF.
     */
    private static boolean isLineTerminator(final byte b) {
        return b == (byte) '\r' || b == (byte) '\n';
    }

    /**
     * Skips the line terminator (CR, LF, or CRLF) at {@code idx}, if present.
     *
     * @param manifest
     *            the bytes of the manifest file.
     * @param idx
     *            the index of the line terminator, or {@code manifest.length} if at the end of the array.
     * @return the start index of the next line.
     */
    private static int skipLineTerminator(final byte[] manifest, final int idx) {
        return skipLineTerminator(manifest, idx, manifest.length);
    }

    /**
     * Skips the line terminator (CR, LF, or CRLF) at {@code idx} within the first {@code len} bytes of
     * {@code manifest}.
     *
     * @param manifest
     *            the buffer containing the manifest bytes.
     * @param idx
     *            the index of the line terminator, or {@code len} if at the end of the valid range.
     * @param len
     *            the number of valid bytes in {@code manifest}.
     * @return the start index of the next line.
     */
    private static int skipLineTerminator(final byte[] manifest, final int idx, final int len) {
        if (idx >= len) {
            return len;
        } else if (manifest[idx] == (byte) '\r' && idx + 1 < len && manifest[idx + 1] == (byte) '\n') {
            return idx + 2;
        } else {
            return idx + 1;
        }
    }
}
