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
import java.util.function.Predicate;

import io.github.classgraph.base.internal.utils.Assert;

/** A list of {@link ModuleInfo} objects, which can be indexed by module name. */
public class ModuleInfoList extends MappableInfoList<ModuleInfo> {
    /** serialVersionUID. */
    @Serial
    private static final long serialVersionUID = 1L;

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
     *            the expected number of elements
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
     * Find the subset of the {@link ModuleInfo} objects in this list for which the given filter predicate is true.
     *
     * @param filter
     *            The filter to apply. Only the {@link ModuleInfo} objects for which the filter returns true are
     *            copied to the returned list.
     * @return The subset of the {@link ModuleInfo} objects in this list for which the given filter predicate is
     *         true.
     */
    public ModuleInfoList filter(final Predicate<ModuleInfo> filter) {
        Assert.notNull(filter, "filter");
        final ModuleInfoList moduleInfoFiltered = new ModuleInfoList();
        for (final ModuleInfo moduleInfo : this) {
            if (filter.test(moduleInfo)) {
                moduleInfoFiltered.add(moduleInfo);
            }
        }
        return unmodifiable(moduleInfoFiltered);
    }
}
