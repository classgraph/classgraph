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

import java.io.Serial;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Objects;
import java.util.function.Predicate;

import io.github.classgraph.base.internal.utils.Assert;
import io.github.classgraph.base.internal.utils.CollectionUtils;

/** A list of {@link PackageInfo} objects, which can be indexed by package name. */
public class PackageInfoList extends MappableInfoList<PackageInfo> {
    /** serialVersionUID. */
    @Serial
    private static final long serialVersionUID = 1L;

    /** An unmodifiable empty {@link PackageInfoList}. */
    static final PackageInfoList EMPTY_LIST = new PackageInfoList();
    static {
        EMPTY_LIST.makeUnmodifiable();
    }

    /**
     * Return an unmodifiable empty {@link PackageInfoList}.
     *
     * @return the unmodifiable empty {@link PackageInfoList}.
     */
    public static PackageInfoList emptyList() {
        return EMPTY_LIST;
    }

    /**
     * Construct a new modifiable empty list of {@link PackageInfo} objects.
     */
    PackageInfoList() {
        super();
    }

    /**
     * Construct a new modifiable empty list of {@link PackageInfo} objects, given a size hint.
     *
     * @param sizeHint
     *            the expected number of elements
     */
    PackageInfoList(final int sizeHint) {
        super(sizeHint);
    }

    /**
     * Construct a new unmodifiable {@link PackageInfoList} from a completed collection of {@link PackageInfo}
     * objects. The collection is copied.
     *
     * @param packageInfoCollection
     *            the package info collection
     */
    public PackageInfoList(final Collection<PackageInfo> packageInfoCollection) {
        super(CollectionUtils
                .sortCopy(Objects.requireNonNull(packageInfoCollection, "packageInfoCollection must not be null")),
                /* modifiable = */ false);
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Find the subset of the {@link PackageInfo} objects in this list for which the given filter predicate is true.
     *
     * @param filter
     *            The filter to apply. Only the {@link PackageInfo} objects for which the filter returns true are
     *            copied to the returned list.
     * @return The subset of the {@link PackageInfo} objects in this list for which the given filter predicate is
     *         true.
     */
    public PackageInfoList filter(final Predicate<PackageInfo> filter) {
        Assert.notNull(filter, "filter");
        final var packageInfoFiltered = new ArrayList<PackageInfo>();
        for (final PackageInfo packageInfo : this) {
            if (filter.test(packageInfo)) {
                packageInfoFiltered.add(packageInfo);
            }
        }
        return new PackageInfoList(packageInfoFiltered);
    }
}
