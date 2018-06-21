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
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;

import io.github.lukehutch.fastclasspathscanner.utils.Parser;
import io.github.lukehutch.fastclasspathscanner.utils.Parser.ParseException;

/**
 * Fast, lightweight Java object to JSON serializer, and JSON to Java object deserializer. Handles cycles in the
 * object graph by inserting reference ids.
 */
class JSONDeserializer {

//    // JSON PEG grammar: https://github.com/azatoth/PanPG/blob/master/grammars/JSON.peg
//
//    private static Entry<String, Object> parseKV(final Parser parser) {
//        // TODO Auto-generated method stub
//        return null;
//    }
//
//    // -------------------------------------------------------------------------------------------------------------
//
//    private static <T> T parseJSONObject(final Parser parser, final Class<T> classType,
//            final Map<Class<?>, SerializableFieldInfo> classToSerializableFieldInfo) throws ParseException {
//        parser.expect('{');
//        T obj = null;
//        try {
//            obj = classType.getDeclaredConstructor().newInstance();
//        } catch (final Exception e) {
//            throw new IllegalArgumentException("Cannot instantiate class " + classType, e);
//        }
//        final SerializableFieldInfo serializableFieldInfo = JSONUtils.getSerializableFieldInfo(classType,
//                classToSerializableFieldInfo);
//
//        boolean first = true;
//        for (Entry<String, Object> kv = parseKV(parser); kv != null; kv = parseKV(parser)) {
//            final Field f; // TODO
//
//            if (first) {
//                first = false;
//            } else {
//                if (parser.peek() == ',') {
//                    parser.expect(',');
//                }
//            }
//        }
//        if (first) {
//            parser.skipWhitespace();
//        }
//        parser.expect('}');
//
//        return null; // TODO
//
//    }
//
//    static <T extends Class<T>> T fromJSONObject(final String json, final T classType) {
//        try {
//            final Parser parser = new Parser(json);
//            final Map<Class<?>, SerializableFieldInfo> classToSerializableFieldInfo = new HashMap<>();
//            return parseJSONObject(parser, classType, classToSerializableFieldInfo);
//        } catch (final ParseException e) {
//            throw new IllegalArgumentException("JSON could not be parsed", e);
//        }
//    }
//
//    // -------------------------------------------------------------------------------------------------------------
//
//    private static Object deserializeClass(final Type type, final Parser parser,
//            final boolean onlySerializePublicFields, final TypeCache typeCache, final int depth)
//            throws IllegalArgumentException, IllegalAccessException, ParseException {
//        parser.expect('{');
//
//        if (obj == null || obj instanceof String || obj instanceof Integer || obj instanceof Boolean
//                || obj instanceof Long || obj instanceof Float || obj instanceof Double || obj instanceof Short
//                || obj instanceof Byte || obj instanceof Character || obj.getClass().isEnum()) {
//            for (int i = 0; i < depth; i++) {
//                System.out.print("  ");
//            }
//            System.out.println(obj == null ? "null" : obj.toString());
//            return;
//        }
//        Class<?> rawType;
//        Type[] typeArguments;
//        TypeVariable<?>[] typeParameters;
//        // TODO: resolve this class' own parameters in terms of its incoming type resolutions 
//        TypeVariableToResolvedTypeList typeResolutions;
//        if (type instanceof Class<?>) {
//            rawType = (Class<?>) type;
//            typeArguments = null;
//            // TODO: need to pick up element type for arrays
//            typeParameters = null;
//            typeResolutions = null;
//        } else if (type instanceof ParameterizedType) {
//            final ParameterizedType parameterizedType = (ParameterizedType) type;
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
//            throw new IllegalArgumentException("Got illegal type: " + type);
//        }
//
//        final TypeResolvedFieldsForClass serializableFieldInfo = typeCache.getResolvedFields(type, typeResolutions,
//                onlySerializePublicFields);
//
//        for (int i = 0; i < depth; i++) {
//            System.out.print("  ");
//        }
//        System.out.println(type.getTypeName());
//
//        for (final FieldResolvedTypeInfo fieldTypeInfo : serializableFieldInfo.fieldOrder) {
//            final Field field = fieldTypeInfo.field;
//            final Type resolvedFieldType = fieldTypeInfo.resolvedFieldType;
//
//            for (int i = 0; i < depth + 1; i++) {
//                System.out.print("  ");
//            }
//            System.out.println("FIELD " + field.getName() + " ## "
//                    + resolvedFieldType /* + " ## " + fieldTypeInfo.typeVariableReplacements */);
//
//            final Object fieldObject = field.get(obj);
//            
//            // TODO: set field in obj
//            
//            deserializeClass(resolvedFieldType, parser, onlySerializePublicFields, typeCache, depth + 2);
//        }
//        
//        parser.expect('}');
//    }
//
//    static Object deserializeClass(final Class<?> cls, final String json, final boolean onlySerializePublicFields)
//            throws IllegalArgumentException {
//        final TypeCache typeCache = new TypeCache();
//        try {
//            Parser parser = new Parser(json);
//            return deserializeClass(cls, parser, onlySerializePublicFields, typeCache, 0);
//        } catch (Exception e) {
//            throw new IllegalArgumentException("Could not parse JSON", e);
//        }
//    }
//
//    static void deserializeAndSetField(final Object containingObject, final String fieldName, String json,
//            final boolean onlySerializePublicFields) throws IllegalArgumentException {
//        final TypeCache typeCache = new TypeCache();
//        final FieldResolvedTypeInfo fieldResolvedTypeInfo = typeCache.getResolvedFields(containingObject.getClass(),
//                /* typeResolutions = */ null, onlySerializePublicFields).fieldNameToResolvedTypeInfo.get(fieldName);
//        if (fieldResolvedTypeInfo == null) {
//            throw new IllegalArgumentException("Class " + containingObject.getClass().getName()
//                    + " does not have a field named \"" + fieldName + "\"");
//        }
//        final Field field = fieldResolvedTypeInfo.field;
//        if (!TypeCache.fieldIsSerializable(field, onlySerializePublicFields)) {
//            throw new IllegalArgumentException("Field " + containingObject.getClass().getName() + "." + fieldName
//                    + " needs to be accessible, non-transient, and non-final");
//        }
//        try {
//            Parser parser = new Parser(json);
//            Object fieldValue = deserializeClass(field.getGenericType(), parser, onlySerializePublicFields,
//                    typeCache, 0);
//            field.set(containingObject, fieldValue);
//        } catch (Exception e) {
//            throw new IllegalArgumentException("Could not parse JSON", e);
//        }
//    }
//
//    //    public static void main(final String[] args) {
//    //        deserializeField(new DD(), "cc", /* onlySerializePublicFields = */ false);
//    //
//    //        System.out.println();
//    //
//    //        deserializeClass(new CC2(2.0f), /* onlySerializePublicFields = */ false);
//    //
//    //        // resolveClass(new BB<>((short) 5)); // Fails as expected, since there is no type parameter context
//    //
//    //        System.out.println();
//    //        System.out.println("Finished.");
//    //    }

}
