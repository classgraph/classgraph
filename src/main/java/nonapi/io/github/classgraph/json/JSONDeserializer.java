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
import java.lang.reflect.Constructor;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.AbstractMap.SimpleEntry;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import io.github.classgraph.ClassGraphException;
import nonapi.io.github.classgraph.types.ParseException;

/**
 * Fast, lightweight Java object to JSON serializer, and JSON to Java object deserializer. Handles cycles in the
 * object graph by inserting reference ids.
 */
public class JSONDeserializer {
    /**
     * Constructor.
     */
    private JSONDeserializer() {
        // Cannot be constructed
    }

    /**
     * Deserialize a JSON basic value (String, Integer, Long, or Double), conforming it to the expected type
     * (Character, Short, etc.).
     *
     * @param jsonVal
     *            the json val
     * @param expectedType
     *            the expected type
     * @param convertStringToNumber
     *            if true, convert strings to numbers
     * @return the object
     */
    private static Object jsonBasicValueToObject(final Object jsonVal, final Type expectedType,
            final boolean convertStringToNumber) {
        if (jsonVal == null) {
            return null;
        } else if (jsonVal instanceof JSONArray || jsonVal instanceof JSONObject) {
            throw ClassGraphException.newClassGraphException("Expected a basic value type");
        }
        if (expectedType instanceof ParameterizedType) {
            if (((ParameterizedType) expectedType).getRawType().getClass() == Class.class) {
                final String str = jsonVal.toString();
                final int idx = str.indexOf('<');
                final String className = str.substring(0, idx < 0 ? str.length() : idx);
                try {
                    return Class.forName(className);
                } catch (final ClassNotFoundException e) {
                    throw new IllegalArgumentException("Could not deserialize class reference " + jsonVal, e);
                }
            } else {
                throw new IllegalArgumentException("Got illegal ParameterizedType: " + expectedType);
            }
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
            if (convertStringToNumber && jsonVal instanceof CharSequence) {
                return Integer.parseInt(jsonVal.toString());
            }
            if (!(jsonVal instanceof Integer)) {
                throw new IllegalArgumentException("Expected integer; got " + jsonVal.getClass().getName());
            }
            return jsonVal;

        } else if (rawType == Long.class || rawType == Long.TYPE) {
            final boolean isLong = jsonVal instanceof Long;
            final boolean isInteger = jsonVal instanceof Integer;
            if (convertStringToNumber && jsonVal instanceof CharSequence) {
                return isLong ? Long.parseLong(jsonVal.toString()) : Integer.parseInt(jsonVal.toString());
            }
            if (!(isLong || isInteger)) {
                throw new IllegalArgumentException("Expected long; got " + jsonVal.getClass().getName());
            }
            if (isLong) {
                return jsonVal;
            } else {
                return (long) (Integer) jsonVal;
            }

        } else if (rawType == Short.class || rawType == Short.TYPE) {
            if (convertStringToNumber && jsonVal instanceof CharSequence) {
                return Short.parseShort(jsonVal.toString());
            }
            if (!(jsonVal instanceof Integer)) {
                throw new IllegalArgumentException("Expected short; got " + jsonVal.getClass().getName());
            }
            final int intValue = (Integer) jsonVal;
            if (intValue < Short.MIN_VALUE || intValue > Short.MAX_VALUE) {
                throw new IllegalArgumentException("Expected short; got out-of-range value " + intValue);
            }
            return (short) intValue;

        } else if (rawType == Float.class || rawType == Float.TYPE) {
            if (convertStringToNumber && jsonVal instanceof CharSequence) {
                return Float.parseFloat(jsonVal.toString());
            }
            if (!(jsonVal instanceof Double)) {
                throw new IllegalArgumentException("Expected float; got " + jsonVal.getClass().getName());
            }
            final double doubleValue = (Double) jsonVal;
            if (doubleValue < Float.MIN_VALUE || doubleValue > Float.MAX_VALUE) {
                throw new IllegalArgumentException("Expected float; got out-of-range value " + doubleValue);
            }
            return (float) doubleValue;

        } else if (rawType == Double.class || rawType == Double.TYPE) {
            if (convertStringToNumber && jsonVal instanceof CharSequence) {
                return Double.parseDouble(jsonVal.toString());
            }
            if (!(jsonVal instanceof Double)) {
                throw new IllegalArgumentException("Expected double; got " + jsonVal.getClass().getName());
            }
            return jsonVal;

        } else if (rawType == Byte.class || rawType == Byte.TYPE) {
            if (convertStringToNumber && jsonVal instanceof CharSequence) {
                return Byte.parseByte(jsonVal.toString());
            }
            if (!(jsonVal instanceof Integer)) {
                throw new IllegalArgumentException("Expected byte; got " + jsonVal.getClass().getName());
            }
            final int intValue = (Integer) jsonVal;
            if (intValue < Byte.MIN_VALUE || intValue > Byte.MAX_VALUE) {
                throw new IllegalArgumentException("Expected byte; got out-of-range value " + intValue);
            }
            return (byte) intValue;

        } else if (rawType == Character.class || rawType == Character.TYPE) {
            if (!(jsonVal instanceof CharSequence)) {
                throw new IllegalArgumentException("Expected character; got " + jsonVal.getClass().getName());
            }
            final CharSequence charSequence = (CharSequence) jsonVal;
            if (charSequence.length() != 1) {
                throw new IllegalArgumentException("Expected single character; got string");
            }
            return charSequence.charAt(0);

        } else if (rawType == Boolean.class || rawType == Boolean.TYPE) {
            if (convertStringToNumber && jsonVal instanceof CharSequence) {
                return Boolean.parseBoolean(jsonVal.toString());
            }
            if (!(jsonVal instanceof Boolean)) {
                throw new IllegalArgumentException("Expected boolean; got " + jsonVal.getClass().getName());
            }
            return jsonVal;
        } else if (Enum.class.isAssignableFrom(rawType)) {
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

        /**
         * Constructor.
         *
         * @param objectInstance
         *            the object instance
         * @param type
         *            the type
         * @param jsonVal
         *            the json val
         */
        public ObjectInstantiation(final Object objectInstance, final Type type, final Object jsonVal) {
            this.jsonVal = jsonVal;
            this.objectInstance = objectInstance;
            this.type = type;
        }
    }

    /**
     * Populate object from json object.
     *
     * @param objectInstance
     *            the object instance
     * @param objectResolvedType
     *            the object resolved type
     * @param jsonVal
     *            the json val
     * @param classFieldCache
     *            the class field cache
     * @param idToObjectInstance
     *            a map from id to object instance
     * @param collectionElementAdders
     *            the collection element adders
     */
    private static void populateObjectFromJsonObject(final Object objectInstance, final Type objectResolvedType,
            final Object jsonVal, final ClassFieldCache classFieldCache,
            final Map<CharSequence, Object> idToObjectInstance, final List<Runnable> collectionElementAdders) {

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
        final Class<?> rawType = objectInstance.getClass();
        final boolean isMap = Map.class.isAssignableFrom(rawType);
        @SuppressWarnings("unchecked")
        final Map<Object, Object> mapInstance = isMap ? (Map<Object, Object>) objectInstance : null;
        final boolean isCollection = Collection.class.isAssignableFrom(rawType);
        @SuppressWarnings("unchecked")
        final Collection<Object> collectionInstance = isCollection ? (Collection<Object>) objectInstance : null;
        final boolean isArray = rawType.isArray();
        final boolean isObj = !(isMap || isCollection || isArray);
        if ((isMap || isObj) != isJsonObject || (isCollection || isArray) != isJsonArray) {
            throw new IllegalArgumentException("Wrong JSON type for class " + objectInstance.getClass().getName());
        }

        // Handle concrete subclasses of generic classes, e.g. ClassInfoList extends List<ClassInfo>
        Type objectResolvedTypeGeneric = objectResolvedType;
        if (objectResolvedType instanceof Class<?>) {
            final Class<?> objectResolvedCls = (Class<?>) objectResolvedType;
            if (Map.class.isAssignableFrom(objectResolvedCls)) {
                if (!isMap) {
                    throw new IllegalArgumentException("Got an unexpected map type");
                }
                objectResolvedTypeGeneric = objectResolvedCls.getGenericSuperclass();
            } else if (Collection.class.isAssignableFrom(objectResolvedCls)) {
                if (!isCollection) {
                    throw new IllegalArgumentException("Got an unexpected map type");
                }
                objectResolvedTypeGeneric = objectResolvedCls.getGenericSuperclass();
            }
        }

        // Get type arguments of resolved type of object, and resolve any type variables
        TypeResolutions typeResolutions;
        // keyType is the first type parameter for maps, otherwise null
        Type mapKeyType;
        // valueType is the component type for arrays, the second type parameter for maps,
        // the first type parameter for collections, or null for standard objects (since
        // fields may be of a range of different types for standard objects)
        Type commonResolvedValueType;
        Class<?> arrayComponentType;
        boolean is1DArray;
        if (objectResolvedTypeGeneric instanceof Class<?>) {
            // Not a Map or Collection subclass
            typeResolutions = null;
            mapKeyType = null;
            final Class<?> objectResolvedCls = (Class<?>) objectResolvedTypeGeneric;
            if (isArray) {
                arrayComponentType = objectResolvedCls.getComponentType();
                is1DArray = !arrayComponentType.isArray();
            } else {
                arrayComponentType = null;
                is1DArray = false;
            }
            commonResolvedValueType = null;
        } else if (objectResolvedTypeGeneric instanceof ParameterizedType) {
            // Get mapping from type variables to resolved types, by comparing the concrete type arguments
            // of the expected type to its type parameters
            final ParameterizedType parameterizedResolvedType = (ParameterizedType) objectResolvedTypeGeneric;
            typeResolutions = new TypeResolutions(parameterizedResolvedType);
            // Correlate type variables with resolved types
            final int numTypeArgs = typeResolutions.resolvedTypeArguments.length;
            if (isMap && numTypeArgs != 2) {
                throw new IllegalArgumentException(
                        "Wrong number of type arguments for Map: got " + numTypeArgs + "; expected 2");
            } else if (isCollection && numTypeArgs != 1) {
                throw new IllegalArgumentException(
                        "Wrong number of type arguments for Collection: got " + numTypeArgs + "; expected 1");
            }
            mapKeyType = isMap ? typeResolutions.resolvedTypeArguments[0] : null;
            commonResolvedValueType = isMap ? typeResolutions.resolvedTypeArguments[1]
                    : isCollection ? typeResolutions.resolvedTypeArguments[0] : null;
            is1DArray = false;
            arrayComponentType = null;
        } else {
            throw new IllegalArgumentException("Got illegal type: " + objectResolvedTypeGeneric);
        }
        final Class<?> commonValueRawType = commonResolvedValueType == null ? null
                : JSONUtils.getRawType(commonResolvedValueType);

        // For maps and collections, or 1D arrays, all the elements are of the same type. 
        // Look up the constructor for the value type just once for speed.
        Constructor<?> commonValueConstructorWithSizeHint;
        Constructor<?> commonValueDefaultConstructor;
        if (isMap || isCollection || (is1DArray && !JSONUtils.isBasicValueType(arrayComponentType))) {
            // Get value type constructor for Collection, Map or 1D array
            commonValueConstructorWithSizeHint = classFieldCache.getConstructorWithSizeHintForConcreteTypeOf(
                    is1DArray ? arrayComponentType : commonValueRawType);
            if (commonValueConstructorWithSizeHint != null) {
                // No need for a default constructor if there is a constructor that takes a size hint
                commonValueDefaultConstructor = null;
            } else {
                commonValueDefaultConstructor = classFieldCache.getDefaultConstructorForConcreteTypeOf(
                        is1DArray ? arrayComponentType : commonValueRawType);
            }
        } else {
            // There is no single constructor for the fields of objects, and arrays and basic value types
            // have no constructor
            commonValueConstructorWithSizeHint = null;
            commonValueDefaultConstructor = null;
        }

        // For standard objects, look up the list of deserializable fields
        final ClassFields classFields = isObj ? classFieldCache.get(rawType) : null;

        // Need to deserialize items in the same order as serialization: create all deserialized objects
        // at the current level in Pass 1, recording any ids that are found, then recurse into child nodes
        // in Pass 2 after objects at the current level have all been instantiated.
        ArrayList<ObjectInstantiation> itemsToRecurseToInPass2 = null;

        // Pass 1: Convert JSON objects in JSONObject items into Java objects
        final int numItems = jsonObject != null ? jsonObject.items.size()
                : jsonArray != null ? jsonArray.items.size() : /* can't happen */ 0;
        for (int i = 0; i < numItems; i++) {
            // Iterate through items of JSONObject or JSONArray (key is null for JSONArray)
            final String itemJsonKey;
            final Object itemJsonValue;
            if (jsonObject != null) {
                final Entry<String, Object> jsonObjectItem = jsonObject.items.get(i);
                itemJsonKey = jsonObjectItem.getKey();
                itemJsonValue = jsonObjectItem.getValue();
            } else if (jsonArray != null) {
                itemJsonKey = null;
                itemJsonValue = jsonArray.items.get(i);
            } else {
                // Can't happen (keep static analyzers happy)
                throw ClassGraphException.newClassGraphException("This exception should not be thrown");
            }
            final boolean itemJsonValueIsJsonObject = itemJsonValue instanceof JSONObject;
            final boolean itemJsonValueIsJsonArray = itemJsonValue instanceof JSONArray;
            final JSONObject itemJsonValueJsonObject = itemJsonValueIsJsonObject ? (JSONObject) itemJsonValue
                    : null;
            final JSONArray itemJsonValueJsonArray = itemJsonValueIsJsonArray ? (JSONArray) itemJsonValue : null;

            // If this is a standard object, look up the field info in the type cache
            FieldTypeInfo fieldTypeInfo;
            if (classFields != null) {
                // Standard objects must interpret the key as a string, since field names are strings.
                // Look up field name directly, using the itemJsonKey string
                fieldTypeInfo = classFields.fieldNameToFieldTypeInfo.get(itemJsonKey);
                if (fieldTypeInfo == null) {
                    throw new IllegalArgumentException("Field " + rawType.getName() + "." + itemJsonKey
                            + " does not exist or is not accessible, non-final, and non-transient");
                }
            } else {
                fieldTypeInfo = null;
            }

            // Standard objects have a different type for each field; arrays have a nested value type;
            // collections and maps have a single common value type for all elements.
            final Type resolvedItemValueType =
                    // For objects, finish resolving partially resolve field types using the set of type
                    // resolutions found by comparing the resolved type of the concrete containing object
                    // with its generic type. (Fields were partially resolved before by substituting type
                    // arguments of subclasses into type variables of superclasses.)
                    fieldTypeInfo != null ? fieldTypeInfo.getFullyResolvedFieldType(typeResolutions)
                            // For arrays, the item type is the array component type
                            : isArray ? arrayComponentType
                                    // For collections and maps, the value type is the same for all items
                                    : commonResolvedValueType;

            // Construct an object of the type needed to hold the value
            final Object instantiatedItemObject;
            if (itemJsonValue == null) {
                // If JSON value is null, no need to recurse to deserialize the value
                instantiatedItemObject = null;

            } else if (resolvedItemValueType == Object.class) {
                // For Object-typed fields, we can only deserialize a JSONObject to Map<Object, Object>
                // or a JSONArray to List<Object>, since we don't have any other type information
                if (itemJsonValueIsJsonObject) {
                    instantiatedItemObject = new HashMap<>();
                    if (itemsToRecurseToInPass2 == null) {
                        itemsToRecurseToInPass2 = new ArrayList<>();
                    }
                    itemsToRecurseToInPass2.add(new ObjectInstantiation(instantiatedItemObject,
                            ParameterizedTypeImpl.MAP_OF_UNKNOWN_TYPE, itemJsonValue));

                } else if (itemJsonValueIsJsonArray) {
                    instantiatedItemObject = new ArrayList<>();
                    if (itemsToRecurseToInPass2 == null) {
                        itemsToRecurseToInPass2 = new ArrayList<>();
                    }
                    itemsToRecurseToInPass2.add(new ObjectInstantiation(instantiatedItemObject,
                            ParameterizedTypeImpl.LIST_OF_UNKNOWN_TYPE, itemJsonValue));

                } else {
                    // Deserialize basic JSON value for assigning to Object-typed field or as Object-typed element
                    instantiatedItemObject = jsonBasicValueToObject(itemJsonValue, resolvedItemValueType,
                            /* convertStringToNumber = */ false);
                }

            } else if (JSONUtils.isBasicValueType(resolvedItemValueType)) {
                // For non-recursive (basic) value types, just convert the values directly.
                if (itemJsonValueIsJsonObject || itemJsonValueIsJsonArray) {
                    throw new IllegalArgumentException(
                            "Got JSONObject or JSONArray type when expecting a simple value type");
                }
                // Deserialize basic JSON value
                instantiatedItemObject = jsonBasicValueToObject(itemJsonValue, resolvedItemValueType,
                        /* convertStringToNumber = */ false);

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
                    // For other items of recursive type (maps, collections, or general objects),
                    // create an empty object instance for the item
                    if (!itemJsonValueIsJsonObject && !itemJsonValueIsJsonArray) {
                        throw new IllegalArgumentException(
                                "Got simple value type when expecting a JSON object or JSON array");
                    }
                    try {
                        // Call the appropriate constructor for the item, whether its type is array, Collection,
                        // Map or other class type. For collections and Maps, call the size hint constructor
                        // for speed when adding items.
                        final int numSubItems = itemJsonValueJsonObject != null
                                ? itemJsonValueJsonObject.items.size()
                                : itemJsonValueJsonArray != null ? itemJsonValueJsonArray.items.size()
                                        : /* can't happen */ 0;
                        if ((resolvedItemValueType instanceof Class<?>
                                && ((Class<?>) resolvedItemValueType).isArray())) {
                            // Instantiate inner array with same number of items as the inner JSONArray
                            if (!itemJsonValueIsJsonArray) {
                                throw new IllegalArgumentException(
                                        "Expected JSONArray, got " + itemJsonValue.getClass().getName());
                            }
                            instantiatedItemObject = Array.newInstance(
                                    ((Class<?>) resolvedItemValueType).getComponentType(), numSubItems);
                        } else {
                            // For maps and collections, all the elements are of the same type
                            if (isCollection || isMap || is1DArray) {
                                // Instantiate a Map or Collection, with a size hint if possible
                                instantiatedItemObject = commonValueConstructorWithSizeHint != null
                                        // Instantiate collection or map with size hint
                                        ? commonValueConstructorWithSizeHint.newInstance(numSubItems)
                                        // Instantiate other object types
                                        : commonValueDefaultConstructor != null
                                                ? commonValueDefaultConstructor.newInstance()
                                                : /* can't happen */ null;
                            } else if (fieldTypeInfo != null) {
                                // For object types, each field has its own constructor, and the constructor can
                                // vary if the field type is completely generic (e.g. "T field").
                                final Constructor<?> valueConstructorWithSizeHint = fieldTypeInfo
                                        .getConstructorForFieldTypeWithSizeHint(resolvedItemValueType,
                                                classFieldCache);
                                if (valueConstructorWithSizeHint != null) {
                                    instantiatedItemObject = valueConstructorWithSizeHint.newInstance(numSubItems);
                                } else {
                                    instantiatedItemObject = fieldTypeInfo.getDefaultConstructorForFieldType(
                                            resolvedItemValueType, classFieldCache).newInstance();
                                }
                            } else if (isArray && !is1DArray) {
                                // Construct next innermost array for an array of 2+ dimensions
                                instantiatedItemObject = Array.newInstance(rawType.getComponentType(), numSubItems);

                            } else {
                                throw new IllegalArgumentException("Got illegal type");
                            }
                        }
                    } catch (final ReflectiveOperationException | SecurityException e) {
                        throw new IllegalArgumentException("Could not instantiate type " + resolvedItemValueType,
                                e);
                    }

                    // Look up any id field in the object (it will be the first field), and if present,
                    // add it to the idToObjectInstance map, so that it is available before recursing 
                    // into any sibling objects.
                    if (itemJsonValue instanceof JSONObject) {
                        final JSONObject itemJsonObject = (JSONObject) itemJsonValue;
                        if (itemJsonObject.objectId != null) {
                            idToObjectInstance.put(itemJsonObject.objectId, instantiatedItemObject);
                        }
                    }

                    // Defer recursing into items
                    if (itemsToRecurseToInPass2 == null) {
                        itemsToRecurseToInPass2 = new ArrayList<>();
                    }
                    itemsToRecurseToInPass2.add(
                            new ObjectInstantiation(instantiatedItemObject, resolvedItemValueType, itemJsonValue));
                }
            }

            // Add instantiated items to parent object
            if (fieldTypeInfo != null) {
                fieldTypeInfo.setFieldValue(objectInstance, instantiatedItemObject);
            } else if (mapInstance != null) {
                // For maps, key type should be deserialized from strings, to support e.g. Integer as a key type.
                // This only works for basic object types though (String, Integer, Enum, etc.)
                final Object mapKey = jsonBasicValueToObject(itemJsonKey, mapKeyType,
                        /* convertStringToNumber = */ true);
                mapInstance.put(mapKey, instantiatedItemObject);
            } else if (isArray) {
                Array.set(objectInstance, i, instantiatedItemObject);
            } else if (collectionInstance != null) {
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
                populateObjectFromJsonObject(i.objectInstance, i.type, i.jsonVal, classFieldCache,
                        idToObjectInstance, collectionElementAdders);
            }
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Set up the initial mapping from id to object, by adding the id of the toplevel object, if it has an id field
     * in JSON.
     *
     * @param objectInstance
     *            the object instance
     * @param parsedJSON
     *            the parsed JSON
     * @return the initial id to object map
     */
    private static Map<CharSequence, Object> getInitialIdToObjectMap(final Object objectInstance,
            final Object parsedJSON) {
        final Map<CharSequence, Object> idToObjectInstance = new HashMap<>();
        if (parsedJSON instanceof JSONObject) {
            final JSONObject itemJsonObject = (JSONObject) parsedJSON;
            if (!itemJsonObject.items.isEmpty()) {
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
     * @param <T>
     *            the expected type
     * @param expectedType
     *            The type that the JSON should conform to.
     * @param json
     *            the JSON string to deserialize.
     * @param classFieldCache
     *            The class field cache. Reusing this cache will increase the speed if many JSON documents of the
     *            same type need to be parsed.
     * @return The object graph after deserialization.
     * @throws IllegalArgumentException
     *             If anything goes wrong during deserialization.
     */
    private static <T> T deserializeObject(final Class<T> expectedType, final String json,
            final ClassFieldCache classFieldCache) throws IllegalArgumentException {
        // Parse the JSON
        Object parsedJSON;
        try {
            parsedJSON = JSONParser.parseJSON(json);
        } catch (final ParseException e) {
            throw new IllegalArgumentException("Could not parse JSON", e);
        }

        T objectInstance;
        try {
            // Construct an object of the expected type
            final Constructor<?> constructor = classFieldCache.getDefaultConstructorForConcreteTypeOf(expectedType);
            @SuppressWarnings("unchecked")
            final T newInstance = (T) constructor.newInstance();
            objectInstance = newInstance;
        } catch (final ReflectiveOperationException | SecurityException e) {
            throw new IllegalArgumentException("Could not construct object of type " + expectedType.getName(), e);
        }

        // Populate the object from the parsed JSON
        final List<Runnable> collectionElementAdders = new ArrayList<>();
        populateObjectFromJsonObject(objectInstance, expectedType, parsedJSON, classFieldCache,
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
     * @param <T>
     *            The type that the JSON should conform to.
     * @param expectedType
     *            The class reference for the type that the JSON should conform to.
     * @param json
     *            the JSON string to deserialize.
     * @return The object graph after deserialization.
     * @throws IllegalArgumentException
     *             If anything goes wrong during deserialization.
     */
    public static <T> T deserializeObject(final Class<T> expectedType, final String json)
            throws IllegalArgumentException {
        final ClassFieldCache classFieldCache = new ClassFieldCache(/* resolveTypes = */ true,
                /* onlySerializePublicFields = */ false);
        return deserializeObject(expectedType, json, classFieldCache);
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
     * @param classFieldCache
     *            The class field cache. Reusing this cache will increase the speed if many JSON documents of the
     *            same type need to be parsed.
     * @throws IllegalArgumentException
     *             If anything goes wrong during deserialization.
     */
    public static void deserializeToField(final Object containingObject, final String fieldName, final String json,
            final ClassFieldCache classFieldCache) throws IllegalArgumentException {
        if (containingObject == null) {
            throw new IllegalArgumentException("Cannot deserialize to a field of a null object");
        }

        // Parse the JSON
        Object parsedJSON;
        try {
            parsedJSON = JSONParser.parseJSON(json);
        } catch (final ParseException e) {
            throw new IllegalArgumentException("Could not parse JSON", e);
        }

        // Create a JSONObject with one field of the requested name, and deserialize that into the requested object
        final JSONObject wrapperJsonObj = new JSONObject(1);
        wrapperJsonObj.items.add(new SimpleEntry<>(fieldName, parsedJSON));

        // Populate the object field
        // (no need to call getInitialIdToObjectMap(), since toplevel object is a wrapper, which doesn't have an id)
        final List<Runnable> collectionElementAdders = new ArrayList<>();
        populateObjectFromJsonObject(containingObject, containingObject.getClass(), wrapperJsonObj, classFieldCache,
                new HashMap<CharSequence, Object>(), collectionElementAdders);
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
        final ClassFieldCache typeCache = new ClassFieldCache(/* resolveTypes = */ true,
                /* onlySerializePublicFields = */ false);
        deserializeToField(containingObject, fieldName, json, typeCache);
    }
}
