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

import java.lang.reflect.Array;
import java.lang.reflect.Field;
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

/**
 * Fast, lightweight Java object to JSON serializer, and JSON to Java object deserializer. Handles cycles in the
 * object graph by inserting reference ids.
 */
class JSONSerializer {
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
            final Map<ReferenceEqualityKey<Object>, JSONObject> objToJSONVal, final TypeCache typeCache,
            final Map<ReferenceEqualityKey<JSONObject>, Object> refdJsonObjToId,
            final Map<ReferenceEqualityKey<JSONReference>, Object> jsonReferenceToId, final AtomicInteger objId,
            final boolean onlySerializePublicFields) {
        if (jsonVal == null) {
            return;
        } else if (jsonVal instanceof JSONObject) {
            for (final Entry<String, Object> item : ((JSONObject) jsonVal).items) {
                assignObjectIds(item.getValue(), objToJSONVal, typeCache, refdJsonObjToId, jsonReferenceToId, objId,
                        onlySerializePublicFields);
            }
        } else if (jsonVal instanceof JSONArray) {
            for (final Object item : ((JSONArray) jsonVal).items) {
                assignObjectIds(item, objToJSONVal, typeCache, refdJsonObjToId, jsonReferenceToId, objId,
                        onlySerializePublicFields);
            }
        } else if (jsonVal instanceof JSONReference) {
            // Get the referenced (non-JSON) object
            final Object refdObj = ((JSONReference) jsonVal).referencedObject;
            if (refdObj == null) {
                // Should not happen
                throw new RuntimeException("Internal inconsistency");
            }
            // Look up the JSON object corresponding to the referenced object
            final ReferenceEqualityKey<Object> refdObjKey = new ReferenceEqualityKey<>(refdObj);
            final JSONObject refdJsonVal = objToJSONVal.get(refdObjKey);
            if (refdJsonVal == null) {
                // Should not happen
                throw new RuntimeException("Internal inconsistency");
            }
            // See if the JSON object has an @Id field
            // (for serialization, typeResolutions can be null)
            final Field annotatedField = typeCache.getResolvedFields(refdObj.getClass(),
                    /* typeResolutions = */ null, onlySerializePublicFields).idField;
            Object idObj = null;
            if (annotatedField != null) {
                // Get id value from field annotated with @Id
                try {
                    idObj = annotatedField.get(refdObj);
                } catch (IllegalArgumentException | IllegalAccessException e) {
                    // Should not happen
                    throw new RuntimeException(e);
                }
            }
            if (idObj == null) {
                // No @Id field, or field value is null -- check if ref'd JSON Object already has an id
                final ReferenceEqualityKey<JSONObject> refdJsonValKey = new ReferenceEqualityKey<>(refdJsonVal);
                idObj = refdJsonObjToId.get(refdJsonValKey);
                if (idObj == null) {
                    // Ref'd JSON object doesn't have an id yet -- generate unique integer id
                    idObj = TinyJSONMapper.ID_PREFIX + objId.getAndIncrement() + TinyJSONMapper.ID_SUFFIX;
                    // Label the referenced node with its new unique id
                    refdJsonObjToId.put(refdJsonValKey, idObj);
                }
            }
            // Link both the JSON representation ob the object to the id
            jsonReferenceToId.put(new ReferenceEqualityKey<>((JSONReference) jsonVal), idObj);
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /** An intermediate object in the serialization process, representing a JSON Object. */
    private static class JSONObject {
        /** Key/value mappings, in display order. */
        List<Entry<String, Object>> items;

        public JSONObject(final List<Entry<String, Object>> items) {
            this.items = items;
        }

        /** Serialize this JSONObject to a string. */
        void toString(final Map<ReferenceEqualityKey<JSONObject>, Object> refdJsonObjToId,
                final Map<ReferenceEqualityKey<JSONReference>, Object> jsonReferenceToId,
                final boolean includeNullValuedFields, final int depth, final int indentWidth,
                final StringBuilder buf) {
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
            final ReferenceEqualityKey<JSONObject> thisKey = new ReferenceEqualityKey<>(this);
            // id will be non-null if this object does not have an @Id field, but was referenced by another object
            // (need to include ID_TAG)
            final Object id = refdJsonObjToId.get(thisKey);
            if (id == null && numDisplayedFields == 0) {
                buf.append("{}");
            } else {
                buf.append(prettyPrint ? "{\n" : "{");
                if (id != null) {
                    if (prettyPrint) {
                        JSONUtils.indent(depth + 1, indentWidth, buf);
                    }
                    buf.append('"');
                    buf.append(TinyJSONMapper.ID_TAG);
                    buf.append(prettyPrint ? "\": " : "\":");
                    jsonValToJSONString(id, refdJsonObjToId, jsonReferenceToId, includeNullValuedFields, depth + 1,
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
                            JSONUtils.indent(depth + 1, indentWidth, buf);
                        }
                        buf.append('"');
                        JSONUtils.escapeJSONString(key, buf);
                        buf.append(prettyPrint ? "\": " : "\":");
                        jsonValToJSONString(val, refdJsonObjToId, jsonReferenceToId, includeNullValuedFields,
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
                    JSONUtils.indent(depth, indentWidth, buf);
                }
                buf.append('}');
            }
        }
    }

    /** An intermediate object in the serialization process, representing a JSON array. */
    private static class JSONArray {
        /** Array items. */
        List<Object> items;

        public JSONArray(final List<Object> items) {
            this.items = items;
        }

        /** Serialize this JSONArray to a string. */
        void toString(final Map<ReferenceEqualityKey<JSONObject>, Object> refdJsonObjToId,
                final Map<ReferenceEqualityKey<JSONReference>, Object> jsonReferenceToId,
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
                    jsonValToJSONString(item, refdJsonObjToId, jsonReferenceToId, includeNullValuedFields,
                            depth + 1, indentWidth, buf);
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

    // -------------------------------------------------------------------------------------------------------------

    /** Serialize a JSON object, array, or value. */
    private static void jsonValToJSONString(final Object jsonVal,
            final Map<ReferenceEqualityKey<JSONObject>, Object> refdJsonObjToId,
            final Map<ReferenceEqualityKey<JSONReference>, Object> jsonReferenceToId,
            final boolean includeNullValuedFields, final int depth, final int indentWidth,
            final StringBuilder buf) {
        if (jsonVal == null) {
            buf.append("null");
        } else if (jsonVal instanceof JSONObject) {
            ((JSONObject) jsonVal).toString(refdJsonObjToId, jsonReferenceToId, includeNullValuedFields, depth,
                    indentWidth, buf);
        } else if (jsonVal instanceof JSONArray) {
            ((JSONArray) jsonVal).toString(refdJsonObjToId, jsonReferenceToId, includeNullValuedFields, depth,
                    indentWidth, buf);
        } else if (jsonVal instanceof JSONReference) {
            final Object referencedObjectId = jsonReferenceToId
                    .get(new ReferenceEqualityKey<>((JSONReference) jsonVal));
            jsonValToJSONString(referencedObjectId, refdJsonObjToId, jsonReferenceToId, includeNullValuedFields,
                    depth, indentWidth, buf);
        } else if (jsonVal instanceof String || jsonVal instanceof Character || jsonVal instanceof JSONReference
                || jsonVal.getClass().isEnum()) {
            buf.append('"');
            JSONUtils.escapeJSONString(jsonVal.toString(), buf);
            buf.append('"');
        } else {
            // Integer, Long, Short, Float, Double, Boolean, Byte (doesn't need escaping)
            buf.append(jsonVal.toString());
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /** Take an array of object values, and recursively convert them (in place) into JSON values. */
    private static void convertVals(final Object[] convertedVals,
            final Set<ReferenceEqualityKey<Object>> visitedOnPath,
            final Set<ReferenceEqualityKey<Object>> standardObjectVisited, final TypeCache typeCache,
            final Map<ReferenceEqualityKey<Object>, JSONObject> objToJSONVal,
            final boolean onlySerializePublicFields) {
        // Pass 1: find standard objects (objects that are not of basic value type or collections/arrays/maps)
        // that have not yet been visited, and mark them as visited. Place a JSONReference placeholder in
        // convertedVals[i] to signify this. This first pass is non-recursive, so that objects are visited
        // as high up the tree as possible, since it is only the first visit of an object that shows in the
        // final JSON doc, and the rest are turned into references.
        final ReferenceEqualityKey<?>[] valKeys = new ReferenceEqualityKey<?>[convertedVals.length];
        final boolean[] needToConvert = new boolean[convertedVals.length];
        for (int i = 0; i < convertedVals.length; i++) {
            final Object val = convertedVals[i];
            // By default (for basic value types), don't convert vals
            needToConvert[i] = !JSONUtils.isBasicValueType(val);
            if (needToConvert[i] && !JSONUtils.isCollectionOrArrayOrMap(val)) {
                // If this object is a standard object, check if it has already been visited
                // elsewhere in the tree. If so, use a JSONReference instead, and mark the object
                // as visited.
                final ReferenceEqualityKey<Object> valKey = new ReferenceEqualityKey<>(val);
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
                convertedVals[i] = toJSONGraph(val, visitedOnPath, standardObjectVisited, typeCache, objToJSONVal,
                        onlySerializePublicFields);
                if (!JSONUtils.isCollectionOrArrayOrMap(val)) {
                    // If this object is a standard object, then it has not been visited before, 
                    // so save the mapping between original object and converted object
                    @SuppressWarnings("unchecked")
                    final ReferenceEqualityKey<Object> valKey = (ReferenceEqualityKey<Object>) valKeys[i];
                    objToJSONVal.put(valKey, (JSONObject) convertedVals[i]);
                }
            }
        }
    }

    /**
     * Turn an object graph into a graph of JSON objects, arrays, and values.
     */
    @SuppressWarnings({ "unchecked", "rawtypes" })
    private static Object toJSONGraph(final Object obj, final Set<ReferenceEqualityKey<Object>> visitedOnPath,
            final Set<ReferenceEqualityKey<Object>> standardObjectVisited, final TypeCache typeCache,
            final Map<ReferenceEqualityKey<Object>, JSONObject> objToJSONVal,
            final boolean onlySerializePublicFields) {

        // For null and basic value types, just return value
        if (obj == null || obj instanceof String || obj instanceof Integer || obj instanceof Boolean
                || obj instanceof Long || obj instanceof Float || obj instanceof Double || obj instanceof Short
                || obj instanceof Byte || obj instanceof Character || obj.getClass().isEnum()) {
            return obj;
        }

        // Check for cycles
        final ReferenceEqualityKey<Object> objKey = new ReferenceEqualityKey<>(obj);
        if (!visitedOnPath.add(objKey)) {
            // Reached cycle in graph
            if (JSONUtils.isCollectionOrArray(obj)) {
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
                if (!JSONUtils.isBasicValueType(key)) {
                    throw new IllegalArgumentException("Map key of type " + key.getClass().getName()
                            + " is not a basic type (String, Integer, etc.), so can't be easily "
                            + "serialized as a JSON associative array key");
                }
                convertedKeys[i] = JSONUtils.escapeJSONString(key.toString());
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
            convertVals(convertedVals, visitedOnPath, standardObjectVisited, typeCache, objToJSONVal,
                    onlySerializePublicFields);

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
            convertVals(convertedVals, visitedOnPath, standardObjectVisited, typeCache, objToJSONVal,
                    onlySerializePublicFields);

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
            convertVals(convertedVals, visitedOnPath, standardObjectVisited, typeCache, objToJSONVal,
                    onlySerializePublicFields);

            // Create new JSON array representing the collection
            jsonVal = new JSONArray(Arrays.asList(convertedVals));

        } else {
            // A standard object -- serialize fields as a JSON associative array.
            try {
                // Cache class fields to include in serialization (typeResolutions can be null,
                // since it's not necessary to resolve type parameters during serialization)
                final TypeResolvedFieldsForClass resolvedFields = typeCache.getResolvedFields(cls,
                        /* typeResolutions = */ null, onlySerializePublicFields);
                final List<FieldResolvedTypeInfo> fieldOrder = resolvedFields.fieldOrder;
                final int n = fieldOrder.size();

                // Convert field values to JSON values
                final String[] fieldNames = new String[n];
                final Object[] convertedVals = new Object[n];
                for (int i = 0; i < n; i++) {
                    final FieldResolvedTypeInfo fieldInfo = fieldOrder.get(i);
                    final Field field = fieldInfo.field;
                    final Class<?> fieldType = field.getType();
                    fieldNames[i] = field.getName();
                    if (fieldType == Integer.TYPE) {
                        convertedVals[i] = Integer.valueOf(field.getInt(obj));
                    } else if (fieldType == Long.TYPE) {
                        convertedVals[i] = Long.valueOf(field.getLong(obj));
                    } else if (fieldType == Short.TYPE) {
                        convertedVals[i] = Short.valueOf(field.getShort(obj));
                    } else if (fieldType == Double.TYPE) {
                        convertedVals[i] = Double.valueOf(field.getDouble(obj));
                    } else if (fieldType == Float.TYPE) {
                        convertedVals[i] = Float.valueOf(field.getFloat(obj));
                    } else if (fieldType == Boolean.TYPE) {
                        convertedVals[i] = Boolean.valueOf(field.getBoolean(obj));
                    } else if (fieldType == Byte.TYPE) {
                        convertedVals[i] = Byte.valueOf(field.getByte(obj));
                    } else if (fieldType == Character.TYPE) {
                        convertedVals[i] = Character.valueOf(field.getChar(obj));
                    } else {
                        convertedVals[i] = field.get(obj);
                    }
                }
                convertVals(convertedVals, visitedOnPath, standardObjectVisited, typeCache, objToJSONVal,
                        onlySerializePublicFields);

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

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Recursively serialize an Object (or array, list, map or set of objects) to JSON, skipping transient and final
     * fields. If indentWidth == 0, no prettyprinting indentation is performed.
     */
    static String toJSON(final Object obj, final int indentWidth, final boolean onlySerializePublicFields) {
        final Set<ReferenceEqualityKey<Object>> visitedOnPath = new HashSet<>();
        final Set<ReferenceEqualityKey<Object>> standardObjectVisited = new HashSet<>();
        final TypeCache typeCache = new TypeCache();
        final HashMap<ReferenceEqualityKey<Object>, JSONObject> objToJSONVal = new HashMap<>();

        final Object rootJsonVal = toJSONGraph(obj, visitedOnPath, standardObjectVisited, typeCache, objToJSONVal,
                onlySerializePublicFields);

        final Map<ReferenceEqualityKey<JSONObject>, Object> refdJsonObjToId = new HashMap<>();
        final Map<ReferenceEqualityKey<JSONReference>, Object> jsonReferenceToId = new HashMap<>();
        final AtomicInteger objId = new AtomicInteger(0);
        assignObjectIds(rootJsonVal, objToJSONVal, typeCache, refdJsonObjToId, jsonReferenceToId, objId,
                onlySerializePublicFields);

        final StringBuilder buf = new StringBuilder(32768);
        jsonValToJSONString(rootJsonVal, refdJsonObjToId, jsonReferenceToId, /* includeNullValuedFields = */ false,
                0, indentWidth, buf);
        return buf.toString();
    }

    /**
     * Recursively serialize an Object (or array, list, map or set of objects) to JSON, skipping transient and final
     * fields.
     */
    static String toJSON(final Object obj) {
        return toJSON(obj, /* indentWidth = */ 0, /* onlySerializePublicFields = */ false);
    }
}
