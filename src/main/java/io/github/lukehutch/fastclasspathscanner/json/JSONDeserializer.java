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
import java.lang.reflect.Constructor;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.util.AbstractMap.SimpleEntry;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

/**
 * Fast, lightweight Java object to JSON serializer, and JSON to Java object deserializer. Handles cycles in the
 * object graph by inserting reference ids.
 */
public class JSONDeserializer {
    /**
     * Deserialize a JSON basic value (String, Integer, Long, or Double), conforming it to the expected type
     * (Character, Short, etc.).
     */
    private static Object jsonBasicValueToObject(final Object jsonVal, final Type expectedType) {
        if (jsonVal == null) {
            return null;
        } else if (jsonVal instanceof JSONArray || jsonVal instanceof JSONObject) {
            throw new RuntimeException("Expected a basic value type");
        }
        if (expectedType instanceof ParameterizedType) {
            // TODO: add support for Class<T> reference values, which may be parameterized
            throw new IllegalArgumentException("Got illegal ParameterizedType: " + expectedType);
        } else if (!(expectedType instanceof Class<?>)) {
            throw new IllegalArgumentException("Got illegal basic value type: " + expectedType);
        }

        final Class<?> rawType = (Class<?>) expectedType;
        if (rawType == String.class) {
            if (!(jsonVal instanceof CharSequence)) {
                throw new IllegalArgumentException("Expected string; got " + jsonVal.getClass().getName());
            }
            return jsonVal.toString();

        } else if (rawType == CharSequence.class) {
            if (!(jsonVal instanceof CharSequence)) {
                throw new IllegalArgumentException("Expected CharSequence; got " + jsonVal.getClass().getName());
            }
            return jsonVal;

        } else if (rawType == Integer.class || rawType == Integer.TYPE) {
            if (!(jsonVal instanceof Integer)) {
                throw new IllegalArgumentException("Expected integer; got " + jsonVal.getClass().getName());
            }
            return jsonVal;

        } else if (rawType == Long.class || rawType == Long.TYPE) {
            final boolean isLong = jsonVal instanceof Long;
            final boolean isInteger = jsonVal instanceof Integer;
            if (!(isLong || isInteger)) {
                throw new IllegalArgumentException("Expected long; got " + jsonVal.getClass().getName());
            }
            if (isLong) {
                return jsonVal;
            } else {
                return Long.valueOf(((Integer) jsonVal).intValue());
            }

        } else if (rawType == Short.class || rawType == Short.TYPE) {
            if (!(jsonVal instanceof Integer)) {
                throw new IllegalArgumentException("Expected short; got " + jsonVal.getClass().getName());
            }
            final int intValue = ((Integer) jsonVal).intValue();
            if (intValue < Short.MIN_VALUE || intValue > Short.MAX_VALUE) {
                throw new IllegalArgumentException("Expected short; got out-of-range value " + intValue);
            }
            return Short.valueOf((short) intValue);

        } else if (rawType == Float.class || rawType == Float.TYPE) {
            if (!(jsonVal instanceof Double)) {
                throw new IllegalArgumentException("Expected float; got " + jsonVal.getClass().getName());
            }
            final double doubleValue = ((Double) jsonVal).doubleValue();
            if (doubleValue < Float.MIN_VALUE || doubleValue > Float.MAX_VALUE) {
                throw new IllegalArgumentException("Expected float; got out-of-range value " + doubleValue);
            }
            return Float.valueOf((float) doubleValue);

        } else if (rawType == Double.class || rawType == Double.TYPE) {
            if (!(jsonVal instanceof Double)) {
                throw new IllegalArgumentException("Expected double; got " + jsonVal.getClass().getName());
            }
            return jsonVal;

        } else if (rawType == Byte.class || rawType == Byte.TYPE) {
            if (!(jsonVal instanceof Integer)) {
                throw new IllegalArgumentException("Expected byte; got " + jsonVal.getClass().getName());
            }
            final int intValue = ((Integer) jsonVal).intValue();
            if (intValue < Byte.MIN_VALUE || intValue > Byte.MAX_VALUE) {
                throw new IllegalArgumentException("Expected byte; got out-of-range value " + intValue);
            }
            return Byte.valueOf((byte) intValue);

        } else if (rawType == Character.class || rawType == Character.TYPE) {
            if (!(jsonVal instanceof CharSequence)) {
                throw new IllegalArgumentException("Expected character; got " + jsonVal.getClass().getName());
            }
            final CharSequence charSequence = (CharSequence) jsonVal;
            if (charSequence.length() != 1) {
                throw new IllegalArgumentException("Expected single character; got string");
            }
            return Character.valueOf(charSequence.charAt(0));

        } else if (rawType == Boolean.class || rawType == Boolean.TYPE) {
            if (!(jsonVal instanceof Boolean)) {
                throw new IllegalArgumentException("Expected boolean; got " + jsonVal.getClass().getName());
            }
            return jsonVal;
        } else if (Enum.class.isAssignableFrom(rawType)) {
            // TODO: test enums
            if (!(jsonVal instanceof CharSequence)) {
                throw new IllegalArgumentException(
                        "Expected string for enum value; got " + jsonVal.getClass().getName());
            }
            @SuppressWarnings({ "rawtypes", "unchecked" })
            final Enum enumValue = Enum.valueOf((Class<Enum>) rawType, jsonVal.toString());
            return enumValue;

        } else if (JSONUtils.getRawType(expectedType).isAssignableFrom(jsonVal.getClass())) {
            return jsonVal;

        } else {
            throw new IllegalArgumentException("Got type " + jsonVal.getClass() + "; expected " + expectedType);
        }
    }

