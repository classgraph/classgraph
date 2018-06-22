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

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Type;
import java.util.Collection;
import java.util.Map;

class FieldResolvedTypeInfo {
    /** The field. */
    final Field field;

    /**
     * The resolved (concrete) type of the field, after type arguments have been substituted into type parameter
     * variables.
     */
    final Type resolvedFieldType;

    /** The index of the type: 0 for non-primitive type; 1-8 for primitive types. */
    private final int primitiveTypeIdx;

    /**
     * The constructor with int-valued size hint for the type of the field, or null if this is not a Collection or
     * Map.
     */
    Constructor<?> constructorForFieldTypeWithSizeHint;

    /**
     * The default (no-arg) constructor for the type of the field, or null if this is a primitive field, or if
     * intConstructorForFieldType is non-null.
     */
    Constructor<?> defaultConstructorForFieldType;

    public FieldResolvedTypeInfo(final Field field, final Type resolvedType) {
        this.field = field;
        this.resolvedFieldType = resolvedType;

        // Get type index of field, for speed in calling setFieldValue
        final Class<?> fieldRawType = field.getType();
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

        // Get default constructor for field type, if field is not of primitive type, and not an array
        if (primitiveTypeIdx == 0 && !fieldRawType.isArray()) {
            if (Collection.class.isAssignableFrom(fieldRawType) || Map.class.isAssignableFrom(fieldRawType)) {
                constructorForFieldTypeWithSizeHint = JSONUtils
                        .getConstructorWithSizeHintForConcreteType(fieldRawType);
            }
            if (constructorForFieldTypeWithSizeHint == null) {
                defaultConstructorForFieldType = JSONUtils.getDefaultConstructorForConcreteType(fieldRawType);
            }
        }
    }

    /** Set the field's value, appropriately handling primitive-typed fields. */
    void setFieldValue(final Object containingObj, final Object value) {
        if (primitiveTypeIdx != 0 && value == null) {
            throw new IllegalArgumentException("Tried to set primitive-typed field to null value");
        }
        try {
            switch (primitiveTypeIdx) {
            case 0:
                field.set(containingObj, value);
                break;
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
        } catch (IllegalArgumentException | IllegalAccessException e) {
            throw new IllegalArgumentException(
                    "Could not set field " + field.getDeclaringClass().getName() + "." + field.getName(), e);
        }
    }

    @Override
    public String toString() {
        return resolvedFieldType + " " + field.getDeclaringClass().getName() + "."
                + field.getDeclaringClass().getName();
    }
}