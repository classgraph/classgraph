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
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.util.Map;

import io.github.lukehutch.fastclasspathscanner.utils.Parser;
import io.github.lukehutch.fastclasspathscanner.utils.Parser.ParseException;

/**
 * Fast, lightweight Java object to JSON serializer, and JSON to Java object deserializer. Handles cycles in the
 * object graph by inserting reference ids.
 */
class JSONDeserializer {

//    private static Object jsonValToObject(final Object jsonVal, final Type expectedResolvedType,
//            final TypeCache typeCache) throws ParseException {
//
//        // TODO -- up to here
//
//        Class<?> rawType;
//        Type[] typeArguments;
//        TypeVariable<?>[] typeParameters;
//        TypeVariableToResolvedTypeList typeResolutions;
//        if (expectedResolvedType instanceof Class<?>) {
//            rawType = (Class<?>) expectedResolvedType;
//            typeArguments = null;
//            typeParameters = null;
//            typeResolutions = null;
//        } else if (expectedResolvedType instanceof ParameterizedType) {
//            // Get mapping from type variables to resolved types, by comparing the concrete type arguments
//            // of the expected type to its type arguments
//            final ParameterizedType parameterizedType = (ParameterizedType) expectedResolvedType;
//            rawType = (Class<?>) parameterizedType.getRawType();
//            typeArguments = parameterizedType.getActualTypeArguments();
//            typeParameters = rawType.getTypeParameters();
//            if (typeArguments.length != typeParameters.length) {
//                throw new IllegalArgumentException("Type parameter count mismatch");
//            }
//            // Correlate type variables with resolved types
//            typeResolutions = new TypeVariableToResolvedTypeList();
//            for (int i = 0; i < typeArguments.length; i++) {
//                if (!(typeParameters[i] instanceof TypeVariable<?>)) {
//                    throw new IllegalArgumentException("Got illegal type pararameter type: " + typeParameters[i]);
//                }
//                typeResolutions.add(new TypeVariableToResolvedType(typeParameters[i], typeArguments[i]));
//            }
//        } else {
//            throw new IllegalArgumentException("Got illegal type: " + expectedResolvedType);
//        }
//
//        if (Map.class.isAssignableFrom(rawType)) {
//            // Special handling for maps
//            if (typeResolutions.size() != 2) {
//                throw new IllegalArgumentException(
//                        "Wrong number of type parameters for map: got " + typeResolutions.size() + "; expected 2");
//            }
//            final Type keyType = typeResolutions.get(0).resolvedType;
//            final Type valType = typeResolutions.get(1).resolvedType;
//
//            // TODO: make sure key type can be de-serialized for non-string keys (e.g. Integer keys)
//            // TODO: check for array value types
//
//        } else {
//            // Deserialize a general object
//            Object objectInstance = null;
//            try {
//                objectInstance = rawType.getDeclaredConstructor().newInstance();
//            } catch (final Exception e) {
//                throw new IllegalArgumentException("Cannot call default constructor for class " + rawType, e);
//            }
//
//            final TypeResolvedFieldsForClass resolvedFields = typeCache.getResolvedFields(expectedResolvedType,
//                    typeResolutions, /* onlySerializePublicFields = */ false);
//
//            for (final FieldResolvedTypeInfo fieldTypeInfo : resolvedFields.fieldOrder) {
//                final Field field = fieldTypeInfo.field;
//                final Type resolvedFieldType = fieldTypeInfo.resolvedFieldType;
//
//                final Object fieldObject = field.get(obj);
//
//                // TODO: set field in obj
//
//                jsonValToObject(jsonFieldObject, resolvedFieldType, typeCache);
//            }
//        }
//    }
//
//    private static Object parseJSON(final Parser parser, final Type expectedType, final TypeCache typeCache)
//            throws ParseException {
//        Class<?> rawType;
//        if (expectedType instanceof ParameterizedType) {
//            rawType = (Class<?>) ((ParameterizedType) expectedType).getRawType();
//        } else if (expectedType instanceof Class<?>) {
//            rawType = (Class<?>) expectedType;
//        } else if (expectedType instanceof TypeVariable<?>) {
//            throw new RuntimeException("Cannot deserialize to generic type variable " + expectedType);
//        } else {
//            throw new RuntimeException("Illegal expected type: " + expectedType);
//        }
//        if (JSONUtils.isCollectionOrArray(rawType)) {
//            parseJSONArray(parser, expectedType, typeCache);
//        } else if (JSONUtils.isBasicValueType(rawType)) {
//            parseJSONBasicValue(parser, expectedType, typeCache);
//        } else {
//            jsonValToObject(parser, expectedType, typeCache);
//        }
//    }
//
//    static Object deserializeClass(final Class<?> expectedType, final String json) throws IllegalArgumentException {
//        try {
//            return jsonValToObject(JSONParser.parseJSON(json), expectedType, new TypeCache());
//        } catch (final Exception e) {
//            throw new IllegalArgumentException("Could not parse JSON", e);
//        }
//    }
//
//    static void deserializeAndSetField(final Object containingObject, final String fieldName, final String json)
//            throws IllegalArgumentException {
//        final TypeCache typeCache = new TypeCache();
//        final FieldResolvedTypeInfo fieldResolvedTypeInfo = typeCache.getResolvedFields(containingObject.getClass(),
//                /* typeResolutions = */ null, /* onlySerializePublicFields = */ false).fieldNameToResolvedTypeInfo
//                        .get(fieldName);
//        if (fieldResolvedTypeInfo == null) {
//            throw new IllegalArgumentException("Class " + containingObject.getClass().getName()
//                    + " does not have a field named \"" + fieldName + "\"");
//        }
//        if (!TypeCache.fieldIsSerializable(fieldResolvedTypeInfo.field, /* onlySerializePublicFields = */ false)) {
//            throw new IllegalArgumentException("Field " + containingObject.getClass().getName() + "." + fieldName
//                    + " needs to be accessible, non-transient, and non-final");
//        }
//        try {
//            final Parser parser = new Parser(json);
//            final Object fieldValue = jsonValToObject(JSONParser.parseJSON(json),
//                    fieldResolvedTypeInfo.resolvedFieldType, typeCache);
//            JSONUtils.setFieldValue(fieldResolvedTypeInfo.field, containingObject, fieldValue);
//        } catch (final Exception e) {
//            throw new IllegalArgumentException("Could not parse JSON", e);
//        }
//    }
}
