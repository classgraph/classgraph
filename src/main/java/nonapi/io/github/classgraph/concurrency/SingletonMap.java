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
 * Copyright (c) 2018 Luke Hutchison
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
package nonapi.io.github.classgraph.concurrency;

import java.util.ArrayList;
import java.util.List;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;

import nonapi.io.github.classgraph.utils.LogNode;

/**
 * A map from keys to singleton instances. Allows you to create object instance singletons and add them to a
 * {@link ConcurrentMap} on demand, based on a key value. Works the same as
 * {@code concurrentMap.computeIfAbsent(key, key -> newInstance(key))}, except that it also works on JDK 7.
 * 
 * @param <K>
 *            The key type.
 * @param <V>
 *            The value type.
 */
public abstract class SingletonMap<K, V> {
    private final ConcurrentMap<K, SingletonHolder<V>> map = new ConcurrentHashMap<>();

    /**
     * Wrapper to allow an object instance to be put into a ConcurrentHashMap using putIfAbsent() without requiring
     * the instance to be initialized first, so that putIfAbsent can be performed without wrapping it with a
     * synchronized lock, and so that initialization work is not wasted if an object is already in the map for the
     * key.
     */
    private static class SingletonHolder<V> {
        private V singleton;
        private final CountDownLatch initialized = new CountDownLatch(1);

        void set(final V singleton) {
            this.singleton = singleton;
            initialized.countDown();
        }

        V get() throws InterruptedException {
            initialized.await();
            return singleton;
        }
    }

    /**
     * Construct a new singleton instance.
     * 
     * @param key
     *            The key for the singleton.
     * @param log
     *            The log.
     * @return The singleton instance.
     * @throws Exception
     *             If something goes wrong.
     */
    public abstract V newInstance(K key, LogNode log) throws Exception;

    /**
     * Check if the given key is in the map, and if so, return it. If not, create a singleton value for that key,
     * and return the created value. Stores null in the map if creating a new singleton instance failed due to
     * throwing an exception, so that the failed operation is not repeated twice, however throws
     * {@link IllegalArgumentException} if during this call or a previous call for the same key,
     * {@link #newInstance(Object, LogNode)} returned null.
     * 
     * @param key
     *            The key for the singleton.
     * @param log
     *            The log.
     * @return The singleton instance, if {@link #newInstance(Object, LogNode)} returned a non-null instance on this
     *         call or a previous call.
     * @throws Exception
     *             if newInstance(key) throws an exception.
     * @throws IllegalArgumentException
     *             if newInstance(key) returns null.
     */
    public V get(final K key, final LogNode log) throws Exception {
        final SingletonHolder<V> singletonHolder = map.get(key);
        if (singletonHolder != null) {
            // There is already a SingletonHolder in the map for this key -- get the value
            return singletonHolder.get();
        } else {
            // There is no SingletonHolder in the map for this key, need to create one
            // (need to handle race condition, hence the putIfAbsent call)
            final SingletonHolder<V> newSingletonHolder = new SingletonHolder<>();
            final SingletonHolder<V> oldSingletonHolder = map.putIfAbsent(key, newSingletonHolder);
            if (oldSingletonHolder != null) {
                // There was already a singleton in the map for this key, due to a race condition --
                // return the existing singleton
                return oldSingletonHolder.get();
            } else {
                // Initialize newSingletonHolder with new instance of value.
                V newInstance = null;
                try {
                    newInstance = newInstance(key, log);
                    if (newInstance == null) {
                        if (log != null) {
                            log.log("newInstance returned null for key " + key);
                        }
                        throw new IllegalArgumentException("newInstance returned null for key " + key);
                    }
                } finally {
                    // Have to call .set() even if an exception is thrown by newInstance(), or if newInstance
                    // is null, since .set() calls initialized.countDown(). Otherwise threads that call .get()
                    // can end up waiting forever.
                    newSingletonHolder.set(newInstance);
                }
                return newInstance;
            }
        }
    }

    /**
     * Get the singleton for a given key, if it is present in the map, otherwise return null. (Note that this will
     * also return null if creating a singleton value for a given key failed due to throwing an exception.)
     * 
     * @param key
     *            The key for the singleton.
     * @return the new singleton instance, initialized by calling newInstance, or null if createSingleton() or
     *         getOrCreateSingleton() has not yet been called. Also returns null if newInstance() threw an exception
     *         or returned null while calling either of these methods.
     * @throws InterruptedException
     *             If getting the singleton was interrupted.
     */
    public V getIfPresent(final K key) throws InterruptedException {
        final SingletonHolder<V> singletonHolder = map.get(key);
        return singletonHolder == null ? null : singletonHolder.get();
    }

    /**
     * Get all singletons in the map.
     * 
     * @return the singleton values in the map.
     * @throws InterruptedException
     *             If getting the values was interrupted.
     */
    public List<V> values() throws InterruptedException {
        final List<V> entries = new ArrayList<>(map.size());
        for (final Entry<K, SingletonHolder<V>> ent : map.entrySet()) {
            final V entryValue = ent.getValue().get();
            if (entryValue != null) {
                entries.add(entryValue);
            }
        }
        return entries;
    }

    /** Clear the map. */
    public void clear() {
        map.clear();
    }
}
