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

import io.github.classgraph.vfs.internal.slice.reader.RandomAccessReader;

/**
 * Decoder for zip entry names.
 *
 * <p>
 * Zip entry names are not stored in the "modified UTF-8" encoding that the Java classfile format uses: bit 11 of an
 * entry's general purpose bit flag (the "language encoding flag") declares that the name is standard UTF-8, and
 * when that bit is clear, the zip specification (APPNOTE.TXT, section 4.4.4) calls for IBM Code Page 437.
 *
 * <p>
 * Standard UTF-8 encodes a character outside the Basic Multilingual Plane as a single four-byte sequence, whereas
 * modified UTF-8 encodes it as a surrogate pair of three-byte sequences, so decoding a standard UTF-8 name with a
 * modified UTF-8 decoder fails outright for any name containing an emoji or another supplementary-plane character.
 */
final class ZipEntryNameCodec {
    /**
     * The characters that IBM Code Page 437 maps bytes 0x80 to 0xff to. (Bytes 0x00 to 0x7f map to the same
     * characters as ASCII.) This table is inlined rather than obtained from {@link java.nio.charset.Charset}, since
     * the "IBM437" charset lives in the {@code jdk.charsets} module, which need not be present in a custom runtime
     * image.
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
            + "°∙·√ⁿ²■ ";

    /** Constructor. */
    private ZipEntryNameCodec() {
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
     * Decode a zip entry name.
     *
     * <p>
     * A name whose UTF-8 flag is set is decoded as UTF-8, with any malformed sequence replaced by U+FFFD rather
     * than rejected, so that one bad name cannot render a whole archive unreadable.
     *
     * <p>
     * A name whose UTF-8 flag is clear is still decoded as UTF-8 if it is valid UTF-8, because writers that do not
     * set the flag nevertheless commonly write UTF-8, and because a name consisting only of ASCII (which is almost
     * all of them) decodes identically either way. Only a name that is not valid UTF-8 is decoded as CP437, which
     * cannot fail, since every byte value maps to a character.
     *
     * @param nameBytes
     *            the bytes of the name
     * @param isUtf8
     *            whether bit 11 of the entry's general purpose bit flag is set, declaring the name to be UTF-8
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
            } catch (final CharacterCodingException e) {
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
