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

        public ParseException(final Parser parser, final String msg) {
            super(parser == null ? msg : msg + " " + parser.getPositionInfo(msg));
        }
    }

    public Parser(final String string) throws ParseException {
        if (string == null) {
            throw new ParseException(null, "Cannot parse null string");
        }
        this.string = string;
    }

    private static final int SHOW_BEFORE = 32;
    private static final int SHOW_AFTER = 8;

    public String getPositionInfo(String msg) {
        int showStart = Math.max(0, position - SHOW_BEFORE);
        int showEnd = Math.min(string.length(), position + SHOW_AFTER);
        return " (before: \"" + string.substring(showStart, position) + "\"; after: \""
                + string.substring(position, showEnd) + "\"; position: " + position + "; token: \"" + token + "\")";
    }

    public Object setState(final Object state) {
        final Object oldState = this.state;
        this.state = state;
        return oldState;
    }

    public Object getState() {
        return state;
    }

    public char getc() throws ParseException {
        if (position >= string.length()) {
            throw new ParseException(this, "Ran out of input while parsing");
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

    public void expect(final char c) throws ParseException {
        final int next = getc();
        if (next != c) {
            throw new ParseException(this, "Expected character '" + c + "', got '" + next + "'");
        }
    }

    public void appendToToken(final String str) {
        token.append(str);
    }

    public void appendToToken(final char c) {
        token.append(c);
    }

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

    @Override
    public String toString() {
        return string + " (position: " + position + "; token: \"" + token + "\")";
    }
}