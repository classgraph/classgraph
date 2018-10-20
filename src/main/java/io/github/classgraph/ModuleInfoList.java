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

/** A list of {@link ModuleInfo} objects. */
public class ModuleInfoList extends ArrayList<ModuleInfo> {

    ModuleInfoList() {
        super();
    }

    ModuleInfoList(final int sizeHint) {
        super(sizeHint);
    }

    ModuleInfoList(final Collection<ModuleInfo> moduleInfoCollection) {
        super(moduleInfoCollection);
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * @return The names of all modules in this list, by calling {@link ModuleInfo#getName()} for each item in the
     *         list.
     */
    public List<String> getNames() {
        if (this.isEmpty()) {
            return Collections.<String> emptyList();
        } else {
            final List<String> moduleNames = new ArrayList<>(this.size());
            for (final ModuleInfo mi : this) {
                moduleNames.add(mi.getName());
            }
            return moduleNames;
        }
    }

    /**
     * @return This {@link ModuleInfoList} as a map from module name to {@link ModuleInfo} object.
     */
    public Map<String, ModuleInfo> asMap() {
        final Map<String, ModuleInfo> moduleNameToModuleInfo = new HashMap<>();
        for (final ModuleInfo mi : this) {
            moduleNameToModuleInfo.put(mi.getName(), mi);
        }
        return moduleNameToModuleInfo;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * @param moduleName
     *            The name of a module.
     * @return true if this list contains a module with the given name.
     */
    public boolean containsName(final String moduleName) {
        for (final ModuleInfo mi : this) {
            if (mi.getName().equals(moduleName)) {
                return true;
            }
        }
        return false;
    }

    /**
     * @param moduleName
     *            The name of a module.
     * @return The {@link ModuleInfo} object in the list with the given name, or null if not found.
     */
    public ModuleInfo get(final String moduleName) {
        for (final ModuleInfo mi : this) {
            if (mi.getName().equals(moduleName)) {
                return mi;
            }
        }
        return null;
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
        public boolean accept(ModuleInfo moduleInfo);
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
