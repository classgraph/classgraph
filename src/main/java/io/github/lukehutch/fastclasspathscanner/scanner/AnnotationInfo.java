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
 * Copyright (c) 2017 Luke Hutchison
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
package io.github.lukehutch.fastclasspathscanner.scanner;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import io.github.lukehutch.fastclasspathscanner.scanner.ScanResult.InfoObject;
import io.github.lukehutch.fastclasspathscanner.utils.ReflectionUtils;

/** Holds metadata about annotations. */
public class AnnotationInfo extends InfoObject implements Comparable<AnnotationInfo> {
    final String annotationName;
    List<AnnotationParamValue> annotationParamValues;
    private ScanResult scanResult;

    @Override
    void setScanResult(final ScanResult scanResult) {
        this.scanResult = scanResult;
        if (annotationParamValues != null) {
            for (final AnnotationParamValue a : annotationParamValues) {
                a.setScanResult(scanResult);
            }
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /** A wrapper used to pair annotation parameter names with annotation parameter values. */
    public static class AnnotationParamValue extends InfoObject implements Comparable<AnnotationParamValue> {
        private final String paramName;
        private final Object paramValue;

        public AnnotationParamValue(final String paramName, final Object paramValue) {
            this.paramName = paramName;
            this.paramValue = paramValue;
        }

        @Override
        void setScanResult(final ScanResult scanResult) {
            if (paramValue != null) {
                if (paramValue instanceof InfoObject) {
                    ((InfoObject) paramValue).setScanResult(scanResult);
                } else {
                    final Class<? extends Object> valClass = paramValue.getClass();
                    if (valClass.isArray()) {
                        for (int j = 0, n = Array.getLength(paramValue); j < n; j++) {
                            final Object elt = Array.get(paramValue, j);
                            if (elt != null && elt instanceof InfoObject) {
                                ((InfoObject) elt).setScanResult(scanResult);
                            }
                        }
                    }
                }
            }
        }

        /** Get the annotation parameter name. */
        public String getParamName() {
            return paramName;
        }

        /**
         * Get the annotation parameter value. May be one of the following types:
         * <ul>
         * <li>String for string constants
         * <li>A wrapper type, e.g. Integer or Character, for primitive-typed constants
         * <li>Object[] for array types (and then the array element type may be one of the types in this list)
         * <li>AnnotationEnumValue, for enum constants (this wraps the enum class and the string name of the
         * constant)
         * <li>AnnotationClassRef, for Class references within annotations (this wraps the name of the referenced
         * class)
         * <li>AnnotationInfo, for nested annotations
         * </ul>
         */
        public Object getParamValue() {
            return paramValue;
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
                final Class<? extends Object> valClass = paramValue.getClass();
                if (valClass.isArray()) {
                    buf.append('{');
                    for (int j = 0, n = Array.getLength(paramValue); j < n; j++) {
                        if (j > 0) {
                            buf.append(", ");
                        }
                        final Object elt = Array.get(paramValue, j);
                        buf.append(elt == null ? "null" : elt.toString());
                    }
                    buf.append('}');
                } else if (paramValue instanceof String) {
                    buf.append('"');
                    buf.append(
                            paramValue.toString().replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r"));
                    buf.append('"');
                } else if (paramValue instanceof Character) {
                    buf.append('\'');
                    buf.append(paramValue.toString().replace("'", "\\'").replace("\n", "\\n").replace("\r", "\\r"));
                    buf.append('\'');
                } else {
                    buf.append(paramValue.toString());
                }
            }
        }

        @Override
        public int compareTo(final AnnotationParamValue o) {
            final int diff = paramName.compareTo(o.getParamName());
            if (diff != 0) {
                return diff;
            }
            if (paramValue == null && o.paramValue == null) {
                return 0;
            } else if (paramValue == null) {
                return -1;
            } else if (o.paramValue == null) {
                return 1;
            } else if (paramValue instanceof Comparable && o.paramValue instanceof Comparable) {
                try {
                    @SuppressWarnings({ "unchecked", "rawtypes" })
                    final int cmp = ((Comparable) paramValue).compareTo(o.paramValue);
                    return cmp;
                } catch (final ClassCastException e) {
                }
            }
            // Use toString() order if trying to compare uncomparable types
            // (this is inefficient, but it's a last-ditch effort to order things consistently)
            return paramValue.toString().compareTo(o.paramValue.toString());
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

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Class for wrapping an enum constant value (split into class name and constant name) referenced inside an
     * annotation.
     */
    public static class AnnotationEnumValue extends InfoObject implements Comparable<AnnotationEnumValue> {
        final String className;
        final String constName;
        private ScanResult scanResult;

        public AnnotationEnumValue(final String className, final String constName) {
            this.className = className;
            this.constName = constName;
        }

        @Override
        void setScanResult(final ScanResult scanResult) {
            this.scanResult = scanResult;
        }

        /** Get the class name of the enum. */
        public String getClassName() {
            return className;
        }

        /** Get the name of the enum constant. */
        public String getConstName() {
            return constName;
        }

        /**
         * Get the enum constant. Causes the ClassLoader to load the enum class.
         * 
         * @throw IllegalArgumentException if the class could not be loaded, or the enum constant is invalid.
         */
        public Object getEnumValueRef() throws IllegalArgumentException {
            final Class<?> classRef = scanResult.classNameToClassRef(className);
            if (!classRef.isEnum()) {
                throw new IllegalArgumentException("Class " + className + " is not an enum");
            }
            Field field;
            try {
                field = classRef.getDeclaredField(constName);
            } catch (NoSuchFieldException | SecurityException e) {
                throw new IllegalArgumentException("Could not find enum constant " + toString(), e);
            }
            if (!field.isEnumConstant()) {
                throw new IllegalArgumentException("Field " + toString() + " is not an enum constant");
            }
            try {
                return field.get(null);
            } catch (final IllegalAccessException e) {
                throw new IllegalArgumentException("Field " + toString() + " is not accessible", e);
            }
        }

        @Override
        public int compareTo(final AnnotationEnumValue o) {
            final int diff = className.compareTo(o.className);
            return diff == 0 ? constName.compareTo(o.constName) : diff;
        }

        @Override
        public boolean equals(final Object o) {
            if (!(o instanceof AnnotationEnumValue)) {
                return false;
            }
            return compareTo((AnnotationEnumValue) o) == 0;
        }

        @Override
        public int hashCode() {
            return className.hashCode() * 11 + constName.hashCode();
        }

        @Override
        public String toString() {
            return className + "." + constName;
        }
    }

    /**
     * Stores a class descriptor in an annotation as a class type string, e.g. "[[[java/lang/String;" is stored as
     * "String[][][]".
     * 
     * Use ReflectionUtils.typeStrToClass() to get a Class<?> reference from this class type string.
     */
    public static class AnnotationClassRef extends InfoObject {
        private final String typeStr;
        private ScanResult scanResult;

        @Override
        void setScanResult(final ScanResult scanResult) {
            this.scanResult = scanResult;
        }

        AnnotationClassRef(final String annotationTypeStr) {
            this.typeStr = annotationTypeStr;
        }

        /**
         * Get a class type string (e.g. "String[][][]" or "int") for a type reference used in an annotation
         * parameter.
         * 
         * Use ReflectionUtils.typeStrToClass() to get a Class<?> reference from this class type string.
         */
        public String getTypeStr() {
            return typeStr;
        }

        /** Get a class reference for a class-reference-typed value used in an annotation parameter. */
        public Class<?> getType() {
            return ReflectionUtils.typeStrToClass(typeStr, scanResult);
        }

        @Override
        public String toString() {
            return typeStr + ".class";
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    public AnnotationInfo(final String annotationName, final List<AnnotationParamValue> annotationParamValues) {
        this.annotationName = annotationName;
        // Sort the annotation parameter values into order for consistency
        if (annotationParamValues != null) {
            Collections.sort(annotationParamValues);
        }
        this.annotationParamValues = annotationParamValues;
    }

    /**
     * Add a set of default values from an annotation class to a concrete annotation. The defaults are overwritten
     * by any annotation values with the same name in the concrete annotation.
     */
    void addDefaultValues(final List<AnnotationParamValue> defaultAnnotationParamValues) {
        if (defaultAnnotationParamValues != null && !defaultAnnotationParamValues.isEmpty()) {
            if (this.annotationParamValues == null || this.annotationParamValues.isEmpty()) {
                this.annotationParamValues = new ArrayList<>(defaultAnnotationParamValues);
            } else {
                // Overwrite defaults with non-defaults
                final Map<String, Object> allParamValues = new HashMap<>();
                for (final AnnotationParamValue annotationParamValue : defaultAnnotationParamValues) {
                    allParamValues.put(annotationParamValue.paramName, annotationParamValue.paramValue);
                }
                for (final AnnotationParamValue annotationParamValue : this.annotationParamValues) {
                    allParamValues.put(annotationParamValue.paramName, annotationParamValue.paramValue);
                }
                this.annotationParamValues.clear();
                for (final Entry<String, Object> ent : allParamValues.entrySet()) {
                    this.annotationParamValues.add(new AnnotationParamValue(ent.getKey(), ent.getValue()));
                }
            }
        }
        if (this.annotationParamValues != null) {
            Collections.sort(this.annotationParamValues);
        }
    }

    /** Get the name of the annotation. */
    public String getAnnotationName() {
        return annotationName;
    }

    /** Get a class reference for the annotation. */
    public Class<?> getAnnotationType() {
        return ReflectionUtils.typeStrToClass(annotationName, scanResult);
    }

    /** Get the parameter value of the annotation. */
    public List<AnnotationParamValue> getAnnotationParamValues() {
        return annotationParamValues;
    }

    @Override
    public int compareTo(final AnnotationInfo o) {
        final int diff = annotationName.compareTo(o.annotationName);
        if (diff != 0) {
            return diff;
        }
        if (annotationParamValues == null && o.annotationParamValues == null) {
            return 0;
        } else if (annotationParamValues == null) {
            return -1;
        } else if (o.annotationParamValues == null) {
            return 1;
        } else {
            for (int i = 0, max = Math.max(annotationParamValues.size(),
                    o.annotationParamValues.size()); i < max; i++) {
                if (i >= annotationParamValues.size()) {
                    return -1;
                } else if (i >= o.annotationParamValues.size()) {
                    return 1;
                } else {
                    final int diff2 = annotationParamValues.get(i).compareTo(o.annotationParamValues.get(i));
                    if (diff2 != 0) {
                        return diff2;
                    }
                }
            }
        }
        return 0;
    }

    @Override
    public boolean equals(final Object obj) {
        if (!(obj instanceof AnnotationInfo)) {
            return false;
        }
        final AnnotationInfo o = (AnnotationInfo) obj;
        return this.compareTo(o) == 0;
    }

    @Override
    public int hashCode() {
        int h = annotationName.hashCode();
        if (annotationParamValues != null) {
            for (int i = 0; i < annotationParamValues.size(); i++) {
                final AnnotationParamValue e = annotationParamValues.get(i);
                h = h * 7 + e.getParamName().hashCode() * 3 + e.getParamValue().hashCode();
            }
        }
        return h;
    }

    @Override
    public String toString() {
        final StringBuilder buf = new StringBuilder();
        buf.append("@" + annotationName);
        if (annotationParamValues != null) {
            buf.append('(');
            for (int i = 0; i < annotationParamValues.size(); i++) {
                if (i > 0) {
                    buf.append(", ");
                }
                final AnnotationParamValue annotationParamValue = annotationParamValues.get(i);
                if (annotationParamValues.size() > 1 || !"value".equals(annotationParamValue.paramName)) {
                    annotationParamValue.toString(buf);
                } else {
                    annotationParamValue.toStringParamValueOnly(buf);
                }
            }
            buf.append(')');
        }
        return buf.toString();
    }

    // -------------------------------------------------------------------------------------------------------------

    private static final String[] EMPTY_STRING_ARRAY = new String[0];

    /** From a collection of AnnotationInfo objects, extract the annotation names, uniquify them, and sort them. */
    public static String[] getUniqueAnnotationNamesSorted(final Collection<AnnotationInfo> annotationInfo) {
        if (annotationInfo == null || annotationInfo.isEmpty()) {
            return EMPTY_STRING_ARRAY;
        }
        final Set<String> annotationNamesSet = new HashSet<>();
        for (final AnnotationInfo annotation : annotationInfo) {
            annotationNamesSet.add(annotation.annotationName);
        }
        final String[] annotationNamesSorted = new String[annotationNamesSet.size()];
        int i = 0;
        for (final String annotationName : annotationNamesSet) {
            annotationNamesSorted[i++] = annotationName;
        }
        Arrays.sort(annotationNamesSorted);
        return annotationNamesSorted;
    }

    /** From an array of AnnotationInfo objects, extract the annotation names, uniquify them, and sort them. */
    public static String[] getUniqueAnnotationNamesSorted(final AnnotationInfo[] annotationInfo) {
        if (annotationInfo == null || annotationInfo.length == 0) {
            return EMPTY_STRING_ARRAY;
        }
        final Set<String> annotationNamesSet = new HashSet<>();
        for (final AnnotationInfo annotation : annotationInfo) {
            annotationNamesSet.add(annotation.annotationName);
        }
        final String[] annotationNamesSorted = new String[annotationNamesSet.size()];
        int i = 0;
        for (final String annotationName : annotationNamesSet) {
            annotationNamesSorted[i++] = annotationName;
        }
        Arrays.sort(annotationNamesSorted);
        return annotationNamesSorted;
    }
}
