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
package io.github.lukehutch.fastclasspathscanner;

import java.lang.reflect.Array;

/** A wrapper used to pair annotation parameter names with annotation parameter values. */
public class AnnotationParamValue extends ScanResultObject implements Comparable<AnnotationParamValue> {
    String paramName;
    AnnotationParamValueWrapper paramValue;

    @Override
    void setScanResult(final ScanResult scanResult) {
        super.setScanResult(scanResult);
        if (paramValue != null) {
            paramValue.setScanResult(scanResult);
        }
    }

    /** Default constructor for deserialization. */
    AnnotationParamValue() {
    }

    /**
     * @param paramName
     *            The annotation paramater name.
     * @param paramValue
     *            The annotation parameter value.
     */
    public AnnotationParamValue(final String paramName, final Object paramValue) {
        this.paramName = paramName;
        this.paramValue = new AnnotationParamValueWrapper(paramValue);
    }

    /**
     * Get the annotation parameter name.
     * 
     * @return The annotation parameter name.
     */
    public String getParamName() {
        return paramName;
    }

    /**
     * Get the annotation parameter value.
     * 
     * @return The annotation parameter value. May be one of the following types:
     *         <ul>
     *         <li>String for string constants
     *         <li>A wrapper type, e.g. Integer or Character, for primitive-typed constants
     *         <li>{@link Object}[] for array types (and then the array element type may be one of the types in this
     *         list)
     *         <li>{@link AnnotationEnumValue}, for enum constants (this wraps the enum class and the string name of
     *         the constant)
     *         <li>{@link AnnotationClassRef}, for Class references within annotations (this wraps the name of the
     *         referenced class)
     *         <li>{@link AnnotationInfo}, for nested annotations
     *         </ul>
     */
    public Object getParamValue() {
        return paramValue == null ? null : paramValue.get();
    }

    @Override
    public String toString() {
        final StringBuilder buf = new StringBuilder();
        toString(buf);
        return buf.toString();
    }

    void toString(final StringBuilder buf) {
        buf.append(paramName);
        buf.append(" = ");
        toStringParamValueOnly(buf);
    }

    void toStringParamValueOnly(final StringBuilder buf) {
        if (paramValue == null) {
            buf.append("null");
        } else {
            final Object paramVal = paramValue.get();
            final Class<? extends Object> valClass = paramVal.getClass();
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

    @Override
    public int compareTo(final AnnotationParamValue o) {
        final int diff = paramName.compareTo(o.getParamName());
        if (diff != 0) {
            return diff;
        }
        // Use toString() order and get() (which can be slow) as a last-ditch effort -- only happens
        // if the annotation has multiple parameters of the same name but different value. 
        final Object p0 = getParamValue();
        final Object p1 = o.getParamValue();
        if (p0 == null && p1 == null) {
            return 0;
        } else if (p0 == null && p1 != null) {
            return -1;
        } else if (p0 != null && p1 == null) {
            return 1;
        }
        return p0.toString().compareTo(p1.toString());
    }

    @Override
    public boolean equals(final Object obj) {
        if (!(obj instanceof AnnotationParamValue)) {
            return false;
        }
        final AnnotationParamValue o = (AnnotationParamValue) obj;
        final int diff = this.compareTo(o);
        return (diff != 0 ? false
                : paramValue == null && o.paramValue == null ? true
                        : paramValue == null || o.paramValue == null ? false : true);
    }
}