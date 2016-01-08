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
import java.util.Map.Entry;

/** Multiset utility functions. */
public class MultiSet {

    public static <K, V> boolean put(final HashMap<K, HashSet<V>> map, final K key, final V value) {
        HashSet<V> set = map.get(key);
        if (set == null) {
            set = new HashSet<V>();
            map.put(key, set);
        }
        return set.add(value);
    }

    public static <K, V> void putAll(final HashMap<K, HashSet<V>> map, final K key, final Iterable<V> values) {
        boolean putSomething = false;
        for (final V val : values) {
            put(map, key, val);
            putSomething = true;
        }
        if (!putSomething && !map.containsKey(key)) {
            map.put(key, new HashSet<V>());
        }
    }

    /** Invert the mapping of the map, storing the result in inv */
    public static <K, V> HashMap<V, HashSet<K>> invert(final HashMap<K, HashSet<V>> map,
            final HashMap<V, HashSet<K>> inv) {
        for (final Entry<K, HashSet<V>> ent : map.entrySet()) {
            final K key = ent.getKey();
            for (final V val : ent.getValue()) {
                put(inv, val, key);
            }
        }
        return inv;
    }

    /** Invert the mapping */
    public static <K, V> HashMap<V, HashSet<K>> invert(final HashMap<K, HashSet<V>> map) {
        final HashMap<V, HashSet<K>> inv = new HashMap<V, HashSet<K>>();
        return invert(map, inv);
    }
}
