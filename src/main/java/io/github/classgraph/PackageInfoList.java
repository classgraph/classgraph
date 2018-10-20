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

/** A list of {@link PackageInfo} objects. */
public class PackageInfoList extends ArrayList<PackageInfo> {

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
     * @return The names of all packages in this list, by calling {@link PackageInfo#getName()} for each item in the
     *         list.
     */
    public List<String> getNames() {
        if (this.isEmpty()) {
            return Collections.<String> emptyList();
        } else {
            final List<String> packageNames = new ArrayList<>(this.size());
            for (final PackageInfo pi : this) {
                packageNames.add(pi.getName());
            }
            return packageNames;
        }
    }

    /**
     * @return This {@link PackageInfoList} as a map from package name to {@link PackageInfo} object.
     */
    public Map<String, PackageInfo> asMap() {
        final Map<String, PackageInfo> packageNameToPackageInfo = new HashMap<>();
        for (final PackageInfo pi : this) {
            packageNameToPackageInfo.put(pi.getName(), pi);
        }
        return packageNameToPackageInfo;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * @param packageName
     *            The name of a package.
     * @return true if this list contains a package with the given name.
     */
    public boolean containsName(final String packageName) {
        for (final PackageInfo pi : this) {
            if (pi.getName().equals(packageName)) {
                return true;
            }
        }
        return false;
    }

    /**
     * @param packageName
     *            The name of a package.
     * @return The {@link PackageInfo} object in the list with the given name, or null if not found.
     */
    public PackageInfo get(final String packageName) {
        for (final PackageInfo pi : this) {
            if (pi.getName().equals(packageName)) {
                return pi;
            }
        }
        return null;
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
