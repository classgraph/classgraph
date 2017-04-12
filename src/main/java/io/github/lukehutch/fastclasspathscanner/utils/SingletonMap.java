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
import java.util.List;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;

/** A map from keys to singleton instances. */
public abstract class SingletonMap<K, V> {
    private final ConcurrentMap<K, SingletonHolder<V>> map = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<SingletonHolder<V>> singletonHolderRecycler = new ConcurrentLinkedQueue<>();

    /**
     * Wrapper to allow an object instance to be put into a ConcurrentHashMap using putIfAbsent() without requiring
     * the instance to be initialized first, so that putIfAbsent can be performed without wrapping it with a
     * synchronized lock, and so that initialization work is not wasted if an object is already in the map for the
     * key.
     */
    private static class SingletonHolder<V> {
        private V singleton;
        private final CountDownLatch initialized = new CountDownLatch(1);

        public void set(final V singleton) {
            this.singleton = singleton;
            initialized.countDown();
        }

        public V get() throws InterruptedException {
            initialized.await();
            return singleton;
        }
    }

    /**
     * Initialize a value object for this key and return true, if this is the first time this key has been seen,
     * otherwise return false.
     * 
     * @throws Exception
     *             if newInstance(key) throws an exception.
     * @throws IllegalArgumentException
     *             if newInstance(key) returns null.
     */
    public boolean createSingleton(final K key) throws Exception {
        SingletonHolder<V> newSingletonHolder = singletonHolderRecycler.poll();
        if (newSingletonHolder == null) {
            newSingletonHolder = new SingletonHolder<>();
        }
        final SingletonHolder<V> oldSingletonHolder = map.putIfAbsent(key, newSingletonHolder);
        if (oldSingletonHolder == null) {
            // Initialize newSingletonHolder with new instance of value.

            V newInstance = null;
            try {
                newInstance = newInstance(key);
                if (newInstance == null) {
                    throw new IllegalArgumentException("newInstance(key) returned null");
                }
            } finally {
                // Have to call .set() even if an exception is thrown by newInstance(), or if newInstance is null,
                // since .set() calls initialized.countDown(). Otherwise threads that call .get() can end up
                // waiting forever.
                newSingletonHolder.set(newInstance);
            }
            return true;
        } else {
            // There was already a singleton in the map for this key
            singletonHolderRecycler.add(newSingletonHolder);
            return false;
        }
    }

    /**
     * Check if the given key is in the map, and if so, return it. If not, create a singleton value for that key,
     * and return the created value.
     * 
     * @throws Exception
     *             if newInstance(key) throws an exception.
     * @throws IllegalArgumentException
     *             if newInstance(key) returns null.
     */
    public V getOrCreateSingleton(final K key) throws Exception {
        final V existingSingleton = get(key);
        if (existingSingleton != null) {
            return existingSingleton;
        } else {
            // Create singleton
            // (in case of race condition, only one thread will cause a new singleton to be created for this key)
            createSingleton(key);
            // Look up newly-created singleton, and get the created value
            return get(key);
        }
    }

    /** Construct a new singleton instance. */
    public abstract V newInstance(K key) throws Exception;

    /**
     * Get the singleton for a given key.
     * 
     * @return the new singleton instance, initialized by calling newInstance, or null if createSingleton() or
     *         getOrCreateSingleton() has not yet been called. Also returns null if newInstance() threw an exception
     *         or returned null while calling either of these methods.
     */
    public V get(final K key) throws InterruptedException {
        final SingletonHolder<V> singletonHolder = map.get(key);
        return singletonHolder == null ? null : singletonHolder.get();
    }

    /** Get all singletons in the map. */
    public List<V> values() throws InterruptedException {
        final List<V> entries = new ArrayList<>(map.size());
        for (final Entry<K, SingletonHolder<V>> ent : map.entrySet()) {
            entries.add(ent.getValue().get());
        }
        return entries;
    }

    /** Clear the map. */
    public void clear() {
        map.clear();
    }
}