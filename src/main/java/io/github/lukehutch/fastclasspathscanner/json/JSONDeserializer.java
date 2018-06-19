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
package io.github.lukehutch.fastclasspathscanner.json;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;

import io.github.lukehutch.fastclasspathscanner.json.JSONUtils.SerializableFieldInfo;
import io.github.lukehutch.fastclasspathscanner.utils.Parser;
import io.github.lukehutch.fastclasspathscanner.utils.Parser.ParseException;

/**
 * Fast, lightweight Java object to JSON serializer, and JSON to Java object deserializer. Handles cycles in the
 * object graph by inserting reference ids.
 */
class JSONDeserializer {

    // JSON PEG grammar: https://github.com/azatoth/PanPG/blob/master/grammars/JSON.peg

    private static Entry<String, Object> parseKV(final Parser parser) {
        // TODO Auto-generated method stub
        return null;
    }

    // -------------------------------------------------------------------------------------------------------------

    private static <T extends Class<T>> T parseJSONObject(final Parser parser, final T classType,
            final Map<Class<?>, SerializableFieldInfo> classToSerializableFieldInfo) throws ParseException {
        parser.expect('{');
        T obj = null;
        try {
            obj = classType.getDeclaredConstructor().newInstance();
        } catch (final Exception e) {
            throw new IllegalArgumentException("Cannot instantiate class " + classType, e);
        }
        final SerializableFieldInfo serializableFieldInfo = JSONUtils
                .getSerializableFieldInfo(classToSerializableFieldInfo, classType);

        boolean first = true;
        for (Entry<String, Object> kv = parseKV(parser); kv != null; kv = parseKV(parser)) {
            final Field f; // TODO

            if (first) {
                first = false;
            } else {
                if (parser.peek() == ',') {
                    parser.expect(',');
                }
            }
        }
        if (first) {
            parser.skipWhitespace();
        }
        parser.expect('}');

        return null; // TODO

    }

    static <T extends Class<T>> T fromJSONObject(final String json, final T classType) {
        try {
            final Parser parser = new Parser(json);
            final Map<Class<?>, SerializableFieldInfo> classToSerializableFieldInfo = new HashMap<>();
            return parseJSONObject(parser, classType, classToSerializableFieldInfo);
        } catch (final ParseException e) {
            throw new IllegalArgumentException("JSON could not be parsed", e);
        }
    }

}
