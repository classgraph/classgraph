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
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;

public class TypeCache {
    final Map<Class<?>, Map<TypeVariableToResolvedTypeList, TypeResolvedFieldsForClass>> //
    typeToTypeResolutionsToTypeResolvedFields = new HashMap<>();

    void print() {
        for (final Entry<Class<?>, Map<TypeVariableToResolvedTypeList, TypeResolvedFieldsForClass>> //
        typeResolutionsToTypeResolvedFields : typeToTypeResolutionsToTypeResolvedFields.entrySet()) {
            System.out.println(typeResolutionsToTypeResolvedFields.getKey().getName());
            for (final Entry<TypeVariableToResolvedTypeList, TypeResolvedFieldsForClass> //
            typeResolutionEnt : typeResolutionsToTypeResolvedFields.getValue().entrySet()) {
                System.out.println("  TYPE RESOLUTIONS: " + typeResolutionEnt.getKey());
                final TypeResolvedFieldsForClass fields = typeResolutionEnt.getValue();
                for (int i = 0; i < fields.fieldOrder.size(); i++) {
                    final FieldResolvedTypeInfo fieldInfo = fields.fieldOrder.get(i);
                    final Field f = fieldInfo.field;
                    System.out.println("    FIELD: " + f);
                    System.out.println("      TYPE: " + f.getType());
                    System.out.println("      RESOLVED TYPE: " + fieldInfo.resolvedFieldType);
                }
            }
        }
    }

    /**
     * For a given resolved type, find the visible and accessible fields, resolve the types of any generically typed
     * fields, and return the resolved fields.
     */
    TypeResolvedFieldsForClass getResolvedFields(final Class<?> rawType,
            final TypeVariableToResolvedTypeList typeResolutions, final boolean onlySerializePublicFields) {

        // Get cached field information for type and type resolutions, if this combination has been requested
        // before, otherwise read and cache fields and field type information
        Map<TypeVariableToResolvedTypeList, TypeResolvedFieldsForClass> typeResolutionsToTypeResolvedFields = //
                typeToTypeResolutionsToTypeResolvedFields.get(rawType);
        if (typeResolutionsToTypeResolvedFields == null) {
            typeToTypeResolutionsToTypeResolvedFields.put(rawType,
                    typeResolutionsToTypeResolvedFields = new HashMap<>());
        }
        TypeResolvedFieldsForClass typeResolvedFields = typeResolutionsToTypeResolvedFields.get(typeResolutions);
        if (typeResolvedFields == null) {
            // Create new cache entry 
            typeResolutionsToTypeResolvedFields.put(typeResolutions,
                    typeResolvedFields = new TypeResolvedFieldsForClass(rawType, typeResolutions,
                            onlySerializePublicFields));
        }
        return typeResolvedFields;
    }
}