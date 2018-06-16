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
package io.github.lukehutch.fastclasspathscanner.scanner;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

public class JSONSerializerDeserializer {

    /** Escape a string to be surrounded in double quotes in JSON. */
    private static void escapeJSONString(final String unsafeStr, final StringBuilder buf) {
        for (int i = 0, n = unsafeStr.length(); i < n; i++) {
            final char c = unsafeStr.charAt(i);
            // See http://www.json.org/ under "string"
            switch (c) {
            case '\\':
            case '"':
                // Forward slash can be escaped, but doesn't have to be.
                // Jackson doesn't escape it, and it makes URLs ugly.
                // case '/':
                buf.append('\\');
                buf.append(c);
                break;
            case '\b':
                buf.append("\\b");
                break;
            case '\t':
                buf.append("\\t");
                break;
            case '\n':
                buf.append("\\n");
                break;
            case '\f':
                buf.append("\\f");
                break;
            case '\r':
                buf.append("\\r");
                break;
            default:
                if (c < ' ') {
                    buf.append("\\u00");
                    final int d1 = (c) >> 4;
                    buf.append(d1 <= 9 ? (char) ('0' + d1) : (char) ('A' + d1 - 10));
                    final int d2 = (c) & 0xf;
                    buf.append(d2 <= 9 ? (char) ('0' + d2) : (char) ('A' + d2 - 10));
                } else {
                    buf.append(c);
                }
            }
        }
    }

    /** Return true for objects that can be converted directly to and from string representation. */
    private static boolean isBasicType(final Object obj) {
        return obj == null || obj instanceof Integer || obj instanceof Boolean || obj instanceof Long
                || obj instanceof Float || obj instanceof Double || obj instanceof Short || obj instanceof Byte
                || obj instanceof Character || obj instanceof String || obj.getClass().isEnum();
    }

    /** Return true for objects that are collections, arrays or Iterables. */
    private static boolean isCollectionOrArrayOrIterable(final Object obj) {
        final Class<? extends Object> cls = obj.getClass();
        return Collection.class.isAssignableFrom(cls) || cls.isArray() || Iterable.class.isAssignableFrom(cls);
    }

    /**
     * An object for wrapping a HashMap key so that the hashmap performs reference equality on the keys, not
     * equals() equality.
     */
    private static class ReferenceEqualityKey {
        Object wrappedKey;

        public ReferenceEqualityKey(final Object wrappedKey) {
            this.wrappedKey = wrappedKey;
        }

        @Override
        public int hashCode() {
            return wrappedKey.hashCode();
        }

        @Override
        public boolean equals(final Object other) {
            return other != null && other instanceof ReferenceEqualityKey
                    && wrappedKey == ((ReferenceEqualityKey) other).wrappedKey;
        }

        @Override
        public String toString() {
            return wrappedKey.toString();
        }
    }

    /**
     * For a list/array/Iterable item, map value, or field value, get an object to render into JSON, whether it be
     * the value itself, or an object id reference. If the object has not been seen before, and is not a basic type
     * (String, Integer, enum value, null, etc.), then assign it a new object id, with the exception that empty
     * collections, arrays and Iterables are not assigned a new id, and will never be displayed as an id reference
     * (so that e.g. Collections.emptyList<>() does not result in a singleton immutable object that is displayed as
     * a reference wherever it is used).
     */
    private static Object getSerializableObjectAndMaybeAssignId(final Object item,
            final Map<ReferenceEqualityKey, Integer> objToIdOut, final Set<Integer> refdObjIdsOut,
            final AtomicInteger objIdOut) {
        Object renderableObject;
        if (isBasicType(item) || isCollectionOrArrayOrIterable(item)) {
            // For items of basic type, or collections/arrays/Iterables, generate JSON directly.
            // This will mean that collections that are referenced in multiple places in the
            // object tree will be duplicated in the JSON, and will be duplicated when the
            // JSON is deserialized.
            renderableObject = item;
        } else {
            Integer itemObjId = objToIdOut.get(new ReferenceEqualityKey(item));
            if (itemObjId == null) {
                // For objects that have not been seen before, assign new object id, and render the item
                objToIdOut.put(new ReferenceEqualityKey(item), itemObjId = objIdOut.getAndIncrement());
                renderableObject = item;
            } else {
                // For objects that have been seen before and have already been assigned an object id,
                // use an id reference instead
                renderableObject = "_ID:" + itemObjId;
                refdObjIdsOut.add(itemObjId);
            }
        }
        return renderableObject;
    }

