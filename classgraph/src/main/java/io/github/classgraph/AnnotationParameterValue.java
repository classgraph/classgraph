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
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import io.github.classgraph.base.internal.log.LogNode;
import io.github.classgraph.base.internal.utils.StringUtils;
import org.jspecify.annotations.Nullable;

/**
 * A wrapper used to pair annotation parameter names with annotation parameter values.
 */
public class AnnotationParameterValue extends ScanResultObject
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
    private @Nullable Object value;

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
     *         <li>{@link AnnotationEnumValue}, for enum constants (this wraps the enum class and the string name of
     *         the constant)
     *         <li>{@link AnnotationClassRef}, for Class references within annotations (this wraps the name of the
     *         referenced class)
     *         <li>{@link AnnotationInfo}, for nested annotations
     *         </ul>
     */
    public @Nullable Object getValue() {
        return value;
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
     *            the map from class name to {@link ClassInfo}.
     * @param refdClassInfo
     *            the referenced class info
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
     *            the map from class name to {@link ClassInfo}.
     * @param refdClassInfo
     *            the referenced class info
     * @param log
     *            the log node, or null to skip logging
     */
    private static void findReferencedClassInfo(final @Nullable Object value,
            final Map<String, ClassInfo> classNameToClassInfo, final Set<ClassInfo> refdClassInfo,
            final @Nullable LogNode log) {
        if (value instanceof final AnnotationClassRef annotationClassRef) {
            final var classInfo = annotationClassRef.getClassInfo();
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
     * For primitive array type params, replace Object[] arrays containing boxed types with primitive arrays (need
     * to check the type of each method of the annotation class to determine if it is a primitive array type).
     *
     * @param annotationClassInfo
     *            the annotation class info
     */
    void convertWrapperArraysToPrimitiveArrays(final @Nullable ClassInfo annotationClassInfo) {
        if (value instanceof final AnnotationInfo annotationInfo) {
            // Recursively convert boxed arrays in nested annotations
            annotationInfo.convertWrapperArraysToPrimitiveArrays();
        } else if (value != null && value.getClass() == Object[].class) {
            final var arrayValue = (Object[]) value;
            for (final Object elt : arrayValue) {
                if (elt instanceof final AnnotationInfo eltAnnotationInfo) {
                    // Recurse
                    eltAnnotationInfo.convertWrapperArraysToPrimitiveArrays();
                }
            }
            final var eltTypeName = getArrayValueTypeName(arrayValue, annotationClassInfo);
            final var eltType = ARRAY_ELEMENT_TYPES.get(eltTypeName);
            if (eltType != null) {
                // The array holds boxed values of a primitive type, or strings -- convert it to an array of that
                // element type
                final var typedArray = Array.newInstance(eltType, arrayValue.length);
                for (var i = 0; i < arrayValue.length; i++) {
                    final var elt = arrayValue[i];
                    if (elt == null && eltType.isPrimitive()) {
                        throw new IllegalArgumentException("Illegal null value for array of element type "
                                + eltTypeName + " in parameter " + name + " of annotation class "
                                + (annotationClassInfo == null ? "<class outside accept>"
                                        : annotationClassInfo.getName()));
                    }
                    Array.set(typedArray, i, elt);
                }
                value = typedArray;
            }
        }
    }

    /**
     * Get the name of the element type of an array-typed annotation parameter value.
     *
     * @param arrayValue
     *            the array-typed annotation parameter value
     * @param annotationClassInfo
     *            the annotation class, or null if the annotation class was not scanned
     * @return the name of the array element type.
     */
    private String getArrayValueTypeName(final Object[] arrayValue, final @Nullable ClassInfo annotationClassInfo) {
        // Find the method in the annotation class with the same name as the annotation parameter.
        final var annotationMethodList = annotationClassInfo == null || annotationClassInfo.methodInfo == null
                ? null
                : annotationClassInfo.methodInfo.get(name);
        if (annotationClassInfo != null && annotationMethodList != null && !annotationMethodList.isEmpty()) {
            if (annotationMethodList.size() > 1) {
                // There should only be one method with a given name in an annotation
                throw new IllegalArgumentException("Duplicated annotation parameter method " + name + "()"
                        + " in annotation class " + annotationClassInfo.getName());
            }
            // Get the result type of the method with the same name as the annotation parameter
            final var annotationMethodResultTypeSig = annotationMethodList.get(0).getTypeSignatureOrTypeDescriptor()
                    .getResultType();
            // The result type has to be an array type
            if (!(annotationMethodResultTypeSig instanceof final ArrayTypeSignature arrayTypeSig)) {
                throw new IllegalArgumentException(
                        "Annotation parameter " + name + " in annotation class " + annotationClassInfo.getName()
                                + " holds an array, but does not have an array type signature");
            }
            if (arrayTypeSig.getNumDimensions() != 1) {
                throw new IllegalArgumentException("Annotations only support 1-dimensional arrays");
            }
            final var elementTypeSig = arrayTypeSig.getElementTypeSignature();
            if (elementTypeSig instanceof final ClassRefTypeSignature classRefTypeSignature) {
                // Look up the name of the element type, for non-primitive arrays
                return classRefTypeSignature.getClassName();
            } else if (elementTypeSig instanceof final BaseTypeSignature baseTypeSignature) {
                // Look up the name of the primitive class, for primitive arrays
                return baseTypeSignature.getTypeName();
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
        if (other == this) {
            return 0;
        }
        final var diff = name.compareTo(other.getName());
        if (diff != 0) {
            return diff;
        }
        if (Objects.deepEquals(value, other.value)) {
            return 0;
        }
        // Use toString() order (which can be slow) as a last-ditch effort -- only happens if the annotation has
        // multiple parameters of the same name but different value.
        final var p0 = value;
        final var p1 = other.value;
        return p0 == null || p1 == null ? (p0 == null ? 0 : 1) - (p1 == null ? 0 : 1)
                : toStringParamValueOnly().compareTo(other.toStringParamValueOnly());
    }

    @Override
    public boolean equals(final @Nullable Object obj) {
        if (obj == this) {
            return true;
        }
        if (!(obj instanceof final AnnotationParameterValue other)) {
            return false;
        }
        // N.B. use deepEquals, so that array-valued parameters are compared by their contents, not by identity
        return this.name.equals(other.name) && Objects.deepEquals(value, other.value);
    }

    @Override
    public int hashCode() {
        // N.B. wrap the value in an array, so that Arrays#deepHashCode hashes an array-valued parameter by its
        // contents, matching equals(Object)
        return name.hashCode() * 31 + Arrays.deepHashCode(new Object[] { value });
    }

    // -------------------------------------------------------------------------------------------------------------

    @Override
    protected void toString(final boolean useSimpleNames, final StringBuilder buf) {
        buf.append(name);
        buf.append("=");
        toStringParamValueOnly(useSimpleNames, buf);
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
     */
    private static void toString(final @Nullable Object val, final boolean useSimpleNames,
            final StringBuilder buf) {
        if (val == null) {
            buf.append("null");
        } else if (val instanceof final ScanResultObject scanResultObject) {
            scanResultObject.toString(useSimpleNames, buf);
        } else if (val instanceof final String str) {
            buf.append('"').append(StringUtils.escapeString(str)).append('"');
        } else if (val instanceof final Character chr) {
            buf.append('\'').append(StringUtils.escapeChar(chr)).append('\'');
        } else if (val instanceof final Byte byteVal) {
            // Annotation::toString renders each value in the Java source syntax for its type, so that the type of
            // the value can be told from the rendering. (There is no source syntax for a short literal, so short
            // values are rendered the same way as int values, matching the JDK.)
            final var unsignedByteVal = byteVal & 0xff;
            buf.append("(byte)0x").append(Character.forDigit(unsignedByteVal >> 4, 16))
                    .append(Character.forDigit(unsignedByteVal & 0xf, 16));
        } else if (val instanceof final Long longVal) {
            buf.append(longVal.longValue()).append('L');
        } else if (val instanceof final Float floatVal) {
            if (Float.isNaN(floatVal)) {
                buf.append("0.0f/0.0f");
            } else if (floatVal.isInfinite()) {
                buf.append(floatVal > 0 ? "1.0f/0.0f" : "-1.0f/0.0f");
            } else {
                buf.append(floatVal.floatValue()).append('f');
            }
        } else if (val instanceof final Double doubleVal) {
            if (Double.isNaN(doubleVal)) {
                buf.append("0.0/0.0");
            } else if (doubleVal.isInfinite()) {
                buf.append(doubleVal > 0 ? "1.0/0.0" : "-1.0/0.0");
            } else {
                buf.append(doubleVal.doubleValue());
            }
        } else {
            buf.append(val);
        }
    }

    /**
     * To string, param value only.
     *
     * @param useSimpleNames
     *            if true, strip package and outer class names from class names
     * @param buf
     *            the buffer to append to
     */
    void toStringParamValueOnly(final boolean useSimpleNames, final StringBuilder buf) {
        final var paramVal = value;
        if (paramVal != null && paramVal.getClass().isArray()) {
            buf.append('{');
            for (int j = 0, n = Array.getLength(paramVal); j < n; j++) {
                if (j > 0) {
                    buf.append(", ");
                }
                toString(Array.get(paramVal, j), useSimpleNames, buf);
            }
            buf.append('}');
        } else {
            toString(paramVal, useSimpleNames, buf);
        }
    }

    /**
     * To string, param value only.
     *
     * @return the string.
     */
    private String toStringParamValueOnly() {
        final StringBuilder buf = new StringBuilder();
        toStringParamValueOnly(false, buf);
        return buf.toString();
    }
}
