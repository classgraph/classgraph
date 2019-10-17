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

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.util.AbstractMap.SimpleEntry;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.classgraph.ClassGraphException;
import nonapi.io.github.classgraph.utils.CollectionUtils;

/**
 * Fast, lightweight Java object to JSON serializer, and JSON to Java object deserializer. Handles cycles in the
 * object graph by inserting reference ids.
 */
public final class JSONSerializer {

    /**
     * Constructor.
     */
    private JSONSerializer() {
        // Cannot be constructed
    }

    /**
     * Create a unique id for each referenced JSON object.
     *
     * @param jsonVal
     *            the json val
     * @param objToJSONVal
     *            a map from obj to JSON val
     * @param classFieldCache
     *            the class field cache
     * @param jsonReferenceToId
     *            a map from json reference to id
     * @param objId
     *            the object id
     * @param onlySerializePublicFields
     *            whether to only serialize public fields
     */
    private static void assignObjectIds(final Object jsonVal,
            final Map<ReferenceEqualityKey<Object>, JSONObject> objToJSONVal, final ClassFieldCache classFieldCache,
            final Map<ReferenceEqualityKey<JSONReference>, CharSequence> jsonReferenceToId,
            final AtomicInteger objId, final boolean onlySerializePublicFields) {
        if (jsonVal instanceof JSONObject) {
            for (final Entry<String, Object> item : ((JSONObject) jsonVal).items) {
                assignObjectIds(item.getValue(), objToJSONVal, classFieldCache, jsonReferenceToId, objId,
                        onlySerializePublicFields);
            }
        } else if (jsonVal instanceof JSONArray) {
            for (final Object item : ((JSONArray) jsonVal).items) {
                assignObjectIds(item, objToJSONVal, classFieldCache, jsonReferenceToId, objId,
                        onlySerializePublicFields);
            }
        } else if (jsonVal instanceof JSONReference) {
            // Get the referenced (non-JSON) object
            final Object refdObj = ((JSONReference) jsonVal).idObject;
            if (refdObj == null) {
                // Should not happen
                throw ClassGraphException.newClassGraphException("Internal inconsistency");
            }
            // Look up the JSON object corresponding to the referenced object
            final ReferenceEqualityKey<Object> refdObjKey = new ReferenceEqualityKey<>(refdObj);
            final JSONObject refdJsonVal = objToJSONVal.get(refdObjKey);
            if (refdJsonVal == null) {
                // Should not happen
                throw ClassGraphException.newClassGraphException("Internal inconsistency");
            }
            // See if the JSON object has an @Id field
            // (for serialization, typeResolutions can be null)
            final Field annotatedField = classFieldCache.get(refdObj.getClass()).idField;
            CharSequence idStr = null;
            if (annotatedField != null) {
                // Get id value from field annotated with @Id
                try {
                    final Object idObject = annotatedField.get(refdObj);
                    if (idObject != null) {
                        idStr = idObject.toString();
                        refdJsonVal.objectId = idStr;
                    }
                } catch (IllegalArgumentException | IllegalAccessException e) {
                    // Should not happen
                    throw new IllegalArgumentException("Could not access @Id-annotated field " + annotatedField, e);
                }
            }
            if (idStr == null) {
                // No @Id field, or field value is null -- check if ref'd JSON Object already has an id
                if (refdJsonVal.objectId == null) {
                    // Ref'd JSON object doesn't have an id yet -- generate unique integer id
                    idStr = JSONUtils.ID_PREFIX + objId.getAndIncrement() + JSONUtils.ID_SUFFIX;
                    refdJsonVal.objectId = idStr;
                } else {
                    idStr = refdJsonVal.objectId;
                }
            }
            // Link both the JSON representation ob the object to the id
            jsonReferenceToId.put(new ReferenceEqualityKey<>((JSONReference) jsonVal), idStr);
        }
        // if (jsonVal == null) then do nothing
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Take an array of object values, and recursively convert them (in place) into JSON values.
     *
     * @param convertedVals
     *            the converted vals
     * @param visitedOnPath
     *            visited nodes
     * @param standardObjectVisited
     *            visited standard objects
     * @param classFieldCache
     *            the class field cache
     * @param objToJSONVal
     *            a map from obj to JSON val
     * @param onlySerializePublicFields
     *            whether to only serialize public fields
     */
    private static void convertVals(final Object[] convertedVals,
            final Set<ReferenceEqualityKey<Object>> visitedOnPath,
            final Set<ReferenceEqualityKey<Object>> standardObjectVisited, final ClassFieldCache classFieldCache,
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
            if (needToConvert[i] && !JSONUtils.isCollectionOrArray(val)) {
                // If this object is a standard object or a map, check if it has already been visited
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
            // Special handling for class references: Convert to class name string
            if (val instanceof Class) {
                convertedVals[i] = ((Class<?>) val).getName();
            }
        }
        // Pass 2: Recursively convert items in standard objects, maps, collections and arrays to JSON objects.
        for (int i = 0; i < convertedVals.length; i++) {
            if (needToConvert[i]) {
                // Recursively convert standard objects (if it is the first time they have been visited)
                // and maps to JSON objects, and convert collections and arrays to JSON arrays.
                final Object val = convertedVals[i];
                convertedVals[i] = toJSONGraph(val, visitedOnPath, standardObjectVisited, classFieldCache,
                        objToJSONVal, onlySerializePublicFields);
                if (!JSONUtils.isCollectionOrArray(val)) {
                    // If this object is a standard object or map, then it has not been visited before, 
                    // so save the mapping between original object and converted object
                    @SuppressWarnings("unchecked")
                    final ReferenceEqualityKey<Object> valKey = (ReferenceEqualityKey<Object>) valKeys[i];
                    objToJSONVal.put(valKey, (JSONObject) convertedVals[i]);
                }
            }
        }
    }

    /**
     * Comparator for set elements, to sort them into some sort of consistent order, so that JSON ordering is
     * deterministic.
     */
    private static final Comparator<Object> SET_COMPARATOR = new Comparator<Object>() {
        @Override
        public int compare(final Object o1, final Object o2) {
            if (o1 == null || o2 == null) {
                return (o1 == null ? 0 : 1) - (o2 == null ? 0 : 1);
            }
            if (Comparable.class.isAssignableFrom(o1.getClass())
                    && Comparable.class.isAssignableFrom(o2.getClass())) {
                @SuppressWarnings("unchecked")
                final Comparable<Object> comparableO1 = (Comparable<Object>) o1;
                return comparableO1.compareTo(o2);
            }
            // If the objects are not comparable, just compare the toString() method, and hope it's overridden
            // (otherwise would need to do a deep compare, which is not worth it)
            return o1.toString().compareTo(o2.toString());
        }
    };

    /**
     * Turn an object graph into a graph of JSON objects, arrays, and values.
     *
     * @param obj
     *            the obj
     * @param visitedOnPath
     *            visited nodes
     * @param standardObjectVisited
     *            standard objects visited
     * @param classFieldCache
     *            the class field cache
     * @param objToJSONVal
     *            a map from obj to json val
     * @param onlySerializePublicFields
     *            whether to only serialize public fields
     * @return the object
     */
    @SuppressWarnings({ "unchecked", "rawtypes" })
    private static Object toJSONGraph(final Object obj, final Set<ReferenceEqualityKey<Object>> visitedOnPath,
            final Set<ReferenceEqualityKey<Object>> standardObjectVisited, final ClassFieldCache classFieldCache,
            final Map<ReferenceEqualityKey<Object>, JSONObject> objToJSONVal,
            final boolean onlySerializePublicFields) {

        // For class references, return class name as a string
        if (obj instanceof Class) {
            return ((Class<?>) obj).getName();
        }

        // For null and basic value types, just return value
        if (JSONUtils.isBasicValueType(obj)) {
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
                                + "assigned object ids. Reached cycle at: " + obj);
            } else {
                // Object is its own ancestor -- output object reference instead of object to break cycle
                return new JSONReference(obj);
            }
        }

