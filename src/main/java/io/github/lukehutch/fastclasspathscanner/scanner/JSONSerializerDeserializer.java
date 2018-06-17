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

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.AbstractMap.SimpleEntry;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

public class JSONSerializerDeserializer {
    /**
     * Annotate a class field with this annotation to use that field's value instead of the automatically-generated
     * id for object references in JSON output. The field value must be a unique identifier across the whole object
     * graph.
     */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.FIELD)
    public static @interface Id {
    }

    private static final String ID_TAG = "_ID";
    private static final String ID_PREFIX = "[ID#";
    private static final String ID_SUFFIX = "]";

    /**
     * A class that serves as a placeholder for circular references between objects. Points to the original object,
     * not the converted JSON object, due to late binding.
     */
    private static class JSONReference {
        Object referencedObject;

        public JSONReference(final Object referencedObject) {
            this.referencedObject = referencedObject;
        }
    }

    /** Create a unique id for each referenced JSON object. */
    private static void assignObjectIds(final Object jsonVal,
            final Map<ReferenceEqualityKey, JSONObject> objToJSONVal,
            final Map<ReferenceEqualityKey, Field> objToIdKeyField,
            final Map<ReferenceEqualityKey, Object> refdJsonValToId,
            final Map<ReferenceEqualityKey, Object> jsonReferenceToId, final AtomicInteger objId) {
        if (jsonVal == null) {
            return;
        } else if (jsonVal instanceof JSONObject) {
            for (final Entry<String, Object> item : ((JSONObject) jsonVal).items) {
                assignObjectIds(item.getValue(), objToJSONVal, objToIdKeyField, refdJsonValToId, jsonReferenceToId,
                        objId);
            }
        } else if (jsonVal instanceof JSONArray) {
            for (final Object item : ((JSONArray) jsonVal).items) {
                assignObjectIds(item, objToJSONVal, objToIdKeyField, refdJsonValToId, jsonReferenceToId, objId);
            }
        } else if (jsonVal instanceof JSONReference) {
            // Get the referenced (non-JSON) object
            final Object referencedObj = ((JSONReference) jsonVal).referencedObject;
            // Look up the JSON object corresponding to the referenced object
            final ReferenceEqualityKey refdObjKey = new ReferenceEqualityKey(referencedObj);
            final JSONObject refdJsonVal = objToJSONVal.get(refdObjKey);
            if (refdJsonVal == null) {
                // Should not happen
                throw new RuntimeException("Internal inconsistency");
            }
            // See if the JSON object has an @Id field
            Field annotatedField = null;
            if (!objToIdKeyField.containsKey(refdObjKey)) {
                for (final Field field : referencedObj.getClass().getDeclaredFields()) {
                    if (field.isAnnotationPresent(Id.class)) {
                        final int modifiers = field.getModifiers();
                        if (Modifier.isTransient(modifiers) || Modifier.isFinal(modifiers)
                                || ((modifiers & 0x1000 /* synthetic */) != 0)) {
                            throw new IllegalArgumentException("@Id-annotated field in class "
                                    + referencedObj.getClass() + " cannot be transient, final or synthetic");
                        }
                        annotatedField = field;
                        break;
                    }
                }
                // Put the annotated field back in the map, or put null if no @Id field was found
                // (to prevent this work being done again)
                objToIdKeyField.put(refdObjKey, annotatedField);
            } else {
                annotatedField = objToIdKeyField.get(refdObjKey);
            }
            Object idObj = null;
            if (annotatedField != null) {
                // Get id value from field annotated with @Id
                try {
                    idObj = annotatedField.get(referencedObj);
                } catch (IllegalArgumentException | IllegalAccessException e) {
                    // Should not happen
                    throw new RuntimeException(e);
                }
            }
            if (idObj == null) {
                // No @Id field, or field value is null -- check if ref'd JSON Object already has an id
                final ReferenceEqualityKey refdJsonValKey = new ReferenceEqualityKey(refdJsonVal);
                idObj = refdJsonValToId.get(refdJsonValKey);
                if (idObj == null) {
                    // Ref'd JSON object doesn't have an id yet -- generate unique integer id
                    idObj = ID_PREFIX + objId.getAndIncrement() + ID_SUFFIX;
                    // Label the referenced node with its new unique id
                    refdJsonValToId.put(refdJsonValKey, idObj);
                }
            }
            // Link both the referenced object and its JSON representation to the id
            jsonReferenceToId.put(new ReferenceEqualityKey(jsonVal), idObj);
        }
    }

    /** Serialize a JSON object, array, or value. */
    private static void jsonValToJSONString(final Object jsonVal,
            final Map<ReferenceEqualityKey, Object> refdJsonValToId,
            final Map<ReferenceEqualityKey, Object> jsonReferenceToId, final boolean includeNullValuedFields,
            final int depth, final int indentWidth, final StringBuilder buf) {
        if (jsonVal == null) {
            buf.append("null");
        } else if (jsonVal instanceof JSONObject) {
            ((JSONObject) jsonVal).toString(refdJsonValToId, jsonReferenceToId, includeNullValuedFields, depth,
                    indentWidth, buf);
        } else if (jsonVal instanceof JSONArray) {
            ((JSONArray) jsonVal).toString(refdJsonValToId, jsonReferenceToId, includeNullValuedFields, depth,
                    indentWidth, buf);
        } else if (jsonVal instanceof JSONReference) {
            final Object referencedObjectId = jsonReferenceToId.get(new ReferenceEqualityKey(jsonVal));
            jsonValToJSONString(referencedObjectId, refdJsonValToId, jsonReferenceToId, includeNullValuedFields,
                    depth, indentWidth, buf);
        } else if (jsonVal instanceof String || jsonVal instanceof Character || jsonVal instanceof JSONReference
                || jsonVal.getClass().isEnum()) {
            buf.append('"');
            escapeJSONString(jsonVal.toString(), buf);
            buf.append('"');
        } else {
            // Integer, Long, Short, Float, Double, Boolean, Byte (doesn't need escaping)
            buf.append(jsonVal.toString());
        }
    }

    private static void indent(final int depth, final int indentWidth, final StringBuilder buf) {
        for (int i = 0, n = depth * indentWidth; i < n; i++) {
            buf.append(' ');
        }
    }

    private static class JSONObject {
        /** Key/value mappings, in display order. */
        List<Entry<String, Object>> items;

        public JSONObject(final List<Entry<String, Object>> items) {
            this.items = items;
        }

        /** Serialize this JSONObject to a string. */
        void toString(final Map<ReferenceEqualityKey, Object> refdJsonValToId,
                final Map<ReferenceEqualityKey, Object> jsonReferenceToId, final boolean includeNullValuedFields,
                final int depth, final int indentWidth, final StringBuilder buf) {
            final boolean prettyPrint = indentWidth > 0;
            final int n = items.size();
            int numDisplayedFields;
            if (includeNullValuedFields) {
                numDisplayedFields = n;
            } else {
                numDisplayedFields = 0;
                for (int i = 0; i < n; i++) {
                    if (items.get(i).getValue() != null) {
                        numDisplayedFields++;
                    }
                }
            }
            final ReferenceEqualityKey thisKey = new ReferenceEqualityKey(this);
            // id will be non-null if this object does not have an @Id field, but was referenced by another object
            // (need to include ID_TAG)
            final Object id = refdJsonValToId.get(thisKey);
            if (id == null && numDisplayedFields == 0) {
                buf.append("{}");
            } else {
                buf.append(prettyPrint ? "{\n" : "{");
                if (id != null) {
                    if (prettyPrint) {
                        indent(depth + 1, indentWidth, buf);
                    }
                    buf.append('"');
                    buf.append(ID_TAG);
                    buf.append(prettyPrint ? "\": " : "\":");
                    jsonValToJSONString(id, refdJsonValToId, jsonReferenceToId, includeNullValuedFields, depth + 1,
                            indentWidth, buf);
                    if (numDisplayedFields > 0) {
                        buf.append(',');
                    }
                    if (prettyPrint) {
                        buf.append('\n');
                    }
                }
                for (int i = 0, j = 0; i < n; i++) {
                    final Entry<String, Object> item = items.get(i);
                    final Object val = item.getValue();
                    if (val != null || includeNullValuedFields) {
                        final String key = item.getKey();
                        if (prettyPrint) {
                            indent(depth + 1, indentWidth, buf);
                        }
                        buf.append('"');
                        escapeJSONString(key, buf);
                        buf.append(prettyPrint ? "\": " : "\":");
                        jsonValToJSONString(val, refdJsonValToId, jsonReferenceToId, includeNullValuedFields,
                                depth + 1, indentWidth, buf);
                        if (++j < numDisplayedFields) {
                            buf.append(',');
                        }
                        if (prettyPrint) {
                            buf.append('\n');
                        }
                    }
                }
                if (prettyPrint) {
                    indent(depth, indentWidth, buf);
                }
                buf.append('}');
            }
        }
    }

    private static class JSONArray {
        /** Array items. */
        List<Object> items;

        public JSONArray(final List<Object> items) {
            this.items = items;
        }

        /** Serialize this JSONArray to a string. */
        void toString(final Map<ReferenceEqualityKey, Object> refdJsonValToId,
                final Map<ReferenceEqualityKey, Object> jsonReferenceToId, final boolean includeNullValuedFields,
                final int depth, final int indentWidth, final StringBuilder buf) {
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
                        indent(depth + 1, indentWidth, buf);
                    }
                    jsonValToJSONString(item, refdJsonValToId, jsonReferenceToId, includeNullValuedFields,
                            depth + 1, indentWidth, buf);
                    if (i < n - 1) {
                        buf.append(',');
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
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    // See http://www.json.org/ under "string"
    private static final String[] JSON_CHAR_REPLACEMENTS = new String[256];
    static {
        for (int i = 0; i < 256; i++) {
            if (i == 32) {
                i = 127;
            }
            final int d1 = i >> 4;
            final char c1 = d1 <= 9 ? (char) ('0' + d1) : (char) ('A' + d1 - 10);
            final int d0 = i & 0xf;
            final char c0 = d0 <= 9 ? (char) ('0' + d0) : (char) ('A' + d0 - 10);
            JSON_CHAR_REPLACEMENTS[i] = "\\u00" + c1 + c0;
        }
        JSON_CHAR_REPLACEMENTS['"'] = "\\\"";
        JSON_CHAR_REPLACEMENTS['\\'] = "\\\\";
        JSON_CHAR_REPLACEMENTS['\n'] = "\\n";
        JSON_CHAR_REPLACEMENTS['\r'] = "\\r";
        JSON_CHAR_REPLACEMENTS['\t'] = "\\t";
        JSON_CHAR_REPLACEMENTS['\b'] = "\\b";
        JSON_CHAR_REPLACEMENTS['\f'] = "\\f";
    }

    /** Escape a string to be surrounded in double quotes in JSON. */
    private static void escapeJSONString(final String unsafeStr, final StringBuilder buf) {
        if (unsafeStr == null) {
            return;
        }
        // Fast path
        boolean needsEscaping = false;
        for (int i = 0, n = unsafeStr.length(); i < n; i++) {
            final char c = unsafeStr.charAt(i);
            if (JSON_CHAR_REPLACEMENTS[c] != null) {
                needsEscaping = true;
                break;
            }
        }
        if (!needsEscaping) {
            buf.append(unsafeStr);
            return;
        }
        // Slow path
        for (int i = 0, n = unsafeStr.length(); i < n; i++) {
            final char c = unsafeStr.charAt(i);
            final String replacement = JSON_CHAR_REPLACEMENTS[c];
            if (replacement == null) {
                buf.append(c);
            } else {
                buf.append(replacement);
            }
        }
    }

    /** Escape a string to be surrounded in double quotes in JSON. */
    private static String escapeJSONString(final String unsafeStr) {
        if (unsafeStr == null) {
            return unsafeStr;
        }
        // Fast path
        boolean needsEscaping = false;
        for (int i = 0, n = unsafeStr.length(); i < n; i++) {
            final char c = unsafeStr.charAt(i);
            if (JSON_CHAR_REPLACEMENTS[c] != null) {
                needsEscaping = true;
                break;
            }
        }
        if (!needsEscaping) {
            return unsafeStr;
        }
        // Slow path
        final StringBuilder buf = new StringBuilder(unsafeStr.length() * 2);
        for (int i = 0, n = unsafeStr.length(); i < n; i++) {
            final char c = unsafeStr.charAt(i);
            final String replacement = JSON_CHAR_REPLACEMENTS[c];
            if (replacement == null) {
                buf.append(c);
            } else {
                buf.append(replacement);
            }
        }
        return buf.toString();
    }

    // -------------------------------------------------------------------------------------------------------------

    /** Return true for objects that can be converted directly to and from string representation. */
    private static boolean isBasicValueType(final Object obj) {
        return obj == null || obj instanceof Integer || obj instanceof Boolean || obj instanceof Long
                || obj instanceof Float || obj instanceof Double || obj instanceof Short || obj instanceof Byte
                || obj instanceof Character || obj instanceof String || obj.getClass().isEnum();
    }

    /** Return true for objects that are collections or arrays. */
    private static boolean isCollectionOrArray(final Object obj) {
        final Class<? extends Object> cls = obj.getClass();
        return Collection.class.isAssignableFrom(cls) || cls.isArray();
    }

    /** Return true for objects that are collections, arrays, or Maps. */
    private static boolean isCollectionOrArrayOrMap(final Object obj) {
        final Class<? extends Object> cls = obj.getClass();
        return Collection.class.isAssignableFrom(cls) || cls.isArray() || Map.class.isAssignableFrom(cls);
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

    /** Take an array of object values, and recursively convert them (in place) into JSON values. */
    private static void convertVals(final Object[] convertedVals, final Set<ReferenceEqualityKey> visitedOnPath,
            final Set<ReferenceEqualityKey> standardObjectVisited,
            final Map<Class<?>, List<Field>> classToSerializableFields,
            final Map<ReferenceEqualityKey, JSONObject> objToJSONVal) {
        // Pass 1: find standard objects (objects that are not of basic value type or collections/arrays/maps)
        // that have not yet been visited, and mark them as visited. Place a JSONReference placeholder in
        // convertedVals[i] to signify this. This first pass is non-recursive, so that objects are visited
        // as high up the tree as possible, since it is only the first visit of an object that shows in the
        // final JSON doc, and the rest are turned into references.
        final ReferenceEqualityKey[] valKeys = new ReferenceEqualityKey[convertedVals.length];
        final boolean[] needToConvert = new boolean[convertedVals.length];
        for (int i = 0; i < convertedVals.length; i++) {
            final Object val = convertedVals[i];
            // By default (for basic value types), don't convert vals
            needToConvert[i] = !isBasicValueType(val);
            if (needToConvert[i] && !isCollectionOrArrayOrMap(val)) {
                // If this object is a standard object, check if it has already been visited
                // elsewhere in the tree. If so, use a JSONReference instead, and mark the object
                // as visited.
                final ReferenceEqualityKey valKey = new ReferenceEqualityKey(val);
                valKeys[i] = valKey;
                final boolean alreadyVisited = !standardObjectVisited.add(valKey);
                if (alreadyVisited) {
                    convertedVals[i] = new JSONReference(val);
                    needToConvert[i] = false;
                }
            }
        }
        // Pass 2: convert standard objects, maps, collections and arrays to JSON objects.
        for (int i = 0; i < convertedVals.length; i++) {
            if (needToConvert[i]) {
                // Recursively convert standard objects (if it is the first time they have been visited)
                // and maps to JSON objects, and convert collections and arrays to JSON arrays.
                final Object val = convertedVals[i];
                convertedVals[i] = toJSONGraph(val, visitedOnPath, standardObjectVisited, classToSerializableFields,
                        objToJSONVal);
                if (!isCollectionOrArrayOrMap(val)) {
                    // If this object is a standard object, then it has not been visited before, 
                    // so save the mapping between original object and converted object
                    objToJSONVal.put(valKeys[i], (JSONObject) convertedVals[i]);
                }
            }
        }
    }

    /**
     * Turn an object graph into a graph of JSON objects, arrays, and values.
     */
    @SuppressWarnings({ "unchecked", "rawtypes" })
    private static Object toJSONGraph(final Object obj, final Set<ReferenceEqualityKey> visitedOnPath,
            final Set<ReferenceEqualityKey> standardObjectVisited,
            final Map<Class<?>, List<Field>> classToSerializableFields,
            final Map<ReferenceEqualityKey, JSONObject> objToJSONVal) {

        // For null and basic value types, just return value
        if (obj == null || obj instanceof String || obj instanceof Integer || obj instanceof Boolean
                || obj instanceof Long || obj instanceof Float || obj instanceof Double || obj instanceof Short
                || obj instanceof Byte || obj instanceof Character || obj.getClass().isEnum()) {
            return obj;
        }

        // Check for cycles
        final ReferenceEqualityKey objKey = new ReferenceEqualityKey(obj);
        if (!visitedOnPath.add(objKey)) {
            // Reached cycle in graph
            if (isCollectionOrArray(obj)) {
                // If we reached a collection that has already been visited, then there is a cycle
                // terminating at this collection. We don't support collection cycles, since collections
                // do not have object ids.
                throw new IllegalArgumentException(
                        "Cycles involving collections cannot be serialized, since collections are not "
                                + "assigned object ids");
            } else {
                // Object is its own ancestor -- output object reference instead of object to break cycle
                return new JSONReference(obj);
            }
        }

        Object jsonVal;
        final Class<? extends Object> cls = obj.getClass();

        if (Map.class.isAssignableFrom(cls)) {
            final Map<Object, Object> map = (Map<Object, Object>) obj;

            // Get map keys, and sort them by value if values are Comparable.
            // Assumes all keys have the same type (or at least that if one key is Comparable, they all are).
            final ArrayList<?> keys = new ArrayList<>(map.keySet());
            final int n = keys.size();
            boolean keysComparable = false;
            Object firstNonNullKey = null;
            for (int i = 0; i < n && firstNonNullKey == null; i++) {
                firstNonNullKey = keys.get(i);
            }
            if (firstNonNullKey != null) {
                if (Comparable.class.isAssignableFrom(firstNonNullKey.getClass())) {
                    Collections.sort((ArrayList<Comparable>) keys);
                    keysComparable = true;
                }
            }

            // Serialize keys into string form
            final String[] convertedKeys = new String[n];
            for (int i = 0; i < n; i++) {
                final Object key = keys.get(i);
                if (!isBasicValueType(key)) {
                    throw new IllegalArgumentException("Map key of type " + key.getClass().getName()
                            + " is not a basic type (String, Integer, etc.), so can't be easily "
                            + "serialized as a JSON associative array key");
                }
                convertedKeys[i] = escapeJSONString(key.toString());
            }

            // Sort value strings lexicographically if values are not Comparable
            if (!keysComparable) {
                Arrays.sort(convertedKeys);
            }

            // Convert map values to JSON values
            final Object[] convertedVals = new Object[n];
            for (int i = 0; i < n; i++) {
                convertedVals[i] = map.get(keys.get(i));
            }
            convertVals(convertedVals, visitedOnPath, standardObjectVisited, classToSerializableFields,
                    objToJSONVal);

            // Create new JSON object representing the map
            final List<Entry<String, Object>> convertedKeyValPairs = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                convertedKeyValPairs.add(new SimpleEntry<>(convertedKeys[i], convertedVals[i]));
            }
            jsonVal = new JSONObject(convertedKeyValPairs);

        } else if (cls.isArray() || List.class.isAssignableFrom(cls)) {
            // Serialize an array or list
            final boolean isList = List.class.isAssignableFrom(cls);
            final List<?> list = isList ? (List<?>) obj : null;
            final int n = isList ? list.size() : Array.getLength(obj);

            // Convert list items to JSON values
            final Object[] convertedVals = new Object[n];
            for (int i = 0; i < n; i++) {
                convertedVals[i] = isList ? list.get(i) : Array.get(obj, i);
            }
            convertVals(convertedVals, visitedOnPath, standardObjectVisited, classToSerializableFields,
                    objToJSONVal);

            // Create new JSON array representing the list
            jsonVal = new JSONArray(Arrays.asList(convertedVals));

        } else if (Collection.class.isAssignableFrom(cls)) {
            final Collection<?> collection = (Collection<?>) obj;

            // Convert items to JSON values
            final List<Object> convertedValsList = new ArrayList<>();
            for (final Object item : collection) {
                convertedValsList.add(item);
            }
            final Object[] convertedVals = convertedValsList.toArray();
            convertVals(convertedVals, visitedOnPath, standardObjectVisited, classToSerializableFields,
                    objToJSONVal);

            // Create new JSON array representing the collection
            jsonVal = new JSONArray(Arrays.asList(convertedVals));

        } else {
            // A standard object -- serialize fields as a JSON associative array.
            try {
                // Cache class fields to include in serialization
                List<Field> serializableFields = classToSerializableFields.get(cls);
                if (serializableFields == null) {
                    classToSerializableFields.put(cls, serializableFields = new ArrayList<>());
                    final Field[] fields = cls.getDeclaredFields();
                    for (int i = 0; i < fields.length; i++) {
                        final Field field = fields[i];
                        // Don't serialize transient, final or synthetic fields
                        final int modifiers = field.getModifiers();
                        if (!Modifier.isTransient(modifiers) && !Modifier.isFinal(modifiers)
                                && ((modifiers & 0x1000 /* synthetic */) == 0)) {
                            field.setAccessible(true);
                            serializableFields.add(field);
                        }
                    }
                }
                final int n = serializableFields.size();

                // Convert field values to JSON values
                final String[] fieldNames = new String[n];
                final Object[] convertedVals = new Object[n];
                for (int i = 0; i < n; i++) {
                    final Field field = serializableFields.get(i);
                    fieldNames[i] = field.getName();
                    convertedVals[i] = field.get(obj);
                }
                convertVals(convertedVals, visitedOnPath, standardObjectVisited, classToSerializableFields,
                        objToJSONVal);

                // Create new JSON object representing the standard object
                final List<Entry<String, Object>> convertedKeyValPairs = new ArrayList<>(n);
                for (int i = 0; i < n; i++) {
                    convertedKeyValPairs.add(new SimpleEntry(fieldNames[i], convertedVals[i]));
                }
                jsonVal = new JSONObject(convertedKeyValPairs);

            } catch (IllegalArgumentException | IllegalAccessException e) {
                throw new RuntimeException("Could not serialize object into JSON", e);
            }
        }

        // In the case of a DAG, just serialize the same object multiple times, i.e. remove obj
        // from visited set when exiting recursion, so that future instances also get serialized.
        visitedOnPath.remove(objKey);

        return jsonVal;
    }

    /**
     * Recursively serialize an Object (or array, list, map or set of objects) to JSON, skipping transient and final
     * fields. If indentWidth == 0, no prettyprinting indentation is performed.
     */
    public static String toJSON(final Object obj, final int indentWidth) {
        final Set<ReferenceEqualityKey> visitedOnPath = new HashSet<>();
        final Set<ReferenceEqualityKey> standardObjectVisited = new HashSet<>();
        final Map<Class<?>, List<Field>> classToSerializableFields = new HashMap<>();
        final HashMap<ReferenceEqualityKey, JSONObject> objToJSONVal = new HashMap<>();

        final Object rootJsonVal = toJSONGraph(obj, visitedOnPath, standardObjectVisited, classToSerializableFields,
                objToJSONVal);

        final Map<ReferenceEqualityKey, Field> objToIdKeyField = new HashMap<>();
        final Map<ReferenceEqualityKey, Object> refdJsonValToId = new HashMap<>();
        final Map<ReferenceEqualityKey, Object> jsonReferenceToId = new HashMap<>();
        final AtomicInteger objId = new AtomicInteger(0);
        assignObjectIds(rootJsonVal, objToJSONVal, objToIdKeyField, refdJsonValToId, jsonReferenceToId, objId);

        final StringBuilder buf = new StringBuilder(32768);
        jsonValToJSONString(rootJsonVal, refdJsonValToId, jsonReferenceToId, /* includeNullValuedFields = */ false,
                0, indentWidth, buf);
        return buf.toString();
    }

    /**
     * Recursively serialize an Object (or array, list, map or set of objects) to JSON, skipping transient and final
     * fields.
     */
    public static String toJSON(final Object obj) {
        return toJSON(obj, /* indentWidth = */ 0);
    }
}
