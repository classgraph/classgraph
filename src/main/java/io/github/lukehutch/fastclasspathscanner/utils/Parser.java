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

public class Parser {
    private final String string;
    private int position;
    private final StringBuilder token = new StringBuilder();
    private Object state;

    public static class ParseException extends Exception {
        static final long serialVersionUID = 1L;
    }

    public Parser(final String string) {
        if (string == null) {
            throw new IllegalArgumentException("Cannot parse null string");
        }
        this.string = string;
    }

    public Object setState(final Object state) {
        final Object oldState = this.state;
        this.state = state;
        return oldState;
    }

    public Object getState() {
        return state;
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

    public boolean peekMatches(final String strMatch) {
        return string.regionMatches(position, strMatch, 0, strMatch.length());
    }

    public void next() {
        position++;
    }

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

    public void appendToToken(final String str) {
        token.append(str);
    }

    public void appendToToken(final char c) {
        token.append(c);
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