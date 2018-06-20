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

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Fast, lightweight Java object to JSON serializer, and JSON to Java object deserializer. Handles cycles in the
 * object graph by inserting reference ids.
 */
public class TinyJSONMapper {
    /**
     * Annotate a class field with this annotation to use that field's value instead of the automatically-generated
     * id for object references in JSON output. The field value must be a unique identifier across the whole object
     * graph.
     */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.FIELD)
    public static @interface Id {
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * JSON object key name for objects that are linked to from more than one object. Key name is only used if the
     * class that a JSON object was serialized from does not have its own id field annotated with {@link Id}.
     */
    private static final String ID_TAG = "_ID";

    /** JSON object reference id prefix. */
    private static final String ID_PREFIX = "[ID#";

    /** JSON object reference id suffix. */
    private static final String ID_SUFFIX = "]";

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Recursively serialize an Object (or array, list, map or set of objects) to JSON, skipping transient and final
     * fields.
     */
    public static String toJSON(final Object obj, final int indentWidth, final boolean onlySerializePublicFields) {
        return JSONSerializer.toJSON(obj, indentWidth, onlySerializePublicFields);
    }

    // -------------------------------------------------------------------------------------------------------------

    //    /** Recursively deserialize an Object from JSON. */
    //    public static <T extends Class<T>> T fromJSONObject(final String json, final T classType) {
    //        return JSONDeserializer.fromJSONObject(json, classType);
    //    }
}
