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

import static io.github.classgraph.PotentiallyUnmodifiableList.unmodifiable;

import java.io.Serial;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

import nonapi.io.github.classgraph.utils.Assert;
import nonapi.io.github.classgraph.utils.LogNode;
import org.jspecify.annotations.Nullable;

/** A list of {@link FieldInfo} objects, which can be indexed by field name. */
public class FieldInfoList extends MappableInfoList<FieldInfo> {
    /** serialVersionUID */
    @Serial
    private static final long serialVersionUID = 1L;

    /** An unmodifiable empty {@link FieldInfoList}. */
    static final FieldInfoList EMPTY_LIST = new FieldInfoList();
    static {
        EMPTY_LIST.makeUnmodifiable();
    }

    /**
     * Return an unmodifiable empty {@link FieldInfoList}.
     *
     * @return the unmodifiable empty {@link FieldInfoList}.
     */
    public static FieldInfoList emptyList() {
        return EMPTY_LIST;
    }

    /**
     * Construct a new modifiable empty list of {@link FieldInfo} objects.
     */
    public FieldInfoList() {
        super();
    }

    /**
     * Construct a new modifiable empty list of {@link FieldInfo} objects, given a size hint.
     *
     * @param sizeHint
     *            the expected number of elements
     */
    public FieldInfoList(final int sizeHint) {
        super(sizeHint);
    }

    /**
     * Construct a new modifiable empty {@link FieldInfoList}, given an initial list of {@link FieldInfo} objects.
     *
     * @param fieldInfoCollection
     *            the collection of {@link FieldInfo} objects.
     */
    public FieldInfoList(final Collection<FieldInfo> fieldInfoCollection) {
        super(Objects.requireNonNull(fieldInfoCollection, "fieldInfoCollection must not be null"));
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Get {@link ClassInfo} objects for any classes referenced in the list.
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
        for (final FieldInfo fi : this) {
            fi.findReferencedClassInfo(classNameToClassInfo, refdClassInfo, log);
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Find the subset of the {@link FieldInfo} objects in this list for which the given filter predicate is true.
     *
     * @param filter
     *            The filter to apply. Only the {@link FieldInfo} objects for which the filter returns true are
     *            copied to the returned list.
     * @return The subset of the {@link FieldInfo} objects in this list for which the given filter predicate is
     *         true.
     */
    public FieldInfoList filter(final Predicate<FieldInfo> filter) {
        Assert.notNull(filter, "filter");
        final FieldInfoList fieldInfoFiltered = new FieldInfoList();
        for (final FieldInfo fieldInfo : this) {
            if (filter.test(fieldInfo)) {
                fieldInfoFiltered.add(fieldInfo);
            }
        }
        return unmodifiable(fieldInfoFiltered);
    }
}
