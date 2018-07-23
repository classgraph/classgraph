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

import java.lang.annotation.Inherited;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

/** Holds metadata about a specific annotation instance on a class, method or field. */
public class AnnotationInfo extends ScanResultObject implements Comparable<AnnotationInfo> {

    String name;
    List<AnnotationParamValue> annotationParamValues;

    /** Link back to the ClassInfo class containing default annotation param values. */
    private ClassInfo classInfo;

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

    /** Set the ClassInfo value (so that the annotation default parameter values can be read). */
    void setClassInfo(final ClassInfo classInfo) {
        this.classInfo = classInfo;
        if (annotationParamValues != null) {
            for (final AnnotationParamValue a : annotationParamValues) {
                if (a != null) {
                    a.setClassInfo(classInfo);
                }
            }
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * @param name
     *            The name of the annotation.
     * @param annotationParamValues
     *            The annotation parameter values, or null if none.
     */
    public AnnotationInfo(final String name, final List<AnnotationParamValue> annotationParamValues) {
        this.name = name;
        this.annotationParamValues = annotationParamValues;
    }

    /**
     * Get the name of the annotation.
     * 
     * @return The annotation name.
     */
    public String getName() {
        return name;
    }

    /**
     * Get the {@link ClassInfo} object for the annotation class of this annotation.
     * 
     * @return The {@link ClassInfo} object for this annotation.
     */
    public ClassInfo getClassInfo() {
        return classInfo;
    }

    /** Returns true if this annotation is meta-annotated with {@link Inherited}. */
    public boolean isInherited() {
        return classInfo.isInherited;
    }

    /** Returns the list of default parameter values for this annotation, or the empty list if there are none. */
    public List<AnnotationParamValue> getDefaultParameterValues() {
        final List<AnnotationParamValue> annotationDefaultParamValues = classInfo.getAnnotationDefaultParamValues();
        return annotationDefaultParamValues == null ? Collections.<AnnotationParamValue> emptyList()
                : annotationDefaultParamValues;
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
    public <T> Class<T> loadClass(final Class<T> superinterfaceType, final boolean ignoreExceptions) {
        return scanResult.loadClass(getName(), superinterfaceType, ignoreExceptions);
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
    public <T> Class<T> loadClass(final Class<T> superinterfaceType) {
        return loadClass(superinterfaceType, /* ignoreExceptions = */ false);
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
    public Class<?> loadClass(final boolean ignoreExceptions) {
        return scanResult.loadClass(getName(), ignoreExceptions);
    }

    /**
     * Get a class reference for the annotation. Causes the ClassLoader to load the annotation class, if it is not
     * already loaded.
     * 
     * @return The annotation type, as a {@code Class<?>} reference.
     * @throws IllegalArgumentException
     *             if there were problems loading the annotation class.
     */
    public Class<?> loadClass() {
        return loadClass(/* ignoreExceptions = */ false);
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Get the parameter value of this annotation, including any default values inherited from the annotation class
     * definition, or the empty list if none.
     * 
     * @return The annotation parameter values, including any default values, or the empty list if none.
     */
    public List<AnnotationParamValue> getAnnotationParamValues() {
        final List<AnnotationParamValue> defaultParamValues = classInfo.annotationDefaultParamValues;

        // Check if one or both of the defaults and the values in this annotation instance are null (empty)
        if (defaultParamValues == null && annotationParamValues == null) {
            return Collections.<AnnotationParamValue> emptyList();
        } else if (defaultParamValues == null) {
            return annotationParamValues;
        } else if (annotationParamValues == null) {
            return defaultParamValues;
        }

        // Overwrite defaults with non-defaults
        final Map<String, Object> allParamValues = new HashMap<>();
        for (final AnnotationParamValue defaultParamValue : defaultParamValues) {
            allParamValues.put(defaultParamValue.paramName, defaultParamValue.paramValue.get());
        }
        for (final AnnotationParamValue annotationParamValue : this.annotationParamValues) {
            allParamValues.put(annotationParamValue.paramName, annotationParamValue.paramValue.get());
        }
        final List<AnnotationParamValue> result = new ArrayList<>();
        for (final Entry<String, Object> ent : allParamValues.entrySet()) {
            result.add(new AnnotationParamValue(ent.getKey(), ent.getValue()));
        }
        Collections.sort(result);
        return result;
    }

    @Override
    public int compareTo(final AnnotationInfo o) {
        final int diff = getName().compareTo(o.getName());
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
        int h = getName().hashCode();
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
        buf.append("@" + getName());
        final List<AnnotationParamValue> paramVals = getAnnotationParamValues();
        if (paramVals != null && !paramVals.isEmpty()) {
            buf.append('(');
            for (int i = 0; i < paramVals.size(); i++) {
                if (i > 0) {
                    buf.append(", ");
                }
                final AnnotationParamValue annotationParamValue = paramVals.get(i);
                if (paramVals.size() > 1 || !"value".equals(annotationParamValue.paramName)) {
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
}
