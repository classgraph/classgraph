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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** An intermediate object in the (de)serialization process, representing a JSON array. */
class JSONArray {
    /** Array items. */
    List<Object> items;

    /**
     * Constructor.
     */
    public JSONArray() {
        items = new ArrayList<>();
    }

    /**
     * Constructor.
     *
     * @param items
     *            the items
     */
    public JSONArray(final List<Object> items) {
        this.items = items;
    }

    /**
     * Serialize this JSONArray to a string.
     *
     * @param jsonReferenceToId
     *            the map from json reference to id
     * @param includeNullValuedFields
     *            whether to include null-valued fields
     * @param depth
     *            the nesting depth
     * @param indentWidth
     *            the indent width
     * @param buf
     *            the buf
     */
    void toJSONString(final Map<ReferenceEqualityKey<JSONReference>, CharSequence> jsonReferenceToId,
            final boolean includeNullValuedFields, final int depth, final int indentWidth,
            final StringBuilder buf) {
        final boolean prettyPrint = indentWidth > 0;
        final int n = items.size();
        if (n == 0) {
            buf.append("[]");
        } else {
            buf.append('[');
            if (prettyPrint) {
                buf.append('\n');
            }
            for (int i = 0; i < n; i++) {
                final Object item = items.get(i);
                if (prettyPrint) {
                    JSONUtils.indent(depth + 1, indentWidth, buf);
                }
                JSONSerializer.jsonValToJSONString(item, jsonReferenceToId, includeNullValuedFields, depth + 1,
                        indentWidth, buf);
                if (i < n - 1) {
                    buf.append(',');
                }
                if (prettyPrint) {
                    buf.append('\n');
                }
            }
            if (prettyPrint) {
                JSONUtils.indent(depth, indentWidth, buf);
            }
            buf.append(']');
        }
    }
}