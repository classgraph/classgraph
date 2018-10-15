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

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** A list of {@link AnnotationParameterValue} objects. */
public class AnnotationParameterValueList extends ArrayList<AnnotationParameterValue> {
    AnnotationParameterValueList() {
        super();
    }

    AnnotationParameterValueList(final int sizeHint) {
        super(sizeHint);
    }

    AnnotationParameterValueList(final Collection<AnnotationParameterValue> AnnotationParameterValueCollection) {
        super(AnnotationParameterValueCollection);
    }

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
     * @return The names of all {@link AnnotationParameterValue} objects in this list, by calling
     *         {@link AnnotationParameterValue#getName()} for each item in the list.
     */
    public List<String> getNames() {
        if (this.isEmpty()) {
            return Collections.<String> emptyList();
        } else {
            final List<String> classNames = new ArrayList<>(this.size());
            for (final AnnotationParameterValue apv : this) {
                classNames.add(apv.getName());
            }
            return classNames;
        }
    }

    /**
     * @return The string representations of all annotation parameter values in this list, by calling
     *         {@link AnnotationParameterValue#toString()} for each item in the list.
     */
    public List<String> getAsStrings() {
        if (this.isEmpty()) {
            return Collections.<String> emptyList();
        } else {
            final List<String> toStringVals = new ArrayList<>(this.size());
            for (final AnnotationParameterValue apv : this) {
                toStringVals.add(apv.toString());
            }
            return toStringVals;
        }
    }

    /**
     * @return This {@link AnnotationParameterValueList} as a map from annotation parameter name to
     *         {@link AnnotationParameterValue} object.
     */
    public Map<String, AnnotationParameterValue> asMap() {
        final Map<String, AnnotationParameterValue> annotationNameToAnnotationParameterValue = new HashMap<>();
        for (final AnnotationParameterValue apv : this) {
            annotationNameToAnnotationParameterValue.put(apv.getName(), apv);
        }
        return annotationNameToAnnotationParameterValue;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * For primitive array type params, replace Object[] arrays containing boxed types with primitive arrays (need
     * to check the type of each method of the annotation class to determine if it is a primitive array type).
     */
    void convertWrapperArraysToPrimitiveArrays(final ClassInfo annotationClassInfo) {
        for (int i = 0; i < size(); i++) {
            final AnnotationParameterValue annotationParamValue = get(i);
            annotationParamValue.convertWrapperArraysToPrimitiveArrays(annotationClassInfo);
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * @param parameterName
     *            The name of an annotation parameter.
     * @return true if this list contains an annotation parameter with the given name.
     */
    public boolean containsName(final String parameterName) {
        for (final AnnotationParameterValue apv : this) {
            if (apv.getName().equals(parameterName)) {
                return true;
            }
        }
        return false;
    }

    /**
     * @param parameterName
     *            The name of an annotation parameter.
     * @return The value of the {@link AnnotationParameterValue} object in the list with the given name, or null if
     *         not found.
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
    public Object get(final String parameterName) {
        for (final AnnotationParameterValue apv : this) {
            if (apv.getName().equals(parameterName)) {
                return apv.getValue();
            }
        }
        return null;
    }
}
