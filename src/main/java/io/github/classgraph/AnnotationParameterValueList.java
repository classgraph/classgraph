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

import java.util.Collection;
import java.util.Set;

import io.github.classgraph.InfoList.MappableInfoList;

/** A list of {@link AnnotationParameterValue} objects. */
public class AnnotationParameterValueList extends MappableInfoList<AnnotationParameterValue> {

    /**
     * Constructor.
     */
    AnnotationParameterValueList() {
        super();
    }

    /**
     * Constructor.
     *
     * @param sizeHint
     *            the size hint
     */
    AnnotationParameterValueList(final int sizeHint) {
        super(sizeHint);
    }

    /**
     * Constructor.
     *
     * @param AnnotationParameterValueCollection
     *            the annotation parameter value collection
     */
    AnnotationParameterValueList(final Collection<AnnotationParameterValue> AnnotationParameterValueCollection) {
        super(AnnotationParameterValueCollection);
    }

    /** An unmodifiable empty {@link AnnotationParameterValueList}. */
    static final AnnotationParameterValueList EMPTY_LIST = new AnnotationParameterValueList() {
        @Override
        public boolean add(final AnnotationParameterValue e) {
            throw new IllegalArgumentException("List is immutable");
        }

        @Override
        public void add(final int index, final AnnotationParameterValue element) {
            throw new IllegalArgumentException("List is immutable");
        }

        @Override
        public boolean remove(final Object o) {
            throw new IllegalArgumentException("List is immutable");
        }

        @Override
        public AnnotationParameterValue remove(final int index) {
            throw new IllegalArgumentException("List is immutable");
        }

        @Override
        public boolean addAll(final Collection<? extends AnnotationParameterValue> c) {
            throw new IllegalArgumentException("List is immutable");
        }

        @Override
        public boolean addAll(final int index, final Collection<? extends AnnotationParameterValue> c) {
            throw new IllegalArgumentException("List is immutable");
        }

        @Override
        public boolean removeAll(final Collection<?> c) {
            throw new IllegalArgumentException("List is immutable");
        }

        @Override
        public boolean retainAll(final Collection<?> c) {
            throw new IllegalArgumentException("List is immutable");
        }

        @Override
        public void clear() {
            throw new IllegalArgumentException("List is immutable");
        }

        @Override
        public AnnotationParameterValue set(final int index, final AnnotationParameterValue element) {
            throw new IllegalArgumentException("List is immutable");
        }
    };

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Find the names of any classes referenced in the methods in this list.
     *
     * @param referencedClassNames
     *            the referenced class names
     */
    void findReferencedClassNames(final Set<String> referencedClassNames) {
        for (final AnnotationParameterValue apv : this) {
            apv.findReferencedClassNames(referencedClassNames);
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
        for (final AnnotationParameterValue annotationParamValue : this) {
            annotationParamValue.convertWrapperArraysToPrimitiveArrays(annotationClassInfo);
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
    public Object getValue(final String parameterName) {
        final AnnotationParameterValue apv = get(parameterName);
        return apv == null ? null : apv.getValue();
    }
}
