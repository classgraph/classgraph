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

import java.lang.annotation.Inherited;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jspecify.annotations.Nullable;

import io.github.classgraph.base.LogNode;

/**
 * Holds metadata about a specific annotation instance on a class, method, method parameter or field.
 */
public final class AnnotationInfo extends ScanResultObject implements Comparable<AnnotationInfo>, HasName {
    /** The name. */
    private final String name;

    /** The annotation param values, or null if none. */
    private @Nullable AnnotationParameterValueList annotationParamValues;

    /**
     * Set to true once any Object[] arrays of boxed values or strings in annotationParamValues have been lazily
     * converted to arrays of their element type. Volatile, so that a thread that sees this flag set also sees the
     * converted values.
     */
    private volatile boolean annotationParamValuesHasBeenConvertedToPrimitive;

    /**
     * The annotation param values with defaults, or null if not yet built. Two threads may build the list at once,
     * and each stores an equal list. Volatile, so that a thread that reads the list also sees its contents.
     */
    private volatile @Nullable AnnotationParameterValueList annotationParamValuesWithDefaults;

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
     * @return true if this annotation is meta-annotated with {@link Inherited}. If the annotation class was not
     *         scanned, this is not known, so false is returned.
     */
    public boolean isInherited() {
        // The annotation class is not scanned for a type annotation declared outside the accepted packages, unlike
        // a declaration annotation, which always gets at least a placeholder ClassInfo
        final var classInfo = getClassInfo();
        return classInfo != null && classInfo.isInherited;
    }

    /**
     * Get the default parameter values.
     *
     * @return the list of default parameter values for this annotation, or the empty list if none. Default
     *         parameter values are declared by the annotation class, so if the annotation class was not scanned, or
     *         was changed into a class that is not an annotation after the use site was compiled, the empty list is
     *         returned.
     */
    public AnnotationParameterValueList getDefaultParameterValues() {
        final var classInfo = getClassInfo();
        return classInfo == null || !classInfo.isAnnotation() ? AnnotationParameterValueList.EMPTY_LIST
                : classInfo.getAnnotationDefaultParameterValues();
    }

    /**
     * Get the parameter values, optionally filling in default values.
     *
     * @param includeDefaultValues
     *            if true, include default values for any annotation parameter value that is missing.
     * @param classInfo
     *            the annotation class, or null if it was not encountered during scanning. Not used if the
     *            {@link ScanResult} has not been set.
     * @return The parameter values of this annotation, including any default parameter values inherited from the
     *         annotation class definition (if requested), or the empty list if none.
     */
    private AnnotationParameterValueList getParameterValues(final boolean includeDefaultValues,
            final @Nullable ClassInfo classInfo) {
        final var paramValues = annotationParamValues;
        if (scanResult == null) {
            // Scanning has not finished, so the annotation class may not have been read yet -- return the values
            // without defaults, and without converting arrays (happens when AnnotationInfo is logged during
            // scanning)
            return paramValues == null ? AnnotationParameterValueList.EMPTY_LIST : paramValues;
        }
        // Lazily convert any Object[] arrays of boxed types to primitive arrays. If the annotation class was not
        // scanned, the element type of each array is worked out from its elements.
        if (paramValues != null && !annotationParamValuesHasBeenConvertedToPrimitive) {
            AnnotationParameterValueList.convertWrapperArraysToPrimitiveArrays(paramValues, classInfo);
            annotationParamValuesHasBeenConvertedToPrimitive = true;
        }
        if (!includeDefaultValues || classInfo == null || !classInfo.isScannedClass || !classInfo.isAnnotation()) {
            // Don't include defaults, or the annotation class was not scanned, so its elements and their default
            // values are not known. If the annotation class was changed into a class that is not an annotation
            // after the use site was compiled, it declares no elements, so the values are also returned unchanged.
            return paramValues == null ? AnnotationParameterValueList.EMPTY_LIST : paramValues;
        }
        // Read the field only once, since another thread may set it between two reads
        var paramValuesWithDefaults = annotationParamValuesWithDefaults;
        if (paramValuesWithDefaults == null) {
            // Overwrite defaults with the values given at the use site. The parameter values themselves are kept,
            // rather than copied, since they have already been converted and given the ScanResult.
            final Map<String, AnnotationParameterValue> allParamValues = new HashMap<>();
            final var defaultParamValues = classInfo.getConvertedAnnotationDefaultParamValues();
            if (defaultParamValues != null) {
                for (final AnnotationParameterValue defaultParamValue : defaultParamValues) {
                    allParamValues.put(defaultParamValue.getName(), defaultParamValue);
                }
            }
            if (paramValues != null) {
                for (final AnnotationParameterValue annotationParamValue : paramValues) {
                    allParamValues.put(annotationParamValue.getName(), annotationParamValue);
                }
            }

            // Keep only the values of the elements the annotation class declares, as the JDK does: a value given
            // for an element that was removed from the annotation class after the use site was compiled is left
            // out. Each element is a method of the annotation class. Only elements have values, so other methods,
            // such as <clinit>, match no name, and an element may be named equals, since it takes no parameter.
            // A scanned annotation class with no methods has a null methodInfo, and has no elements.
            final List<AnnotationParameterValue> withDefaults = new ArrayList<>();
            if (classInfo.methodInfo != null) {
                for (final MethodInfo mi : classInfo.methodInfo) {
                    // Remove the value, so that it is listed only once if two methods have the same name
                    final var paramValue = allParamValues.remove(mi.getName());
                    if (paramValue != null) {
                        withDefaults.add(paramValue);
                    }
                }
            }
            // The methods of a class are sorted by name, so the parameter values are already in name order
            paramValuesWithDefaults = new AnnotationParameterValueList(withDefaults);
            annotationParamValuesWithDefaults = paramValuesWithDefaults;
        }
        return paramValuesWithDefaults;
    }

