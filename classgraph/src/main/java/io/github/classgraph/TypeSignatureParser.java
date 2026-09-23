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
package io.github.classgraph;

import java.util.ArrayList;
import java.util.List;

import io.github.classgraph.base.internal.utils.StringUtils;

/**
 * The recursive descent parser that the type signature classes read JVM type signatures with. It holds the string
 * being parsed and the position within it, and provides the primitive operations the grammar rules are written in
 * terms of: peeking at, consuming and accumulating characters, and reporting where parsing failed.
 */
class TypeSignatureParser {
    /** The string being parsed. */
    private final String string;

    /** The current position. */
    private int position;

    /** The token buffer. */
    private final StringBuilder token = new StringBuilder();

    /** The type variable signatures parsed so far, which a method signature links back to once it is parsed. */
    private final List<TypeVariableSignature> typeVariableSignatures = new ArrayList<>();

    /** How much context to show before the current position. */
    private static final int SHOW_BEFORE = 80;

    /** How much context to show after the current position. */
    private static final int SHOW_AFTER = 80;

    /**
     * Construct a parser.
     *
     * @param string
     *            The string to parse.
     * @throws TypeSignatureParseException
     *             If the string was null.
     */
    public TypeSignatureParser(final String string) throws TypeSignatureParseException {
        if (string == null) {
            throw new TypeSignatureParseException(null, "Cannot parse null string");
        }
        this.string = string;
    }

    /**
     * Get the parsing context as a string, for debugging.
     *
     * @return A string showing parsing context, for debugging.
     */
    public String getPositionInfo() {
        final var showStart = Math.max(0, position - SHOW_BEFORE);
        final var showEnd = Math.min(string.length(), position + SHOW_AFTER);
        return "before: \"" + StringUtils.escapeString(string.substring(showStart, position)) + "\"; after: \""
                + StringUtils.escapeString(string.substring(position, showEnd)) + "\"; position: " + position
                + "; token: \"" + token + "\"";
    }

    /**
     * Record a type variable signature that has been parsed.
     *
     * @param typeVariableSignature
     *            The type variable signature.
     */
    public void addTypeVariableSignature(final TypeVariableSignature typeVariableSignature) {
        typeVariableSignatures.add(typeVariableSignature);
    }

    /**
     * Get the type variable signatures parsed so far.
     *
     * @return The type variable signatures, in the order they were parsed.
     */
    public List<TypeVariableSignature> getTypeVariableSignatures() {
        return typeVariableSignatures;
    }

    /**
     * Get the next character.
     *
     * @return The next character.
     * @throws TypeSignatureParseException
     *             If there were no more characters in the string.
     */
    public char getc() throws TypeSignatureParseException {
        if (position >= string.length()) {
            throw new TypeSignatureParseException(this, "Ran out of input while parsing");
        }
        return string.charAt(position++);
    }

    /**
     * Expect the next character.
     *
     * @param expectedChar
     *            The expected character.
     * @throws TypeSignatureParseException
     *             If the next character was not the expected character.
     */
    public void expect(final char expectedChar) throws TypeSignatureParseException {
        final int next = getc();
        if (next != expectedChar) {
            throw new TypeSignatureParseException(this,
                    "Expected '" + expectedChar + "'; got '" + (char) next + "'");
        }
    }

    /**
     * Peek at the next character without reading it.
     *
     * @return The next character, or '\0' if at the end of the string.
     */
    public char peek() {
        return position == string.length() ? '\0' : string.charAt(position);
    }

    /**
     * Advance one character without returning the value of the character.
     */
    public void next() {
        position++;
    }

    /**
     * Check to see if there are more characters to parse.
     *
     * @return true if the input has not all been consumed.
     */
    public boolean hasMore() {
        return position < string.length();
    }

    /**
     * Get the current position.
     *
     * @return the current position.
     */
    public int getPosition() {
        return position;
    }

    /**
     * Return a substring of the input string.
     *
     * @param startPosition
     *            The start position.
     * @param endPosition
     *            The end position.
     * @return The substring.
     */
    public String getSubstring(final int startPosition, final int endPosition) {
        return string.substring(startPosition, endPosition);
    }

    /**
     * Append the given character to the token buffer.
     *
     * @param c
     *            The character to append.
     */
    public void appendToToken(final char c) {
        token.append(c);
    }

    /**
     * Get the current token, and reset the token to empty.
     *
     * @return The current token. Resets the current token to empty.
     */
    public String currToken() {
        final var tok = token.toString();
        token.setLength(0);
        return tok;
    }

    @Override
    public String toString() {
        return getPositionInfo();
    }
}
