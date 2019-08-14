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
 * Copyright (c) 2019 Luke Hutchison
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
import java.util.Iterator;
import java.util.ListIterator;

/**
 * A potentially unmodifiable list of objects.
 *
 * @param <T>
 *            the element type
 */
class PotentiallyUnmodifiableList<T> extends ArrayList<T> {
    /** serialVersionUID. */
    static final long serialVersionUID = 1L;

    /** Whether or not the list is modifiable. */
    boolean modifiable = true;

    /**
     * Constructor.
     */
    PotentiallyUnmodifiableList() {
        super();
    }

    /**
     * Constructor.
     *
     * @param sizeHint
     *            the size hint
     */
    PotentiallyUnmodifiableList(final int sizeHint) {
        super(sizeHint);
    }

    /**
     * Constructor.
     *
     * @param collection
     *            the initial elements.
     */
    PotentiallyUnmodifiableList(final Collection<T> collection) {
        super(collection);
    }

    // Keep Scrutinizer happy
    @Override
    public boolean equals(final Object o) {
        return super.equals(o);
    }

    // Keep Scrutinizer happy
    @Override
    public int hashCode() {
        return super.hashCode();
    }

    /** Make this list unmodifiable. */
    void makeUnmodifiable() {
        modifiable = false;
    }

    @Override
    public boolean add(final T element) {
        if (!modifiable) {
            throw new IllegalArgumentException("List is immutable");
        } else {
            return super.add(element);
        }
    }

    @Override
    public void add(final int index, final T element) {
        if (!modifiable) {
            throw new IllegalArgumentException("List is immutable");
        } else {
            super.add(index, element);
        }
    }

    @Override
    public boolean remove(final Object o) {
        if (!modifiable) {
            throw new IllegalArgumentException("List is immutable");
        } else {
            return super.remove(o);
        }
    }

    @Override
    public T remove(final int index) {
        if (!modifiable) {
            throw new IllegalArgumentException("List is immutable");
        } else {
            return super.remove(index);
        }
    }

    @Override
    public boolean addAll(final Collection<? extends T> c) {
        if (!modifiable && !c.isEmpty()) {
            throw new IllegalArgumentException("List is immutable");
        } else {
            return super.addAll(c);
        }
    }

    @Override
    public boolean addAll(final int index, final Collection<? extends T> c) {
        if (!modifiable && !c.isEmpty()) {
            throw new IllegalArgumentException("List is immutable");
        } else {
            return super.addAll(index, c);
        }
    }

    @Override
    public boolean removeAll(final Collection<?> c) {
        if (!modifiable && !c.isEmpty()) {
            throw new IllegalArgumentException("List is immutable");
        } else {
            return super.removeAll(c);
        }
    }

    @Override
    public boolean retainAll(final Collection<?> c) {
        if (!modifiable && !isEmpty()) {
            throw new IllegalArgumentException("List is immutable");
        } else {
            return super.retainAll(c);
        }
    }

    @Override
    public void clear() {
        if (!modifiable && !isEmpty()) {
            throw new IllegalArgumentException("List is immutable");
        } else {
            super.clear();
        }
    }

    @Override
    public T set(final int index, final T element) {
        if (!modifiable) {
            throw new IllegalArgumentException("List is immutable");
        } else {
            return super.set(index, element);
        }
    }

    // Provide replacement iterators so that there is no chance of a thread that
    // is trying to sort the empty list causing a ConcurrentModificationException
    // in another thread that is iterating over the empty list (#334)

    @Override
    public Iterator<T> iterator() {
        final Iterator<T> iterator = super.iterator();
        return new Iterator<T>() {
            @Override
            public boolean hasNext() {
                if (isEmpty()) {
                    return false;
                } else {
                    return iterator.hasNext();
                }
            }

            @Override
            public T next() {
                return iterator.next();
            }

            @Override
            public void remove() {
                if (!modifiable) {
                    throw new IllegalArgumentException("List is immutable");
                } else {
                    iterator.remove();
                }
            }
        };
    }

    @Override
    public ListIterator<T> listIterator() {
        final ListIterator<T> iterator = super.listIterator();
        return new ListIterator<T>() {
            @Override
            public boolean hasNext() {
                if (isEmpty()) {
                    return false;
                } else {
                    return iterator.hasNext();
                }
            }

            @Override
            public T next() {
                return iterator.next();
            }

            @Override
            public boolean hasPrevious() {
                if (isEmpty()) {
                    return false;
                } else {
                    return iterator.hasPrevious();
                }
            }

            @Override
            public T previous() {
                return iterator.previous();
            }

            @Override
            public int nextIndex() {
                if (isEmpty()) {
                    return 0;
                } else {
                    return iterator.nextIndex();
                }
            }

            @Override
            public int previousIndex() {
                if (isEmpty()) {
                    return -1;
                } else {
                    return iterator.previousIndex();
                }
            }

            @Override
            public void remove() {
                if (!modifiable) {
                    throw new IllegalArgumentException("List is immutable");
                } else {
                    iterator.remove();
                }
            }

            @Override
            public void set(final T e) {
                if (!modifiable) {
                    throw new IllegalArgumentException("List is immutable");
                } else {
                    iterator.set(e);
                }
            }

            @Override
            public void add(final T e) {
                if (!modifiable) {
                    throw new IllegalArgumentException("List is immutable");
                } else {
                    iterator.add(e);
                }
            }
        };
    }
}
