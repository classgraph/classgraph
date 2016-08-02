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

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

/** A multimap from key to a set of values. */
public class MultiMapKeyToSet<S, T> {
    /** The underlying map. */
    private final HashMap<S, Set<T>> map;

    /** A multimap from key to a set of values. */
    public MultiMapKeyToSet() {
        this.map = new HashMap<>();
    }

    /** Put a value into the multimap. */
    public void put(final S key, final T value) {
        Set<T> Set = map.get(key);
        if (Set == null) {
            map.put(key, Set = new HashSet<>());
        }
        Set.add(value);
    }

    /** Get a value from the multimap. */
    public Set<T> get(final S key) {
        return map.get(key);
    }

    /** Get the entry set from the underlying map. */
    public Set<Entry<S, Set<T>>> entrySet() {
        return map.entrySet();
    }

    /** Get the underlying map. */
    public Map<S, Set<T>> getRawMap() {
        return map;
    }

    /** Return true if this map is empty. */
    public boolean isEmpty() {
        return map.isEmpty();
    }
}
