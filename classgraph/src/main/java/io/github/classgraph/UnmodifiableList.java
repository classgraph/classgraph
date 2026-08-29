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

import java.util.AbstractList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.RandomAccess;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

/**
 * The unmodifiable list that the lists returned by the ClassGraph API are built on.
 *
 * <p>
 * {@link AbstractList} supplies most of the unmodifiability: the methods that add, remove or replace a single
 * element are left unimplemented by it, so they throw {@link UnsupportedOperationException}. The bulk methods that
 * {@link java.util.AbstractCollection} and {@link List} build on top of those, however, only reach them if there is
 * something to change, so on an empty list, or with an argument that would not change the list, they would
 * otherwise silently succeed. They are therefore overridden here to be rejected unconditionally, which is also what
 * the unmodifiable views returned by {@link java.util.Collections} do.
 *
 * @param <T>
 *            the element type
 */
abstract class UnmodifiableList<T> extends AbstractList<T> implements RandomAccess {
    /** The elements of this list, as an unmodifiable view. */
    private final List<T> elements;

    /**
     * Constructor.
     *
     * <p>
     * This list takes ownership of the given list rather than copying it, so the caller must not use the list again
     * after handing it over. A constructor that is reachable from outside this package therefore has to make the
     * copy itself, since it cannot make that demand of its caller.
     *
     * <p>
     * Sorting is also the caller's job, for lists whose order would otherwise be undefined, so that a list is never
     * sorted twice.
     *
     * @param elements
     *            the elements of the list
     */
    UnmodifiableList(final List<T> elements) {
        this.elements = Collections.unmodifiableList(elements);
    }

    @Override
    public T get(final int index) {
        return elements.get(index);
    }

    @Override
    public int size() {
        return elements.size();
    }

    // -------------------------------------------------------------------------------------------------------------
    // Views of this list, which have to reject modification too. These are delegated to the unmodifiable view of
    // the elements, rather than inherited from AbstractList, because AbstractList's iterators reject modification
    // only once they have an element to modify: listIterator(i).set(x) throws IllegalStateException rather than
    // UnsupportedOperationException if next() has not been called yet.

    @Override
    public Iterator<T> iterator() {
        return elements.iterator();
    }

    @Override
    public ListIterator<T> listIterator(final int index) {
        return elements.listIterator(index);
    }

    @Override
    public List<T> subList(final int fromIndex, final int toIndex) {
        return elements.subList(fromIndex, toIndex);
    }

    // -------------------------------------------------------------------------------------------------------------
    // Bulk mutators, which have to be rejected even when they would not change the list

    @Override
    public boolean addAll(final Collection<? extends T> c) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean addAll(final int index, final Collection<? extends T> c) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean remove(final Object o) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean removeAll(final Collection<?> c) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean retainAll(final Collection<?> c) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean removeIf(final Predicate<? super T> filter) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void replaceAll(final UnaryOperator<T> operator) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void sort(final Comparator<? super T> c) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void clear() {
        throw new UnsupportedOperationException();
    }
}
