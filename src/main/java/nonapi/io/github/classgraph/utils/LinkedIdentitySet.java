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
package nonapi.io.github.classgraph.utils;

import java.util.AbstractSet;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * A set that compares its elements by reference identity rather than {@code equals()}, and iterates them in
 * insertion order. Removal operations are not supported and throw {@link UnsupportedOperationException}.
 *
 * @param <E>
 *            the element type.
 */
public final class LinkedIdentitySet<E> extends AbstractSet<E> {
    /** The elements, for reference-equality lookup. */
    private final Map<E, Boolean> elements = new IdentityHashMap<>();

    /** The elements, in the order they were added. */
    private final List<E> elementsInOrder = new ArrayList<>();

    /** Constructor. */
    public LinkedIdentitySet() {
    }

    /**
     * Add an element, if the same object is not already in the set.
     *
     * @param element
     *            the element to add.
     * @return true if the element was added, or false if the very same object was already in the set.
     */
    @Override
    public boolean add(final E element) {
        if (elements.put(element, Boolean.TRUE) == null) {
            elementsInOrder.add(element);
            return true;
        }
        return false;
    }

    /**
     * Determine whether the very same object is in the set.
     *
     * @param element
     *            the element to look for.
     * @return true if the very same object is in the set.
     */
    @Override
    public boolean contains(final Object element) {
        return elements.containsKey(element);
    }

    /**
     * Iterate the elements in the order they were added. The iterator does not support removal.
     *
     * @return the iterator.
     */
    @Override
    public Iterator<E> iterator() {
        return Collections.unmodifiableList(elementsInOrder).iterator();
    }

    /**
     * The number of elements in the set.
     *
     * @return the number of elements.
     */
    @Override
    public int size() {
        return elementsInOrder.size();
    }

    /** The message of the {@link UnsupportedOperationException} that every removal method throws. */
    private static final String CANNOT_REMOVE = "Elements cannot be removed from a LinkedIdentitySet";

    /**
     * Elements cannot be removed.
     *
     * @param element
     *            ignored.
     * @return never returns.
     */
    @Override
    public boolean remove(final Object element) {
        // AbstractSet#remove removes the first element that equals() the given one, which need not be the element
        // that #contains reports, since #contains compares by reference. Rather than remove an element that was
        // never asked for, refuse: the point of this set is that equals() cannot be trusted to mean "same object"
        throw new UnsupportedOperationException(CANNOT_REMOVE);
    }

    /**
     * Elements cannot be removed.
     *
     * @param elements
     *            ignored.
     * @return never returns.
     */
    @Override
    public boolean removeAll(final Collection<?> elements) {
        throw new UnsupportedOperationException(CANNOT_REMOVE);
    }

    /**
     * Elements cannot be removed.
     *
     * @param elements
     *            ignored.
     * @return never returns.
     */
    @Override
    public boolean retainAll(final Collection<?> elements) {
        throw new UnsupportedOperationException(CANNOT_REMOVE);
    }

    /**
     * Not supported: elements cannot be removed.
     *
     * @throws UnsupportedOperationException
     *             always.
     */
    @Override
    public void clear() {
        // AbstractCollection#clear removes through the iterator, which refuses removal, so clearing a non-empty
        // set already threw -- but clearing an empty set had nothing to remove and returned quietly
        throw new UnsupportedOperationException(CANNOT_REMOVE);
    }
}
