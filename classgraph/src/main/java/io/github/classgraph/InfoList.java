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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A list of named objects.
 *
 * <p>
 * Lists returned by the ClassGraph API are unmodifiable: their elements are fixed when the list is created, and
 * every method that would add, remove, replace or sort an element throws {@link UnsupportedOperationException}.
 * Copy the list if you need a modifiable version of it, e.g. {@code new ArrayList<>(list)}.
 *
 * @param <T>
 *            the element type
 */
public class InfoList<T extends HasName> extends UnmodifiableList<T> {
    /**
     * Constructor. As in {@link UnmodifiableList#UnmodifiableList(List)}, this list claims the given list rather
     * than copying it, and the caller is responsible for having sorted it.
     *
     * @param elements
     *            the elements of the list
     */
    InfoList(final List<T> elements) {
        super(elements);
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Get the names of all items in this list, by calling {@code getName()} on each item in the list.
     *
     * @return The names of all items in this list, by calling {@code getName()} on each item in the list.
     */
    public List<String> getNames() {
        if (this.isEmpty()) {
            return List.of();
        } else {
            final List<String> names = new ArrayList<>(this.size());
            for (final T i : this) {
                names.add(i.getName());
            }
            return Collections.unmodifiableList(names);
        }
    }

    /**
     * Get the String representations of all items in this list, by calling {@code toString()} on each item in the
     * list.
     *
     * @return The String representations of all items in this list, by calling {@code toString()} on each item in
     *         the list.
     */
    public List<String> getAsStrings() {
        if (this.isEmpty()) {
            return List.of();
        } else {
            final List<String> toStringVals = new ArrayList<>(this.size());
            for (final T i : this) {
                toStringVals.add(i.toString());
            }
            return Collections.unmodifiableList(toStringVals);
        }
    }

    /**
     * Get the String representations of all items in this list, using only <a href=
     * "https://docs.oracle.com/en/java/javase/15/docs/api/java.base/java/lang/Class.html#getSimpleName()">simple
     * names</a> of any named classes, by calling {@code ScanResultObject#toStringWithSimpleNames()} if the object
     * is a subclass of {@code ScanResultObject} (e.g. {@link ClassInfo}, {@link MethodInfo} or {@link FieldInfo}
     * object), otherwise calling {@code toString()}, for each item in the list.
     *
     * @return The String representations of all items in this list, using only the <a href=
     *         "https://docs.oracle.com/en/java/javase/15/docs/api/java.base/java/lang/Class.html#getSimpleName()">
     *         simple names</a> of any named classes.
     */
    public List<String> getAsStringsWithSimpleNames() {
        if (this.isEmpty()) {
            return List.of();
        } else {
            final List<String> toStringVals = new ArrayList<>(this.size());
            for (final T i : this) {
                toStringVals.add(i instanceof final ScanResultObject scanResultObject
                        ? scanResultObject.toStringWithSimpleNames()
                        : i.toString());
            }
            return Collections.unmodifiableList(toStringVals);
        }
    }
}
