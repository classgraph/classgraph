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
package io.github.classgraph.utils;

import io.github.classgraph.utils.Parser.ParseException;

/**
 * Utilities for parsing Java type descriptors and type signatures.
 * 
 * @author lukehutch
 */
public class TypeUtils {
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
     * @throws ParseException
     *             If the parser ran out of input.
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

}
