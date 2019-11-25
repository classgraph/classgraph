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

import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.github.classgraph.ScanResult;

/**
 * The list of fields that can be (de)serialized (non-final, non-transient, non-synthetic, accessible), and their
 * corresponding resolved (concrete) types.
 */
class ClassFields {
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
    final List<FieldTypeInfo> fieldOrder = new ArrayList<>();

    /** Map from field name to field and resolved type. */
    final Map<String, FieldTypeInfo> fieldNameToFieldTypeInfo = new HashMap<>();

    /** If non-null, this is the field that has an {@link Id} annotation. */
    // TODO: replace this with getter/setter MethodHandles for speed
    Field idField;

    /** Used to sort fields into deterministic order. */
    private static final Comparator<Field> FIELD_NAME_ORDER_COMPARATOR = //
            new Comparator<Field>() {
                @Override
                public int compare(final Field a, final Field b) {
                    return a.getName().compareTo(b.getName());
                }
            };

    /**
     * Used to sort fields into deterministic order for SerializationFormat class (which needs to have "format"
     * field in first position for ClassGraph's serialization format) (#383).
     */
    private static final Comparator<Field> SERIALIZATION_FORMAT_FIELD_NAME_ORDER_COMPARATOR = //
            new Comparator<Field>() {
                @Override
                public int compare(final Field a, final Field b) {
                    return a.getName().equals("format") ? -1
                            : b.getName().equals("format") ? 1 : a.getName().compareTo(b.getName());
                }
            };

    /** The name of the SerializationFormat class (used by ClassGraph to serialize a ScanResult). */
    private static final String SERIALIZATION_FORMAT_CLASS_NAME = ScanResult.class.getName()
            + "$SerializationFormat";

    /**
     * Constructor.
     *
     * @param cls
     *            the class
     * @param resolveTypes
     *            whether to resolve types
     * @param onlySerializePublicFields
     *            whether to only serialize public fields
     * @param classFieldCache
     *            the class field cache
     */
    public ClassFields(final Class<?> cls, final boolean resolveTypes, final boolean onlySerializePublicFields,
            final ClassFieldCache classFieldCache) {

        // Find declared accessible fields in all superclasses, and resolve generic types
        final Set<String> visibleFieldNames = new HashSet<>();
        final List<List<FieldTypeInfo>> fieldSuperclassReversedOrder = new ArrayList<>();
        TypeResolutions currTypeResolutions = null;
        for (Type currType = cls; currType != Object.class && currType != null;) {
            Class<?> currRawType;
            ParameterizedType currParameterizedType;
            if (currType instanceof ParameterizedType) {
                currParameterizedType = (ParameterizedType) currType;
                currRawType = (Class<?>) currParameterizedType.getRawType();
            } else if (currType instanceof Class<?>) {
                currRawType = (Class<?>) currType;
            } else {
                // Class definitions should not be of type WildcardType or GenericArrayType 
                throw new IllegalArgumentException("Illegal class type: " + currType);
            }

            // getDeclaredFields() does not guarantee any given order, so need to sort fields. (#383)
            final Field[] fields = currRawType.getDeclaredFields();
            Arrays.sort(fields, cls.getName().equals(SERIALIZATION_FORMAT_CLASS_NAME)
                    // Special sort order for SerializationFormat class: put "format" field first
                    ? SERIALIZATION_FORMAT_FIELD_NAME_ORDER_COMPARATOR
                    // Otherwise just sort by name so that order is deterministic
                    : FIELD_NAME_ORDER_COMPARATOR);

            // Find any @Id-annotated field, and get Field type info
            final List<FieldTypeInfo> fieldOrderWithinClass = new ArrayList<>();
            for (final Field field : fields) {
                // Mask superclass fields if subclass has a field of the same name
                if (visibleFieldNames.add(field.getName())) {
                    // Check for @Id annotation
                    final boolean isIdField = field.isAnnotationPresent(Id.class);
                    if (isIdField) {
                        if (idField != null) {
                            throw new IllegalArgumentException(
                                    "More than one @Id annotation: " + idField.getDeclaringClass() + "." + idField
                                            + " ; " + currRawType.getName() + "." + field.getName());
                        }
                        idField = field;
                    }

                    if (JSONUtils.fieldIsSerializable(field, onlySerializePublicFields)) {
                        // Resolve field type variables, if any, using the current type resolutions. This will
                        // completely resolve some types (in the superclass), if the subclass extends a concrete
                        // version of a generic superclass, but it will only partially resolve variables in
                        // superclasses in general.
                        final Type fieldGenericType = field.getGenericType();
                        final Type fieldTypePartiallyResolved = currTypeResolutions != null && resolveTypes
                                ? currTypeResolutions.resolveTypeVariables(fieldGenericType)
                                : fieldGenericType;

                        // Save field and its partially resolved type
                        final FieldTypeInfo fieldTypeInfo = new FieldTypeInfo(field, fieldTypePartiallyResolved,
                                classFieldCache);
                        fieldNameToFieldTypeInfo.put(field.getName(), fieldTypeInfo);
                        fieldOrderWithinClass.add(fieldTypeInfo);

                    } else if (isIdField) {
                        throw new IllegalArgumentException(
                                "@Id annotation field must be accessible, final, and non-transient: "
                                        + currRawType.getName() + "." + field.getName());
                    }
                }
            }
            // Save fields group in the order they were defined in the class, but in reverse order of superclasses
            fieldSuperclassReversedOrder.add(fieldOrderWithinClass);

            // Move up to superclass, resolving superclass type variables using current class' type resolutions
            // e.g. if the current resolutions list is { T => Integer }, and the current class is C<T>, all fields
            // of type T were resolved above to type Integer. If C<T> extends B<T>, then resolve B<T> to B<Integer>,
            // and look up B's own generic type to produce the list of resolutions for fields in B (e.g. if B is
            // defined as B<V>, then after resolving B<T> to B<Integer>, we can produce a new list of resolutions,
            // { V => Integer } ). 
            final Type genericSuperType = currRawType.getGenericSuperclass();
            if (resolveTypes) {
                if (genericSuperType instanceof ParameterizedType) {
                    // Resolve TypeVariables in the generic supertype of the class, using the current type resolutions
                    final Type resolvedSupertype = currTypeResolutions == null ? genericSuperType
                            : currTypeResolutions.resolveTypeVariables(genericSuperType);

                    // Produce new type resolutions for the superclass, by comparing its concrete to its generic type 
                    currTypeResolutions = resolvedSupertype instanceof ParameterizedType
                            ? new TypeResolutions((ParameterizedType) resolvedSupertype)
                            : null;

                    // Iterate to superclass
                    currType = resolvedSupertype;

                } else if (genericSuperType instanceof Class<?>) {
                    // In the case of a raw class, the generic supertype may already have resolved type variables,
                    // e.g. "class A extends B<Integer>"
                    currType = genericSuperType;
                    currTypeResolutions = null;

                } else {
                    throw new IllegalArgumentException("Got unexpected supertype " + genericSuperType);
                }
            } else {
                // If not resolving types, just move up to supertype
                currType = genericSuperType;
            }
        }
        // Reverse the order of field visibility, so that ancestral superclass fields appear top-down, in field
        // definition order (if not masked by same-named fields in subclasses), followed by fields in sublcasses.
        for (int i = fieldSuperclassReversedOrder.size() - 1; i >= 0; i--) {
            final List<FieldTypeInfo> fieldGroupingForClass = fieldSuperclassReversedOrder.get(i);
            fieldOrder.addAll(fieldGroupingForClass);
        }
    }
}