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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The list of fields that can be (de)serialized (non-final, non-transient, non-synthetic, accessible), and their
 * corresponding resolved (concrete) types.
 */
class TypeResolvedFieldsForClass {
    /**
     * The list of fields that can be (de)serialized (non-final, non-transient, non-synthetic, accessible), and
     * their corresponding resolved (concrete) types.
     * 
     * <p>
     * For arrays, the {@link Type} will be a {@code Class<?>} reference where {@link Class#isArray()} is true, and
     * {@link Class#getComponentType()} is the element type (the element type will itself be an array-typed
     * {@code Class<?>} reference for multi-dimensional arrays).
     * 
     * <p>
     * For generics, the {@link Type} will be an implementation of {@link ParameterizedType}.
     */
    final List<FieldResolvedTypeInfo> fieldOrder = new ArrayList<>();

    /** Map from field name to field and resolved type. */
    final Map<String, FieldResolvedTypeInfo> fieldNameToResolvedTypeInfo = new HashMap<>();

    /** If non-null, this is the field that has an {@link Id} annotation. */
    Field idField;

    public TypeResolvedFieldsForClass(final Type type, final TypeVariableToResolvedTypeList typeResolutions,
            final boolean onlySerializePublicFields) {

        // Find declared accessible fields in all superclasses, and resolve generic types
        final Set<String> visibleFieldNames = new HashSet<>();
        final List<List<FieldResolvedTypeInfo>> fieldGroupingReversedOrder = new ArrayList<>();
        TypeVariableToResolvedTypeList currTypeResolutions = typeResolutions;
        for (Type currType = type, superClsType; currType != Object.class
                && currType != null; currType = superClsType) {
            Class<?> currCls;
            if (currType instanceof ParameterizedType) {
                final ParameterizedType superclassParameterizedType = (ParameterizedType) currType;

                // Resolve superclass type arguments using subclass type variable definitions
                final Type[] typeArgs = superclassParameterizedType.getActualTypeArguments();
                final Type[] resolvedTypeArgs = new Type[typeArgs.length];
                for (int i = 0; i < typeArgs.length; i++) {
                    resolvedTypeArgs[i] = TypeVariableToResolvedTypeList.resolveTypeVariables(typeArgs[i],
                            currTypeResolutions);
                }
                final TypeVariable<?>[] classTypeParams = ((Class<?>) superclassParameterizedType.getRawType())
                        .getTypeParameters();

                // Create new mappings between superclass type variables and resolved types
                if (classTypeParams.length != resolvedTypeArgs.length) {
                    throw new IllegalArgumentException("Parameter length mismatch");
                }
                final TypeVariableToResolvedTypeList superclassTypeResolutions //
                        = new TypeVariableToResolvedTypeList(typeArgs.length);
                for (int i = 0; i < typeArgs.length; i++) {
                    superclassTypeResolutions
                            .add(new TypeVariableToResolvedType(classTypeParams[i], resolvedTypeArgs[i]));
                }

                // Iterate to superclass
                currTypeResolutions = superclassTypeResolutions;
                currCls = (Class<?>) superclassParameterizedType.getRawType();
                superClsType = currCls.getGenericSuperclass();

            } else if (currType instanceof Class<?>) {
                currCls = (Class<?>) currType;
                superClsType = currCls.getGenericSuperclass();
            } else {
                throw new IllegalArgumentException("Got illegal superclass type: " + currType);
            }
            final Field[] fields = currCls.getDeclaredFields();
            final List<FieldResolvedTypeInfo> fieldGroupingForClass = new ArrayList<>();
            for (int i = 0; i < fields.length; i++) {
                final Field field = fields[i];
                // Mask superclass fields if subclass has a field of the same name
                if (visibleFieldNames.add(field.getName())) {
                    // Check for @Id annotation
                    final boolean isIdField = field.isAnnotationPresent(Id.class);
                    if (isIdField) {
                        if (idField != null) {
                            throw new IllegalArgumentException(
                                    "More than one @Id annotation: " + idField.getDeclaringClass() + "." + idField
                                            + " ; " + currCls.getName() + "." + field.getName());
                        }
                        idField = field;
                    }

                    if (JSONUtils.fieldIsSerializable(field, onlySerializePublicFields)) {
                        // Resolve field type variables, if any
                        final Type fieldGenericType = field.getGenericType();
                        final Type fieldTypeResolved = fieldGenericType instanceof TypeVariable<?>
                                || fieldGenericType instanceof ParameterizedType
                                        ? TypeVariableToResolvedTypeList.resolveTypeVariables(fieldGenericType,
                                                currTypeResolutions)
                                        : fieldGenericType;

                        // Save field and its resolved type
                        final FieldResolvedTypeInfo resolvedFieldTypeInfo = new FieldResolvedTypeInfo(field,
                                fieldTypeResolved);
                        fieldNameToResolvedTypeInfo.put(field.getName(), resolvedFieldTypeInfo);
                        fieldGroupingForClass.add(resolvedFieldTypeInfo);

                    } else if (isIdField) {
                        throw new IllegalArgumentException(
                                "@Id annotation field must be accessible, final, and non-transient: "
                                        + currCls.getName() + "." + field.getName());
                    }
                }
            }
            fieldGroupingReversedOrder.add(fieldGroupingForClass);
        }
        // Reverse the order of field visibility, so that ancestral superclass fields appear top-down, in field
        // definition order (if not masked by same-named fields in subclasses), followed by fields in sublcasses.
        for (int i = fieldGroupingReversedOrder.size() - 1; i >= 0; i--) {
            final List<FieldResolvedTypeInfo> fieldGroupingForClass = fieldGroupingReversedOrder.get(i);
            fieldOrder.addAll(fieldGroupingForClass);
        }
    }
}