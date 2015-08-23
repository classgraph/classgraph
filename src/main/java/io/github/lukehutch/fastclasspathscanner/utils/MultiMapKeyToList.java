/*
 * This file is part of FastClasspathScanner.
 * 
 * Author: Luke Hutchison <luke .dot. hutch .at. gmail .dot. com>
 * 
 * Hosted at: https://github.com/lukehutch/fast-classpath-scanner
 * 
 * --
 *
 * The MIT License (MIT)
 *
 * Copyright (c) 2015 Luke Hutchison
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
import java.util.HashMap;
import java.util.Map.Entry;
import java.util.Set;

/** Simple multimap class. */
public class MultiMapKeyToList<S, T> {

    HashMap<S, ArrayList<T>> map = new HashMap<S, ArrayList<T>>();

    public MultiMapKeyToList() {
    }

    /** Returns true as specified by List.add(). */
    public boolean put(S key, T value) {
        ArrayList<T> list = map.get(key);
        if (list == null) {
            list = new ArrayList<T>();
            map.put(key, list);
        }
        return list.add(value);
    }

    public ArrayList<T> get(S key) {
        return map.get(key);
    }

    public boolean containsKey(S key) {
        return map.containsKey(key);
    }

    public int sizeKeys() {
        return map.size();
    }

    public Set<S> keySet() {
        return map.keySet();
    }

    public Set<Entry<S, ArrayList<T>>> entrySet() {
        return map.entrySet();
    }

    public HashMap<S, ArrayList<T>> getRawMap() {
        return map;
    }

    public void putAll(S key, Iterable<T> values) {
        boolean putSomething = false;
        for (T val : values) {
            put(key, val);
            putSomething = true;
        }
        if (!putSomething && !map.containsKey(key)) {
            // If putting an empty collection, need to create an empty set at the key 
            map.put(key, new ArrayList<T>());
        }
    }

    public void putAll(S key, T[] values) {
        if (values.length == 0 && !map.containsKey(key)) {
            // If putting an empty collection, need to create an empty set at the key 
            map.put(key, new ArrayList<T>());
        } else {
            for (T val : values) {
                put(key, val);
            }
        }
    }

    /** Invert the mapping */
    public MultiMapKeyToList<T, S> invert() {
        MultiMapKeyToList<T, S> inv = new MultiMapKeyToList<T, S>();
        for (Entry<S, ArrayList<T>> ent : map.entrySet()) {
            S s = ent.getKey();
            for (T t : ent.getValue()) {
                inv.put(t, s);
            }
        }
        return inv;
    }
}
