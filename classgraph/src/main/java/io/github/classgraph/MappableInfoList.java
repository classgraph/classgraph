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
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

import io.github.classgraph.base.internal.utils.Assert;
import org.jspecify.annotations.Nullable;

/**
 * A list of named objects that can be indexed by name.
 *
 * @param <T>
 *            the element type
 */
public class MappableInfoList<T extends HasName> extends InfoList<T> {
    /** serialVersionUID. */
    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * Constructor.
     */
    MappableInfoList() {
        super();
    }

    /**
     * Constructor.
     *
     * @param sizeHint
     *            the expected number of elements
     */
    MappableInfoList(final int sizeHint) {
        super(sizeHint);
    }

    /**
     * Constructor.
     *
     * @param infoCollection
     *            the initial elements
     */
    MappableInfoList(final Collection<T> infoCollection) {
        super(infoCollection);
    }

    /**
     * Constructor.
     *
     * @param infoCollection
     *            the initial elements
     * @param modifiable
     *            whether the list may be modified after construction
     */
    MappableInfoList(final Collection<T> infoCollection, final boolean modifiable) {
        super(infoCollection, modifiable);
    }

    /**
     * Get an index for this list, as a map from the name of each list item (obtained by calling {@code getName()}
     * on each list item) to the list item.
     *
     * @return An index for this list, as a map from the name of each list item (obtained by calling
     *         {@code getName()} on each list item) to the list item, sorted by name.
     */
    public Map<String, T> asMap() {
        final Map<String, T> nameToInfoObject = new TreeMap<>();
        for (final T i : this) {
            if (i != null) {
                nameToInfoObject.put(i.getName(), i);
            }
        }
        return Collections.unmodifiableMap(nameToInfoObject);
    }

    /**
     * Check if this list contains an item with the given name.
     *
     * @param name
     *            The name to search for.
     * @return true if this list contains an item with the given name.
     */
    public boolean containsName(final String name) {
        Assert.notNull(name, "name");
        for (final T i : this) {
            if (i != null && i.getName().equals(name)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Get the list item with the given name, or null if not found.
     *
     * @param name
     *            The name to search for.
     * @return The list item with the given name, or null if not found.
     */
    public @Nullable T get(final String name) {
        Assert.notNull(name, "name");
        for (final T i : this) {
            if (i != null && i.getName().equals(name)) {
                return i;
            }
        }
        return null;
    }
}
