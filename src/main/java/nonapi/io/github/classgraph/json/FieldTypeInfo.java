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

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.util.Collection;
import java.util.Map;

/**
 * Information on the type of a field.
 */
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
    private final PrimitiveType primitiveType;

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

    /**
     * The Enum PrimitiveType.
     */
    private enum PrimitiveType {
        /** Non-primitive type. */
        NON_PRIMITIVE,
        /** Integer type. */
        INTEGER,
        /** Long type. */
        LONG,
        /** Short type. */
        SHORT,
        /** Double type. */
        DOUBLE,
        /** Float type. */
        FLOAT,
        /** Boolean type. */
        BOOLEAN,
        /** Byte type. */
        BYTE,
        /** Character type. */
        CHARACTER,
        /** Class reference */
        CLASS_REF;
    }

    /**
     * Check if the type has type variables.
     *
     * @param type
     *            the type
     * @return true if the type has type variables.
     */
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

    /**
     * Constructor.
     *
     * @param field
     *            the field
     * @param fieldTypePartiallyResolved
     *            the field type, partially resolved
     * @param classFieldCache
     *            the class field cache
     */
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
            this.primitiveType = PrimitiveType.NON_PRIMITIVE;
        } else {
            // Get type index of field, for speed in calling setFieldValue
            final Class<?> fieldRawType = JSONUtils.getRawType(fieldTypePartiallyResolved);
            if (fieldRawType == Integer.TYPE) {
                this.primitiveType = PrimitiveType.INTEGER;
            } else if (fieldRawType == Long.TYPE) {
                this.primitiveType = PrimitiveType.LONG;
            } else if (fieldRawType == Short.TYPE) {
                this.primitiveType = PrimitiveType.SHORT;
            } else if (fieldRawType == Double.TYPE) {
                this.primitiveType = PrimitiveType.DOUBLE;
            } else if (fieldRawType == Float.TYPE) {
                this.primitiveType = PrimitiveType.FLOAT;
            } else if (fieldRawType == Boolean.TYPE) {
                this.primitiveType = PrimitiveType.BOOLEAN;
            } else if (fieldRawType == Byte.TYPE) {
                this.primitiveType = PrimitiveType.BYTE;
            } else if (fieldRawType == Character.TYPE) {
                this.primitiveType = PrimitiveType.CHARACTER;
            } else if (fieldRawType == Class.class) {
                this.primitiveType = PrimitiveType.CLASS_REF;
            } else {
                this.primitiveType = PrimitiveType.NON_PRIMITIVE;
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

    /**
     * Get the constructor with size hint for the field type.
     *
     * @param fieldTypeFullyResolved
     *            the field type
     * @param classFieldCache
     *            the class field cache
     * @return the constructor with size hint for the field type
     */
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

    /**
     * Get the default constructor for the field type.
     *
     * @param fieldTypeFullyResolved
     *            the field type
     * @param classFieldCache
     *            the class field cache
     * @return the default constructor for the field type
     */
    public Constructor<?> getDefaultConstructorForFieldType(final Type fieldTypeFullyResolved,
            final ClassFieldCache classFieldCache) {
        if (!isTypeVariable) {
            return defaultConstructorForFieldType;
        } else {
            final Class<?> fieldRawTypeFullyResolved = JSONUtils.getRawType(fieldTypeFullyResolved);
            return classFieldCache.getDefaultConstructorForConcreteTypeOf(fieldRawTypeFullyResolved);
        }
    }

    /**
     * Get the fully resolved field type.
     *
     * @param typeResolutions
     *            the type resolutions
     * @return the fully resolved field type
     */
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

    /**
     * Set the field's value, appropriately handling primitive-typed fields.
     *
     * @param containingObj
     *            the containing object
     * @param value
     *            the field value
     */
    void setFieldValue(final Object containingObj, final Object value) {
        try {
            if (value == null) {
                if (primitiveType != PrimitiveType.NON_PRIMITIVE) {
                    throw new IllegalArgumentException("Tried to set primitive-typed field "
                            + field.getDeclaringClass().getName() + "." + field.getName() + " to null value");
                }
                field.set(containingObj, null);
                return;
            }
            switch (primitiveType) {
            case NON_PRIMITIVE:
                field.set(containingObj, value);
                break;
            case CLASS_REF:
                if (!(value instanceof Class)) {
                    throw new IllegalArgumentException(
                            "Expected value of type Class<?>; got " + value.getClass().getName());
                }
                field.set(containingObj, value);
                break;
            case INTEGER:
                if (!(value instanceof Integer)) {
                    throw new IllegalArgumentException(
                            "Expected value of type Integer; got " + value.getClass().getName());
                }
                field.setInt(containingObj, (Integer) value);
                break;
            case LONG:
                if (!(value instanceof Long)) {
                    throw new IllegalArgumentException(
                            "Expected value of type Long; got " + value.getClass().getName());
                }
                field.setLong(containingObj, (Long) value);
                break;
            case SHORT:
                if (!(value instanceof Short)) {
                    throw new IllegalArgumentException(
                            "Expected value of type Short; got " + value.getClass().getName());
                }
                field.setShort(containingObj, (Short) value);
                break;
            case DOUBLE:
                if (!(value instanceof Double)) {
                    throw new IllegalArgumentException(
                            "Expected value of type Double; got " + value.getClass().getName());
                }
                field.setDouble(containingObj, (Double) value);
                break;
            case FLOAT:
                if (!(value instanceof Float)) {
                    throw new IllegalArgumentException(
                            "Expected value of type Float; got " + value.getClass().getName());
                }
                field.setFloat(containingObj, (Float) value);
                break;
            case BOOLEAN:
                if (!(value instanceof Boolean)) {
                    throw new IllegalArgumentException(
                            "Expected value of type Boolean; got " + value.getClass().getName());
                }
                field.setBoolean(containingObj, (Boolean) value);
                break;
            case BYTE:
                if (!(value instanceof Byte)) {
                    throw new IllegalArgumentException(
                            "Expected value of type Byte; got " + value.getClass().getName());
                }
                field.setByte(containingObj, (Byte) value);
                break;
            case CHARACTER:
                if (!(value instanceof Character)) {
                    throw new IllegalArgumentException(
                            "Expected value of type Character; got " + value.getClass().getName());
                }
                field.setChar(containingObj, (Character) value);
                break;
            default:
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException | IllegalAccessException e) {
            throw new IllegalArgumentException(
                    "Could not set field " + field.getDeclaringClass().getName() + "." + field.getName(), e);
        }
    }

    /* (non-Javadoc)
     * @see java.lang.Object#toString()
     */
    @Override
    public String toString() {
        return fieldTypePartiallyResolved + " " + field.getDeclaringClass().getName() + "."
                + field.getDeclaringClass().getName();
    }
}