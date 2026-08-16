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

/**
 * A parser for the main section of a jarfile manifest, {@code META-INF/MANIFEST.MF}.
 *
 * <p>
 * This works directly on the manifest bytes, rather than going through {@link java.util.jar.Manifest}, because the
 * latter reads through an {@link java.io.InputStream} and builds a {@link java.util.jar.Attributes} map keyed by
 * {@link java.util.jar.Attributes.Name} objects, which is a lot of work to read a handful of values from a file
 * that is read for every jarfile on the classpath.
 */
public final class ManifestParser {
    /**
     * The largest main section {@link #parse(InputStream)} will read, in bytes.
     *
     * <p>
     * The main section describes the jarfile as a whole, so it is short: across the 6857 jarfiles of a large local
     * Maven repository plus a JDK installation, the median is 371 bytes and the largest is 91kB. This limit is
     * therefore more than an order of magnitude above anything a build tool produces, while still bounding the
     * amount of memory a jarfile can cause to be allocated simply by being opened -- a manifest is deflated, so
     * without a limit a small jarfile can inflate to an arbitrarily large one.
     */
    private static final int MAX_MAIN_SECTION_SIZE = 2 * 1024 * 1024;

    /** The initial size of the buffer the main section is read into. */
    private static final int INITIAL_BUFFER_SIZE = 1024;

    /** Not instantiable. */
    private ManifestParser() {
        // Cannot be constructed
    }

    /**
     * Parses the main section of a manifest, reading only as far into the manifest as the main section extends.
     *
     * <p>
     * The per-entry sections that follow the main section are not read at all. They are not parsed in any case (see
     * {@link #parse(byte[])}), and in a signed jarfile they hold a digest of every entry, which makes them orders
     * of magnitude larger than the main section.
     *
     * @param manifestInputStream
     *            the manifest file. Not closed by this method.
     * @return an immutable map from attribute name to attribute value. Manifest attribute names are case
     *         insensitive, so the returned map is too.
     * @throws IOException
     *             if the manifest could not be read, or if its main section is larger than
     *             {@value #MAX_MAIN_SECTION_SIZE} bytes.
     */
    public static Map<String, String> parse(final InputStream manifestInputStream) throws IOException {
        return parse(readMainSection(manifestInputStream));
    }

    /**
     * Reads the bytes of the main section of a manifest, i.e. everything up to the first blank line, stopping as
     * soon as that line is reached.
     *
     * @param manifestInputStream
     *            the manifest file. Not closed by this method.
     * @return the bytes of the main section, without the blank line that ends it.
     * @throws IOException
     *             if the manifest could not be read, or if its main section is larger than
     *             {@value #MAX_MAIN_SECTION_SIZE} bytes.
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
            final var numBytes = manifestInputStream.read(buf, numBytesRead, buf.length - numBytesRead);
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
     * Parses the main section of a manifest.
     *
     * <p>
     * Only the main section is parsed, i.e. everything up to the first blank line. The per-entry sections that may
     * follow it describe individual entries of the jarfile, rather than the jarfile as a whole, and their keys are
     * therefore not manifest-wide attributes.
     *
     * @param manifest
     *            the bytes of the manifest file.
     * @return an immutable map from attribute name to attribute value. Manifest attribute names are case
     *         insensitive, so the returned map is too.
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
            final var name = new String(manifest, curr, colonIdx - curr, StandardCharsets.UTF_8);
            final var valueEndIdx = readValue(manifest, colonIdx + 1, attributes, name);
            curr = valueEndIdx;
        }
        return Collections.unmodifiableMap(attributes);
    }

    /**
     * Reads one attribute value, and stores it in the map of attributes.
     *
     * <p>
     * A value may be continued across any number of following lines, each of which starts with a single space that
     * is not part of the value.
     *
     * @param manifest
     *            the bytes of the manifest file.
     * @param valueStartIdx
     *            the index of the first character after the ':' that separates the value from its name.
     * @param attributes
     *            the map to store the attribute in.
     * @param name
     *            the name of the attribute.
     * @return the index of the start of the line after the value.
     */
    private static int readValue(final byte[] manifest, final int valueStartIdx,
            final Map<String, String> attributes, final String name) {
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
        attributes.put(name, value.endsWith(" ") ? value.trim() : value);
        return nextLineIdx;
    }

    /**
     * Determines whether a byte starts a line terminator. A manifest may use any of CR, LF or CRLF.
     *
     * @param b
     *            the byte.
     * @return true if the byte is CR or LF.
     */
    private static boolean isLineTerminator(final byte b) {
        return b == (byte) '\r' || b == (byte) '\n';
    }

    /**
     * Skips the line terminator at an index, if there is one.
     *
     * @param manifest
     *            the bytes of the manifest file.
     * @param idx
     *            the index of the line terminator, or the length of the manifest if the last line was not
     *            terminated.
     * @return the index of the start of the next line.
     */
    private static int skipLineTerminator(final byte[] manifest, final int idx) {
        return skipLineTerminator(manifest, idx, manifest.length);
    }

    /**
     * Skips the line terminator at an index, if there is one, within the first {@code len} bytes of an array.
     *
     * @param manifest
     *            the bytes of the manifest file, which may be longer than the manifest itself.
     * @param idx
     *            the index of the line terminator, or {@code len} if the last line was not terminated.
     * @param len
     *            the number of bytes of the array that hold the manifest.
     * @return the index of the start of the next line.
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
