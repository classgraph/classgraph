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
package io.github.classgraph.base.internal.utils;

import java.util.AbstractSet;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * A set that compares its elements by reference rather than by {@code equals()}, and that iterates them in the
 * order they were added: a {@link java.util.LinkedHashSet} with the comparison semantics of an
 * {@link IdentityHashMap}.
 *
 * <p>
 * This is for deduplicating objects whose {@code equals()} method cannot be trusted to mean "the same object". Two
 * {@link ClassLoader} instances are two classloaders, and each of them can load a different set of classes,
 * whatever either of them says about being equal to the other -- TomEE makes an instance of
 * {@code CxfContainerClassLoader} equal to the instance of {@code TomEEWebappClassLoader} that it delegates to
 * (#515), so an equals-based set drops whichever of the two it sees second, and the classpath entries of the
 * dropped one are never scanned.
 *
 * <p>
 * Like {@link Collections#newSetFromMap(Map)} over an {@link IdentityHashMap}, this deliberately breaks the general
 * contract of {@link java.util.Set}, which is defined in terms of {@code equals()}. Elements cannot be removed:
 * every removal method throws {@link UnsupportedOperationException}, rather than fall back on the equals-based
 * implementation it inherits and remove an element that was never asked for.
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
     * Not supported: elements cannot be removed.
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
     * Not supported: elements cannot be removed.
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
     * Not supported: elements cannot be removed.
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
}
