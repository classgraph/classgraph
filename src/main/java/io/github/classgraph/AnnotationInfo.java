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

import java.lang.annotation.Inherited;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import nonapi.io.github.classgraph.utils.LogNode;
import org.jspecify.annotations.Nullable;

/**
 * Holds metadata about a specific annotation instance on a class, method, method parameter or field.
 */
public class AnnotationInfo extends ScanResultObject implements Comparable<AnnotationInfo>, HasName {
    /** The name. */
    private final String name;

    /** The annotation param values, or null if none. */
    private @Nullable AnnotationParameterValueList annotationParamValues;

    /**
     * Set to true once any Object[] arrays of boxed types in annotationParamValues have been lazily converted to
     * primitive arrays.
     */
    private boolean annotationParamValuesHasBeenConvertedToPrimitive;

    /** The annotation param values with defaults. */
    private @Nullable AnnotationParameterValueList annotationParamValuesWithDefaults;

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Constructor.
     *
     * @param name
     *            The name of the annotation.
     * @param annotationParamValues
     *            The annotation parameter values, or null if none.
     */
    AnnotationInfo(final String name, final @Nullable AnnotationParameterValueList annotationParamValues) {
        super();
        this.name = name;
        this.annotationParamValues = annotationParamValues;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Get the name.
     *
     * @return The name of the annotation class.
     */
    @Override
    public String getName() {
        return name;
    }

    /**
     * Checks if the annotation is inherited.
     *
     * @return true if this annotation is meta-annotated with {@link Inherited}.
     */
    public boolean isInherited() {
        return Objects.requireNonNull(getClassInfo()).isInherited;
    }

    /**
     * Get the default parameter values.
     *
     * @return the list of default parameter values for this annotation, or the empty list if none.
     */
    public AnnotationParameterValueList getDefaultParameterValues() {
        return Objects.requireNonNull(getClassInfo()).getAnnotationDefaultParameterValues();
    }

    /**
     * Get the parameter values, optionally filling in default values.
     *
     * @param includeDefaultValues
     *            if true, include default values for any annotation parameter value that is missing.
     * @return The parameter values of this annotation, including any default parameter values inherited from the
     *         annotation class definition (if requested), or the empty list if none.
     */
    private AnnotationParameterValueList getParameterValues(final boolean includeDefaultValues) {
        final var paramValues = annotationParamValues;
        final var classInfo = getClassInfo();
        if (classInfo == null) {
            // ClassInfo has not yet been set, just return values without defaults
            // (happens when trying to log AnnotationInfo during scanning, before ScanResult
            // is available)
            return paramValues == null ? AnnotationParameterValueList.EMPTY_LIST : paramValues;
        }
        // Lazily convert any Object[] arrays of boxed types to primitive arrays
        if (paramValues != null && !annotationParamValuesHasBeenConvertedToPrimitive) {
            paramValues.convertWrapperArraysToPrimitiveArrays(classInfo);
            annotationParamValuesHasBeenConvertedToPrimitive = true;
        }
        if (!includeDefaultValues) {
            // Don't include defaults
            return paramValues == null ? AnnotationParameterValueList.EMPTY_LIST : paramValues;
        }
        if (annotationParamValuesWithDefaults == null) {
            if (classInfo.annotationDefaultParamValues != null
                    && !classInfo.annotationDefaultParamValuesHasBeenConvertedToPrimitive) {
                classInfo.annotationDefaultParamValues.convertWrapperArraysToPrimitiveArrays(classInfo);
                classInfo.annotationDefaultParamValuesHasBeenConvertedToPrimitive = true;
            }

            // Check if one or both of the defaults and the values in this annotation
            // instance are null (empty)
            final var defaultParamValues = classInfo.annotationDefaultParamValues;
            if (defaultParamValues == null) {
                return paramValues == null ? AnnotationParameterValueList.EMPTY_LIST : paramValues;
            } else if (paramValues == null) {
                return defaultParamValues;
            }

            // Overwrite defaults with non-defaults
            final Map<String, Object> allParamValues = new HashMap<>();
            for (final AnnotationParameterValue defaultParamValue : defaultParamValues) {
                allParamValues.put(defaultParamValue.getName(), defaultParamValue.getValue());
            }
            for (final AnnotationParameterValue annotationParamValue : paramValues) {
                allParamValues.put(annotationParamValue.getName(), annotationParamValue.getValue());
            }

            // Put annotation values in the same order as the annotation methods (there is
            // one method for each
            // annotation constant)
            if (classInfo.methodInfo == null) {
                // Should not happen (when classfile is read, methods are always read, whether
                // or not
                // scanSpec.enableMethodInfo is true)
                throw new IllegalStateException("Could not find methods for annotation " + classInfo.getName());
            }
            final var withDefaults = new AnnotationParameterValueList();
            annotationParamValuesWithDefaults = withDefaults;
            for (final MethodInfo mi : classInfo.methodInfo) {
                final var paramName = mi.getName();
                switch (paramName) {
                // None of these method names should be present in the @interface class itself,
                // it should only
                // contain methods for the annotation constants (but skip them anyway to be
                // safe). These methods
                // should only exist in concrete instances of the annotation.
                case "<init>", "<clinit>", "hashCode", "equals", "toString", "annotationType" -> {
                    // Skip
                }
                default -> {
                    // Annotation constant
                    final var paramValue = allParamValues.get(paramName);
                    // Annotation values cannot be null (or absent, from either defaults or
                    // annotation instance)
                    if (paramValue != null) {
                        withDefaults.add(new AnnotationParameterValue(paramName, paramValue));
                    }
                }
                }
            }
        }
        return Objects.requireNonNull(annotationParamValuesWithDefaults);
    }

    /**
     * Get the parameter values of this annotation, including any parameter values that were not given explicitly at
     * the annotation use site, but that have a default value declared by the annotation type.
     *
     * @return The parameter values of this annotation, including any default parameter values inherited from the
     *         annotation class definition, or the empty list if none.
     */
    public AnnotationParameterValueList getParameterValues() {
        return getParameterValues(/* includeDefaultValues = */ true);
    }

    /**
     * Get only the parameter values that were given explicitly at the annotation use site, without filling in any
     * default values declared by the annotation type. Compare with {@link #getParameterValues()}, which does fill
     * in defaults, and {@link #getDefaultParameterValues()}, which returns only the defaults.
     *
     * @return The parameter values given explicitly at the annotation use site, or the empty list if none.
     */
    public AnnotationParameterValueList getDeclaredParameterValues() {
        return getParameterValues(/* includeDefaultValues = */ false);
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Get the name of the annotation class, for {@link #getClassInfo()}.
     *
     * @return the class name
     */
    @Override
    protected String getClassName() {
        return name;
    }

    /*
     * (non-Javadoc)
     *
     * @see
     * io.github.classgraph.ScanResultObject#setScanResult(io.github.classgraph.
     * ScanResult)
     */
    @Override
    void setScanResult(final @Nullable ScanResult scanResult) {
        super.setScanResult(scanResult);
        final var paramValues = annotationParamValues;
        if (paramValues != null) {
            for (final AnnotationParameterValue a : paramValues) {
                a.setScanResult(scanResult);
            }
        }
    }

    /**
     * Get {@link ClassInfo} objects for any classes referenced in the type descriptor or type signature.
     *
     * @param classNameToClassInfo
     *            the map from class name to {@link ClassInfo}.
     * @param refdClassInfo
     *            the referenced class info
     */
    @Override
    void findReferencedClassInfo(final Map<String, ClassInfo> classNameToClassInfo,
            final Set<ClassInfo> refdClassInfo, final @Nullable LogNode log) {
        super.findReferencedClassInfo(classNameToClassInfo, refdClassInfo, log);
        final var paramValues = annotationParamValues;
        if (paramValues != null) {
            for (final AnnotationParameterValue annotationParamValue : paramValues) {
                annotationParamValue.findReferencedClassInfo(classNameToClassInfo, refdClassInfo, log);
            }
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Return the {@link ClassInfo} object for the annotation class, or null if the annotation class was not
     * encountered during scanning.
     */
    @Override
    public @Nullable ClassInfo getClassInfo() {
        return super.getClassInfo();
    }

    /**
     * Convert wrapper arrays to primitive arrays.
     */
    void convertWrapperArraysToPrimitiveArrays() {
        final var paramValues = annotationParamValues;
        if (paramValues != null) {
            paramValues.convertWrapperArraysToPrimitiveArrays(getClassInfo());
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /*
     * (non-Javadoc)
     *
     * @see java.lang.Comparable#compareTo(java.lang.Object)
     */
    @Override
    public int compareTo(final AnnotationInfo o) {
        final var diff = this.name.compareTo(o.name);
        if (diff != 0) {
            return diff;
        }
        final var paramValues = annotationParamValues;
        final var oParamValues = o.annotationParamValues;
        if (paramValues == null && oParamValues == null) {
            return 0;
        } else if (paramValues == null) {
            return -1;
        } else if (oParamValues == null) {
            return 1;
        } else {
            for (int i = 0, max = Math.max(paramValues.size(), oParamValues.size()); i < max; i++) {
                if (i >= paramValues.size()) {
                    return -1;
                } else if (i >= oParamValues.size()) {
                    return 1;
                } else {
                    final var diff2 = paramValues.get(i).compareTo(oParamValues.get(i));
                    if (diff2 != 0) {
                        return diff2;
                    }
                }
            }
        }
        return 0;
    }

    /*
     * (non-Javadoc)
     *
     * @see java.lang.Object#equals(java.lang.Object)
     */
    @Override
    public boolean equals(final @Nullable Object obj) {
        if (obj == this) {
            return true;
        }
        if (!(obj instanceof final AnnotationInfo other)) {
            return false;
        }
        return this.compareTo(other) == 0;
    }

    /*
     * (non-Javadoc)
     *
     * @see java.lang.Object#hashCode()
     */
    @Override
    public int hashCode() {
        var h = name.hashCode();
        final var paramValues = annotationParamValues;
        if (paramValues != null) {
            for (final AnnotationParameterValue e : paramValues) {
                h = h * 7 + e.getName().hashCode() * 3 + Objects.requireNonNull(e.getValue()).hashCode();
            }
        }
        return h;
    }

    @Override
    protected void toString(final boolean useSimpleNames, final StringBuilder buf) {
        buf.append('@').append(useSimpleNames ? ClassInfo.getSimpleName(name) : name);
        final var paramVals = getParameterValues();
        if (!paramVals.isEmpty()) {
            buf.append('(');
            for (var i = 0; i < paramVals.size(); i++) {
                if (i > 0) {
                    buf.append(", ");
                }
                final var paramVal = paramVals.get(i);
                if (paramVals.size() > 1 || !"value".equals(paramVal.getName())) {
                    paramVal.toString(useSimpleNames, buf);
                } else {
                    paramVal.toStringParamValueOnly(useSimpleNames, buf);
                }
            }
            buf.append(')');
        }
    }
}
