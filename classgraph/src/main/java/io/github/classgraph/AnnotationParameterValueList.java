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

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import io.github.classgraph.base.LogNode;
import io.github.classgraph.base.internal.utils.Assert;
import io.github.classgraph.base.internal.utils.CollectionUtils;
import org.jspecify.annotations.Nullable;

/** A list of {@link AnnotationParameterValue} objects, which can be indexed by annotation parameter name. */
public class AnnotationParameterValueList extends MappableInfoList<AnnotationParameterValue> {
    /** An unmodifiable empty {@link AnnotationParameterValueList}. */
    static final AnnotationParameterValueList EMPTY_LIST = new AnnotationParameterValueList(List.of());

    /**
     * Return an unmodifiable empty {@link AnnotationParameterValueList}.
     *
     * @return the unmodifiable empty {@link AnnotationParameterValueList}.
     */
    public static AnnotationParameterValueList emptyList() {
        return EMPTY_LIST;
    }

    /**
     * Construct a new unmodifiable {@link AnnotationParameterValueList}, given an initial collection of
     * {@link AnnotationParameterValue} objects. The collection is copied, so changing it afterwards does not change
     * this list, and the parameter values are sorted by name.
     *
     * @param annotationParameterValueCollection
     *            the collection of {@link AnnotationParameterValue} objects.
     */
    public AnnotationParameterValueList(
            final Collection<AnnotationParameterValue> annotationParameterValueCollection) {
        this(CollectionUtils.sortCopy(Objects.requireNonNull(annotationParameterValueCollection,
                "annotationParameterValueCollection must not be null")));
    }

    /**
     * Constructor. As in {@link InfoList#InfoList(List)}, this list claims the given list, and the caller is
     * responsible for having sorted it.
     *
     * @param annotationParameterValues
     *            the elements of the list
     */
    AnnotationParameterValueList(final List<AnnotationParameterValue> annotationParameterValues) {
        super(annotationParameterValues);
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Get {@link ClassInfo} objects for any classes referenced in the methods in this list.
     *
     * @param classNameToClassInfo
     *            the map from class name to {@link ClassInfo}.
     * @param refdClassInfo
     *            the referenced class info
     * @param log
     *            the log node, or null to skip logging
     */
    void findReferencedClassInfo(final Map<String, ClassInfo> classNameToClassInfo,
            final Set<ClassInfo> refdClassInfo, final @Nullable LogNode log) {
        for (final AnnotationParameterValue apv : this) {
            apv.findReferencedClassInfo(classNameToClassInfo, refdClassInfo, log);
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * For primitive array type params, replace Object[] arrays containing boxed types with primitive arrays (need
     * to check the type of each method of the annotation class to determine if it is a primitive array type).
     *
     * @param paramValues
     *            the annotation parameter values to convert
     * @param annotationClassInfo
     *            the annotation class info
     */
    static void convertWrapperArraysToPrimitiveArrays(final List<AnnotationParameterValue> paramValues,
            final @Nullable ClassInfo annotationClassInfo) {
        for (final AnnotationParameterValue apv : paramValues) {
            apv.convertWrapperArraysToPrimitiveArrays(annotationClassInfo);
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Get the annotation parameter value, by calling {@link AnnotationParameterValue#getValue()} on the result of
     * {@link #get(String)}, if non-null.
     *
     * @param parameterName
     *            The name of an annotation parameter.
     * @return The value of the {@link AnnotationParameterValue} object in the list with the given name, by calling
     *         {@link AnnotationParameterValue#getValue()} on that object, or null if not found.
     *
     *         <p>
     *         The annotation parameter value may be one of the following types:
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
    public @Nullable Object getValue(final String parameterName) {
        Assert.notNull(parameterName, "parameterName");
        final var apv = get(parameterName);
        return apv == null ? null : apv.getValue();
    }
}
