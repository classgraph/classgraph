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
package io.github.classgraph.classpath.internal;

import java.util.AbstractSet;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * An insertion-ordered set that compares elements by reference identity ({@code ==}) rather than
 * {@link Object#equals(Object)}. Removal operations are not supported.
 *
 * @param <E>
 *            the element type.
 */
final class LinkedIdentitySet<E> extends AbstractSet<E> {
    /** Identity map backing membership checks. */
    private final Map<E, Boolean> elements = new IdentityHashMap<>();

    /** Elements in insertion order. */
    private final List<E> elementsInOrder = new ArrayList<>();

    /** Constructor. */
    LinkedIdentitySet() {
    }

    /**
     * Adds {@code element} if an identical reference is not already present.
     *
     * @param element
     *            the element to add.
     * @return true if the element was added, or false if the reference was already present.
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
     * Checks whether the given object reference is present in this set.
     *
     * @param element
     *            the object reference to look up.
     * @return true if the reference is in the set.
     */
    @Override
    public boolean contains(final Object element) {
        return elements.containsKey(element);
    }

    /**
     * Returns an unmodifiable iterator over the elements in insertion order.
     *
     * @return an iterator over the elements.
     */
    @Override
    public Iterator<E> iterator() {
        return Collections.unmodifiableList(elementsInOrder).iterator();
    }

    /**
     * Returns the number of elements in the set.
     *
     * @return the number of elements.
     */
    @Override
    public int size() {
        return elementsInOrder.size();
    }

    /** Exception message for unsupported removal operations. */
    private static final String CANNOT_REMOVE = "Elements cannot be removed from a LinkedIdentitySet";

    /**
     * Unsupported operation.
     *
     * @param element
     *            ignored.
     * @return never returns.
     * @throws UnsupportedOperationException
     *             always.
     */
    @Override
    public boolean remove(final Object element) {
        // AbstractSet#remove removes the first element that equals() the given one, which need not be the element
        // that #contains reports, since #contains compares by reference. Rather than remove an element that was
        // never asked for, refuse: the point of this set is that equals() cannot be trusted to mean "same object"
        throw new UnsupportedOperationException(CANNOT_REMOVE);
    }

    /**
     * Unsupported operation.
     *
     * @param elements
     *            ignored.
     * @return never returns.
     * @throws UnsupportedOperationException
     *             always.
     */
    @Override
    public boolean removeAll(final Collection<?> elements) {
        throw new UnsupportedOperationException(CANNOT_REMOVE);
    }

    /**
     * Unsupported operation.
     *
     * @param elements
     *            ignored.
     * @return never returns.
     * @throws UnsupportedOperationException
     *             always.
     */
    @Override
    public boolean retainAll(final Collection<?> elements) {
        throw new UnsupportedOperationException(CANNOT_REMOVE);
    }

    /**
     * Unsupported operation.
     *
     * @throws UnsupportedOperationException
     *             always.
     */
    @Override
    public void clear() {
        throw new UnsupportedOperationException(CANNOT_REMOVE);
    }
}
