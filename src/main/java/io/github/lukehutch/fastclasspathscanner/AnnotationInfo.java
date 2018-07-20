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

import io.github.lukehutch.fastclasspathscanner.utils.Parser.ParseException;

/** Holds metadata about annotations. */
public class AnnotationInfo extends ScanResultObject implements Comparable<AnnotationInfo> {
    String annotationName;
    List<AnnotationParamValue> annotationParamValues;

    /** Default constructor for deserialization. */
    AnnotationInfo() {
    }

    @Override
    void setScanResult(final ScanResult scanResult) {
        super.setScanResult(scanResult);
        if (annotationParamValues != null) {
            for (final AnnotationParamValue a : annotationParamValues) {
                if (a != null) {
                    a.setScanResult(scanResult);
                }
            }
        }
    }

    /**
     * Stores a class descriptor in an annotation as a class type string, e.g. "[[[java/lang/String;" is stored as
     * "String[][][]".
     *
     * <p>
     * Use ReflectionUtils.typeStrToClass() to get a {@code Class<?>} reference from this class type string.
     */
    public static class AnnotationClassRef extends ScanResultObject {
        String typeDescriptor;
        transient TypeSignature typeSignature;
        transient ScanResult scanResult;

        AnnotationClassRef() {
        }

        @Override
        void setScanResult(final ScanResult scanResult) {
            super.setScanResult(scanResult);
            if (this.typeSignature != null) {
                this.typeSignature.setScanResult(scanResult);
            }
        }

        AnnotationClassRef(final String classRefTypeDescriptor) {
            this.typeDescriptor = classRefTypeDescriptor;
        }

        /**
         * Get the type signature for a type reference used in an annotation parameter.
         *
         * <p>
         * Call getType() to get a {@code Class<?>} reference for this class.
         * 
         * @return The type signature of the annotation class ref.
         */
        public TypeSignature getTypeSignature() {
            if (typeSignature == null) {
                try {
                    typeSignature = TypeSignature.parse(typeDescriptor);
                } catch (final ParseException e) {
                    throw new IllegalArgumentException(e);
                }
            }
            return typeSignature;
        }

        /**
         * Get a class reference for a class-reference-typed value used in an annotation parameter. Causes the
         * ClassLoader to load the class, if it is not already loaded.
         * 
         * @return The type signature of the annotation class ref, as a {@code Class<?>} reference.
         * @throws IllegalArgumentException
         *             if an exception or error is thrown while loading the class.
         */
        public Class<?> getClassRef() {
            return getTypeSignature().instantiate();
        }

        @Override
        public int hashCode() {
            return getTypeSignature().hashCode();
        }

        @Override
        public boolean equals(final Object obj) {
            if (!(obj instanceof AnnotationClassRef)) {
                return false;
            }
            return getTypeSignature().equals(((AnnotationClassRef) obj).getTypeSignature());
        }

