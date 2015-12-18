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
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;

/**
 * Used to wrap a map with lazy evaluation, so work is not done unless it is needed. (e.g. if you don't use the
 * annotations features of the API, most of the annotations data structures are not built.)
 * 
 * Either override initialize() if you need to initialize and finalize the entire map at once, or, better, override
 * generateValue() to compute map entries only as they are requested.
 */
public abstract class LazyMap<K, V> {
    protected final HashMap<K, V> map = new HashMap<>();
    private boolean initialized = false;

    /** Override this to generate a single value each time get() is called. The result will be cached in the map. */
    protected V generateValue(final K key) {
        return null;
    }

    /** Override this to initialize the entire map the first time get() is called. */
    public void initialize() {
    }

    private void checkInitialized() {
        if (!initialized) {
            initialize();
            initialized = true;
        }
    }

    /** Clear the map. */
    public void clear() {
        map.clear();
        initialized = false;
    }

    /** Ensure that all keys in the provided collection have been initialized or generated. */
    public void generateAllValues(final Collection<K> keys) {
        checkInitialized();
        for (final K key : keys) {
            get(key);
        }
    }

    /**
     * Get the requested key. If initialize() has not yet been called, it will be called. If the map does not yet
     * contain the key, generateValue(key) will be called, and the result will be stored in the map. If there is
     * still no value corresponding to the key in the map, null will be returned.
     */
    public V get(final K key) {
        checkInitialized();
        V cachedVal = map.get(key);
        if (cachedVal == null) {
            cachedVal = generateValue(key);
            if (cachedVal != null) {
                map.put(key, cachedVal);
            }
        }
        return cachedVal;
    }

    /**
     * Returns all values in the map that have been initialized so far. If the map is not exhaustively initialized
     * in the initialize() method, this will not contain all possible values for the map, only the values that have
     * been computed as a result of calling generateValue().
     */
    public Collection<V> values() {
        checkInitialized();
        return map.values();
    }

    /**
     * Returns all keys in the map that have been initialized so far. If the map is not exhaustively initialized in
     * the initialize() method, this will not contain all possible keys for the map, only the keys that have been
     * passed so far to generateValue().
     */
    public Set<K> keySet() {
        checkInitialized();
        return map.keySet();
    }

    @Override
    public String toString() {
        checkInitialized();
        return map.toString();
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * For all keys in templateLazyMap, generate the value for each key, then invert the map to find the preimage of
     * each unique value. The keySet in templateLazyMap is read the first time get() is called on the returned
     * LazyMap. (i.e. the template is only needed so that even keySet generation happens lazily.)
     */
    public static <K, V, T> LazyMap<V, HashSet<K>> invertMultiSet(final LazyMap<K, HashSet<V>> lazyMap, //
            final LazyMap<K, T> templateLazyMap) {
        return new LazyMap<V, HashSet<K>>() {
            @Override
            public void initialize() {
                lazyMap.generateAllValues(templateLazyMap.keySet());
                MultiSet.invert(lazyMap.map, this.map);
            }
        };
    }

    /**
     * Invert the map to find the preimage of each unique value. The LazyMap should be defined by overriding
     * initialize(), not using generateValue().
     */
    public static <K, V, T> LazyMap<V, HashSet<K>> invertMultiSet(final LazyMap<K, HashSet<V>> lazyMap) {
        return new LazyMap<V, HashSet<K>>() {
            @Override
            public void initialize() {
                lazyMap.checkInitialized();
                MultiSet.invert(lazyMap.map, this.map);
            }
        };
    }

    /** Convert a lazy MultiSet into a lazy MultiMap. Value lists are sorted in the result. */
    public static <K, V extends Comparable<V>> LazyMap<K, ArrayList<V>> convertToMultiMapSorted(
            final LazyMap<K, HashSet<V>> lazyMap) {
        return new LazyMap<K, ArrayList<V>>() {
            @Override
            protected ArrayList<V> generateValue(final K key) {
                final HashSet<V> setVals = lazyMap.get(key);
                return setVals == null ? null : Utils.sortedCopy(setVals);
            }
        };
    }
}
