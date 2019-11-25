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
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/** A list of {@link MethodInfo} objects. */
public class MethodInfoList extends InfoList<MethodInfo> {
    /** An unmodifiable empty {@link MethodInfoList}. */
    static final MethodInfoList EMPTY_LIST = new MethodInfoList();
    static {
        EMPTY_LIST.makeUnmodifiable();
    }

    /**
     * Return an unmodifiable empty {@link MethodInfoList}.
     *
     * @return the unmodifiable empty {@link MethodInfoList}.
     */
    public static MethodInfoList emptyList() {
        return EMPTY_LIST;
    }

    /** Construct a new modifiable empty list of {@link MethodInfo} objects. */
    public MethodInfoList() {
        super();
    }

    /**
     * Construct a new modifiable empty list of {@link MethodInfo} objects, given a size hint.
     *
     * @param sizeHint
     *            the size hint
     */
    public MethodInfoList(final int sizeHint) {
        super(sizeHint);
    }

    /**
     * Construct a new modifiable empty {@link MethodInfoList}, given an initial collection of {@link MethodInfo}
     * objects.
     *
     * @param methodInfoCollection
     *            the collection of {@link MethodInfo} objects.
     */
    public MethodInfoList(final Collection<MethodInfo> methodInfoCollection) {
        super(methodInfoCollection);
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Get {@link ClassInfo} objects for any classes referenced in the type descriptor or type signature.
     *
     * @param classNameToClassInfo
     *            the map from class name to {@link ClassInfo}.
     * @param refdClassInfo
     *            the referenced class info
     */
    protected void findReferencedClassInfo(final Map<String, ClassInfo> classNameToClassInfo,
            final Set<ClassInfo> refdClassInfo) {
        for (final MethodInfo mi : this) {
            mi.findReferencedClassInfo(classNameToClassInfo, refdClassInfo);
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Get this {@link MethodInfoList} as a map from method name to a {@link MethodInfoList} of methods with that
     * name.
     *
     * @return This {@link MethodInfoList} as a map from method name to a {@link MethodInfoList} of methods with
     *         that name.
     */
    public Map<String, MethodInfoList> asMap() {
        // Note that MethodInfoList extends InfoList rather than MappableInfoList, because one
        // name can be shared by multiple MethodInfo objects (so asMap() needs to be of type
        // Map<String, MethodInfoList> rather than Map<String, MethodInfo>)
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
     * Check whether the list contains a method with the given name.
     *
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
     * due to overloading, so this returns a {@link MethodInfoList} rather than a single {@link MethodInfo}.)
     * 
     * @param methodName
     *            The name of a method.
     * @return A {@link MethodInfoList} of {@link MethodInfo} objects from this list that have the given name (there
     *         may be more than one method with a given name, due to overloading, so this returns a
     *         {@link MethodInfoList} rather than a single {@link MethodInfo}). Returns the empty list if no method
     *         had a matching name.
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
        boolean accept(MethodInfo methodInfo);
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
