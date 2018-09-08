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
package io.github.classgraph;

import java.lang.annotation.Inherited;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

/** Holds metadata about a specific annotation instance on a class, method, method parameter or field. */
public class AnnotationInfo extends ScanResultObject implements Comparable<AnnotationInfo> {

    private String name;
    private List<AnnotationParameterValue> annotationParamValues;

    private transient List<AnnotationParameterValue> annotationParamValuesWithDefaults;

    /** Default constructor for deserialization. */
    AnnotationInfo() {
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * @param name
     *            The name of the annotation.
     * @param annotationParamValues
     *            The annotation parameter values, or null if none.
     */
    AnnotationInfo(final String name, final List<AnnotationParameterValue> annotationParamValues) {
        this.name = name;
        this.annotationParamValues = annotationParamValues;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * @return The name of the annotation class.
     */
    public String getName() {
        return name;
    }

    /**
     * @return true if this annotation is meta-annotated with {@link Inherited}.
     */
    public boolean isInherited() {
        return getClassInfo().isInherited;
    }

    /**
     * @return the list of default parameter values for this annotation, or the empty list if none.
     */
    public List<AnnotationParameterValue> getDefaultParameterValues() {
        return getClassInfo().getAnnotationDefaultParameterValues();
    }

    /**
     * @return The parameter values of this annotation, including any default parameter values inherited from the
     *         annotation class definition, or the empty list if none.
     */
    public List<AnnotationParameterValue> getParameterValues() {
        if (annotationParamValuesWithDefaults == null) {
            final ClassInfo classInfo = getClassInfo();
            if (classInfo == null) {
                // ClassInfo has not yet been set, just return values without defaults
                // (happens when trying to log AnnotationInfo during scanning, before ScanResult is available)
                return annotationParamValues;
            }
            final List<AnnotationParameterValue> defaultParamValues = classInfo.annotationDefaultParamValues;

            // Check if one or both of the defaults and the values in this annotation instance are null (empty)
            if (defaultParamValues == null && annotationParamValues == null) {
                return Collections.<AnnotationParameterValue> emptyList();
            } else if (defaultParamValues == null) {
                return annotationParamValues;
            } else if (annotationParamValues == null) {
                return defaultParamValues;
            }

            // Overwrite defaults with non-defaults
            final Map<String, Object> allParamValues = new HashMap<>();
            for (final AnnotationParameterValue defaultParamValue : defaultParamValues) {
                allParamValues.put(defaultParamValue.getName(), defaultParamValue.getValue());
            }
            for (final AnnotationParameterValue annotationParamValue : this.annotationParamValues) {
                allParamValues.put(annotationParamValue.getName(), annotationParamValue.getValue());
            }
            annotationParamValuesWithDefaults = new ArrayList<>();
            for (final Entry<String, Object> ent : allParamValues.entrySet()) {
                annotationParamValuesWithDefaults.add(new AnnotationParameterValue(ent.getKey(), ent.getValue()));
            }
            Collections.sort(annotationParamValuesWithDefaults);
        }
        return annotationParamValuesWithDefaults;
    }

    // -------------------------------------------------------------------------------------------------------------

    /** Return the name of the annotation class, for {@link #getClassInfo()}. */
    @Override
    protected String getClassName() {
        return name;
    }

    /** @return The {@link ClassInfo} object for the annotation class. */
    @Override
    public ClassInfo getClassInfo() {
        getClassName();
        return super.getClassInfo();
    }

    @Override
    void setScanResult(final ScanResult scanResult) {
        super.setScanResult(scanResult);
        if (annotationParamValues != null) {
            for (final AnnotationParameterValue a : annotationParamValues) {
                if (a != null) {
                    a.setScanResult(scanResult);
                }
            }
        }
    }

    /** Get the names of any classes referenced in the type descriptors of annotation parameters. */
    @Override
    void getClassNamesFromTypeDescriptors(final Set<String> classNames) {
        classNames.add(name);
        if (annotationParamValues != null) {
            for (final AnnotationParameterValue annotationParamValue : annotationParamValues) {
                annotationParamValue.getClassNamesFromTypeDescriptors(classNames);
            }
        }
    }

    // -------------------------------------------------------------------------------------------------------------

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
                final AnnotationParameterValue e = annotationParamValues.get(i);
                h = h * 7 + e.getName().hashCode() * 3 + e.getValue().hashCode();
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
    void toString(final StringBuilder buf) {
        buf.append("@" + getName());
        final List<AnnotationParameterValue> paramVals = getParameterValues();
        if (paramVals != null && !paramVals.isEmpty()) {
            buf.append('(');
            for (int i = 0; i < paramVals.size(); i++) {
                if (i > 0) {
                    buf.append(", ");
                }
                final AnnotationParameterValue annotationParamValue = paramVals.get(i);
                if (paramVals.size() > 1 || !"value".equals(annotationParamValue.getName())) {
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
