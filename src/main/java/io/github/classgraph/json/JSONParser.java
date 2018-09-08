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
package io.github.classgraph.json;

import java.util.AbstractMap.SimpleEntry;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map.Entry;

import io.github.classgraph.utils.Parser;

/**
 * A JSON parser, based on the PEG grammar found at:
 * 
 * https://github.com/azatoth/PanPG/blob/master/grammars/JSON.peg
 */
class JSONParser extends Parser {
    private JSONParser(final String string) throws ParseException {
        super(string);
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Parse a quoted/escaped JSON string.
     * 
     * <pre>
     * 
     *     String ← S? ["] ( [^ " \ U+0000-U+001F ] / Escape )* ["] S?
     * 
     *     Escape ← [\] ( [ " / \ b f n r t ] / UnicodeEscape )
     * 
     *     UnicodeEscape ← "u" [0-9A-Fa-f]{4}
     * 
     * </pre>
     */
    private CharSequence parseString() throws ParseException {
        skipWhitespace();
        if (peek() != '"') {
            return null;
        }
        next();
        final int startIdx = getPosition();

        // Fast path
        boolean hasEscape = false;
        while (hasMore()) {
            final char c = getc();
            if (c == '\\') {
                switch (getc()) {
                case 'b':
                case 'f':
                case 'n':
                case 'r':
                case 't':
                case '\'':
                case '"':
                case '/':
                case '\\':
                    hasEscape = true;
                    break;
                case 'u':
                    hasEscape = true;
                    advance(4);
                    break;
                default:
                    throw new ParseException(this, "Invalid escape sequence: \\" + c);
                }
            } else if (c == '"') {
                break;
            }
        }
        final int endIdx = getPosition() - 1;
        if (!hasEscape) {
            return getSubsequence(startIdx, endIdx);
        }

        // Slow path (for strings with escape characters)
        setPosition(startIdx);
        final StringBuilder buf = new StringBuilder();
        while (hasMore()) {
            final char c = getc();
            if (c == '\\') {
                switch (getc()) {
                case 'b':
                    buf.append('\b');
                    break;
                case 'f':
                    buf.append('\f');
                    break;
                case 'n':
                    buf.append('\n');
                    break;
                case 'r':
                    buf.append('\r');
                    break;
                case 't':
                    buf.append('\t');
                    break;
                case '\'':
                case '"':
                case '/':
                case '\\':
                    buf.append(c);
                    break;
                case 'u':
                    int charVal = 0;
                    boolean charValInvalid = false;
                    final char h3 = getc();
                    if (h3 >= '0' && h3 <= '9') {
                        charVal |= ((h3 - '0') << 12);
                    } else if (h3 >= 'a' && h3 <= 'f') {
                        charVal |= ((h3 - 'a' + 10) << 12);
                    } else if (h3 >= 'A' && h3 <= 'F') {
                        charVal |= ((h3 - 'A' + 10) << 12);
                    } else {
                        charValInvalid = true;
                    }
                    final char h2 = getc();
                    if (h2 >= '0' && h2 <= '9') {
                        charVal |= ((h2 - '0') << 8);
                    } else if (h2 >= 'a' && h2 <= 'f') {
                        charVal |= ((h2 - 'a' + 10) << 8);
                    } else if (h2 >= 'A' && h2 <= 'F') {
                        charVal |= ((h2 - 'A' + 10) << 8);
                    } else {
                        charValInvalid = true;
                    }
                    final char h1 = getc();
                    if (h1 >= '0' && h1 <= '9') {
                        charVal |= ((h1 - '0') << 4);
                    } else if (h1 >= 'a' && h1 <= 'f') {
                        charVal |= ((h1 - 'a' + 10) << 4);
                    } else if (h1 >= 'A' && h1 <= 'F') {
                        charVal |= ((h1 - 'A' + 10) << 4);
                    } else {
                        charValInvalid = true;
                    }
                    final char h0 = getc();
                    if (h0 >= '0' && h0 <= '9') {
                        charVal |= (h0 - '0');
                    } else if (h0 >= 'a' && h0 <= 'f') {
                        charVal |= (h0 - 'a' + 10);
                    } else if (h0 >= 'A' && h0 <= 'F') {
                        charVal |= (h0 - 'A' + 10);
                    } else {
                        charValInvalid = true;
                    }
                    if (charValInvalid) {
                        throw new ParseException(this,
                                "Invalid Unicode escape sequence: \\" + c + "" + h3 + "" + h2 + "" + h1 + "" + h0);
                    }
                    buf.append((char) charVal);
                    break;
                default:
                    throw new ParseException(this, "Invalid escape sequence: \\" + c);
                }
            } else if (c == '"') {
                break;
            } else {
                buf.append(c);
            }
        }
        skipWhitespace();
        return buf.toString();
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Parses and returns Integer, Long or Double type.
     * 
     * <pre>
     * 
     *     Number ← Minus? IntegralPart FractionalPart? ExponentPart?
     * 
     *     Minus ← "-"
     * 
     *     IntegralPart ← "0" / [1-9] [0-9]*
     * 
     *     FractionalPart ← "." [0-9]+
     * 
     *     ExponentPart ← ( "e" / "E" ) ( "+" / "-" )? [0-9]+
     * 
     * </pre>
     */
    private Object parseNumber() throws ParseException {
        final int startIdx = getPosition();
        if (peek() == '-') {
            next();
        }
        final int integralStartIdx = getPosition();
        for (; hasMore(); next()) {
            final char c = peek();
            if (c < '0' || c > '9') {
                break;
            }
        }
        final int integralEndIdx = getPosition();
        final int numIntegralDigits = integralEndIdx - integralStartIdx;
        if (numIntegralDigits == 0) {
            throw new ParseException(this, "Expected a number");
        }
        final boolean hasFractionalPart = peek() == '.';
        if (hasFractionalPart) {
            next();
            for (; hasMore(); next()) {
                final char c = peek();
                if (c < '0' || c > '9') {
                    break;
                }
            }
            if (getPosition() - (integralEndIdx + 1) == 0) {
                throw new ParseException(this, "Expected digits after decimal point");
            }
        }
        final boolean hasExponentPart = peek() == '.';
        if (hasExponentPart) {
            next();
            final char sign = peek();
            if (sign == '-' || sign == '+') {
                next();
            }
            final int exponentStart = getPosition();
            for (; hasMore(); next()) {
                final char c = peek();
                if (c < '0' || c > '9') {
                    break;
                }
            }
            if (getPosition() - exponentStart == 0) {
                throw new ParseException(this, "Expected an exponent");
            }
        }
        final int endIdx = getPosition();
        final String numberStr = getSubstring(startIdx, endIdx).toString();
        if (hasFractionalPart || hasExponentPart) {
            return Double.valueOf(numberStr);
        } else if (numIntegralDigits < 9) {
            return Integer.valueOf(numberStr);
        } else if (numIntegralDigits == 9) {
            // For 9-digit numbers, could be int or long
            final long longVal = Long.parseLong(numberStr);
            if (longVal >= Integer.MIN_VALUE && longVal < Integer.MAX_VALUE) {
                return Integer.valueOf((int) longVal);
            } else {
                return Long.valueOf(longVal);
            }
        } else {
            return Long.valueOf(numberStr);
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * <pre>
     * 
     *     Array ← "[" ( JSON ( "," JSON )* / S? ) "]"
     * 
     * </pre>
     */
    private JSONArray parseJSONArray() throws ParseException {
        expect('[');
        skipWhitespace();
        if (peek() == ']') {
            // Empty array
            next();
            return new JSONArray(Collections.emptyList());
        }

        final List<Object> elements = new ArrayList<>();
        boolean first = true;
        while (peek() != ']') {
            if (first) {
                first = false;
            } else {
                expect(',');
            }
            elements.add(parseJSON());
        }
        expect(']');
        return new JSONArray(elements);
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Parse a JSON Object.
     * 
     * <pre>
     * 
     *     Object ← "{" ( String ":" JSON ( "," String ":" JSON )* / S? ) "}"
     * 
     * </pre>
     */

    private JSONObject parseJSONObject() throws ParseException {
        expect('{');
        skipWhitespace();
        if (peek() == '}') {
            // Empty object
            next();
            return new JSONObject(Collections.<Entry<String, Object>> emptyList());
        }

        final List<Entry<String, Object>> kvPairs = new ArrayList<>();
        final JSONObject jsonObject = new JSONObject(kvPairs);
        boolean first = true;
        while (peek() != '}') {
            if (first) {
                first = false;
            } else {
                expect(',');
            }
            final CharSequence key = parseString();
            if (key == null) {
                throw new ParseException(this, "Object keys must be strings");
            }
            if (peek() != ':') {
                return null;
            }
            expect(':');
            final Object value = parseJSON();

            // Check for special object id key
            if (key.equals(JSONUtils.ID_KEY)) {
                if (value == null) {
                    throw new ParseException(this, "Got null value for \"" + JSONUtils.ID_KEY + "\" key");
                }
                jsonObject.objectId = (CharSequence) value;
            } else {
                kvPairs.add(new SimpleEntry<>(key.toString(), value));
            }
        }
        expect('}');
        return jsonObject;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Parse a JSON type (object / array / value).
     * 
     * <p>
     * String values will have CharSequence type. Numerical values will have Integer, Long or Double type. Can
     * return null for JSON null value.
     *
     * <pre>
     * 
     *     JSON ← S? ( Object / Array / String / True / False / Null / Number ) S?
     *
     * </pre>
     */
    private Object parseJSON() throws ParseException {
        skipWhitespace();
        try {
            final char c = peek();
            if (c == '{') {
                // Parse a JSON object
                return parseJSONObject();

            } else if (c == '[') {
                // Parse a JSON array
                return parseJSONArray();

            } else if (c == '"') {
                // Parse a JSON string or object reference
                final CharSequence charSequence = parseString();
                if (charSequence == null) {
                    throw new ParseException(this, "Invalid string");
                }
                return charSequence;

            } else if (peekMatches("true")) {
                // Parse true value
                advance(4);
                return Boolean.valueOf(true);

            } else if (peekMatches("false")) {
                // Parse true value
                advance(5);
                return Boolean.valueOf(false);

            } else if (peekMatches("null")) {
                advance(4);
                // Parse null value (in string representation)
                return null;

            } else {
                // The only remaining option is that the value must be a number
                return parseNumber();
            }
        } finally {
            skipWhitespace();
        }
    }

    /** Parse a JSON object, array, string, value or object reference. */
    static Object parseJSON(final String str) throws ParseException {
        return new JSONParser(str).parseJSON();
    }
}