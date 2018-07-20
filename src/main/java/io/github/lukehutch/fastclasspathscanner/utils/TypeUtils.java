/*
 * This file is part of FastClasspathScanner.
 *
 * Author: Luke Hutchison
 *
 * Hosted at: https://github.com/lukehutch/fast-classpath-scanner
 *
 * --
 *
 * The MIT License (MIT)
 *
 * Copyright (c) 2018 Luke Hutchison
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
package io.github.lukehutch.fastclasspathscanner.utils;

import java.lang.reflect.Modifier;

import io.github.lukehutch.fastclasspathscanner.utils.Parser.ParseException;

/**
 * Utilities for parsing Java type descriptors and type signatures.
 * 
 * @author lukehutch
 */
public class TypeUtils {
    /** The modifier bit for synthetic parameters. */
    public static final int MODIFIER_SYNTHETIC = 0x1000;

    /** The modifier bit for mandated parameters. */
    public static final int MODIFIER_MANDATED = 0x8000;

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Convert modifiers into a string representation, e.g. "public static final".
     * 
     * @param modifiers
     *            The field, method or class modifiers.
     * @param isMethod
     *            True if this is a method, false if this is a field or class.
     * @return The modifiers, as a string.
     */
    public static String modifiersToString(final int modifiers, final boolean isMethod) {
        final StringBuilder buf = new StringBuilder();
        modifiersToString(modifiers, isMethod, buf);
        return buf.toString();
    }

    /**
     * Convert modifiers into a string representation, e.g. "public static final".
     * 
     * @param modifiers
     *            The field or method modifiers.
     * @param isMethod
     *            True if this is a method, false if this is a field or class.
     * @param buf
     *            The buffer to write the result into.
     */
    public static void modifiersToString(final int modifiers, final boolean isMethod, final StringBuilder buf) {
        if ((modifiers & Modifier.PUBLIC) != 0) {
            buf.append("public");
        } else if ((modifiers & Modifier.PROTECTED) != 0) {
            buf.append("protected");
        } else if ((modifiers & Modifier.PRIVATE) != 0) {
            buf.append("private");
        }
        if ((modifiers & Modifier.ABSTRACT) != 0) {
            if (buf.length() > 0) {
                buf.append(' ');
            }
            buf.append("abstract");
        }
        if ((modifiers & Modifier.STATIC) != 0) {
            if (buf.length() > 0) {
                buf.append(' ');
            }
            buf.append("static");
        }
        if ((modifiers & Modifier.FINAL) != 0) {
            if (buf.length() > 0) {
                buf.append(' ');
            }
            buf.append("final");
        }
        if (!isMethod && (modifiers & Modifier.TRANSIENT) != 0) {
            // TRANSIENT has the same value as VARARGS, since they are mutually exclusive (TRANSIENT applies only to
            // fields, VARARGS applies only to methods)
            if (buf.length() > 0) {
                buf.append(' ');
            }
            buf.append("transient");
        } else if ((modifiers & Modifier.VOLATILE) != 0) {
            // VOLATILE has the same value as BRIDGE, since they are mutually exclusive (VOLATILE applies only to
            // fields, BRIDGE applies only to methods)
            if (buf.length() > 0) {
                buf.append(' ');
            }
            if (!isMethod) {
                buf.append("volatile");
            } else {
                buf.append("bridge");
            }
        }
        if (!isMethod && ((modifiers & MODIFIER_SYNTHETIC) != 0)) {
            if (buf.length() > 0) {
                buf.append(' ');
            }
            // For a synthetic class (synthetic method parameters have the synthetic keyword added manually) 
            buf.append("synthetic");
        }
        if ((modifiers & Modifier.SYNCHRONIZED) != 0) {
            if (buf.length() > 0) {
                buf.append(' ');
            }
            buf.append("synchronized");
        }
        if ((modifiers & Modifier.NATIVE) != 0) {
            if (buf.length() > 0) {
                buf.append(' ');
            }
            buf.append("native");
        }
        if ((modifiers & Modifier.STRICT) != 0) {
            if (buf.length() > 0) {
                buf.append(' ');
            }
            buf.append("strictfp");
        }
    }

    /**
     * Parse a Java identifier with the given separator ('.' or '/'). Potentially replaces the separator with a
     * different character. Appends the identifier to the token buffer in the parser.
     */
    public static boolean getIdentifierToken(final Parser parser, final char separator, final char separatorReplace)
            throws ParseException {
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
     */
    public static boolean getIdentifierToken(final Parser parser) throws ParseException {
        return getIdentifierToken(parser, '\0', '\0');
    }

}