        @Override
        public String toString() {
            return getTypeSignature().toString();
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * @param annotationName
     *            The name of the annotation.
     * @param annotationParamValues
     *            The annotation parameter values, or null if none.
     */
    public AnnotationInfo(final String annotationName, final List<AnnotationParamValue> annotationParamValues) {
        this.annotationName = annotationName;
        // Sort the annotation parameter values into order for consistency
        if (annotationParamValues != null) {
            Collections.sort(annotationParamValues);
        }
        this.annotationParamValues = annotationParamValues;
    }

    /**
     * Add a set of default values, stored in an annotation class' classfile, to a concrete instance of that
     * annotation. The defaults are overwritten by any annotation parameter values in the concrete annotation.
     * 
     * @param defaultAnnotationParamValues
     *            the default parameter values for the annotation.
     */
    void addDefaultValues(final List<AnnotationParamValue> defaultAnnotationParamValues) {
        if (defaultAnnotationParamValues != null && !defaultAnnotationParamValues.isEmpty()) {
            if (this.annotationParamValues == null || this.annotationParamValues.isEmpty()) {
                this.annotationParamValues = new ArrayList<>(defaultAnnotationParamValues);
            } else {
                // Overwrite defaults with non-defaults
                final Map<String, Object> allParamValues = new HashMap<>();
                for (final AnnotationParamValue annotationParamValue : defaultAnnotationParamValues) {
                    allParamValues.put(annotationParamValue.paramName, annotationParamValue.paramValue.get());
                }
                for (final AnnotationParamValue annotationParamValue : this.annotationParamValues) {
                    allParamValues.put(annotationParamValue.paramName, annotationParamValue.paramValue.get());
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

    /**
     * Get the name of the annotation.
     * 
     * @return The annotation name.
     */
    public String getAnnotationName() {
        return annotationName;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Get a class reference for the annotation. Causes the ClassLoader to load the annotation class, if it is not
     * already loaded.
     * 
     * <p>
     * Important note: since {@code superinterfaceType} is a class reference for an already-loaded class, it is
     * critical that {@code superinterfaceType} is loaded by the same classloader as the class referred to by this
     * {@link AnnotationInfo} object, otherwise the class cast will fail.
     * 
     * @param superinterfaceType
     *            The type to cast the loaded annotation class to.
     * @return The annotation type, as a {@code Class<?>} reference, or null, if ignoreExceptions is true and there
     *         was an exception or error loading the class.
     * @throws IllegalArgumentException
     *             if ignoreExceptions is false and there was a problem loading the annotation class.
     */
    public <T> Class<T> getClassRef(final Class<T> superinterfaceType, final boolean ignoreExceptions) {
        return scanResult.loadClass(annotationName, superinterfaceType, ignoreExceptions);
    }

    /**
     * Get a class reference for the annotation. Causes the ClassLoader to load the annotation class, if it is not
     * already loaded.
     * 
     * <p>
     * Important note: since {@code superinterfaceType} is a class reference for an already-loaded class, it is
     * critical that {@code superinterfaceType} is loaded by the same classloader as the class referred to by this
     * {@code AnnotationInfo} object, otherwise the class cast will fail.
     * 
     * @param superinterfaceType
     *            The type to cast the loaded annotation class to.
     * @return The annotation type, as a {@code Class<?>} reference.
     * @throws IllegalArgumentException
     *             if there was a problem loading the annotation class.
     */
    public <T> Class<T> getClassRef(final Class<T> superinterfaceType) {
        return getClassRef(superinterfaceType, /* ignoreExceptions = */ false);
    }

    /**
     * Get a class reference for the annotation. Causes the ClassLoader to load the annotation class, if it is not
     * already loaded.
     * 
     * @return The annotation type, as a {@code Class<?>} reference, or null, if ignoreExceptions is true and there
     *         was an exception or error loading the class.
     * @throws IllegalArgumentException
     *             if ignoreExceptions is false and there was a problem loading the annotation class.
     */
    public Class<?> getClassRef(final boolean ignoreExceptions) {
        return scanResult.loadClass(annotationName, ignoreExceptions);
    }

    /**
     * Get a class reference for the annotation. Causes the ClassLoader to load the annotation class, if it is not
     * already loaded.
     * 
     * @return The annotation type, as a {@code Class<?>} reference.
     * @throws IllegalArgumentException
     *             if there were problems loading the annotation class.
     */
    public Class<?> getClassRef() {
        return getClassRef(/* ignoreExceptions = */ false);
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Get the parameter value of the annotation.
     * 
     * @return The annotation parameter values.
     */
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

    /**
     * Render as a string, into a StringBuilder buffer.
     * 
     * @param buf
     *            The buffer.
     */
    public void toString(final StringBuilder buf) {
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
    }

    @Override
    public String toString() {
        final StringBuilder buf = new StringBuilder();
        toString(buf);
        return buf.toString();
    }

    // -------------------------------------------------------------------------------------------------------------

    private static final String[] EMPTY_STRING_ARRAY = new String[0];

    /**
     * From a collection of AnnotationInfo objects, extract the annotation names, uniquify them, and sort them.
     * 
     * @param annotationInfo
     *            The annotation info.
     * @return The sorted, uniquified annotation names.
     */
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

    /**
     * From an array of AnnotationInfo objects, extract the annotation names, uniquify them, and sort them.
     * 
     * @param annotationInfo
     *            The annotation info.
     * @return The sorted, uniquified annotation names.
     */
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
