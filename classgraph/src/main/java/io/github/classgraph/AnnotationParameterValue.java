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
 * Copyright (c) 2026 Luke Hutchison
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
package io.github.classgraph;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.jspecify.annotations.Nullable;

import io.github.classgraph.base.LogNode;
import io.github.classgraph.base.internal.utils.StringUtils;

/**
 * The name and value of an annotation parameter.
 */
public final class AnnotationParameterValue extends ScanResultObject
        implements HasName, Comparable<AnnotationParameterValue> {
    /**
     * The array element types that an {@code Object[]} annotation parameter value holding boxed values can be
     * converted to.
     */
    private static final Map<String, Class<?>> ARRAY_ELEMENT_TYPES = Map.of( //
            "int", int.class, //
            "long", long.class, //
            "short", short.class, //
            "char", char.class, //
            "boolean", boolean.class, //
            "byte", byte.class, //
            "float", float.class, //
            "double", double.class, //
            "java.lang.String", String.class);

    /** The parameter name. */
    private final String name;

    /** The parameter value. */
    private volatile @Nullable Object value;

    /**
     * Constructor.
     *
     * @param name
     *            The annotation parameter name.
     * @param value
     *            The annotation parameter value.
     */
    AnnotationParameterValue(final String name, final @Nullable Object value) {
        super();
        this.name = name;
        this.value = value;
    }

    /**
     * Get the annotation parameter name.
     *
     * @return The annotation parameter name.
     */
    @Override
    public String getName() {
        return name;
    }

    /**
     * Get the annotation parameter value.
     *
     * @return The annotation parameter value. May be one of the following types:
     *         <ul>
     *         <li>String for string constants
     *         <li>String[] for arrays of strings
     *         <li>A boxed type, e.g. Integer or Character, for primitive-typed constants
     *         <li>A 1-dimensional primitive-typed array (i.e. int[], long[], short[], char[], byte[], boolean[],
     *         float[], or double[]), for arrays of primitives
     *         <li>A 1-dimensional {@link Object}[] array for array types (and then the array element type may be
     *         one of the types in this list)
     *         <li>{@link AnnotationEnumValue}, for enum constants (this holds the name of the enum class and the
     *         name of the constant)
     *         <li>{@link AnnotationClassRef}, for Class references within annotations (this holds the name of the
     *         referenced class)
     *         <li>{@link AnnotationInfo}, for nested annotations
     *         </ul>
     *         Array values are defensively copied on each call. If the annotation class was not scanned and an
     *         array is empty, or if the parameter's declared type does not match the array elements, an
     *         {@code Object[]} array is returned.
     */
    public @Nullable Object getValue() {
        final var val = value;
        if (val != null && val.getClass().isArray()) {
            final var length = Array.getLength(val);
            final var copy = Array.newInstance(val.getClass().getComponentType(), length);
            System.arraycopy(val, 0, copy, 0, length);
            return copy;
        }
        return val;
    }

    // -------------------------------------------------------------------------------------------------------------

    @Override
    protected String getClassName() {
        // getClassInfo() is not valid for this type, so getClassName() does not need to be implemented
        throw new UnsupportedOperationException("getClassName() cannot be called here");
    }

    @Override
    protected ClassInfo getClassInfo() {
        throw new UnsupportedOperationException("getClassInfo() cannot be called here");
    }

    @Override
    void setScanResult(final @Nullable ScanResult scanResult) {
        super.setScanResult(scanResult);
        setScanResult(value, scanResult);
    }

    /**
     * Set the {@link ScanResult} of an annotation parameter value, and of the elements of an array-typed value.
     *
     * @param value
     *            the annotation parameter value
     * @param scanResult
     *            the {@link ScanResult}
     */
    private static void setScanResult(final @Nullable Object value, final @Nullable ScanResult scanResult) {
        if (value instanceof final ScanResultObject scanResultObject) {
            scanResultObject.setScanResult(scanResult);
        } else if (value instanceof final Object[] arrayValue) {
            for (final Object elt : arrayValue) {
                setScanResult(elt, scanResult);
            }
        }
    }

    /**
     * Get {@link ClassInfo} objects for any classes referenced in the annotation parameters.
     *
     * @param classNameToClassInfo
     *            the map to add a {@link ClassInfo} object to, for a referenced class that has none in the scan
     *            result.
     * @param refdClassInfo
     *            the referenced class info
     * @param log
     *            the log node, or null to skip logging
     */
    @Override
    void findReferencedClassInfo(final Map<String, ClassInfo> classNameToClassInfo,
            final Set<ClassInfo> refdClassInfo, final @Nullable LogNode log) {
        findReferencedClassInfo(value, classNameToClassInfo, refdClassInfo, log);
    }

    /**
     * Get {@link ClassInfo} objects for any classes referenced in an annotation parameter value, or in the elements
     * of an array-typed value.
     *
     * @param value
     *            the annotation parameter value
     * @param classNameToClassInfo
     *            the map to add a {@link ClassInfo} object to, for a referenced class that has none in the scan
     *            result.
     * @param refdClassInfo
     *            the referenced class info
     * @param log
     *            the log node, or null to skip logging
     */
    private static void findReferencedClassInfo(final @Nullable Object value,
            final Map<String, ClassInfo> classNameToClassInfo, final Set<ClassInfo> refdClassInfo,
            final @Nullable LogNode log) {
        if (value instanceof final AnnotationClassRef annotationClassRef) {
            final @Nullable ClassInfo classInfo;
            try {
                classInfo = annotationClassRef.getClassInfo();
            } catch (final IllegalArgumentException e) {
                // The type descriptor of the class reference is malformed, so it does not refer to any class
                if (log != null) {
                    log.log("Ignoring malformed class reference in annotation: " + annotationClassRef, e);
                }
                return;
            }
            if (classInfo != null) {
                refdClassInfo.add(classInfo);
            }
        } else if (value instanceof final AnnotationEnumValue annotationEnumValue) {
            annotationEnumValue.findReferencedClassInfo(classNameToClassInfo, refdClassInfo, log);
        } else if (value instanceof final AnnotationInfo annotationInfo) {
            annotationInfo.findReferencedClassInfo(classNameToClassInfo, refdClassInfo, log);
        } else if (value instanceof final Object[] arrayValue) {
            for (final Object elt : arrayValue) {
                findReferencedClassInfo(elt, classNameToClassInfo, refdClassInfo, log);
            }
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Convert {@code Object[]} arrays of boxed primitives or strings to typed arrays.
     *
     * @param annotationClassInfo
     *            the annotation class, or null if the annotation class was not scanned
     */
    void convertWrapperArraysToPrimitiveArrays(final @Nullable ClassInfo annotationClassInfo) {
        final var val = value;
        if (val instanceof final AnnotationInfo annotationInfo) {
            // Recursively convert boxed arrays in nested annotations
            annotationInfo.convertWrapperArraysToPrimitiveArrays();
        } else if (val != null && val.getClass() == Object[].class) {
            final var arrayValue = (Object[]) val;
            for (final Object elt : arrayValue) {
                if (elt instanceof final AnnotationInfo eltAnnotationInfo) {
                    // Recurse
                    eltAnnotationInfo.convertWrapperArraysToPrimitiveArrays();
                }
            }
            final var eltTypeName = getArrayValueTypeName(arrayValue, annotationClassInfo);
            final var eltType = eltTypeName == null ? null : ARRAY_ELEMENT_TYPES.get(eltTypeName);
            if (eltType != null) {
                // The array holds boxed values of a primitive type, or strings -- convert it to an array of that
                // element type
                final var typedArray = Array.newInstance(eltType, arrayValue.length);
                try {
                    for (var i = 0; i < arrayValue.length; i++) {
                        Array.set(typedArray, i, arrayValue[i]);
                    }
                } catch (final IllegalArgumentException _) {
                    // An element cannot be stored in an array of the element type, for example because the
                    // annotation class was recompiled with another element type after the annotated class was
                    // compiled. Like the JDK, which reports a type mismatch only when this value is read, keep the
                    // Object[] array
                    return;
                }
                value = typedArray;
            }
        }
    }

    /**
     * Get the element type name of an array-typed annotation parameter value.
     *
     * @param arrayValue
     *            the array-typed annotation parameter value
     * @param annotationClassInfo
     *            the annotation class, or null if the annotation class was not scanned
     * @return the name of the array element type, or null if the array cannot be converted.
     */
    private @Nullable String getArrayValueTypeName(final Object[] arrayValue,
            final @Nullable ClassInfo annotationClassInfo) {
        // Find the method in the annotation class with the same name as the annotation parameter.
        final var annotationMethodList = annotationClassInfo == null || annotationClassInfo.methodInfo == null
                ? null
                : annotationClassInfo.methodInfo.get(name);
        if (annotationClassInfo != null && annotationMethodList != null && !annotationMethodList.isEmpty()) {
            // The annotation class may have been recompiled after the annotated class was compiled, so that the
            // parameter no longer has a one-dimensional array type, or the annotation class may not be valid
            // (javac cannot write two methods with the same name in an annotation class). The JDK reports such a
            // value as a type mismatch only when it is read, so leave the value unconverted.
            if (annotationMethodList.size() > 1) {
                return null;
            }
            // Get the result type of the method with the same name as the annotation parameter. If its type
            // descriptor does not parse (only a corrupt classfile has such a descriptor), its type is unknown, so
            // leave the value unconverted too.
            final TypeSignature annotationMethodResultTypeSig;
            try {
                annotationMethodResultTypeSig = annotationMethodList.get(0).getTypeSignatureOrTypeDescriptor()
                        .getResultType();
            } catch (final IllegalArgumentException _) {
                return null;
            }
            if (!(annotationMethodResultTypeSig instanceof final ArrayTypeSignature arrayTypeSig)
                    || arrayTypeSig.getNumDimensions() != 1) {
                return null;
            }
            switch (arrayTypeSig.getElementTypeSignature()) {
            case final ClassRefTypeSignature classRefTypeSignature -> {
                // Look up the name of the element type, for non-primitive arrays
                return classRefTypeSignature.getClassName();
            }
            case final BaseTypeSignature baseTypeSignature -> {
                // Look up the name of the primitive class, for primitive arrays
                return baseTypeSignature.getTypeName();
            }
            case ArrayTypeSignature _,TypeVariableSignature _ -> {
                // The innermost element type of an array is not an array, and an annotation parameter cannot have
                // a type variable as its type -- fall through and use Object as the element type
            }
            }
        } else {
            // Could not find a method with this name -- this is an external class. Find first non-null element in
            // the array, and use its type as the element type of the array.
            for (final Object elt : arrayValue) {
                if (elt != null) {
                    // Primitive typed arrays will be turned into arrays of boxed types
                    if (elt instanceof String) {
                        return "java.lang.String";
                    } else if (elt instanceof Integer) {
                        return "int";
                    } else if (elt instanceof Long) {
                        return "long";
                    } else if (elt instanceof Short) {
                        return "short";
                    } else if (elt instanceof Character) {
                        return "char";
                    } else if (elt instanceof Byte) {
                        return "byte";
                    } else if (elt instanceof Boolean) {
                        return "boolean";
                    } else if (elt instanceof Double) {
                        return "double";
                    } else if (elt instanceof Float) {
                        return "float";
                    } else {
                        // The element type could not be determined (the element is an enum value, a class reference
                        // or a nested annotation) -- fall through and use Object as the element type
                        break;
                    }
                }
            }
        }
        // Could not determine the element type -- just use Object
        return "java.lang.Object";
    }

    // -------------------------------------------------------------------------------------------------------------

    @Override
    public int compareTo(final AnnotationParameterValue other) {
        return compareTo(other, new ArrayList<>());
    }

    /**
     * Compare annotation parameter values, tracking already-compared annotation pairs to guard against cycles.
     *
     * @param other
     *            the parameter value to compare with
     * @param comparedPairs
     *            the pairs of annotations compared so far
     * @return a negative integer, zero, or a positive integer as this parameter value sorts before, equal to, or
     *         after the other parameter value
     */
    int compareTo(final AnnotationParameterValue other, final List<AnnotationInfo[]> comparedPairs) {
        if (other == this) {
            return 0;
        }
        final var diff = name.compareTo(other.getName());
        if (diff != 0) {
            return diff;
        }
        // Read each value once, since another thread may replace it with its converted array between two reads
        return compareValues(value, other.value, comparedPairs);
    }

    /**
     * Compare two annotation parameter values.
     *
     * @param v0
     *            the first value, or null.
     * @param v1
     *            the second value, or null.
     * @param comparedPairs
     *            the pairs of annotations compared so far
     * @return the result of the comparison.
     */
    @SuppressWarnings("unchecked")
    private static int compareValues(final @Nullable Object v0, final @Nullable Object v1,
            final List<AnnotationInfo[]> comparedPairs) {
        if (v0 == null || v1 == null) {
            return (v0 == null ? 0 : 1) - (v1 == null ? 0 : 1);
        }
        final var isArray0 = v0.getClass().isArray();
        final var isArray1 = v1.getClass().isArray();
        if (isArray0 && isArray1) {
            final var len0 = Array.getLength(v0);
            final var len1 = Array.getLength(v1);
            for (var i = 0; i < Math.min(len0, len1); i++) {
                final var diff = compareValues(Array.get(v0, i), Array.get(v1, i), comparedPairs);
                if (diff != 0) {
                    return diff;
                }
            }
            return Integer.compare(len0, len1);
        } else if (isArray0 != isArray1) {
            // Arrays sort before other values
            return isArray0 ? -1 : 1;
        } else if (v0.getClass() != v1.getClass()) {
            return v0.getClass().getName().compareTo(v1.getClass().getName());
        } else if (v0 instanceof final AnnotationInfo annotationInfo) {
            return annotationInfo.compareTo((AnnotationInfo) v1, comparedPairs);
        } else if (v0 instanceof Comparable) {
            return ((Comparable<Object>) v0).compareTo(v1);
        } else {
            return v0.equals(v1) ? 0 : v0.toString().compareTo(v1.toString());
        }
    }

    @Override
    public boolean equals(final @Nullable Object obj) {
        if (obj == this) {
            return true;
        }
        if (!(obj instanceof final AnnotationParameterValue other)) {
            return false;
        }
        return this.name.equals(other.name) && valuesEqual(value, other.value);
    }

    /**
     * Check whether two annotation parameter values are equal.
     *
     * @param v0
     *            the first value, or null.
     * @param v1
     *            the second value, or null.
     * @return true if the values are equal.
     */
    private static boolean valuesEqual(final @Nullable Object v0, final @Nullable Object v1) {
        if (v0 != null && v1 != null && v0.getClass() != v1.getClass() && v0.getClass().isArray()
                && v1.getClass().isArray()) {
            return compareValues(v0, v1, new ArrayList<>()) == 0;
        }
        return Objects.deepEquals(v0, v1);
    }

    @Override
    public int hashCode() {
        return name.hashCode() * 31 + hashValue(value);
    }

    /**
     * Compute the hash code of an annotation parameter value.
     *
     * @param val
     *            the value, or null.
     * @return the hash code.
     */
    private static int hashValue(final @Nullable Object val) {
        if (val instanceof final AnnotationInfo annotationInfo) {
            return annotationInfo.getName().hashCode();
        } else if (val != null && val.getClass().isArray()) {
            var h = 1;
            for (int i = 0, n = Array.getLength(val); i < n; i++) {
                h = h * 31 + hashValue(Array.get(val, i));
            }
            return h;
        } else {
            return Objects.hashCode(val);
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    @Override
    protected void toString(final boolean useSimpleNames, final StringBuilder buf) {
        toString(useSimpleNames, buf, Collections.newSetFromMap(new IdentityHashMap<>()));
    }

    /**
     * Write the parameter name and value to the buffer.
     *
     * @param useSimpleNames
     *            if true, strip package and outer class names from class names
     * @param buf
     *            the buffer to append to
     * @param enclosing
     *            the annotations whose string representation is being written, as in
     *            {@link AnnotationInfo#toString(boolean, StringBuilder, Set)}
     */
    void toString(final boolean useSimpleNames, final StringBuilder buf, final Set<AnnotationInfo> enclosing) {
        buf.append(name);
        buf.append("=");
        toStringParamValueOnly(useSimpleNames, buf, enclosing);
    }

    /**
     * Write an annotation parameter value's string representation to the buffer.
     *
     * @param val
     *            the value
     * @param useSimpleNames
     *            if true, strip package and outer class names from class names
     * @param buf
     *            the buffer to append to
     * @param enclosing
     *            the annotations whose string representation is being written, as in
     *            {@link AnnotationInfo#toString(boolean, StringBuilder, Set)}
     */
    private static void toString(final @Nullable Object val, final boolean useSimpleNames, final StringBuilder buf,
            final Set<AnnotationInfo> enclosing) {
        if (val == null) {
            buf.append("null");
        } else if (val instanceof final AnnotationInfo annotationInfo) {
            annotationInfo.toString(useSimpleNames, buf, enclosing);
        } else if (val instanceof final ScanResultObject scanResultObject) {
            scanResultObject.toString(useSimpleNames, buf);
        } else if (val instanceof final Byte byteVal) {
            // Each value is rendered in the Java source syntax for its type, so that the type of the value can be
            // told from the rendering. A byte is rendered as Annotation::toString renders it. (There is no source
            // syntax for a short literal, so short values are rendered the same way as int values, matching the
            // JDK.) NaN and the infinities are rendered as the constants that name them, as in FieldInfo#toString,
            // where Annotation::toString renders them as a division, such as 0.0f/0.0f.
            final var unsignedByteVal = byteVal & 0xff;
            buf.append("(byte)0x").append(Character.forDigit(unsignedByteVal >> 4, 16))
                    .append(Character.forDigit(unsignedByteVal & 0xf, 16));
        } else {
            StringUtils.appendSourceLiteral(val, buf);
        }
    }

    /**
     * Write the parameter value, without the parameter name, to the buffer.
     *
     * @param useSimpleNames
     *            if true, strip package and outer class names from class names
     * @param buf
     *            the buffer to append to
     * @param enclosing
     *            the annotations whose string representation is being written, as in
     *            {@link AnnotationInfo#toString(boolean, StringBuilder, Set)}
     */
    void toStringParamValueOnly(final boolean useSimpleNames, final StringBuilder buf,
            final Set<AnnotationInfo> enclosing) {
        final var paramVal = value;
        if (paramVal != null && paramVal.getClass().isArray()) {
            buf.append('{');
            for (int j = 0, n = Array.getLength(paramVal); j < n; j++) {
                if (j > 0) {
                    buf.append(", ");
                }
                toString(Array.get(paramVal, j), useSimpleNames, buf, enclosing);
            }
            buf.append('}');
        } else {
            toString(paramVal, useSimpleNames, buf, enclosing);
        }
    }
}