    private static void indent(final int depth, final int indentWidth, final StringBuilder buf) {
        for (int i = 0, n = depth * indentWidth; i < n; i++) {
            buf.append(' ');
        }
    }

    /**
     * Recursively render JSON, skipping fields marked with Private or PrivateGet annotations, and id fields of
     * DBModel objects. This produces a JSON rendering that may be served over a Web connection without exposing
     * internal server state. It's lighter-weight and faster than other introspection-based JSON renderers.
     */
    @SuppressWarnings({ "unchecked", "rawtypes" })
    private static void toJSONRec(final Object obj, final Set<ReferenceEqualityKey> visited,
            final Map<Class<?>, List<Field>> classToSerializableFields, final boolean includeNullFields,
            final Map<ReferenceEqualityKey, Integer> objToId, final Set<Integer> definedObjIds,
            final Set<Integer> refdObjIds, final AtomicInteger objId, final int depth, final int indentWidth,
            final StringBuilder buf) {
        if (obj == null) {
            buf.append("null");
            return;
        }
        final boolean prettyPrint = indentWidth > 0;

        final ReferenceEqualityKey objKeyWrapper = new ReferenceEqualityKey(obj);
        final boolean previouslyVisited = !visited.add(objKeyWrapper);

        if (previouslyVisited) {
            // Reached cycle in graph
            if (isCollectionOrArrayOrIterable(obj)) {
                // If we reached a collection that has already been visited, then there is a cycle
                // terminating in this collection. We don't support collections involving cycles,
                // since collections are not given object ids.
                throw new IllegalArgumentException(
                        "Cycles involving collections cannot be serialized, since collections are not "
                                + "assigned object ids in JSON");
            } else {
                // Object is its own ancestor -- output object id instead of object
                final Integer prevObjId = objToId.get(obj);
                if (prevObjId == null) {
                    // Should not happen
                    throw new IllegalArgumentException("prevObjId == null");
                }
                buf.append("\"_ID:" + prevObjId + "\"");
                return;
            }
        }

        try {
            final Class<? extends Object> cls = obj.getClass();
            if (cls == String.class) {
                buf.append('"');
                escapeJSONString((String) obj, buf);
                buf.append('"');

            } else if (cls == Integer.class || cls == Boolean.class || cls == Long.class || cls == Float.class
                    || cls == Double.class || cls == Short.class) {
                buf.append(obj.toString());

            } else if (cls == Byte.class) {
                buf.append(Integer.toString(((Byte) obj).intValue()));

            } else if (cls == Character.class) {
                buf.append('"');
                escapeJSONString(((Character) obj).toString(), buf);
                buf.append('"');

            } else if (Map.class.isAssignableFrom(cls)) {
                final Map<Object, Object> map = (Map<Object, Object>) obj;
                if (map.size() == 0) {
                    buf.append("{}");
                } else {
                    buf.append(prettyPrint ? "{\n" : "{");
                    final ArrayList<?> keys = new ArrayList<>(map.keySet());
                    final int n = keys.size();
                    final String[] serializableKeys = new String[n];
                    boolean keysSortedByComparableType = false;
                    Object firstNonNullKey = null;
                    for (int i = 0; i < n && firstNonNullKey == null; i++) {
                        firstNonNullKey = keys.get(i);
                    }
                    if (firstNonNullKey != null) {
                        if (Comparable.class.isAssignableFrom(firstNonNullKey.getClass())) {
                            Collections.sort((ArrayList<Comparable>) keys);
                            keysSortedByComparableType = true;
                        }
                    }

                    // Serialize keys into string form
                    for (int i = 0; i < n; i++) {
                        final Object key = keys.get(i);
                        if (!isBasicType(key)) {
                            throw new IllegalArgumentException("Map key of type " + key.getClass().getName()
                                    + " is not a basic type (String, Integer, etc.), so can't be easily "
                                    + "rendered as a JSON associative array key");
                        }
                        final String keyStr = key.toString();
                        final StringBuilder keyBuf = new StringBuilder();
                        escapeJSONString(keyStr, keyBuf);
                        serializableKeys[i] = keyBuf.toString();
                    }
                    if (!keysSortedByComparableType) {
                        // Sort strings lexicographically, if non-string ordering is not Comparable
                        Arrays.sort(serializableKeys);
                    }

                    // Replace vals vith object id references if vals already have object ids
                    // (do this in a separate pass, before recursing on each value, so that all
                    // values are assigned values breadth-first)
                    final Object[] serializableVals = new Object[n];
                    for (int i = 0; i < n; i++) {
                        final Object val = map.get(keys.get(i));
                        serializableVals[i] = getSerializableObjectAndMaybeAssignId(val, objToId, refdObjIds,
                                objId);
                    }

                    // Render map values
                    for (int i = 0; i < n; i++) {
                        final String keyStr = serializableKeys[i];
                        final Object val = serializableVals[i];

                        if (prettyPrint) {
                            indent(depth + 1, indentWidth, buf);
                        }

                        buf.append('"');
                        buf.append(keyStr);
                        buf.append(prettyPrint ? "\": " : "\":");

                        // Recursively render value
                        toJSONRec(val, visited, classToSerializableFields, includeNullFields, objToId,
                                definedObjIds, refdObjIds, objId, depth + 1, indentWidth, buf);
                        if (i < n - 1) {
                            buf.append(prettyPrint ? ",\n" : ",");
                        } else if (prettyPrint) {
                            buf.append('\n');
                        }
                    }
                    if (prettyPrint) {
                        indent(depth, indentWidth, buf);
                    }
                    buf.append('}');
                }

            } else if (Iterable.class.isAssignableFrom(cls)) {
                // Render an Iterable (e.g. a Set)
                final Iterable<?> iterable = (Iterable<?>) obj;

                // Assign all elements in the Iterable new ids
                // (do this in a separate pass, before recursing on each item, so that all
                // items are assigned values breadth-first)
                final List<Object> serializableItems = new ArrayList<>();
                for (final Object item : iterable) {
                    final Object serializableItem = getSerializableObjectAndMaybeAssignId(item, objToId, refdObjIds,
                            objId);
                    serializableItems.add(serializableItem);
                }
                final int n = serializableItems.size();

                if (n == 0) {
                    buf.append("[]");
                } else {
                    buf.append('[');
                    if (prettyPrint) {
                        buf.append('\n');
                    }
                    for (int i = 0; i < n; i++) {
                        if (prettyPrint) {
                            indent(depth + 1, indentWidth, buf);
                        }
                        // Recursively render value
                        final Object item = serializableItems.get(i);
                        toJSONRec(item, visited, classToSerializableFields, includeNullFields, objToId,
                                definedObjIds, refdObjIds, objId, depth + 1, indentWidth, buf);
                        if (i < n - 1) {
                            buf.append(",");
                        }
                        if (prettyPrint) {
                            buf.append('\n');
                        }
                    }
                    if (prettyPrint) {
                        indent(depth, indentWidth, buf);
                    }
                    buf.append(']');
                }

            } else if (cls.isArray() || List.class.isAssignableFrom(cls)) {
                // Render an array or list
                final boolean isList = List.class.isAssignableFrom(cls);
                final List<?> list = isList ? (List<?>) obj : null;
                final int n = isList ? list.size() : Array.getLength(obj);
                if (n == 0) {
                    buf.append("[]");
                } else {
                    // Replace list items with object id references if items already have object ids 
                    // (do this in a separate pass, before recursing on each item, so that all
                    // items are assigned values breadth-first)
                    final Object[] serializableItems = new Object[n];
                    for (int i = 0; i < n; i++) {
                        final Object item = isList ? list.get(i) : Array.get(obj, i);
                        final Object serializableItem = getSerializableObjectAndMaybeAssignId(item, objToId,
                                refdObjIds, objId);
                        serializableItems[i] = serializableItem;
                    }
                    buf.append('[');
                    if (prettyPrint) {
                        buf.append('\n');
                    }
                    for (int i = 0; i < n; i++) {
                        if (prettyPrint) {
                            indent(depth + 1, indentWidth, buf);
                        }
                        // Recursively render item
                        final Object item = serializableItems[i];
                        toJSONRec(item, visited, classToSerializableFields, includeNullFields, objToId,
                                definedObjIds, refdObjIds, objId, depth + 2, indentWidth, buf);
                        if (i < n - 1) {
                            buf.append(",");
                        }
                        if (prettyPrint) {
                            buf.append('\n');
                        }
                    }
                    if (prettyPrint) {
                        indent(depth, indentWidth, buf);
                    }
                    buf.append(']');
                }

            } else {
                // Some other class -- render fields as a JSON associative array using introspection.

                // Look up class fields
                List<Field> serializableFields = classToSerializableFields.get(cls);
                if (serializableFields == null) {
                    // Cache fieldsToInclude
                    classToSerializableFields.put(cls, serializableFields = new ArrayList<>());
                    final Field[] fields = cls.getDeclaredFields();
                    for (int i = 0; i < fields.length; i++) {
                        final Field field = fields[i];
                        // Don't serialize transient or final fields
                        final int modifiers = field.getModifiers();
                        if (!Modifier.isTransient(modifiers) && !Modifier.isFinal(modifiers)
                                && ((modifiers & 0x1000 /* synthetic */) == 0)) {
                            field.setAccessible(true);
                            final Object fieldVal = fields[i].get(obj);
                            if (fieldVal != null || includeNullFields) {
                                serializableFields.add(field);
                            }
                        }
                    }
                }
                final int n = serializableFields.size();

                // Assign all elements in field values new ids
                // (do this in a separate pass, before recursing on each field value, so that all
                // field values are assigned values breadth-first)
                final Object[] serializableFieldVals = new Object[n];
                for (int i = 0; i < n; i++) {
                    final Field field = serializableFields.get(i);
                    final Object fieldVal = field.get(obj);
                    serializableFieldVals[i] = getSerializableObjectAndMaybeAssignId(fieldVal, objToId, refdObjIds,
                            objId);
                }

                // Add pseudo field that defines the object id
                final Integer thisObjId = objToId.get(new ReferenceEqualityKey(obj));
                if (thisObjId == null) {
                    // Should not happen -- object ids should have been added by caller
                    throw new IllegalArgumentException("object id could not be found");
                }
                definedObjIds.add(thisObjId);

                buf.append(prettyPrint ? "{\n" : "{");

                if (prettyPrint) {
                    indent(depth + 1, indentWidth, buf);
                }
                buf.append(prettyPrint ? "\"_ID\": " : "\"_ID\":");
                buf.append(thisObjId);
                buf.append(prettyPrint ? ",\n" : ",");

                if (prettyPrint) {
                    indent(depth + 1, indentWidth, buf);
                }
                buf.append(prettyPrint ? "\"_TYPE\": " : "\"_TYPE\":");
                buf.append("\"" + obj.getClass().getName() + "\"");
                if (n > 0) {
                    buf.append(",");
                }
                if (prettyPrint) {
                    buf.append('\n');
                }

                for (int i = 0; i < n; i++) {
                    final Field field = serializableFields.get(i);
                    final Object fieldVal = serializableFieldVals[i];
                    if (prettyPrint) {
                        indent(depth + 1, indentWidth, buf);
                    }
                    buf.append('"');
                    // Render field name as key
                    escapeJSONString(field.getName(), buf);
                    buf.append(prettyPrint ? "\": " : "\":");

                    // Render value
                    // Turn primitive types into strings, they have their own getter methods
                    final Class<?> fieldType = field.getType();
                    if (fieldType == Integer.TYPE) {
                        buf.append(Integer.toString(field.getInt(obj)));
                    } else if (fieldType == Boolean.TYPE) {
                        buf.append(Boolean.toString(field.getBoolean(obj)));
                    } else if (fieldType == Long.TYPE) {
                        buf.append(Long.toString(field.getLong(obj)));
                    } else if (fieldType == Float.TYPE) {
                        buf.append(Float.toString(field.getFloat(obj)));
                    } else if (fieldType == Double.TYPE) {
                        buf.append(Double.toString(field.getDouble(obj)));
                    } else if (fieldType == Short.TYPE) {
                        buf.append(Short.toString(field.getShort(obj)));
                    } else if (fieldType == Byte.TYPE) {
                        buf.append(Integer.toString(field.getByte(obj)));
                    } else if (fieldType == Character.TYPE) {
                        buf.append('"');
                        escapeJSONString(Character.toString(field.getChar(obj)), buf);
                        buf.append('"');
                    } else {
                        // Not a primitive type; recursively render value
                        toJSONRec(fieldVal, visited, classToSerializableFields, includeNullFields, objToId,
                                definedObjIds, refdObjIds, objId, depth + 1, indentWidth, buf);
                    }
                    if (i < n - 1) {
                        buf.append(',');
                    }
                    if (prettyPrint) {
                        buf.append("\n");
                    }
                }
                if (prettyPrint) {
                    indent(depth, indentWidth, buf);
                }
                buf.append('}');
            }
        } catch (IllegalArgumentException | IllegalAccessException e) {
            throw new RuntimeException("Could not render object into JSON", e);
        }

        // In the case of a DAG, just serialize the same object multiple times, i.e. remove obj
        // from visited set when exiting recursion, so that future instances also get serialized.
        visited.remove(objKeyWrapper);
    }

