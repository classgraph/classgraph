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
package nonapi.io.github.classgraph.utils;

import java.util.Collections;
import java.util.Comparator;
import java.util.ConcurrentModificationException;
import java.util.List;

/**
 * Collection utilities.
 */
public final class CollectionUtils {
    /** Class can't be constructed. */
    private CollectionUtils() {
        // Empty
    }

    /**
     * Sort a collection if it is not empty (to prevent {@link ConcurrentModificationException} if an immutable
     * empty list that has been returned more than once is being sorted in one thread and iterated through in
     * another thread -- #334).
     *
     * @param <T>
     *            the element type
     * @param list
     *            the list
     */
    public static <T extends Comparable<? super T>> void sortIfNotEmpty(final List<T> list) {
        if (!list.isEmpty()) {
            Collections.sort(list);
        }
    }

    /**
     * Sort a collection if it is not empty (to prevent {@link ConcurrentModificationException} if an immutable
     * empty list that has been returned more than once is being sorted in one thread and iterated through in
     * another thread -- #334).
     *
     * @param <T>
     *            the element type
     * @param list
     *            the list
     * @param comparator
     *            the comparator
     */
    public static <T> void sortIfNotEmpty(final List<T> list, final Comparator<? super T> comparator) {
        if (!list.isEmpty()) {
            Collections.sort(list, comparator);
        }
    }
}
