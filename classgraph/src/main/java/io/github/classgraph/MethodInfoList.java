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

import static io.github.classgraph.PotentiallyUnmodifiableList.unmodifiable;

import java.io.Serial;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

import io.github.classgraph.base.internal.utils.Assert;
import io.github.classgraph.base.internal.utils.LogNode;
import org.jspecify.annotations.Nullable;

/**
 * A list of {@link MethodInfo} objects.
 *
 * <p>
 * Unlike the other list types, this is not a {@link MappableInfoList}, because method names are not unique within a
 * class: a class may declare several overloads of the same method name. Consequently {@link #get(String)} returns a
 * {@link MethodInfoList} of all the overloads with the requested name, and {@link #asMap()} maps each method name
 * to a {@link MethodInfoList}. Use {@link #getSingleMethod(String)} when you expect exactly one method of a given
 * name.
 */
public class MethodInfoList extends InfoList<MethodInfo> {
    /** serialVersionUID */
    @Serial
    private static final long serialVersionUID = 1L;

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
     *            the expected number of elements
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
        super(Objects.requireNonNull(methodInfoCollection, "methodInfoCollection must not be null"));
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Get {@link ClassInfo} objects for any classes referenced in the type descriptor or type signature.
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
        for (final MethodInfo mi : this) {
            mi.findReferencedClassInfo(classNameToClassInfo, refdClassInfo, log);
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
        // Note that MethodInfoList extends InfoList rather than MappableInfoList, because one name can be shared by
        // multiple MethodInfo objects (so asMap() needs to be of type Map<String, MethodInfoList> rather than
        // Map<String, MethodInfo>)
        final Map<String, MethodInfoList> methodNameToMethodInfoList = new HashMap<>();
        for (final MethodInfo methodInfo : this) {
            methodNameToMethodInfoList.computeIfAbsent(methodInfo.getName(), k -> new MethodInfoList(1))
                    .add(methodInfo);
        }
        for (final MethodInfoList methodInfoList : methodNameToMethodInfoList.values()) {
            methodInfoList.makeUnmodifiable();
        }
        return Collections.unmodifiableMap(methodNameToMethodInfoList);
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
        Assert.notNull(methodName, "methodName");
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
        Assert.notNull(methodName, "methodName");
        var hasMethodWithName = false;
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
            return unmodifiable(matchingMethods);
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
    public @Nullable MethodInfo getSingleMethod(final String methodName) {
        Assert.notNull(methodName, "methodName");
        MethodInfo foundMethod = null;
        for (final MethodInfo mi : this) {
            if (mi.getName().equals(methodName)) {
                if (foundMethod != null) {
                    throw new IllegalArgumentException("There are multiple methods named \"" + methodName
                            + "\" in class " + mi.getClassName());
                }
                foundMethod = mi;
            }
        }
        return foundMethod;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Find the subset of the {@link MethodInfo} objects in this list for which the given filter predicate is true.
     *
     * @param filter
     *            The filter to apply. Only the {@link MethodInfo} objects for which the filter returns true are
     *            copied to the returned list.
     * @return The subset of the {@link MethodInfo} objects in this list for which the given filter predicate is
     *         true.
     */
    public MethodInfoList filter(final Predicate<MethodInfo> filter) {
        Assert.notNull(filter, "filter");
        final MethodInfoList methodInfoFiltered = new MethodInfoList();
        for (final MethodInfo methodInfo : this) {
            if (filter.test(methodInfo)) {
                methodInfoFiltered.add(methodInfo);
            }
        }
        return unmodifiable(methodInfoFiltered);
    }
}
