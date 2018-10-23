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
package io.github.classgraph.json;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.util.Collection;
import java.util.Map;

class FieldTypeInfo {
    /** The field. */
    final Field field;

    /**
     * The type of the field after any concrete type arguments of a specific subclass have been substituted into
     * type parameter variables. There may still be type variables present, if the subclass itself has unresolved
     * type variables.
     */
    private final Type fieldTypePartiallyResolved;

    /** True if the field still has unresolved type variables from the defining subclass. */
    private final boolean hasUnresolvedTypeVariables;

    /**
     * If the type of this field is a type variable, it could be any type, so we need to defer getting and caching
     * the constructor in this case.
     */
    private final boolean isTypeVariable;

    /** The index of the type: 0 for non-primitive type; 1-8 for primitive types. */
    private final int primitiveTypeIdx;

    /**
     * The constructor with int-valued size hint for the type of the field, or null if this is not a Collection or
     * Map.
     */
    private Constructor<?> constructorForFieldTypeWithSizeHint;

    /**
     * The default (no-arg) constructor for the type of the field, or null if this is a primitive field, or if
     * intConstructorForFieldType is non-null.
     */
    private Constructor<?> defaultConstructorForFieldType;

    private static boolean hasTypeVariables(final Type type) {
        if (type instanceof TypeVariable<?> || type instanceof GenericArrayType) {
            return true;
        } else if (type instanceof ParameterizedType) {
            for (final Type arg : ((ParameterizedType) type).getActualTypeArguments()) {
                if (hasTypeVariables(arg)) {
                    return true;
                }
            }
        }
        return false;
    }

    public FieldTypeInfo(final Field field, final Type fieldTypePartiallyResolved,
            final ClassFieldCache classFieldCache) {
        this.field = field;
        this.fieldTypePartiallyResolved = fieldTypePartiallyResolved;
        this.isTypeVariable = fieldTypePartiallyResolved instanceof TypeVariable<?>;
        this.hasUnresolvedTypeVariables = isTypeVariable || hasTypeVariables(fieldTypePartiallyResolved);

        final boolean isArray = fieldTypePartiallyResolved instanceof GenericArrayType
                || (fieldTypePartiallyResolved instanceof Class<?>
                        && ((Class<?>) fieldTypePartiallyResolved).isArray());

        if (isArray || isTypeVariable) {
            this.primitiveTypeIdx = 0;
        } else {
            // Get type index of field, for speed in calling setFieldValue
            final Class<?> fieldRawType = JSONUtils.getRawType(fieldTypePartiallyResolved);
            if (fieldRawType == Integer.TYPE) {
                this.primitiveTypeIdx = 1;
            } else if (fieldRawType == Long.TYPE) {
                this.primitiveTypeIdx = 2;
            } else if (fieldRawType == Short.TYPE) {
                this.primitiveTypeIdx = 3;
            } else if (fieldRawType == Double.TYPE) {
                this.primitiveTypeIdx = 4;
            } else if (fieldRawType == Float.TYPE) {
                this.primitiveTypeIdx = 5;
            } else if (fieldRawType == Boolean.TYPE) {
                this.primitiveTypeIdx = 6;
            } else if (fieldRawType == Byte.TYPE) {
                this.primitiveTypeIdx = 7;
            } else if (fieldRawType == Character.TYPE) {
                this.primitiveTypeIdx = 8;
            } else {
                this.primitiveTypeIdx = 0;
            }
            // Get default constructor for field type, if field is not of basic type, and not an array, and not
            // a type variable
            if (!JSONUtils.isBasicValueType(fieldRawType)) {
                if (Collection.class.isAssignableFrom(fieldRawType) || Map.class.isAssignableFrom(fieldRawType)) {
                    constructorForFieldTypeWithSizeHint = classFieldCache
                            .getConstructorWithSizeHintForConcreteTypeOf(fieldRawType);
                }
                if (constructorForFieldTypeWithSizeHint == null) {
                    defaultConstructorForFieldType = classFieldCache
                            .getDefaultConstructorForConcreteTypeOf(fieldRawType);
                }
            }
        }
    }

