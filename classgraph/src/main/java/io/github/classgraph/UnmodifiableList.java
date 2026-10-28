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
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.RandomAccess;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

import org.jetbrains.annotations.Unmodifiable;

/**
 * Base class for unmodifiable lists returned by the ClassGraph API.
 *
 * @param <T>
 *            the element type
 */
// TODO: This class and every public list class that extends it is annotated @Unmodifiable, as are the views returned
// by iterator(), listIterator(), subList() and reversed() below, but Kotlin ignores the annotation on a class, and on
// a method that overrides a method of a java.util interface: it honors it only on a method that returns a java.util
// collection interface type and overrides nothing. Kotlin therefore still sees these lists and their views as
// mutable, and only the deprecation of the mutators below makes it warn about a call to one of them on a list.
// Update this whenever Kotlin resolves https://youtrack.jetbrains.com/issue/KT-70485 in a reasonable way, so that
// Kotlin sees these classes as read-only lists. See also https://github.com/classgraph/classgraph/issues/948
@Unmodifiable
abstract class UnmodifiableList<T> extends AbstractList<T> implements RandomAccess {
    /** The elements of this list, as an unmodifiable view. */
    private final List<T> elements;

    /**
     * Construct an {@link UnmodifiableList} backed by the given list.
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
    // UnsupportedOperationException if next() has not been called yet. Their @Unmodifiable annotations are ignored by
    // Kotlin for now: see the TODO on this class.

    @Override
    public @Unmodifiable Iterator<T> iterator() {
        return elements.iterator();
    }

    @Override
    public @Unmodifiable ListIterator<T> listIterator() {
        return elements.listIterator();
    }

    @Override
    public @Unmodifiable ListIterator<T> listIterator(final int index) {
        return elements.listIterator(index);
    }

    @Override
    public @Unmodifiable List<T> subList(final int fromIndex, final int toIndex) {
        return elements.subList(fromIndex, toIndex);
    }

    // List's default reversed() returns a view whose addAll(), removeAll() and retainAll() succeed when they would
    // not change the list, as Collections.unmodifiableList(list).reversed() does too. Since this list never changes,
    // a reversed copy is equivalent to a view.

    @Override
    public @Unmodifiable List<T> reversed() {
        final var reversed = new ArrayList<>(elements);
        Collections.reverse(reversed);
        return Collections.unmodifiableList(reversed);
    }

    // -------------------------------------------------------------------------------------------------------------
    // Single-element mutators, which AbstractList already rejects. They are overridden only to deprecate them.

    /**
     * @deprecated This list is unmodifiable, so this method always throws {@link UnsupportedOperationException}.
     */
    @Deprecated
    @Override
    public boolean add(final T e) {
        throw new UnsupportedOperationException();
    }

    /**
     * @deprecated This list is unmodifiable, so this method always throws {@link UnsupportedOperationException}.
     */
    @Deprecated
    @Override
    public void add(final int index, final T element) {
        throw new UnsupportedOperationException();
    }

    /**
     * @deprecated This list is unmodifiable, so this method always throws {@link UnsupportedOperationException}.
     */
    @Deprecated
    @Override
    public T set(final int index, final T element) {
        throw new UnsupportedOperationException();
    }

    /**
     * @deprecated This list is unmodifiable, so this method always throws {@link UnsupportedOperationException}.
     */
    @Deprecated
    @Override
    public T remove(final int index) {
        throw new UnsupportedOperationException();
    }

    /**
     * @deprecated This list is unmodifiable, so this method always throws {@link UnsupportedOperationException}.
     */
    @Deprecated
    @Override
    public void addFirst(final T e) {
        throw new UnsupportedOperationException();
    }

    /**
     * @deprecated This list is unmodifiable, so this method always throws {@link UnsupportedOperationException}.
     */
    @Deprecated
    @Override
    public void addLast(final T e) {
        throw new UnsupportedOperationException();
    }

    // As with the unmodifiable views returned by java.util.Collections, removeFirst() and removeLast() on an empty
    // list throw NoSuchElementException, from List's default implementation, rather than
    // UnsupportedOperationException.

    /**
     * @deprecated This list is unmodifiable, so this method throws {@link UnsupportedOperationException}, or
     *             {@link java.util.NoSuchElementException} if the list is empty.
     */
    @Deprecated
    @Override
    public T removeFirst() {
        return super.removeFirst();
    }

    /**
     * @deprecated This list is unmodifiable, so this method throws {@link UnsupportedOperationException}, or
     *             {@link java.util.NoSuchElementException} if the list is empty.
     */
    @Deprecated
    @Override
    public T removeLast() {
        return super.removeLast();
    }

    // -------------------------------------------------------------------------------------------------------------
    // Bulk mutators, which have to be rejected even when they would not change the list

    /**
     * @deprecated This list is unmodifiable, so this method always throws {@link UnsupportedOperationException}.
     */
    @Deprecated
    @Override
    public boolean addAll(final Collection<? extends T> c) {
        throw new UnsupportedOperationException();
    }

    /**
     * @deprecated This list is unmodifiable, so this method always throws {@link UnsupportedOperationException}.
     */
    @Deprecated
    @Override
    public boolean addAll(final int index, final Collection<? extends T> c) {
        throw new UnsupportedOperationException();
    }

    /**
     * @deprecated This list is unmodifiable, so this method always throws {@link UnsupportedOperationException}.
     */
    @Deprecated
    @Override
    public boolean remove(final Object o) {
        throw new UnsupportedOperationException();
    }

    /**
     * @deprecated This list is unmodifiable, so this method always throws {@link UnsupportedOperationException}.
     */
    @Deprecated
    @Override
    public boolean removeAll(final Collection<?> c) {
        throw new UnsupportedOperationException();
    }

    /**
     * @deprecated This list is unmodifiable, so this method always throws {@link UnsupportedOperationException}.
     */
    @Deprecated
    @Override
    public boolean retainAll(final Collection<?> c) {
        throw new UnsupportedOperationException();
    }

    /**
     * @deprecated This list is unmodifiable, so this method always throws {@link UnsupportedOperationException}.
     */
    @Deprecated
    @Override
    public boolean removeIf(final Predicate<? super T> filter) {
        throw new UnsupportedOperationException();
    }

    /**
     * @deprecated This list is unmodifiable, so this method always throws {@link UnsupportedOperationException}.
     */
    @Deprecated
    @Override
    public void replaceAll(final UnaryOperator<T> operator) {
        throw new UnsupportedOperationException();
    }

    /**
     * @deprecated This list is unmodifiable, so this method always throws {@link UnsupportedOperationException}.
     */
    @Deprecated
    @Override
    public void sort(final Comparator<? super T> c) {
        throw new UnsupportedOperationException();
    }

    /**
     * @deprecated This list is unmodifiable, so this method always throws {@link UnsupportedOperationException}.
     */
    @Deprecated
    @Override
    public void clear() {
        throw new UnsupportedOperationException();
    }
}
