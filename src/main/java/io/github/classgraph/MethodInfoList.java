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
import java.util.HashMap;
import java.util.Map;

/** A list of {@link MethodInfo} objects. */
public class MethodInfoList extends InfoList<MethodInfo> {

    /** Construct a list of {@link MethodInfo} objects. */
    MethodInfoList() {
        super();
    }

    MethodInfoList(final int sizeHint) {
        super(sizeHint);
    }

    MethodInfoList(final Collection<MethodInfo> methodInfoCollection) {
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

    /**
     * @return This {@link MethodInfoList} as a map from method name to a {@link MethodInfoList} of methods with
     *         that name.
     */
    public Map<String, MethodInfoList> asMap() {
        final Map<String, MethodInfoList> methodNameToMethodInfoList = new HashMap<>();
        for (final MethodInfo methodInfo : this) {
            final String name = methodInfo.getName();
            MethodInfoList methodInfoList = methodNameToMethodInfoList.get(name);
            if (methodInfoList == null) {
                methodInfoList = new MethodInfoList(1);
                methodNameToMethodInfoList.put(name, methodInfoList);
            }
            methodInfoList.add(methodInfo);
        }
        return methodNameToMethodInfoList;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * @param methodName
     *            The name of a class.
     * @return true if the list contains a method with the given name.
     */
    public boolean containsName(final String methodName) {
        for (final MethodInfo mi : this) {
            if (mi.getName().equals(methodName)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns a list of all methods matching a given name. (There may be more than one method with a given name,
     * due to overloading.)
     * 
     * @param methodName
     *            The name of a method.
     * @return A list of {@link MethodInfo} objects in the list with the given name (there may be more than one
     *         method with a given name, due to overloading). Returns the empty list if no method had a matching
     *         name.
     */
    public MethodInfoList get(final String methodName) {
        boolean hasMethodWithName = false;
        for (final MethodInfo mi : this) {
            if (mi.getName().equals(methodName)) {
                hasMethodWithName = true;
                break;
            }
        }
        if (!hasMethodWithName) {
            return EMPTY_LIST;
        } else {
            final MethodInfoList matchingMethods = new MethodInfoList(2);
            for (final MethodInfo mi : this) {
                if (mi.getName().equals(methodName)) {
                    matchingMethods.add(mi);
                }
            }
            return matchingMethods;
        }
    }

    /**
     * Returns a single method with the given name, or null if not found. Throws {@link IllegalArgumentException} if
     * there are two methods with the given name.
     * 
     * @param methodName
     *            The name of a method.
     * @return The {@link MethodInfo} object from the list with the given name, if there is exactly one method with
     *         the given name. Returns null if there were no methods with the given name.
     * @throws IllegalArgumentException
     *             if there are two or more methods with the given name.
     */
    public MethodInfo getSingleMethod(final String methodName) {
        int numMethodsWithName = 0;
        MethodInfo lastFoundMethod = null;
        for (final MethodInfo mi : this) {
            if (mi.getName().equals(methodName)) {
                numMethodsWithName++;
                lastFoundMethod = mi;
            }
        }
        if (numMethodsWithName == 0) {
            return null;
        } else if (numMethodsWithName == 1) {
            return lastFoundMethod;
        } else {
            throw new IllegalArgumentException("There are multiple methods named \"" + methodName + "\" in class "
                    + iterator().next().getName());
        }
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
