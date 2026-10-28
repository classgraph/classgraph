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
package io.github.classgraph.vfs.internal.zip;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

import io.github.classgraph.vfs.reader.RandomAccessReader;

/**
 * Decoder for zip entry names (standard UTF-8 when bit 11 of the general purpose bit flag is set, or UTF-8 with
 * fallback to IBM Code Page 437 when bit 11 is clear).
 */
final class ZipEntryNameDecoder {
    /**
     * Characters mapped from bytes 0x80..0xff in IBM Code Page 437 (inlined to avoid requiring the
     * {@code jdk.charsets} module).
     */
    private static final String CP437_HIGH = "" //
            + "Çüéâäàåç" //
            + "êëèïîìÄÅ" //
            + "ÉæÆôöòûù" //
            + "ÿÖÜ¢£¥₧ƒ" //
            + "áíóúñÑªº" //
            + "¿⌐¬½¼¡«»" //
            + "░▒▓│┤╡╢╖" //
            + "╕╣║╗╝╜╛┐" //
            + "└┴┬├─┼╞╟" //
            + "╚╔╩╦╠═╬╧" //
            + "╨╤╥╙╘╒╓╫" //
            + "╪┘┌█▄▌▐▀" //
            + "αßΓπΣσµτ" //
            + "ΦΘΩδ∞φε∩" //
            + "≡±≥≤⌠⌡÷≈" //
            // (0xff is a non-breaking space, written as an escape so that it cannot be mistaken for, or turned
            // into, an ordinary space)
            + "°∙·√ⁿ²■" + "\u00a0";

    /** Not instantiable. */
    private ZipEntryNameDecoder() {
        // Cannot be constructed
    }

    /**
     * Read an entry name from a zipfile's central directory, and decode it.
     *
     * @param reader
     *            a reader for the central directory
     * @param offset
     *            the offset of the name within the central directory
     * @param numBytes
     *            the length of the name in bytes
     * @param isUtf8
     *            whether bit 11 of the entry's general purpose bit flag is set, declaring the name to be UTF-8
     * @return the decoded name
     * @throws IOException
     *             If an I/O exception occurs, or the name extends beyond the end of the central directory.
     */
    static String readEntryName(final RandomAccessReader reader, final long offset, final int numBytes,
            final boolean isUtf8) throws IOException {
        if (numBytes == 0) {
            return "";
        }
        final var nameBytes = new byte[numBytes];
        if (reader.read(offset, nameBytes, 0, numBytes) < numBytes) {
            throw new IOException("Zip entry name extends beyond the end of the central directory");
        }
        return decodeEntryName(nameBytes, isUtf8);
    }

    /**
     * Decode zip entry name bytes as UTF-8 if {@code isUtf8} is true (replacing malformed sequences with U+FFFD),
     * or as strict UTF-8 falling back to IBM Code Page 437 if {@code isUtf8} is false.
     *
     * @param nameBytes
     *            the bytes of the name
     * @param isUtf8
     *            whether bit 11 of the entry's general purpose bit flag is set
     * @return the decoded name
     */
    static String decodeEntryName(final byte[] nameBytes, final boolean isUtf8) {
        if (!isUtf8) {
            // Decode strictly, so that a name that is not valid UTF-8 can be recognized and decoded as CP437
            try {
                return StandardCharsets.UTF_8.newDecoder() //
                        .onMalformedInput(CodingErrorAction.REPORT) //
                        .onUnmappableCharacter(CodingErrorAction.REPORT) //
                        .decode(ByteBuffer.wrap(nameBytes)).toString();
            } catch (final CharacterCodingException _) {
                return decodeCp437(nameBytes);
            }
        }
        // String's UTF-8 decoder replaces a malformed sequence with U+FFFD rather than throwing
        return new String(nameBytes, StandardCharsets.UTF_8);
    }

    /**
     * Decode bytes as IBM Code Page 437.
     *
     * @param nameBytes
     *            the bytes to decode
     * @return the decoded string
     */
    private static String decodeCp437(final byte[] nameBytes) {
        final var chars = new char[nameBytes.length];
        for (var i = 0; i < nameBytes.length; i++) {
            final var b = nameBytes[i] & 0xff;
            chars[i] = b < 0x80 ? (char) b : CP437_HIGH.charAt(b - 0x80);
        }
        return new String(chars);
    }
}