    /**
     * Used to hold object instantiations temporarily before their fields can be populated, so that object
     * references can be resolved in the same order during deserialization as they were created during
     * serialization.
     */
    private static class ObjectInstantiation {
        /** The JSONObject or JSONArray to recurse into. */
        Object jsonVal;

        /** The Java object instance to populate from the JSONObject or JSONArray. */
        Object objectInstance;

        /** The resolved type of the object instance. */
        Type type;

        public ObjectInstantiation(final Object objectInstance, final Type type, final Object jsonVal) {
            this.jsonVal = jsonVal;
            this.objectInstance = objectInstance;
            this.type = type;
        }
    }

    private static void populateObjectFromJsonObject(final Object objectInstance, final Type objectType,
            final Object jsonVal, final TypeCache typeCache, final Map<CharSequence, Object> idToObjectInstance,
            final List<Runnable> collectionElementAdders) {

        // Leave objectInstance empty (or leave fields null) if jsonVal is null
        if (jsonVal == null) {
            return;
        }

        // Check jsonVal is JSONObject or JSONArray
        final boolean isJsonObject = jsonVal instanceof JSONObject;
        final boolean isJsonArray = jsonVal instanceof JSONArray;
        if (!(isJsonArray || isJsonObject)) {
            throw new IllegalArgumentException(
                    "Expected JSONObject or JSONArray, got " + jsonVal.getClass().getSimpleName());
        }
        final JSONObject jsonObject = isJsonObject ? (JSONObject) jsonVal : null;
        final JSONArray jsonArray = isJsonArray ? (JSONArray) jsonVal : null;

        // Check concrete type of object instance
        final Class<?> concreteType = objectInstance.getClass();
        final boolean isMap = Map.class.isAssignableFrom(concreteType);
        @SuppressWarnings("unchecked")
        final Map<Object, Object> mapInstance = isMap ? (Map<Object, Object>) objectInstance : null;
        final boolean isCollection = Collection.class.isAssignableFrom(concreteType);
        @SuppressWarnings("unchecked")
        final Collection<Object> collectionInstance = isCollection ? (Collection<Object>) objectInstance : null;
        final boolean isArray = concreteType.isArray();
        final boolean isObj = !(isMap || isCollection || isArray);
        if ((isMap || isObj) != isJsonObject || (isCollection || isArray) != isJsonArray) {
            throw new IllegalArgumentException("Wrong JSON type for class " + objectInstance.getClass().getName());
        }

        // Get type arguments of resolved type of object, and resolve any type variables
        Type[] typeArguments;
        TypeVariable<?>[] typeParameters;
        TypeVariableToResolvedTypeList typeResolutions;
        // keyType is the first type parameter for maps, otherwise null
        Type mapKeyType;
        // valueType is the component type for arrays, the second type parameter for maps,
        // the first type parameter for collections, or null for standard objects (since
        // fields may be of a range of different types for standard objects)
        Type commonValueType;
        Class<?> arrayComponentType;
        boolean is1DArray;
        if (objectType instanceof Class<?>) {
            typeArguments = null;
            typeParameters = null;
            typeResolutions = null;
            mapKeyType = null;
            arrayComponentType = isArray ? ((Class<?>) objectType).getComponentType() : null;
            is1DArray = isArray && !arrayComponentType.isArray();
            commonValueType = null;
        } else if (objectType instanceof ParameterizedType) {
            // Get mapping from type variables to resolved types, by comparing the concrete type arguments
            // of the expected type to its type parameters
            final ParameterizedType parameterizedType = (ParameterizedType) objectType;
            typeArguments = parameterizedType.getActualTypeArguments();
            typeParameters = ((Class<?>) parameterizedType.getRawType()).getTypeParameters();
            if (typeArguments.length != typeParameters.length) {
                throw new IllegalArgumentException("Type parameter count mismatch");
            }
            // Correlate type variables with resolved types
            typeResolutions = new TypeVariableToResolvedTypeList(typeArguments.length);
            for (int i = 0; i < typeArguments.length; i++) {
                if (!(typeParameters[i] instanceof TypeVariable<?>)) {
                    throw new IllegalArgumentException("Got illegal type pararameter type: " + typeParameters[i]);
                }
                typeResolutions.add(new TypeVariableToResolvedType(typeParameters[i], typeArguments[i]));
            }
            if (isMap && typeArguments.length != 2) {
                throw new IllegalArgumentException(
                        "Wrong number of type arguments for Map: got " + typeResolutions.size() + "; expected 2");
            } else if (isCollection && typeArguments.length != 1) {
                throw new IllegalArgumentException("Wrong number of type arguments for Collection: got "
                        + typeResolutions.size() + "; expected 1");
            }
            mapKeyType = isMap ? typeResolutions.get(0).resolvedType : null;
            commonValueType = isMap ? typeResolutions.get(1).resolvedType
                    : isCollection ? typeResolutions.get(0).resolvedType : null;
            is1DArray = false;
            arrayComponentType = null;
        } else {
            throw new IllegalArgumentException("Got illegal type: " + objectType);
        }
        final Class<?> commonValueRawType = commonValueType == null ? null : JSONUtils.getRawType(commonValueType);

        // For maps and collections, or 1D arrays, all the elements are of the same type -- 
        // look up the constructor for the value type just once for speed
        Constructor<?> commonValueConstructorWithSizeHint;
        Constructor<?> commonValueDefaultConstructor;
        if (isMap || isCollection) {
            // Get value type constructor for Collection or Map
            commonValueConstructorWithSizeHint = JSONUtils
                    .getConstructorWithSizeHintForConcreteType(commonValueRawType);
            if (commonValueConstructorWithSizeHint != null) {
                // No need for a default constructor if there is a constructor that takes a size hint
                commonValueDefaultConstructor = null;
            } else {
                commonValueDefaultConstructor = JSONUtils.getDefaultConstructorForConcreteType(commonValueRawType);
            }
        } else if (is1DArray && !JSONUtils.isBasicValueType(arrayComponentType)) {
            // Get value type constructor for 1D array (i.e. array with non-array component type),
            // as long as the component type is not a basic value type
            commonValueConstructorWithSizeHint = JSONUtils
                    .getConstructorWithSizeHintForConcreteType(arrayComponentType);
            if (commonValueConstructorWithSizeHint != null) {
                // No need for a default constructor if there is a constructor that takes a size hint
                commonValueDefaultConstructor = null;
            } else {
                commonValueDefaultConstructor = JSONUtils.getDefaultConstructorForConcreteType(arrayComponentType);
            }
        } else {
            // There is no single constructor for the fields of objects, and arrays and basic value types
            // have no constructor
            commonValueConstructorWithSizeHint = null;
            commonValueDefaultConstructor = null;
        }

        // For standard objects, look up the list of deserializable fields
        TypeResolvedFieldsForClass resolvedFields;
        if (isObj) {
            // Get field info from cache
            resolvedFields = typeCache.getResolvedFields(concreteType, typeResolutions,
                    /* onlySerializePublicFields = */ false);
        } else {
            resolvedFields = null;
        }

        // Need to deserialize items in the same order as serialization: create all deserialized objects
        // at the current level in Pass 1, recording any ids that are found, then recurse into child nodes
        // in Pass 2 after objects at the current level have all been instantiated.
        ArrayList<ObjectInstantiation> itemsToRecurseToInPass2 = new ArrayList<>();

        // Pass 1: Convert JSON objects in JSONObject items into Java objects
        final int numItems = isJsonObject ? jsonObject.items.size() : jsonArray.items.size();
        for (int i = 0; i < numItems; i++) {
            // Iterate through items of JSONObject or JSONArray (key is null for JSONArray)
            final Entry<String, Object> jsonObjectItem = isJsonObject ? jsonObject.items.get(i) : null;
            final Object jsonArrayItem = isJsonObject ? null : jsonArray.items.get(i);
            final String itemJsonKey = isJsonObject ? jsonObjectItem.getKey() : null;
            final Object itemJsonValue = isJsonObject ? jsonObjectItem.getValue() : jsonArrayItem;

            // If this is a standard object, look up the field info in the type cache
            FieldResolvedTypeInfo fieldResolvedTypeInfo;
            if (isObj) {
                // Standard objects must interpret the key as a string, since field names are strings.
                // Look up field name directly, using the itemJsonKey string
                final String fieldName = itemJsonKey;
                fieldResolvedTypeInfo = resolvedFields.fieldNameToResolvedTypeInfo.get(fieldName);
                if (fieldResolvedTypeInfo == null) {
                    throw new IllegalArgumentException("Field " + concreteType.getName() + "." + fieldName
                            + " does not exist or is not accessible, non-final, and non-transient");
                }
            } else {
                fieldResolvedTypeInfo = null;
            }

            // For maps, key type should be deserialized from strings, to support e.g. Integer as a key type.
            // This only works for basic object types though (String, Integer, Enum, etc.)
            Object mapKey;
            if (isMap) {
                // TODO: test maps with non-String keys
                mapKey = jsonBasicValueToObject(itemJsonKey, mapKeyType);
            } else {
                mapKey = null;
            }

            // Standard objects have a different type for each field; arrays have a nested value type;
            // collections and maps have a single common value type for all elements
            final Type valueType = isObj ? fieldResolvedTypeInfo.resolvedFieldType
                    : isArray ? arrayComponentType : commonValueType;
            final Class<?> valueRawType = JSONUtils.getRawType(valueType);

            // For maps and collections, all the elements are of the same type -- look up the constructor
            // for the value type just once for speed
            Constructor<?> valueConstructorWithSizeHint;
            Constructor<?> valueDefaultConstructor;
            if (isObj) {
                // For object types, each field has its own constructor
                valueConstructorWithSizeHint = fieldResolvedTypeInfo.constructorForFieldTypeWithSizeHint;
                valueDefaultConstructor = fieldResolvedTypeInfo.defaultConstructorForFieldType;
            } else {
                // For Collections, Maps and Ararys, use the common value constructors, if available
                valueConstructorWithSizeHint = commonValueConstructorWithSizeHint;
                valueDefaultConstructor = commonValueDefaultConstructor;
            }

            // Construct an object of the type needed to hold the value
            final Object instantiatedItemObject;
            if (itemJsonValue == null) {
                // If JSON value is null, no need to recurse to deserialize the value
                instantiatedItemObject = null;

            } else if (valueRawType == Object.class) {
                // For Object-typed fields, we only support deserializing basic value types, since we don't know
                // the element type of JSONObjects and JSONArrays
                if (itemJsonValue instanceof JSONObject || itemJsonValue instanceof JSONArray) {
                    // throw new IllegalArgumentException(
                    // System.err.println("Got JSON object or array type when the expected type is java.lang.Object"
                    //         + " -- cannot deserialize without type information");
                    continue;
                }
                // Deserialize basic JSON value
                instantiatedItemObject = jsonBasicValueToObject(itemJsonValue, valueType);

            } else if (JSONUtils.isBasicValueType(valueRawType)) {
                // For non-recursive (basic) value types, just convert the values directly.
                if (itemJsonValue instanceof JSONObject || itemJsonValue instanceof JSONArray) {
                    throw new IllegalArgumentException(
                            "Got JSON object or array type when expecting a simple value type");
                }
                // Deserialize basic JSON value
                instantiatedItemObject = jsonBasicValueToObject(itemJsonValue, valueType);

            } else {
                // Value type is a recursive type (has fields or items)
                if (CharSequence.class.isAssignableFrom(itemJsonValue.getClass())) {
                    // This must be an id ref -- it is a string in a position that requires a recursive type.  
                    // Look up JSON reference, based on the id in itemJsonValue.
                    final Object linkedObject = idToObjectInstance.get(itemJsonValue);
                    if (linkedObject == null) {
                        // Since we are deserializing objects in the same order as they were 
                        // serialized, this should not happen
                        throw new IllegalArgumentException("Object id not found: " + itemJsonValue);
                    }
                    // Use linked value in place of a new object instantiation, but don't recurse
                    instantiatedItemObject = linkedObject;

                } else {
                    // For other items of recursive type, create an empty object instance for the item
                    try {
                        if (valueRawType.isArray()) {
                            // Instantiate inner array with same number of items as the JSONArray
                            instantiatedItemObject = Array.newInstance(valueRawType.getComponentType(), numItems);
                        } else {
                            // Instantiate a Map or Collection, with a size hint if possible
                            instantiatedItemObject = valueConstructorWithSizeHint != null
                                    // Instantiate collection or map with size hint
                                    ? valueConstructorWithSizeHint.newInstance(numItems)
                                    // Instantiate other object types
                                    : valueDefaultConstructor.newInstance();
                        }
                    } catch (final Exception e) {
                        throw new IllegalArgumentException("Could not instantiate class " + valueRawType.getName(),
                                e);
                    }

                    // Look up any id field in the object (it will be the first field), and if present,
                    // add it to the idToObjectInstance map, so that it is available before recursing 
                    // into any sibling objects.
                    if (itemJsonValue != null && itemJsonValue instanceof JSONObject) {
                        final JSONObject itemJsonObject = (JSONObject) itemJsonValue;
                        if (itemJsonObject.objectId != null) {
                            idToObjectInstance.put(itemJsonObject.objectId, instantiatedItemObject);
                        }
                    }

                    // Defer recursing into items
                    if (itemsToRecurseToInPass2 == null) {
                        itemsToRecurseToInPass2 = new ArrayList<>();
                    }
                    itemsToRecurseToInPass2
                            .add(new ObjectInstantiation(instantiatedItemObject, valueType, itemJsonValue));
                }
            }

            // Add instantiated items to parent object
            if (isObj) {
                fieldResolvedTypeInfo.setFieldValue(objectInstance, instantiatedItemObject);
            } else if (isMap) {
                mapInstance.put(mapKey, instantiatedItemObject);
            } else if (isArray) {
                Array.set(objectInstance, i, instantiatedItemObject);
            } else if (isCollection) {
                // Can't add partially-deserialized item objects to Collections yet, since their
                // hashCode() and equals() methods may depend upon fields that have not yet been set.
                collectionElementAdders.add(new Runnable() {
                    @Override
                    public void run() {
                        collectionInstance.add(instantiatedItemObject);
                    }
                });
            }
        }

        // Pass 2: Recurse into child items to populate child fields.
        if (itemsToRecurseToInPass2 != null) {
            for (final ObjectInstantiation i : itemsToRecurseToInPass2) {
                populateObjectFromJsonObject(i.objectInstance, i.type, i.jsonVal, typeCache, idToObjectInstance,
                        collectionElementAdders);
            }
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Set up the initial mapping from id to object, by adding the id of the toplevel object, if it has an id field
     * in JSON.
     */
    private static HashMap<CharSequence, Object> getInitialIdToObjectMap(final Object objectInstance,
            final Object parsedJSON) {
        final HashMap<CharSequence, Object> idToObjectInstance = new HashMap<>();
        if (parsedJSON != null && parsedJSON instanceof JSONObject) {
            final JSONObject itemJsonObject = (JSONObject) parsedJSON;
            if (itemJsonObject.items.size() > 0) {
                final Entry<String, Object> firstItem = itemJsonObject.items.get(0);
                if (firstItem.getKey().equals(JSONUtils.ID_KEY)) {
                    final Object firstItemValue = firstItem.getValue();
                    if (firstItemValue == null || !CharSequence.class.isAssignableFrom(firstItemValue.getClass())) {
                        idToObjectInstance.put((CharSequence) firstItemValue, objectInstance);
                    }
                }
            }
        }
        return idToObjectInstance;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Deserialize JSON to a new object graph, with the root object of the specified expected type, using or reusing
     * the given type cache. Does not work for generic types, since it is not possible to obtain the generic type of
     * a Class reference.
     * 
     * @param expectedType
     *            The type that the JSON should conform to.
     * @param json
     *            the JSON string to deserialize.
     * @param typeCache
     *            The type cache. Reusing the type cache will increase the speed if many JSON documents of the same
     *            type need to be parsed.
     * @return The object graph after deserialization.
     * @throws IllegalArgumentException
     *             If anything goes wrong during deserialization.
     */
    public static Object deserializeObject(final Class<?> expectedType, final String json,
            final TypeCache typeCache) throws IllegalArgumentException {
        // Parse the JSON
        Object parsedJSON;
        try {
            parsedJSON = JSONParser.parseJSON(json);
        } catch (final Exception e) {
            throw new IllegalArgumentException("Could not parse JSON", e);
        }

        Object objectInstance;
        try {
            // Construct an object of the expected type
            final Constructor<?> constructor = JSONUtils.getDefaultConstructorForConcreteType(expectedType);
            objectInstance = constructor.newInstance();
        } catch (final Exception e) {
            throw new IllegalArgumentException("Could not construct object of type " + expectedType.getName(), e);
        }

        // Populate the object from the parsed JSON
        final List<Runnable> collectionElementAdders = new ArrayList<>();
        populateObjectFromJsonObject(objectInstance, expectedType, parsedJSON, typeCache,
                getInitialIdToObjectMap(objectInstance, parsedJSON), collectionElementAdders);
        for (final Runnable runnable : collectionElementAdders) {
            runnable.run();
        }
        return objectInstance;

    }

    /**
     * Deserialize JSON to a new object graph, with the root object of the specified expected type. Does not work
     * for generic types, since it is not possible to obtain the generic type of a Class reference.
     * 
     * @param expectedType
     *            The type that the JSON should conform to.
     * @param json
     *            the JSON string to deserialize.
     * @return The object graph after deserialization.
     * @throws IllegalArgumentException
     *             If anything goes wrong during deserialization.
     */
    public static Object deserializeObject(final Class<?> expectedType, final String json)
            throws IllegalArgumentException {
        final TypeCache typeCache = new TypeCache();
        return deserializeObject(expectedType, json, typeCache);
    }

    /**
     * Deserialize JSON to a new object graph, with the root object of the specified expected type, and store the
     * root object in the named field of the given containing object. Works for generic types, since it is possible
     * to obtain the generic type of a field.
     * 
     * @param containingObject
     *            The object containing the named field to deserialize the object graph into.
     * @param fieldName
     *            The name of the field to set with the result.
     * @param json
     *            the JSON string to deserialize.
     * @param typeCache
     *            The type cache. Reusing the type cache will increase the speed if many JSON documents of the same
     *            type need to be parsed.
     * @throws IllegalArgumentException
     *             If anything goes wrong during deserialization.
     */
    public static void deserializeToField(final Object containingObject, final String fieldName, final String json,
            final TypeCache typeCache) throws IllegalArgumentException {
        if (containingObject == null) {
            throw new IllegalArgumentException("Cannot deserialize to a field of a null object");
        }

        // Parse the JSON
        Object parsedJSON;
        try {
            parsedJSON = JSONParser.parseJSON(json);
        } catch (final Exception e) {
            throw new IllegalArgumentException("Could not parse JSON", e);
        }

        // Create a JSONObject with one field of the requested name, and deserialize that into the requested object
        final JSONObject wrapperJsonObj = new JSONObject();
        wrapperJsonObj.items.add(new SimpleEntry<>(fieldName, parsedJSON));

        // Populate the object field
        // (no need to call getInitialIdToObjectMap(), since toplevel object is a wrapper, which doesn't have an id)
        final List<Runnable> collectionElementAdders = new ArrayList<>();
        populateObjectFromJsonObject(containingObject, containingObject.getClass(), wrapperJsonObj, typeCache,
                new HashMap<>(), collectionElementAdders);
        for (final Runnable runnable : collectionElementAdders) {
            runnable.run();
        }
    }

    /**
     * Deserialize JSON to a new object graph, with the root object of the specified expected type, and store the
     * root object in the named field of the given containing object. Works for generic types, since it is possible
     * to obtain the generic type of a field.
     * 
     * @param containingObject
     *            The object containing the named field to deserialize the object graph into.
     * @param fieldName
     *            The name of the field to set with the result.
     * @param json
     *            the JSON string to deserialize.
     * @throws IllegalArgumentException
     *             If anything goes wrong during deserialization.
     */
    public static void deserializeToField(final Object containingObject, final String fieldName, final String json)
            throws IllegalArgumentException {
        final TypeCache typeCache = new TypeCache();
        deserializeToField(containingObject, fieldName, json, typeCache);
    }
}
