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
package io.github.lukehutch.fastclasspathscanner.typesignature;

import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

public class TypeUtils {
    /** The modifier bit for synthetic parameters. */
    public static final int MODIFIER_SYNTHETIC = 0x1000;

    /** The modifier bit for mandated parameters. */
    public static final int MODIFIER_MANDATED = 0x8000;

    // -------------------------------------------------------------------------------------------------------------

    static class ParseException extends Exception {
        static final long serialVersionUID = 1L;
    }

    static class ParseState {
        private final String string;
        private int position;
        private final StringBuilder token = new StringBuilder();
        private final List<TypeVariableSignature> typeVariableSignatures = new ArrayList<>();

        public ParseState(final String string) {
            if (string == null) {
                throw new IllegalArgumentException("Cannot parse null string");
            }
            this.string = string;
        }

        public void addTypeVariableSignature(final TypeVariableSignature typeVariableSignature) {
            typeVariableSignatures.add(typeVariableSignature);
        }

        public List<TypeVariableSignature> getTypeVariableSignatures() {
            return typeVariableSignatures;
        }

        public char getc() {
            if (position >= string.length()) {
                return '\0';
            }
            return string.charAt(position++);
        }

        public char peek() {
            return position == string.length() ? '\0' : string.charAt(position);
        }

        @SuppressWarnings("unused")
        public boolean peekMatches(final String strMatch) {
            return string.regionMatches(position, strMatch, 0, strMatch.length());
        }

        public void next() {
            position++;
        }

        @SuppressWarnings("unused")
        public void advance(final int n) {
            position += n;
        }

        public boolean hasMore() {
            return position < string.length();
        }

        public void expect(final char c) {
            final int next = getc();
            if (next != c) {
                throw new IllegalArgumentException(
                        "Got character '" + (char) next + "', expected '" + c + "' in string: " + this);
            }
        }

        @SuppressWarnings("unused")
        public void appendToToken(final String str) {
            token.append(str);
        }

        public void appendToToken(final char c) {
            token.append(c);
        }

        /** Get the current token, and reset the token to empty. */
        public String currToken() {
            final String tok = token.toString();
            token.setLength(0);
            return tok;
        }

        public boolean parseIdentifier(final char separator, final char separatorReplace) throws ParseException {
            boolean consumedChar = false;
            while (hasMore()) {
                final char c = peek();
                if (c == separator) {
                    appendToToken(separatorReplace);
                    next();
                    consumedChar = true;
                } else if (c != ';' && c != '[' && c != '<' && c != '>' && c != ':' && c != '/' && c != '.') {
                    appendToToken(c);
                    next();
                    consumedChar = true;
                } else {
                    break;
                }
            }
            return consumedChar;
        }

        public boolean parseIdentifier() throws ParseException {
            return parseIdentifier('\0', '\0');
        }

        @Override
        public String toString() {
            return string + " (position: " + position + "; token: \"" + token + "\")";
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /** Convert field or method modifiers into a string representation, e.g. "public static final". */
    public static String modifiersToString(final int modifiers, final boolean isMethod) {
        final StringBuilder buf = new StringBuilder();
        modifiersToString(modifiers, isMethod, buf);
        return buf.toString();
    }

    /** Convert field or method modifiers into a string representation, e.g. "public static final". */
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
}
