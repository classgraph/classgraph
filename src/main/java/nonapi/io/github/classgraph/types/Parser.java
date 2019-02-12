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

import nonapi.io.github.classgraph.json.JSONUtils;

/**
 * A generic PEG parser.
 */
public class Parser {
    /** The string being parsed. */
    private final String string;

    /** The current position. */
    private int position;

    /** The token buffer. */
    private final StringBuilder token = new StringBuilder();

    /** Extra parsing state. */
    private Object state;

    /** How much context to show before the current position. */
    private static final int SHOW_BEFORE = 80;

    /** How much context to show after the current position. */
    private static final int SHOW_AFTER = 80;

    /**
     * Construct a parser.
     * 
     * @param string
     *            The string to parse.
     * @throws ParseException
     *             If the string was null.
     */
    public Parser(final String string) throws ParseException {
        if (string == null) {
            throw new ParseException(null, "Cannot parse null string");
        }
        this.string = string;
    }

    /**
     * Get the parsing context as a string, for debugging.
     *
     * @return A string showing parsing context, for debugging.
     */
    public String getPositionInfo() {
        final int showStart = Math.max(0, position - SHOW_BEFORE);
        final int showEnd = Math.min(string.length(), position + SHOW_AFTER);
        return "before: \"" + JSONUtils.escapeJSONString(string.substring(showStart, position)) + "\"; after: \""
                + JSONUtils.escapeJSONString(string.substring(position, showEnd)) + "\"; position: " + position
                + "; token: \"" + token + "\"";
    }

    /**
     * Set the "state object" from the parser (can be used to parse state between parser functions).
     * 
     * @param state
     *            The state object.
     * @return The old value of the state object.
     */
    public Object setState(final Object state) {
        final Object oldState = this.state;
        this.state = state;
        return oldState;
    }

    /**
     * Get the "state object" from the parser (can be used to parse state between parser functions).
     * 
     * @return The current value of the state object.
     */
    public Object getState() {
        return state;
    }

    /**
     * Get the next character.
     * 
     * @return The next character.
     * @throws ParseException
     *             If there were no more characters in the string.
     */
    public char getc() throws ParseException {
        if (position >= string.length()) {
            throw new ParseException(this, "Ran out of input while parsing");
        }
        return string.charAt(position++);
    }

    /**
     * Expect the next character.
     * 
     * @param expectedChar
     *            The expected character.
     * @throws ParseException
     *             If the next character was not the expected character.
     */
    public void expect(final char expectedChar) throws ParseException {
        final int next = getc();
        if (next != expectedChar) {
            throw new ParseException(this, "Expected '" + expectedChar + "'; got '" + (char) next + "'");
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
     * Get the next character, throwing a {@link ParseException} if the next character is not the expected
     * character.
     * 
     * @param expectedChar
     *            The expected next character.
     * @throws ParseException
     *             If the next character is not the expected next character.
     */
    public void peekExpect(final char expectedChar) throws ParseException {
        if (position == string.length()) {
            throw new ParseException(this, "Expected '" + expectedChar + "'; reached end of string");
        }
        final char next = string.charAt(position);
        if (next != expectedChar) {
            throw new ParseException(this, "Expected '" + expectedChar + "'; got '" + next + "'");
        }
    }

    /**
     * Peek operator that can look ahead several characters.
     * 
     * @param strMatch
     *            The string to compare, starting at the current position, as a "peek" operation.
     * @return True if the strMatch matches a substring of the remaining string starting at the current position.
     */
    public boolean peekMatches(final String strMatch) {
        return string.regionMatches(position, strMatch, 0, strMatch.length());
    }

    /**
     * Advance one character without returning the value of the character.
     */
    public void next() {
        position++;
    }

    /**
     * Advance numChars character positions.
     * 
     * @param numChars
     *            The number of character positions to advance.
     * @throws IllegalArgumentException
     *             If there are insufficient characters remaining in the string.
     */
    public void advance(final int numChars) {
        if (position + numChars >= string.length()) {
            throw new IllegalArgumentException("Invalid skip distance");
        }
        position += numChars;
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
     * Set the position of the parser within the string.
     * 
     * @param position
     *            The position to move to.
     * @throws IllegalArgumentException
     *             If the position is out of range.
     */
    public void setPosition(final int position) {
        if (position < 0 || position >= string.length()) {
            throw new IllegalArgumentException("Invalid position");
        }
        this.position = position;
    }

    /**
     * Return a subsequence of the input string.
     * 
     * @param startPosition
     *            The start position.
     * @param endPosition
     *            The end position.
     * @return The subsequence.
     */
    public CharSequence getSubsequence(final int startPosition, final int endPosition) {
        return string.subSequence(startPosition, endPosition);
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
     * Append the given string to the token buffer.
     * 
     * @param str
     *            The string to append.
     */
    public void appendToToken(final String str) {
        token.append(str);
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
     * Skip whitespace starting at the current position.
     */
    public void skipWhitespace() {
        while (position < string.length()) {
            final char c = string.charAt(position);
            if (c == ' ' || c == '\n' || c == '\r' || c == '\t') {
                position++;
            } else {
                break;
            }
        }
    }

    /**
     * Get the current token, and reset the token to empty.
     * 
     * @return The current token. Resets the current token to empty.
     */
    public String currToken() {
        final String tok = token.toString();
        token.setLength(0);
        return tok;
    }

    /* (non-Javadoc)
     * @see java.lang.Object#toString()
     */
    @Override
    public String toString() {
        return getPositionInfo();
    }
}