    public Constructor<?> getConstructorForFieldTypeWithSizeHint(final Type fieldTypeFullyResolved,
            final ClassFieldCache classFieldCache) {
        if (!isTypeVariable) {
            return constructorForFieldTypeWithSizeHint;
        } else {
            final Class<?> fieldRawTypeFullyResolved = JSONUtils.getRawType(fieldTypeFullyResolved);
            if (!Collection.class.isAssignableFrom(fieldRawTypeFullyResolved)
                    && !Map.class.isAssignableFrom(fieldRawTypeFullyResolved)) {
                // Don't call constructor with size hint if this is not a Collection or Map
                // (since the constructor could do anything)
                return null;
            }
            return classFieldCache.getConstructorWithSizeHintForConcreteTypeOf(fieldRawTypeFullyResolved);
        }
    }

    public Constructor<?> getDefaultConstructorForFieldType(final Type fieldTypeFullyResolved,
            final ClassFieldCache classFieldCache) {
        if (!isTypeVariable) {
            return defaultConstructorForFieldType;
        } else {
            final Class<?> fieldRawTypeFullyResolved = JSONUtils.getRawType(fieldTypeFullyResolved);
            return classFieldCache.getDefaultConstructorForConcreteTypeOf(fieldRawTypeFullyResolved);
        }
    }

    public Type getFullyResolvedFieldType(final TypeResolutions typeResolutions) {
        if (!hasUnresolvedTypeVariables) {
            // Fast path -- don't try to resolve type variables if there aren't any to resolve
            return fieldTypePartiallyResolved;
        }
        // Resolve any remaining type variables using type resolutions
        // (N.B. I tried caching this type resolution process using a HashMap, but it was a bit slower
        // than this uncached version, because type resolution is relatively fast in most cases.)
        return typeResolutions.resolveTypeVariables(fieldTypePartiallyResolved);
    }

    /** Set the field's value, appropriately handling primitive-typed fields. */
    void setFieldValue(final Object containingObj, final Object value) {
        try {
            if (primitiveTypeIdx == 0) {
                field.set(containingObj, value);
            } else {
                if (value == null) {
                    throw new IllegalArgumentException("Tried to set primitive-typed field to null value");
                }
                switch (primitiveTypeIdx) {
                case 1:
                    if (!(value instanceof Integer)) {
                        throw new IllegalArgumentException(
                                "Expected value of type Integer; got " + value.getClass().getName());
                    }
                    field.setInt(containingObj, ((Integer) value).intValue());
                    break;
                case 2:
                    if (!(value instanceof Long)) {
                        throw new IllegalArgumentException(
                                "Expected value of type Long; got " + value.getClass().getName());
                    }
                    field.setLong(containingObj, ((Long) value).longValue());
                    break;
                case 3:
                    if (!(value instanceof Short)) {
                        throw new IllegalArgumentException(
                                "Expected value of type Short; got " + value.getClass().getName());
                    }
                    field.setShort(containingObj, ((Short) value).shortValue());
                    break;
                case 4:
                    if (!(value instanceof Double)) {
                        throw new IllegalArgumentException(
                                "Expected value of type Double; got " + value.getClass().getName());
                    }
                    field.setDouble(containingObj, ((Double) value).doubleValue());
                    break;
                case 5:
                    if (!(value instanceof Float)) {
                        throw new IllegalArgumentException(
                                "Expected value of type Float; got " + value.getClass().getName());
                    }
                    field.setFloat(containingObj, ((Float) value).floatValue());
                    break;
                case 6:
                    if (!(value instanceof Boolean)) {
                        throw new IllegalArgumentException(
                                "Expected value of type Boolean; got " + value.getClass().getName());
                    }
                    field.setBoolean(containingObj, ((Boolean) value).booleanValue());
                    break;
                case 7:
                    if (!(value instanceof Byte)) {
                        throw new IllegalArgumentException(
                                "Expected value of type Byte; got " + value.getClass().getName());
                    }
                    field.setByte(containingObj, ((Byte) value).byteValue());
                    break;
                case 8:
                    if (!(value instanceof Character)) {
                        throw new IllegalArgumentException(
                                "Expected value of type Character; got " + value.getClass().getName());
                    }
                    field.setChar(containingObj, ((Character) value).charValue());
                    break;
                default:
                    throw new IllegalArgumentException();
                }
            }
        } catch (IllegalArgumentException | IllegalAccessException e) {
            throw new IllegalArgumentException(
                    "Could not set field " + field.getDeclaringClass().getName() + "." + field.getName(), e);
        }
    }

    @Override
    public String toString() {
        return fieldTypePartiallyResolved + " " + field.getDeclaringClass().getName() + "."
                + field.getDeclaringClass().getName();
    }
}