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

import io.github.classgraph.InfoList.MappableInfoList;

/** A list of {@link PackageInfo} objects. */
public class PackageInfoList extends MappableInfoList<PackageInfo> {

    PackageInfoList() {
        super();
    }

    PackageInfoList(final int sizeHint) {
        super(sizeHint);
    }

    PackageInfoList(final Collection<PackageInfo> packageInfoCollection) {
        super(packageInfoCollection);
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Filter an {@link PackageInfoList} using a predicate mapping an {@link PackageInfo} object to a boolean,
     * producing another {@link PackageInfoList} for all items in the list for which the predicate is true.
     */
    @FunctionalInterface
    public interface PackageInfoFilter {
        /**
         * Whether or not to allow an {@link PackageInfo} list item through the filter.
         *
         * @param packageInfo
         *            The {@link PackageInfo} item to filter.
         * @return Whether or not to allow the item through the filter. If true, the item is copied to the output
         *         list; if false, it is excluded.
         */
        public boolean accept(PackageInfo packageInfo);
    }

    /**
     * Find the subset of the {@link PackageInfo} objects in this list for which the given filter predicate is true.
     *
     * @param filter
     *            The {@link PackageInfoFilter} to apply.
     * @return The subset of the {@link PackageInfo} objects in this list for which the given filter predicate is
     *         true.
     */
    public PackageInfoList filter(final PackageInfoFilter filter) {
        final PackageInfoList packageInfoFiltered = new PackageInfoList();
        for (final PackageInfo resource : this) {
            if (filter.accept(resource)) {
                packageInfoFiltered.add(resource);
            }
        }
        return packageInfoFiltered;
    }
}
