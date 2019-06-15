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
package nonapi.io.github.classgraph.json;

import java.util.AbstractMap.SimpleEntry;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map.Entry;

import nonapi.io.github.classgraph.types.ParseException;
import nonapi.io.github.classgraph.types.Parser;

/**
 * A JSON parser, based on the PEG grammar found at:
 * 
 * https://github.com/azatoth/PanPG/blob/master/grammars/JSON.peg
 */
final class JSONParser extends Parser {

    /**
     * Constructor.
     *
     * @param string
     *            the string
     * @throws ParseException
     *             if parsing fails
     */
    private JSONParser(final String string) throws ParseException {
        super(string);
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Get and parse a hexadecimal digit character.
     *
     * @return the hex char
     * @throws ParseException
     *             if the character was not hexadecimal
     */
    private int getAndParseHexChar() throws ParseException {
        final char hexChar = getc();
        if (hexChar >= '0' && hexChar <= '9') {
            return hexChar - '0';
        } else if (hexChar >= 'a' && hexChar <= 'f') {
            return hexChar - 'a' + 10;
        } else if (hexChar >= 'A' && hexChar <= 'F') {
            return hexChar - 'A' + 10;
        } else {
            throw new ParseException(this, "Invalid character in Unicode escape sequence: " + hexChar);
        }
    }

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
     *
     * @return the char sequence
     * @throws ParseException
     *             if the escape sequence was invalid
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
                    charVal = getAndParseHexChar() << 12;
                    charVal |= getAndParseHexChar() << 8;
                    charVal |= getAndParseHexChar() << 4;
                    charVal |= getAndParseHexChar();
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
     *
     * @return the number
     * @throws ParseException
     *             if parsing fails
     */
    private Number parseNumber() throws ParseException {
        final int startIdx = getPosition();
        if (peekMatches("Infinity")) {
            advance(8);
            return Double.POSITIVE_INFINITY;
        } else if (peekMatches("-Infinity")) {
            advance(9);
            return Double.NEGATIVE_INFINITY;
        } else if (peekMatches("NaN")) {
            advance(3);
            return Double.NaN;
        }
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
        final boolean hasExponentPart = peek() == 'e' || peek() == 'E';
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
        final String numberStr = getSubstring(startIdx, endIdx);
        if (hasFractionalPart || hasExponentPart) {
            return Double.valueOf(numberStr);
        } else if (numIntegralDigits < 9) {
            return Integer.valueOf(numberStr);
        } else if (numIntegralDigits == 9) {
            // For 9-digit numbers, could be int or long
            final long longVal = Long.parseLong(numberStr);
            if (longVal >= Integer.MIN_VALUE && longVal <= Integer.MAX_VALUE) {
                return (int) longVal;
            } else {
                return longVal;
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
     * 
     * .
     *
     * @return the JSON array
     * @throws ParseException
     *             if parsing fails
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
     *
     * @return the JSON object
     * @throws ParseException
     *             if parsing fails
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
     *
     * @return the parsed JSON object
     * @throws ParseException
     *             if parsing fails
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
                return Boolean.TRUE;

            } else if (peekMatches("false")) {
                // Parse true value
                advance(5);
                return Boolean.FALSE;

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

    /**
     * Parse a JSON object, array, string, value or object reference.
     *
     * @param str
     *            the str
     * @return the parsed JSON object
     * @throws ParseException
     *             if parsing fails
     */
    static Object parseJSON(final String str) throws ParseException {
        return new JSONParser(str).parseJSON();
    }
}