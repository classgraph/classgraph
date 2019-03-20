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

/** A list of {@link ModuleInfo} objects. */
public class ModuleInfoList extends MappableInfoList<ModuleInfo> {
    /**
     * Constructor.
     */
    ModuleInfoList() {
        super();
    }

    /**
     * Constructor.
     *
     * @param sizeHint
     *            the size hint
     */
    ModuleInfoList(final int sizeHint) {
        super(sizeHint);
    }

    /**
     * Constructor.
     *
     * @param moduleInfoCollection
     *            the module info collection
     */
    ModuleInfoList(final Collection<ModuleInfo> moduleInfoCollection) {
        super(moduleInfoCollection);
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Filter an {@link ModuleInfoList} using a predicate mapping an {@link ModuleInfo} object to a boolean,
     * producing another {@link ModuleInfoList} for all items in the list for which the predicate is true.
     */
    @FunctionalInterface
    public interface ModuleInfoFilter {
        /**
         * Whether or not to allow an {@link ModuleInfo} list item through the filter.
         *
         * @param moduleInfo
         *            The {@link ModuleInfo} item to filter.
         * @return Whether or not to allow the item through the filter. If true, the item is copied to the output
         *         list; if false, it is excluded.
         */
        boolean accept(ModuleInfo moduleInfo);
    }

    /**
     * Find the subset of the {@link ModuleInfo} objects in this list for which the given filter predicate is true.
     *
     * @param filter
     *            The {@link ModuleInfoFilter} to apply.
     * @return The subset of the {@link ModuleInfo} objects in this list for which the given filter predicate is
     *         true.
     */
    public ModuleInfoList filter(final ModuleInfoFilter filter) {
        final ModuleInfoList moduleInfoFiltered = new ModuleInfoList();
        for (final ModuleInfo resource : this) {
            if (filter.accept(resource)) {
                moduleInfoFiltered.add(resource);
            }
        }
        return moduleInfoFiltered;
    }
}