    /**
     * Recursively render an Object (or array, list, map or set of objects) as JSON, skipping transient and final
     * fields. If indentWidth == 0, no prettyprinting indentation is performed.
     */
    public static String toJSON(final Object obj, final int indentWidth) {
        final StringBuilder buf = new StringBuilder(32768);

        final HashSet<Integer> definedObjIds = new HashSet<>();
        final HashSet<Integer> refdObjIds = new HashSet<>();
        final HashMap<ReferenceEqualityKey, Integer> objToId = new HashMap<>();
        final AtomicInteger objId = new AtomicInteger(0);
        if (!isCollectionOrArrayOrIterable(obj)) {
            // Collections aren't assigned object ids
            objToId.put(new ReferenceEqualityKey(obj), objId.getAndIncrement());
        }

        toJSONRec(obj, new HashSet<>(), new HashMap<Class<?>, List<Field>>(), false, objToId, definedObjIds,
                refdObjIds, objId, 0, indentWidth, buf);

        refdObjIds.removeAll(definedObjIds);
        if (!refdObjIds.isEmpty()) {
            throw new IllegalArgumentException("Object graph contains cyclic dependencies between collections "
                    + "(collection objects are not labeled with their object ID in the outpt JSON, so circular "
                    + "dependencies between them cannot be represented in JSON)");
        }
        return buf.toString();
    }

    /**
     * Recursively render an Object (or array, list, map or set of objects) as JSON, skipping transient and final
     * fields.
     */
    public static String toJSON(final Object obj) {
        return toJSON(obj, /* indentWidth = */ 0);
    }
}
