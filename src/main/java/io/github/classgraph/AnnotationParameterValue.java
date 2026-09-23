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

import nonapi.io.github.classgraph.utils.LogNode;

/** A wrapper used to pair annotation parameter names with annotation parameter values. */
public class AnnotationParameterValue extends ScanResultObject
        implements HasName, Comparable<AnnotationParameterValue> {
    /** The parameter name. */
    private String name;

    /** The parameter value. */
    private ObjectTypedValueWrapper value;

    /** Default constructor for deserialization. */
    AnnotationParameterValue() {
        super();
    }

    /**
     * Constructor.
     *
     * @param name
     *            The annotation parameter name.
     * @param value
     *            The annotation parameter value.
     */
    AnnotationParameterValue(final String name, final Object value) {
        super();
        this.name = name;
        this.value = new ObjectTypedValueWrapper(value);
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
    public Object getValue() {
        return value == null ? null : value.get();
    }

    /**
     * Set (update) the value of the annotation parameter. Used to replace Object[] arrays containing boxed types
     * into primitive arrays.
     *
     * @param newValue
     *            the new value
     */
    void setValue(final Object newValue) {
        this.value = new ObjectTypedValueWrapper(newValue);
    }

    // -------------------------------------------------------------------------------------------------------------

    /* (non-Javadoc)
     * @see io.github.classgraph.ScanResultObject#getClassName()
     */
    @Override
    protected String getClassName() {
        // getClassInfo() is not valid for this type, so getClassName() does not need to be implemented
        throw new IllegalArgumentException("getClassName() cannot be called here");
    }

    /* (non-Javadoc)
     * @see io.github.classgraph.ScanResultObject#getClassInfo()
     */
    @Override
    protected ClassInfo getClassInfo() {
        throw new IllegalArgumentException("getClassInfo() cannot be called here");
    }

    /* (non-Javadoc)
     * @see io.github.classgraph.ScanResultObject#setScanResult(io.github.classgraph.ScanResult)
     */
    @Override
    void setScanResult(final ScanResult scanResult) {
        super.setScanResult(scanResult);
        if (value != null) {
            value.setScanResult(scanResult);
        }
    }

    /**
     * Get {@link ClassInfo} objects for any classes referenced in the annotation parameters.
     *
     * @param classNameToClassInfo
     *            the map from class name to {@link ClassInfo}.
     * @param refdClassInfo
     *            the referenced class info
     * @param log
     *            the log
     */
    @Override
    protected void findReferencedClassInfo(final Map<String, ClassInfo> classNameToClassInfo,
            final Set<ClassInfo> refdClassInfo, final LogNode log) {
        if (value != null) {
            value.findReferencedClassInfo(classNameToClassInfo, refdClassInfo, log);
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
    void convertWrapperArraysToPrimitiveArrays(final ClassInfo annotationClassInfo) {
        if (value != null) {
            value.convertWrapperArraysToPrimitiveArrays(annotationClassInfo, name);
        }
    }

    /**
     * Instantiate an annotation parameter value.
     *
     * @param annotationClassInfo
     *            the annotation class info
     * @return the instance
     */
    Object instantiate(final ClassInfo annotationClassInfo) {
        return value.instantiateOrGet(annotationClassInfo, name);
    }

    // -------------------------------------------------------------------------------------------------------------

    /* (non-Javadoc)
     * @see java.lang.Comparable#compareTo(java.lang.Object)
     */
    @Override
    public int compareTo(final AnnotationParameterValue other) {
        if (other == this) {
            return 0;
        }
        final int diff = name.compareTo(other.getName());
        if (diff != 0) {
            return diff;
        }
        // N.B. value is null for an AnnotationParameterValue that has been deserialized but not yet populated
        if (value == null ? other.value == null : value.equals(other.value)) {
            return 0;
        }
        return compareValues(getValue(), other.getValue());
    }

    /**
     * Compare two annotation parameter values. Values of the same class are compared by their natural order, or by
     * their string form if they have no natural order. Arrays are compared element by element, so an Object[]
     * array that has not yet been converted to a primitive array compares equal to the converted array. Values of
     * different classes cannot be compared with each other, so they are ordered by class name, which keeps the
     * order transitive.
     *
     * @param v0
     *            the first value, or null.
     * @param v1
     *            the second value, or null.
     * @return the result of the comparison.
     */
    @SuppressWarnings("unchecked")
    private static int compareValues(final Object v0, final Object v1) {
        if (v0 == null || v1 == null) {
            return (v0 == null ? 0 : 1) - (v1 == null ? 0 : 1);
        }
        final boolean isArray0 = v0.getClass().isArray();
        final boolean isArray1 = v1.getClass().isArray();
        if (isArray0 && isArray1) {
            final int len0 = Array.getLength(v0);
            final int len1 = Array.getLength(v1);
            for (int i = 0; i < Math.min(len0, len1); i++) {
                final int diff = compareValues(Array.get(v0, i), Array.get(v1, i));
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
        } else if (v0 instanceof Comparable) {
            return ((Comparable<Object>) v0).compareTo(v1);
        } else {
            return v0.toString().compareTo(v1.toString());
        }
    }

    /**
     * Check whether this annotation parameter value equals another, consistently with
     * {@link #compareTo(AnnotationParameterValue)}. Arrays are compared by their contents, and an Object[] array
     * that has not yet been converted to a primitive array or a String[] array equals the converted array.
     *
     * @param obj
     *            the object to compare with.
     * @return true if the object is an equal annotation parameter value.
     */
    @Override
    public boolean equals(final Object obj) {
        if (obj == this) {
            return true;
        } else if (!(obj instanceof AnnotationParameterValue)) {
            return false;
        }
        final AnnotationParameterValue other = (AnnotationParameterValue) obj;
        if (!Objects.equals(name, other.name) || (value == null) != (other.value == null)) {
            return false;
        }
        if (value == null || value.equals(other.value)) {
            return true;
        }
        final Object v0 = getValue();
        final Object v1 = other.getValue();
        return v0 != null && v1 != null && v0.getClass() != v1.getClass() && v0.getClass().isArray()
                && v1.getClass().isArray() && compareValues(v0, v1) == 0;
    }

    /**
     * Get the hash code, which is consistent with {@link #equals(Object)}.
     *
     * @return the hash code.
     */
    @Override
    public int hashCode() {
        // N.B. wrap the value in an array, so that Arrays#deepHashCode hashes an array-valued parameter by its
        // contents. A primitive array hashes each element the same way as the boxed value, so an Object[] array
        // that has not yet been converted hashes the same as the converted array.
        return Objects.hashCode(name) * 31 + Arrays.deepHashCode(new Object[] { getValue() });
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
     *            the use simple names
     * @param buf
     *            the buffer
     */
    private static void toString(final Object val, final boolean useSimpleNames, final StringBuilder buf) {
        if (val == null) {
            buf.append("null");
        } else if (val instanceof ScanResultObject) {
            ((ScanResultObject) val).toString(useSimpleNames, buf);
        } else {
            buf.append(val);
        }
    }

    /**
     * To string, param value only.
     *
     * @param useSimpleNames
     *            whether to use simple names for classes
     * @param buf
     *            the buf
     */
    void toStringParamValueOnly(final boolean useSimpleNames, final StringBuilder buf) {
        final Object paramVal = value == null ? null : value.get();
        if (paramVal == null) {
            buf.append("null");
        } else {
            final Class<?> valClass = paramVal.getClass();
            if (valClass.isArray()) {
                buf.append('{');
                for (int j = 0, n = Array.getLength(paramVal); j < n; j++) {
                    if (j > 0) {
                        buf.append(", ");
                    }
                    final Object elt = Array.get(paramVal, j);
                    toString(elt, useSimpleNames, buf);
                }
                buf.append('}');
            } else if (paramVal instanceof String) {
                buf.append('"');
                buf.append(paramVal.toString().replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r"));
                buf.append('"');
            } else if (paramVal instanceof Character) {
                buf.append('\'');
                buf.append(paramVal.toString().replace("'", "\\'").replace("\n", "\\n").replace("\r", "\\r"));
                buf.append('\'');
            } else {
                toString(paramVal, useSimpleNames, buf);
            }
        }
    }
}
