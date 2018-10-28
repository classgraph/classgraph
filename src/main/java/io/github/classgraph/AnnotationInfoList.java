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

import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import io.github.classgraph.InfoList.MappableInfoList;

/** A list of {@link AnnotationInfo} objects. */
public class AnnotationInfoList extends MappableInfoList<AnnotationInfo> {

    /**
     * The set of annotations directly related to a class or method and not inherited through a meta-annotated annotation.
     * This field is nullable, as they annotation info list is build incremental. To fill it accordingly, we would have
     * to overwrite all list methods. Instead, checks are handled in {@link #directOnly()}.
     */
    private final AnnotationInfoList directlyRelatedAnnotations;

    AnnotationInfoList() {
        super();
        this.directlyRelatedAnnotations = null;
    }

    AnnotationInfoList(final int sizeHint) {
        super(sizeHint);
        this.directlyRelatedAnnotations = null;
    }

    AnnotationInfoList(final AnnotationInfoList reachableAnnotations) {
        // If only reachable annotations are given, treat all of them as direct
        this(reachableAnnotations, reachableAnnotations);
    }

    AnnotationInfoList(final AnnotationInfoList reachableAnnotations,
        final AnnotationInfoList directlyRelatedAnnotations) {
        super(reachableAnnotations);
        this.directlyRelatedAnnotations = directlyRelatedAnnotations;
    }

    static final AnnotationInfoList EMPTY_LIST = new AnnotationInfoList() {
        @Override
        public boolean add(final AnnotationInfo e) {
            throw new IllegalArgumentException("List is immutable");
        }

        @Override
        public void add(final int index, final AnnotationInfo element) {
            throw new IllegalArgumentException("List is immutable");
        }

        @Override
        public boolean remove(final Object o) {
            throw new IllegalArgumentException("List is immutable");
        }

        @Override
        public AnnotationInfo remove(final int index) {
            throw new IllegalArgumentException("List is immutable");
        }

        @Override
        public boolean addAll(final Collection<? extends AnnotationInfo> c) {
            throw new IllegalArgumentException("List is immutable");
        }

        @Override
        public boolean addAll(final int index, final Collection<? extends AnnotationInfo> c) {
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
        public AnnotationInfo set(final int index, final AnnotationInfo element) {
            throw new IllegalArgumentException("List is immutable");
        }
    };

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Filter an {@link AnnotationInfoList} using a predicate mapping an {@link AnnotationInfo} object to a boolean,
     * producing another {@link AnnotationInfoList} for all items in the list for which the predicate is true.
     */
    @FunctionalInterface
    public interface AnnotationInfoFilter {
        /**
         * Whether or not to allow an {@link AnnotationInfo} list item through the filter.
         *
         * @param annotationInfo
         *            The {@link AnnotationInfo} item to filter.
         * @return Whether or not to allow the item through the filter. If true, the item is copied to the output
         *         list; if false, it is excluded.
         */
        boolean accept(AnnotationInfo annotationInfo);
    }

    /**
     * Find the subset of the {@link AnnotationInfo} objects in this list for which the given filter predicate is
     * true.
     *
     * @param filter
     *            The {@link AnnotationInfoFilter} to apply.
     * @return The subset of the {@link AnnotationInfo} objects in this list for which the given filter predicate is
     *         true.
     */
    public AnnotationInfoList filter(final AnnotationInfoFilter filter) {
        final AnnotationInfoList annotationInfoFiltered = new AnnotationInfoList();
        for (final AnnotationInfo resource : this) {
            if (filter.accept(resource)) {
                annotationInfoFiltered.add(resource);
            }
        }
        return annotationInfoFiltered;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Get the list of annotations that were directly related, as opposed to reachable through multiple steps. Direct
     * annotations are all annotations declared on the element itself and not annotations on a super class or overriden
     * method that are meta-annotated with {@link java.lang.annotation.Inherited @Inherited}.
     *
     * @return The list of directly-related annotations.
     */
    public AnnotationInfoList directOnly() {
        // Return "this" seems a bit odd at first, but it is necessary due to the way annotation info lists are
        // constructed in ClassInfo: They are initialized on first annotation added and then modified in place.
        // So they _are_ the direct annotations already and returned as is in ClassInfo#getAnnotationInfo() and
        // can be passed on here. Otherwise create the sublist.
        return this.directlyRelatedAnnotations == null ?
            this : new AnnotationInfoList(directlyRelatedAnnotations, directlyRelatedAnnotations);
    }
}
