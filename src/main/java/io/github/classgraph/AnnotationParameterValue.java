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
package io.github.classgraph;

import java.lang.reflect.Array;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** A wrapper used to pair annotation parameter names with annotation parameter values. */
public class AnnotationParameterValue extends ScanResultObject
        implements HasName, Comparable<AnnotationParameterValue> {
    /** The the parameter name. */
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
     *            The annotation paramater name.
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
     */
    @Override
    protected void findReferencedClassInfo(final Map<String, ClassInfo> classNameToClassInfo,
            final Set<ClassInfo> refdClassInfo) {
        if (value != null) {
            value.findReferencedClassInfo(classNameToClassInfo, refdClassInfo);
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
     * @see java.lang.Object#toString()
     */
    @Override
    public String toString() {
        final StringBuilder buf = new StringBuilder();
        toString(buf);
        return buf.toString();
    }

    /**
     * To string.
     *
     * @param buf
     *            the buf
     */
    void toString(final StringBuilder buf) {
        buf.append(name);
        buf.append("=");
        toStringParamValueOnly(buf);
    }

    /**
     * To string, param value only.
     *
     * @param buf
     *            the buf
     */
    void toStringParamValueOnly(final StringBuilder buf) {
        if (value == null) {
            buf.append("null");
        } else {
            final Object paramVal = value.get();
            final Class<?> valClass = paramVal.getClass();
            if (valClass.isArray()) {
                buf.append('{');
                for (int j = 0, n = Array.getLength(paramVal); j < n; j++) {
                    if (j > 0) {
                        buf.append(", ");
                    }
                    final Object elt = Array.get(paramVal, j);
                    buf.append(elt == null ? "null" : elt.toString());
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
                buf.append(paramVal.toString());
            }
        }
    }

    /**
     * To string, param value only.
     * 
     * @return the string.
     */
    private String toStringParamValueOnly() {
        final StringBuilder buf = new StringBuilder();
        toStringParamValueOnly(buf);
        return buf.toString();
    }

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
        if (value.equals(other.value)) {
            return 0;
        }
        // Use toString() order (which can be slow) as a last-ditch effort -- only happens
        // if the annotation has multiple parameters of the same name but different value. 
        final Object p0 = getValue();
        final Object p1 = other.getValue();
        return p0 == null || p1 == null ? (p0 == null ? 0 : 1) - (p1 == null ? 0 : 1)
                : toStringParamValueOnly().compareTo(other.toStringParamValueOnly());
    }

    /* (non-Javadoc)
     * @see java.lang.Object#equals(java.lang.Object)
     */
    @Override
    public boolean equals(final Object obj) {
        if (obj == this) {
            return true;
        } else if (!(obj instanceof AnnotationParameterValue)) {
            return false;
        }
        final AnnotationParameterValue other = (AnnotationParameterValue) obj;
        return this.name.equals(other.name) && (value == null) == (other.value == null)
                && (value == null || value.equals(other.value));
    }

    /* (non-Javadoc)
     * @see java.lang.Object#hashCode()
     */
    @Override
    public int hashCode() {
        return Objects.hash(name, value);
    }
}
