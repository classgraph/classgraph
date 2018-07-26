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
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/** A list of {@link MethodInfo} objects. */
public class MethodInfoList extends ArrayList<MethodInfo> {

    public MethodInfoList() {
        super();
    }

    public MethodInfoList(final int sizeHint) {
        super(sizeHint);
    }

    public MethodInfoList(final Collection<MethodInfo> methodInfoCollection) {
        super(methodInfoCollection);
    }

    static final MethodInfoList EMPTY_LIST = new MethodInfoList() {
        @Override
        public boolean add(final MethodInfo e) {
            throw new IllegalArgumentException("List is immutable");
        }

        @Override
        public void add(final int index, final MethodInfo element) {
            throw new IllegalArgumentException("List is immutable");
        }

        @Override
        public boolean remove(final Object o) {
            throw new IllegalArgumentException("List is immutable");
        }

        @Override
        public MethodInfo remove(final int index) {
            throw new IllegalArgumentException("List is immutable");
        }

        @Override
        public boolean addAll(final Collection<? extends MethodInfo> c) {
            throw new IllegalArgumentException("List is immutable");
        }

        @Override
        public boolean addAll(final int index, final Collection<? extends MethodInfo> c) {
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
        public MethodInfo set(final int index, final MethodInfo element) {
            throw new IllegalArgumentException("List is immutable");
        }
    };

    // -------------------------------------------------------------------------------------------------------------

    /** Get the names of all methods in this list. */
    public List<String> getNames() {
        if (this.isEmpty()) {
            return Collections.<String> emptyList();
        } else {
            final List<String> methodNames = new ArrayList<>(this.size());
            for (final MethodInfo mi : this) {
                methodNames.add(mi.getName());
            }
            return methodNames;
        }
    }

    /**
     * Get the string representations of all methods in this list (with annotations, modifiers, params, etc.), by
     * calling {@link MethodInfo#toString()} on each item in the list.
     */
    public List<String> getAsStrings() {
        if (this.isEmpty()) {
            return Collections.<String> emptyList();
        } else {
            final List<String> toStringVals = new ArrayList<>(this.size());
            for (final MethodInfo mi : this) {
                toStringVals.add(mi.toString());
            }
            return toStringVals;
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /** Return true if this list contains a method with the given name. */
    public boolean containsName(final String methodName) {
        for (final MethodInfo mi : this) {
            if (mi.getName().equals(methodName)) {
                return true;
            }
        }
        return false;
    }

    /** Return the {@link MethodInfo} object in the list with the given name, or null if not found. */
    public MethodInfo get(final String methodName) {
        for (final MethodInfo mi : this) {
            if (mi.getName().equals(methodName)) {
                return mi;
            }
        }
        return null;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Filter an {@link MethodInfoList} using a predicate mapping an {@link MethodInfo} object to a boolean,
     * producing another {@link MethodInfoList} for all items in the list for which the predicate is true.
     */
    @FunctionalInterface
    public interface MethodInfoFilter {
        /**
         * Whether or not to allow an {@link MethodInfo} list item through the filter.
         *
         * @param methodInfo
         *            The {@link MethodInfo} item to filter.
         * @return Whether or not to allow the item through the filter. If true, the item is copied to the output
         *         list; if false, it is excluded.
         */
        public boolean accept(MethodInfo methodInfo);
    }

    /**
     * Find the subset of the {@link MethodInfo} objects in this list for which the given filter predicate is true.
     *
     * @param filter
     *            The {@link MethodInfoFilter} to apply.
     * @return The subset of the {@link MethodInfo} objects in this list for which the given filter predicate is
     *         true.
     */
    public MethodInfoList filter(final MethodInfoFilter filter) {
        final MethodInfoList methodInfoFiltered = new MethodInfoList();
        for (final MethodInfo resource : this) {
            if (filter.accept(resource)) {
                methodInfoFiltered.add(resource);
            }
        }
        return methodInfoFiltered;
    }
}
