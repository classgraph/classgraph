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
package nonapi.io.github.classgraph.types;

import java.lang.reflect.Modifier;

/**
 * Utilities for parsing Java type descriptors and type signatures.
 * 
 * @author lukehutch
 */
public final class TypeUtils {

    /**
     * Constructor.
     */
    private TypeUtils() {
        // Cannot be constructed
    }

    /**
     * Parse a Java identifier with the given separator ('.' or '/'). Potentially replaces the separator with a
     * different character. Appends the identifier to the token buffer in the parser.
     * 
     * @param parser
     *            The parser.
     * @param separator
     *            The separator character.
     * @param separatorReplace
     *            The character to replace the separator with.
     * @return true if at least one identifier character was parsed.
     */
    public static boolean getIdentifierToken(final Parser parser, final char separator,
            final char separatorReplace) {
        boolean consumedChar = false;
        while (parser.hasMore()) {
            final char c = parser.peek();
            if (c == separator) {
                parser.appendToToken(separatorReplace);
                parser.next();
                consumedChar = true;
            } else if (c != ';' && c != '[' && c != '<' && c != '>' && c != ':' && c != '/' && c != '.') {
                parser.appendToToken(c);
                parser.next();
                consumedChar = true;
            } else {
                break;
            }
        }
        return consumedChar;
    }

    /**
     * Parse a Java identifier part (between separators and other non-alphanumeric characters). Appends the
     * identifier to the token buffer in the parser.
     * 
     * @param parser
     *            The parser.
     * @return true if at least one identifier character was parsed.
     * @throws ParseException
     *             If the parser ran out of input.
     */
    public static boolean getIdentifierToken(final Parser parser) throws ParseException {
        return getIdentifierToken(parser, '\0', '\0');
    }

    /** The origin of the modifier bits. */
    public enum ModifierType {
        /** The modifier bits apply to a class. */
        CLASS,
        /** The modifier bits apply to a method. */
        METHOD,
        /** The modifier bits apply to a field. */
        FIELD;
    }

    /**
     * Append a space if necessary (if not at the beginning of the buffer, and the last character is not already a
     * space), then append a modifier keyword.
     *
     * @param buf
     *            the buf
     * @param modifierKeyword
     *            the modifier keyword
     */
    private static void appendModifierKeyword(final StringBuilder buf, final String modifierKeyword) {
        if (buf.length() > 0 && buf.charAt(buf.length() - 1) != ' ') {
            buf.append(' ');
        }
        buf.append(modifierKeyword);
    }

    /**
     * Convert modifiers into a string representation, e.g. "public static final".
     * 
     * @param modifiers
     *            The field or method modifiers.
     * @param modifierType
     *            The {@link ModifierType} these modifiers apply to.
     * @param isDefault
     *            for methods, true if this is a default method (else ignored).
     * @param buf
     *            The buffer to write the result into.
     */
    public static void modifiersToString(final int modifiers, final ModifierType modifierType,
            final boolean isDefault, final StringBuilder buf) {
        if ((modifiers & Modifier.PUBLIC) != 0) {
            appendModifierKeyword(buf, "public");
        } else if ((modifiers & Modifier.PRIVATE) != 0) {
            appendModifierKeyword(buf, "private");
        } else if ((modifiers & Modifier.PROTECTED) != 0) {
            appendModifierKeyword(buf, "protected");
        }
        if (modifierType != ModifierType.FIELD && (modifiers & Modifier.ABSTRACT) != 0) {
            appendModifierKeyword(buf, "abstract");
        }
        if ((modifiers & Modifier.STATIC) != 0) {
            appendModifierKeyword(buf, "static");
        }
        if (modifierType == ModifierType.FIELD) {
            if ((modifiers & Modifier.VOLATILE) != 0) {
                // "bridge" and "volatile" overlap in bit 0x40
                appendModifierKeyword(buf, "volatile");
            }
            if ((modifiers & Modifier.TRANSIENT) != 0) {
                appendModifierKeyword(buf, "transient");
            }
        }
        if ((modifiers & Modifier.FINAL) != 0) {
            appendModifierKeyword(buf, "final");
        }
        if (modifierType == ModifierType.METHOD) {
            if ((modifiers & Modifier.SYNCHRONIZED) != 0) {
                appendModifierKeyword(buf, "synchronized");
            }
            if (isDefault) {
                appendModifierKeyword(buf, "default");
            }
        }
        if ((modifiers & 0x1000) != 0) {
            appendModifierKeyword(buf, "synthetic");
        }
        if (modifierType != ModifierType.FIELD && (modifiers & 0x40) != 0) {
            // "bridge" and "volatile" overlap in bit 0x40
            appendModifierKeyword(buf, "bridge");
        }
        if (modifierType == ModifierType.METHOD && (modifiers & Modifier.NATIVE) != 0) {
            appendModifierKeyword(buf, "native");
        }
        if (modifierType != ModifierType.FIELD && (modifiers & Modifier.STRICT) != 0) {
            appendModifierKeyword(buf, "strictfp");
        }
        // Ignored:
        // ACC_SUPER (0x0020): Treat superclass methods specially when invoked by the invokespecial instruction
    }
}
