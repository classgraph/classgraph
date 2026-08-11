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
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

import org.jspecify.annotations.Nullable;

/**
 * A list of objects that can be frozen in place by calling {@link #makeUnmodifiable()}. Freezing in place, rather
 * than wrapping the list in {@link Collections#unmodifiableList(List)}, preserves the concrete list type, so that
 * the utility methods added by subclasses (e.g. {@link ClassInfoList#filter}) remain available to callers.
 *
 * <p>
 * Once frozen, every mutation method throws {@link UnsupportedOperationException}, whether or not the mutation
 * would actually change the contents of the list -- the same contract as the unmodifiable views returned by
 * {@link Collections}.
 *
 * @param <T>
 *            the element type
 */
class PotentiallyUnmodifiableList<T> extends ArrayList<T> {
    /** serialVersionUID. */
    @Serial
    private static final long serialVersionUID = 1L;

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
     *            the expected number of elements
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

    /** Make this list unmodifiable. */
    void makeUnmodifiable() {
        modifiable = false;
    }

    /**
     * Make a list unmodifiable and return it, so that a list can be frozen in a return statement.
     *
     * @param <L>
     *            the list type
     * @param list
     *            the list to make unmodifiable
     * @return the list
     */
    static <L extends PotentiallyUnmodifiableList<?>> L unmodifiable(final L list) {
        list.makeUnmodifiable();
        return list;
    }

    @Override
    public boolean add(final T element) {
        if (!modifiable) {
            throw new UnsupportedOperationException("List is immutable");
        } else {
            return super.add(element);
        }
    }

    @Override
    public void add(final int index, final T element) {
        if (!modifiable) {
            throw new UnsupportedOperationException("List is immutable");
        } else {
            super.add(index, element);
        }
    }

    @Override
    public boolean remove(final Object o) {
        if (!modifiable) {
            throw new UnsupportedOperationException("List is immutable");
        } else {
            return super.remove(o);
        }
    }

    @Override
    public T remove(final int index) {
        if (!modifiable) {
            throw new UnsupportedOperationException("List is immutable");
        } else {
            return super.remove(index);
        }
    }

    @Override
    public boolean addAll(final Collection<? extends T> c) {
        if (!modifiable) {
            throw new UnsupportedOperationException("List is immutable");
        } else {
            return super.addAll(c);
        }
    }

    @Override
    public boolean addAll(final int index, final Collection<? extends T> c) {
        if (!modifiable) {
            throw new UnsupportedOperationException("List is immutable");
        } else {
            return super.addAll(index, c);
        }
    }

    @Override
    public boolean removeAll(final Collection<?> c) {
        if (!modifiable) {
            throw new UnsupportedOperationException("List is immutable");
        } else {
            return super.removeAll(c);
        }
    }

    @Override
    public boolean retainAll(final Collection<?> c) {
        if (!modifiable) {
            throw new UnsupportedOperationException("List is immutable");
        } else {
            return super.retainAll(c);
        }
    }

    @Override
    public void clear() {
        if (!modifiable) {
            throw new UnsupportedOperationException("List is immutable");
        } else {
            super.clear();
        }
    }

    @Override
    public T set(final int index, final T element) {
        if (!modifiable) {
            throw new UnsupportedOperationException("List is immutable");
        } else {
            return super.set(index, element);
        }
    }

    // Provide replacement iterators so that there is no chance of a thread that is trying to sort the empty list
    // causing a ConcurrentModificationException in another thread that is iterating over the empty list (#334)

    @Override
    public Iterator<T> iterator() {
        final var iterator = super.iterator();
        return new Iterator<>() {
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
                    throw new UnsupportedOperationException("List is immutable");
                } else {
                    iterator.remove();
                }
            }
        };
    }

    @Override
    public void sort(final @Nullable Comparator<? super T> c) {
        if (!modifiable) {
            throw new UnsupportedOperationException("List is immutable");
        } else {
            super.sort(c);
        }
    }

    @Override
    public boolean removeIf(final Predicate<? super T> filter) {
        if (!modifiable) {
            throw new UnsupportedOperationException("List is immutable");
        } else {
            return super.removeIf(filter);
        }
    }

    @Override
    public void replaceAll(final UnaryOperator<T> operator) {
        if (!modifiable) {
            throw new UnsupportedOperationException("List is immutable");
        } else {
            super.replaceAll(operator);
        }
    }

    // ArrayList#subList returns a view that writes through to the backing array, bypassing the overrides in this
    // class, so an unmodifiable view has to be wrapped around it

    @Override
    public List<T> subList(final int fromIndex, final int toIndex) {
        final var subList = super.subList(fromIndex, toIndex);
        return modifiable ? subList : Collections.unmodifiableList(subList);
    }

    @Override
    public ListIterator<T> listIterator() {
        return listIterator(0);
    }

    @Override
    public ListIterator<T> listIterator(final int index) {
        final var iterator = super.listIterator(index);
        return new ListIterator<>() {
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
                    throw new UnsupportedOperationException("List is immutable");
                } else {
                    iterator.remove();
                }
            }

            @Override
            public void set(final T e) {
                if (!modifiable) {
                    throw new UnsupportedOperationException("List is immutable");
                } else {
                    iterator.set(e);
                }
            }

            @Override
            public void add(final T e) {
                if (!modifiable) {
                    throw new UnsupportedOperationException("List is immutable");
                } else {
                    iterator.add(e);
                }
            }
        };
    }
}
