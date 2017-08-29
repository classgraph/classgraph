/*
 * This file is part of FastClasspathScanner.
 * 
 * Author: Luke Hutchison
 * 
 * Hosted at: https://github.com/lukehutch/fast-classpath-scanner
 * 
 * --
 *
 * The MIT License (MIT)
 *
 * Copyright (c) 2016 Luke Hutchison
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
package io.github.lukehutch.fastclasspathscanner.utils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/**
 * A simplified set that has O(1) add time, but also preserves a list of elements in the order they were added.
 */
public class AdditionOrderedSet<T> {
    private final HashSet<T> set = new HashSet<>();
    private final ArrayList<T> list = new ArrayList<>();

    /** Add an element to the set. Returns true if the element was added; false if it was already in the set. */
    public boolean add(final T elt) {
        if (set.add(elt)) {
            list.add(elt);
            return true;
        } else {
            return false;
        }
    }

    /** Add all items of a list to the set. Returns true if the list changed as a result of the add. */
    public boolean addAll(final List<T> items) {
        boolean changed = false;
        for (final T item : items) {
            changed |= add(item);
        }
        return changed;
    }

    /** Get the elements in addition order (i.e. in the order they were added) */
    public List<T> getList() {
        return list;
    }

    public AdditionOrderedSet() {
    }

    public AdditionOrderedSet(final List<T> elts) {
        for (final T elt : elts) {
            add(elt);
        }
    }

    public AdditionOrderedSet(final T[] elts) {
        for (final T elt : elts) {
            add(elt);
        }
    }

    public static <T> List<T> dedup(final List<T> list) {
        return new AdditionOrderedSet<>(list).getList();
    }
}
