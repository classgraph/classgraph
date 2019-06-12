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

import java.util.Collection;
import java.util.Iterator;
import java.util.ListIterator;
import java.util.Set;
import java.util.Spliterator;
import java.util.function.Consumer;

/** A list of {@link FieldInfo} objects. */
public class FieldInfoList extends MappableInfoList<FieldInfo> {

    /** An unmodifiable empty {@link FieldInfoList}. */
    static final FieldInfoList EMPTY_LIST = new FieldInfoList() {
        @Override
        public boolean add(final FieldInfo e) {
            throw new IllegalArgumentException("List is immutable");
        }

        @Override
        public void add(final int index, final FieldInfo element) {
            throw new IllegalArgumentException("List is immutable");
        }

        @Override
        public boolean remove(final Object o) {
            throw new IllegalArgumentException("List is immutable");
        }

        @Override
        public FieldInfo remove(final int index) {
            throw new IllegalArgumentException("List is immutable");
        }

        @Override
        public boolean addAll(final Collection<? extends FieldInfo> c) {
            throw new IllegalArgumentException("List is immutable");
        }

        @Override
        public boolean addAll(final int index, final Collection<? extends FieldInfo> c) {
            throw new IllegalArgumentException("List is immutable");
        }

        @Override
        public boolean removeAll(final Collection<?> c) {
            throw new IllegalArgumentException("List is immutable");
        }

        @Override
        public boolean retainAll(final Collection<?> c) {
            throw new IllegalArgumentException("List is immutable");
        }

        @Override
        public void clear() {
            throw new IllegalArgumentException("List is immutable");
        }

        @Override
        public FieldInfo set(final int index, final FieldInfo element) {
            throw new IllegalArgumentException("List is immutable");
        }

        // Provide replacement iterators so that there is no chance of a thread that
        // is trying to sort the empty list causing a ConcurrentModificationException
        // in another thread that is iterating over the empty list (#334)
        @Override
        public Iterator<FieldInfo> iterator() {
            return new Iterator<FieldInfo>() {
                @Override
                public boolean hasNext() {
                    return false;
                }

                @Override
                public FieldInfo next() {
                    return null;
                }
            };
        }

        @Override
        public Spliterator<FieldInfo> spliterator() {
            return new Spliterator<FieldInfo>() {
                @Override
                public boolean tryAdvance(final Consumer<? super FieldInfo> action) {
                    return false;
                }

                @Override
                public Spliterator<FieldInfo> trySplit() {
                    return null;
                }

                @Override
                public long estimateSize() {
                    return 0;
                }

                @Override
                public int characteristics() {
                    return 0;
                }
            };
        }

        @Override
        public ListIterator<FieldInfo> listIterator() {
            return new ListIterator<FieldInfo>() {
                @Override
                public boolean hasNext() {
                    return false;
                }

                @Override
                public FieldInfo next() {
                    return null;
                }

                @Override
                public boolean hasPrevious() {
                    return false;
                }

                @Override
                public FieldInfo previous() {
                    return null;
                }

                @Override
                public int nextIndex() {
                    return 0;
                }

                @Override
                public int previousIndex() {
                    return 0;
                }

                @Override
                public void remove() {
                    throw new IllegalArgumentException("List is immutable");
                }

                @Override
                public void set(final FieldInfo e) {
                    throw new IllegalArgumentException("List is immutable");
                }

                @Override
                public void add(final FieldInfo e) {
                    throw new IllegalArgumentException("List is immutable");
                }
            };
        }
    };

    /**
     * Return an unmodifiable empty {@link FieldInfoList}.
     *
     * @return the unmodifiable empty {@link FieldInfoList}.
     */
    public static FieldInfoList emptyList() {
        return EMPTY_LIST;
    }

    /**
     * Construct a new modifiable empty list of {@link FieldInfo} objects.
     */
    public FieldInfoList() {
        super();
    }

    /**
     * Construct a new modifiable empty list of {@link FieldInfo} objects, given a size hint.
     *
     * @param sizeHint
     *            the size hint
     */
    public FieldInfoList(final int sizeHint) {
        super(sizeHint);
    }

    /**
     * Construct a new modifiable empty {@link FieldInfoList}, given an initial list of {@link FieldInfo} objects.
     *
     * @param fieldInfoCollection
     *            the collection of {@link FieldInfo} objects.
     */
    public FieldInfoList(final Collection<FieldInfo> fieldInfoCollection) {
        super(fieldInfoCollection);
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Find the names of any classes referenced in the fields in this list.
     *
     * @param referencedClassNames
     *            the referenced class names
     */
    void findReferencedClassNames(final Set<String> referencedClassNames) {
        for (final FieldInfo fi : this) {
            fi.findReferencedClassNames(referencedClassNames);
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Filter an {@link FieldInfoList} using a predicate mapping an {@link FieldInfo} object to a boolean, producing
     * another {@link FieldInfoList} for all items in the list for which the predicate is true.
     */
    @FunctionalInterface
    public interface FieldInfoFilter {
        /**
         * Whether or not to allow an {@link FieldInfo} list item through the filter.
         *
         * @param fieldInfo
         *            The {@link FieldInfo} item to filter.
         * @return Whether or not to allow the item through the filter. If true, the item is copied to the output
         *         list; if false, it is excluded.
         */
        boolean accept(FieldInfo fieldInfo);
    }

    /**
     * Find the subset of the {@link FieldInfo} objects in this list for which the given filter predicate is true.
     *
     * @param filter
     *            The {@link FieldInfoFilter} to apply.
     * @return The subset of the {@link FieldInfo} objects in this list for which the given filter predicate is
     *         true.
     */
    public FieldInfoList filter(final FieldInfoFilter filter) {
        final FieldInfoList fieldInfoFiltered = new FieldInfoList();
        for (final FieldInfo resource : this) {
            if (filter.accept(resource)) {
                fieldInfoFiltered.add(resource);
            }
        }
        return fieldInfoFiltered;
    }
}