    /**
     * Get the parameter values of this annotation, including any parameter values that were not given explicitly at
     * the annotation use site, but that have a default value declared by the annotation type.
     *
     * @return The parameter values of this annotation, including any default parameter values inherited from the
     *         annotation class definition, or the empty list if none.
     */
    public AnnotationParameterValueList getParameterValues() {
        return getParameterValues(/* includeDefaultValues = */ true, getClassInfo());
    }

    /**
     * Get only the parameter values that were given explicitly at the annotation use site, without filling in any
     * default values declared by the annotation type. Compare with {@link #getParameterValues()}, which does fill
     * in defaults, and {@link #getDefaultParameterValues()}, which returns only the defaults.
     *
     * @return The parameter values given explicitly at the annotation use site, or the empty list if none.
     */
    public AnnotationParameterValueList getDeclaredParameterValues() {
        return getParameterValues(/* includeDefaultValues = */ false, getClassInfo());
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
     * Get the {@link ClassInfo} object for the annotation class, as {@link #getClassInfo()} does, but without
     * throwing if the {@link ScanResult} has been closed, so that {@link #equals(Object)}, {@link #hashCode()} and
     * {@link #compareTo(AnnotationInfo)} keep working after it is closed. The map of class names is not cleared
     * when the {@link ScanResult} is closed.
     *
     * @return the annotation class, or null if the {@link ScanResult} has not been set or the annotation class was
     *         not encountered during scanning.
     */
    private @Nullable ClassInfo getClassInfoEvenIfClosed() {
        final var sr = scanResult;
        return sr == null ? null : sr.classNameToClassInfo.get(name);
    }

    /**
     * Convert wrapper arrays to primitive arrays. Called for a nested annotation, which may be compared after the
     * {@link ScanResult} has been closed.
     */
    void convertWrapperArraysToPrimitiveArrays() {
        final var paramValues = annotationParamValues;
        if (paramValues != null) {
            AnnotationParameterValueList.convertWrapperArraysToPrimitiveArrays(paramValues,
                    getClassInfoEvenIfClosed());
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Compare annotations by name, then by parameter values, counting default values, so that the order is
     * consistent with {@link #equals(Object)}. Unlike most methods, this keeps working after the {@link ScanResult}
     * is closed, so that an {@link AnnotationInfo} in a sorted collection stays usable.
     *
     * @param o
     *            the annotation to compare with
     * @return a negative number, zero or a positive number if this annotation sorts before, the same as or after
     *         the other annotation
     */
    @Override
    public int compareTo(final AnnotationInfo o) {
        return compareTo(o, new ArrayList<>());
    }

    /**
     * Compare annotations, as in {@link #compareTo(AnnotationInfo)}.
     *
     * @param o
     *            the annotation to compare with
     * @param comparedPairs
     *            the pairs of annotations compared so far, which are either still being compared or were found
     *            equal, since any difference ends the whole comparison. Default values can form a cycle (see
     *            {@link #toString(boolean, StringBuilder, Set)}), and two annotations that expand to the same
     *            infinite annotation then lead back to a pair that is still being compared. Such a pair is counted
     *            as equal, so that the comparison ends: a difference between the two annotations is found by
     *            comparing their other parameter values.
     * @return a negative number, zero or a positive number if this annotation sorts before, the same as or after
     *         the other annotation
     */
    int compareTo(final AnnotationInfo o, final List<AnnotationInfo[]> comparedPairs) {
        final var diff = this.name.compareTo(o.name);
        if (diff != 0) {
            return diff;
        }
        for (final AnnotationInfo[] pair : comparedPairs) {
            if (pair[0] == this && pair[1] == o) {
                return 0;
            }
        }
        comparedPairs.add(new AnnotationInfo[] { this, o });
        // Both lists are sorted by parameter name
        final var paramValues = getParameterValues(/* includeDefaultValues = */ true, getClassInfoEvenIfClosed());
        final var oParamValues = o.getParameterValues(/* includeDefaultValues = */ true,
                o.getClassInfoEvenIfClosed());
        for (int i = 0, max = Math.max(paramValues.size(), oParamValues.size()); i < max; i++) {
            if (i >= paramValues.size()) {
                return -1;
            } else if (i >= oParamValues.size()) {
                return 1;
            } else {
                final var diff2 = paramValues.get(i).compareTo(oParamValues.get(i), comparedPairs);
                if (diff2 != 0) {
                    return diff2;
                }
            }
        }
        return 0;
    }

    /**
     * Two annotations are equal when they have the same name and the same parameter values, counting default
     * values, as in {@link java.lang.annotation.Annotation#equals(Object)}: {@code @A} equals {@code @A(x = 5)} if
     * 5 is the default value of {@code x}. The default values are declared by the annotation class, so they are
     * counted only if the annotation class was scanned. Unlike most methods, this keeps working after the
     * {@link ScanResult} is closed, so that an {@link AnnotationInfo} in a hash set or sorted collection stays
     * usable.
     *
     * @param obj
     *            the object to compare with
     * @return true if the other object is an equal annotation
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

    /**
     * Get the hash code, which counts default values, as {@link #equals(Object)} does. Unlike most methods, this
     * keeps working after the {@link ScanResult} is closed, so that an {@link AnnotationInfo} in a hash set stays
     * usable.
     *
     * @return the hash code
     */
    @Override
    public int hashCode() {
        // The default values are known only once the ScanResult has been set, which changes the hash code, so an
        // AnnotationInfo must not be put in a hash set or map during the scan
        var h = name.hashCode();
        for (final AnnotationParameterValue e : getParameterValues(/* includeDefaultValues = */ true,
                getClassInfoEvenIfClosed())) {
            // AnnotationParameterValue#hashCode() hashes an array-valued parameter by its elements, matching
            // equals(Object), and a nested annotation by its name only, since default values can form a cycle
            h = h * 7 + e.hashCode();
        }
        return h;
    }

    @Override
    protected void toString(final boolean useSimpleNames, final StringBuilder buf) {
        toString(useSimpleNames, buf, Collections.newSetFromMap(new IdentityHashMap<>()));
    }

    /**
     * Write the string representation of this annotation to the buffer.
     *
     * @param useSimpleNames
     *            if true, strip package and outer class names from class names
     * @param buf
     *            the buffer to append to
     * @param enclosing
     *            the annotations whose string representation is being written, which hold this annotation as a
     *            parameter value, directly or through other annotations. Default values can form a cycle, such as
     *            an element of {@code @A} whose default value is {@code @B}, and an element of {@code @B} whose
     *            default value is {@code @A}, which javac refuses to compile, but which the JVM loads. An
     *            annotation that is reached again is written without its parameter values, as {@code @A(...)}, so
     *            that writing it ends.
     */
    void toString(final boolean useSimpleNames, final StringBuilder buf, final Set<AnnotationInfo> enclosing) {
        buf.append('@').append(useSimpleNames ? ClassInfo.getSimpleName(name) : name);
        final var paramVals = getParameterValues();
        if (!paramVals.isEmpty()) {
            if (!enclosing.add(this)) {
                buf.append("(...)");
                return;
            }
            buf.append('(');
            for (var i = 0; i < paramVals.size(); i++) {
                if (i > 0) {
                    buf.append(", ");
                }
                final var paramVal = paramVals.get(i);
                if (paramVals.size() > 1 || !"value".equals(paramVal.getName())) {
                    paramVal.toString(useSimpleNames, buf, enclosing);
                } else {
                    paramVal.toStringParamValueOnly(useSimpleNames, buf, enclosing);
                }
            }
            enclosing.remove(this);
            buf.append(')');
        }
    }
}