        Object jsonVal;
        final Class<?> cls = obj.getClass();
        final boolean isArray = cls.isArray();

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
            if (firstNonNullKey != null && Comparable.class.isAssignableFrom(firstNonNullKey.getClass())) {
                CollectionUtils.sortIfNotEmpty((ArrayList<Comparable>) keys);
                keysComparable = true;
            }

            // Serialize keys into string form
            final String[] convertedKeys = new String[n];
            for (int i = 0; i < n; i++) {
                final Object key = keys.get(i);
                if (key != null && !JSONUtils.isBasicValueType(key)) {
                    throw new IllegalArgumentException("Map key of type " + key.getClass().getName()
                            + " is not a basic type (String, Integer, etc.), so can't be easily "
                            + "serialized as a JSON associative array key");
                }
                convertedKeys[i] = JSONUtils.escapeJSONString(key == null ? "null" : key.toString());
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
            convertVals(convertedVals, visitedOnPath, standardObjectVisited, classFieldCache, objToJSONVal,
                    onlySerializePublicFields);

            // Create new JSON object representing the map
            final List<Entry<String, Object>> convertedKeyValPairs = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                convertedKeyValPairs.add(new SimpleEntry<>(convertedKeys[i], convertedVals[i]));
            }
            jsonVal = new JSONObject(convertedKeyValPairs);

        } else if (isArray || List.class.isAssignableFrom(cls)) {
            // Serialize an array or list
            final boolean isList = List.class.isAssignableFrom(cls);
            final List<?> list = isList ? (List<?>) obj : null;
            final int n = list != null ? list.size() : isArray ? Array.getLength(obj) : 0;

            // Convert list items to JSON values
            final Object[] convertedVals = new Object[n];
            for (int i = 0; i < n; i++) {
                convertedVals[i] = list != null ? list.get(i) : isArray ? Array.get(obj, i) : 0;
            }
            convertVals(convertedVals, visitedOnPath, standardObjectVisited, classFieldCache, objToJSONVal,
                    onlySerializePublicFields);

            // Create new JSON array representing the list
            jsonVal = new JSONArray(Arrays.asList(convertedVals));

        } else if (Collection.class.isAssignableFrom(cls)) {
            final Collection<?> collection = (Collection<?>) obj;

            // If collection is a set, need to sort values into some sort of consistent order 
            final List<Object> convertedValsList = new ArrayList<>(collection);
            if (Set.class.isAssignableFrom(cls)) {
                CollectionUtils.sortIfNotEmpty(convertedValsList, SET_COMPARATOR);
            }

            // Convert items to JSON values
            final Object[] convertedVals = convertedValsList.toArray();
            convertVals(convertedVals, visitedOnPath, standardObjectVisited, classFieldCache, objToJSONVal,
                    onlySerializePublicFields);

            // Create new JSON array representing the collection
            jsonVal = new JSONArray(Arrays.asList(convertedVals));

        } else {
            // A standard object -- serialize fields as a JSON associative array.
            try {
                // Cache class fields to include in serialization (typeResolutions can be null,
                // since it's not necessary to resolve type parameters during serialization)
                final ClassFields resolvedFields = classFieldCache.get(cls);
                final List<FieldTypeInfo> fieldOrder = resolvedFields.fieldOrder;
                final int n = fieldOrder.size();

                // Convert field values to JSON values
                final String[] fieldNames = new String[n];
                final Object[] convertedVals = new Object[n];
                for (int i = 0; i < n; i++) {
                    final FieldTypeInfo fieldInfo = fieldOrder.get(i);
                    final Field field = fieldInfo.field;
                    fieldNames[i] = field.getName();
                    convertedVals[i] = JSONUtils.getFieldValue(obj, field);
                }
                convertVals(convertedVals, visitedOnPath, standardObjectVisited, classFieldCache, objToJSONVal,
                        onlySerializePublicFields);

                // Create new JSON object representing the standard object
                final List<Entry<String, Object>> convertedKeyValPairs = new ArrayList<>(n);
                for (int i = 0; i < n; i++) {
                    convertedKeyValPairs.add(new SimpleEntry(fieldNames[i], convertedVals[i]));
                }
                jsonVal = new JSONObject(convertedKeyValPairs);

            } catch (IllegalArgumentException | IllegalAccessException e) {
                throw ClassGraphException.newClassGraphException("Could not get value of field in object: " + obj,
                        e);
            }
        }

        // In the case of a DAG, just serialize the same object multiple times, i.e. remove obj
        // from visited set when exiting recursion, so that future instances also get serialized.
        visitedOnPath.remove(objKey);

        return jsonVal;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Serialize a JSON object, array, or value.
     *
     * @param jsonVal
     *            the json val
     * @param jsonReferenceToId
     *            a map from json reference to id
     * @param includeNullValuedFields
     *            the include null valued fields
     * @param depth
     *            the depth
     * @param indentWidth
     *            the indent width
     * @param buf
     *            the buf
     */
    static void jsonValToJSONString(final Object jsonVal,
            final Map<ReferenceEqualityKey<JSONReference>, CharSequence> jsonReferenceToId,
            final boolean includeNullValuedFields, final int depth, final int indentWidth,
            final StringBuilder buf) {

        if (jsonVal == null) {
            buf.append("null");

        } else if (jsonVal instanceof JSONObject) {
            // Serialize JSONObject to string
            ((JSONObject) jsonVal).toJSONString(jsonReferenceToId, includeNullValuedFields, depth, indentWidth,
                    buf);

        } else if (jsonVal instanceof JSONArray) {
            // Serialize JSONArray to string
            ((JSONArray) jsonVal).toJSONString(jsonReferenceToId, includeNullValuedFields, depth, indentWidth, buf);

        } else if (jsonVal instanceof JSONReference) {
            // Serialize JSONReference to string
            final Object referencedObjectId = jsonReferenceToId
                    .get(new ReferenceEqualityKey<>((JSONReference) jsonVal));
            jsonValToJSONString(referencedObjectId, jsonReferenceToId, includeNullValuedFields, depth, indentWidth,
                    buf);

        } else if (jsonVal instanceof CharSequence || jsonVal instanceof Character || jsonVal.getClass().isEnum()) {
            // Serialize String, Character or enum val to quoted/escaped string
            buf.append('"');
            JSONUtils.escapeJSONString(jsonVal.toString(), buf);
            buf.append('"');

        } else {
            // Serialize a numeric or Boolean type (Integer, Long, Short, Float, Double, Boolean, Byte) to string
            // (doesn't need quoting or escaping)
            buf.append(jsonVal.toString());
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Recursively serialize an Object (or array, list, map or set of objects) to JSON, skipping transient and final
     * fields.
     * 
     * @param obj
     *            The root object of the object graph to serialize.
     * @param indentWidth
     *            If indentWidth == 0, no prettyprinting indentation is performed, otherwise this specifies the
     *            number of spaces to indent each level of JSON.
     * @param onlySerializePublicFields
     *            If true, only serialize public fields.
     * @param classFieldCache
     *            The class field cache. Reusing this cache will increase the speed if many JSON documents of the
     *            same type need to be produced.
     * @return The object graph in JSON form.
     * @throws IllegalArgumentException
     *             If anything goes wrong during serialization.
     */
    public static String serializeObject(final Object obj, final int indentWidth,
            final boolean onlySerializePublicFields, final ClassFieldCache classFieldCache) {
        final HashMap<ReferenceEqualityKey<Object>, JSONObject> objToJSONVal = new HashMap<>();

        final Object rootJsonVal = toJSONGraph(obj, new HashSet<ReferenceEqualityKey<Object>>(),
                new HashSet<ReferenceEqualityKey<Object>>(), classFieldCache, objToJSONVal,
                onlySerializePublicFields);

        final Map<ReferenceEqualityKey<JSONReference>, CharSequence> jsonReferenceToId = new HashMap<>();
        final AtomicInteger objId = new AtomicInteger(0);
        assignObjectIds(rootJsonVal, objToJSONVal, classFieldCache, jsonReferenceToId, objId,
                onlySerializePublicFields);

        final StringBuilder buf = new StringBuilder(32768);
        jsonValToJSONString(rootJsonVal, jsonReferenceToId, /* includeNullValuedFields = */ false, 0, indentWidth,
                buf);
        return buf.toString();
    }

    /**
     * Recursively serialize an Object (or array, list, map or set of objects) to JSON, skipping transient and final
     * fields.
     * 
     * @param obj
     *            The root object of the object graph to serialize.
     * @param indentWidth
     *            If indentWidth == 0, no prettyprinting indentation is performed, otherwise this specifies the
     *            number of spaces to indent each level of JSON.
     * @param onlySerializePublicFields
     *            If true, only serialize public fields.
     * @return The object graph in JSON form.
     * @throws IllegalArgumentException
     *             If anything goes wrong during serialization.
     */
    public static String serializeObject(final Object obj, final int indentWidth,
            final boolean onlySerializePublicFields) {
        return serializeObject(obj, indentWidth, onlySerializePublicFields,
                new ClassFieldCache(/* resolveTypes = */ false, /* onlySerializePublicFields = */ false));
    }

    /**
     * Recursively serialize an Object (or array, list, map or set of objects) to JSON, skipping transient and final
     * fields.
     * 
     * @param obj
     *            The root object of the object graph to serialize.
     * @return The object graph in JSON form.
     * @throws IllegalArgumentException
     *             If anything goes wrong during serialization.
     */
    public static String serializeObject(final Object obj) {
        return serializeObject(obj, /* indentWidth = */ 0, /* onlySerializePublicFields = */ false);
    }

    /**
     * Recursively serialize the named field of an object, skipping transient and final fields.
     * 
     * @param containingObject
     *            The object containing the field value to serialize.
     * @param fieldName
     *            The name of the field to serialize.
     * @param indentWidth
     *            If indentWidth == 0, no prettyprinting indentation is performed, otherwise this specifies the
     *            number of spaces to indent each level of JSON.
     * @param onlySerializePublicFields
     *            If true, only serialize public fields.
     * @param classFieldCache
     *            The class field cache. Reusing this cache will increase the speed if many JSON documents of the
     *            same type need to be produced.
     * @return The object graph in JSON form.
     * @throws IllegalArgumentException
     *             If anything goes wrong during serialization.
     */
    public static String serializeFromField(final Object containingObject, final String fieldName,
            final int indentWidth, final boolean onlySerializePublicFields, final ClassFieldCache classFieldCache) {
        final FieldTypeInfo fieldResolvedTypeInfo = classFieldCache
                .get(containingObject.getClass()).fieldNameToFieldTypeInfo.get(fieldName);
        if (fieldResolvedTypeInfo == null) {
            throw new IllegalArgumentException("Class " + containingObject.getClass().getName()
                    + " does not have a field named \"" + fieldName + "\"");
        }
        final Field field = fieldResolvedTypeInfo.field;
        if (!JSONUtils.fieldIsSerializable(field, /* onlySerializePublicFields = */ false)) {
            throw new IllegalArgumentException("Field " + containingObject.getClass().getName() + "." + fieldName
                    + " needs to be accessible, non-transient, and non-final");
        }
        Object fieldValue;
        try {
            fieldValue = JSONUtils.getFieldValue(containingObject, field);
        } catch (final IllegalAccessException e) {
            throw new IllegalArgumentException("Could get value of field " + fieldName, e);
        }
        return serializeObject(fieldValue, indentWidth, onlySerializePublicFields, classFieldCache);
    }

    /**
     * Recursively serialize the named field of an object, skipping transient and final fields.
     * 
     * @param containingObject
     *            The object containing the field value to serialize.
     * @param fieldName
     *            The name of the field to serialize.
     * @param indentWidth
     *            If indentWidth == 0, no prettyprinting indentation is performed, otherwise this specifies the
     *            number of spaces to indent each level of JSON.
     * @param onlySerializePublicFields
     *            If true, only serialize public fields.
     * @return The object graph in JSON form.
     * @throws IllegalArgumentException
     *             If anything goes wrong during serialization.
     */
    public static String serializeFromField(final Object containingObject, final String fieldName,
            final int indentWidth, final boolean onlySerializePublicFields) {
        // Don't need to resolve types during serialization
        final ClassFieldCache classFieldCache = new ClassFieldCache(/* resolveTypes = */ false,
                onlySerializePublicFields);
        return serializeFromField(containingObject, fieldName, indentWidth, onlySerializePublicFields,
                classFieldCache);
    }
}
