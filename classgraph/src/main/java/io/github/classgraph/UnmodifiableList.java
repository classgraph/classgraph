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
import java.util.NoSuchElementException;
import java.util.RandomAccess;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

import org.jetbrains.annotations.Unmodifiable;

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
 * <p>
 * Every mutator is overridden here, including the ones that {@link AbstractList} already rejects, so that it can be
 * marked {@link Deprecated}. That makes the compiler warn about a call to one of them through any of the list types
 * that the ClassGraph API returns. This matters most for Kotlin, which treats a Java class that implements
 * {@link List} as a {@code MutableList}, so that without the deprecation a call to a mutator compiles with no
 * warning. Guava marks the mutators of its immutable collections deprecated for the same reason. Kotlin provides no
 * way for a Java class to declare that it implements only Kotlin's read-only {@code List} (KT-70485).
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

    // On JDK 21 and later, List's default reversed() returns a view whose addAll(), removeAll() and retainAll()
    // succeed when they would not change the list, as Collections.unmodifiableList(list).reversed() does too. This
    // method overrides it there; reversed() does not exist in the JDK 17 API, so it cannot be marked @Override. Since
    // this list never changes, a reversed copy is equivalent to a view.

    /**
     * Get the elements of this list in reverse order.
     *
     * @return an unmodifiable list of the elements of this list, in reverse order
     */
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

    // -------------------------------------------------------------------------------------------------------------
    // The mutators that JDK 21 added to List, through SequencedCollection. They do not exist in the JDK 17 API that
    // this branch is compiled against, so they cannot be marked @Override, but on JDK 21 and later they override
    // List's default methods, so that they are deprecated there too.

    /**
     * @deprecated This list is unmodifiable, so this method always throws {@link UnsupportedOperationException}.
     * @param e
     *            the element to add
     */
    @Deprecated
    @SuppressWarnings("unused") // e is unused, and on JDK 17 this method overrides nothing
    public void addFirst(final T e) {
        throw new UnsupportedOperationException();
    }

    /**
     * @deprecated This list is unmodifiable, so this method always throws {@link UnsupportedOperationException}.
     * @param e
     *            the element to add
     */
    @Deprecated
    @SuppressWarnings("unused") // e is unused, and on JDK 17 this method overrides nothing
    public void addLast(final T e) {
        throw new UnsupportedOperationException();
    }

    // As with the unmodifiable views returned by java.util.Collections, removeFirst() and removeLast() on an empty
    // list throw NoSuchElementException, as List's default implementations do, rather than
    // UnsupportedOperationException.

    /**
     * @deprecated This list is unmodifiable, so this method throws {@link UnsupportedOperationException}, or
     *             {@link NoSuchElementException} if the list is empty.
     * @return never returns normally
     */
    @Deprecated
    public T removeFirst() {
        if (isEmpty()) {
            throw new NoSuchElementException();
        }
        throw new UnsupportedOperationException();
    }

    /**
     * @deprecated This list is unmodifiable, so this method throws {@link UnsupportedOperationException}, or
     *             {@link NoSuchElementException} if the list is empty.
     * @return never returns normally
     */
    @Deprecated
    public T removeLast() {
        if (isEmpty()) {
            throw new NoSuchElementException();
        }
        throw new UnsupportedOperationException();
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
