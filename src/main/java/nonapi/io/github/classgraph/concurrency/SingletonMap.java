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
package nonapi.io.github.classgraph.concurrency;

import java.util.AbstractMap.SimpleEntry;
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
 * @param <E>
 *            the element type
 */
public abstract class SingletonMap<K, V, E extends Exception> {
    /** The map. */
    private final ConcurrentMap<K, SingletonHolder<V>> map = new ConcurrentHashMap<>();

    // -------------------------------------------------------------------------------------------------------------

    /** Thrown when {@link SingletonMap#newInstance(Object, LogNode)} returns null. */
    public static class NullSingletonException extends Exception {
        /** serialVersionUID. */
        static final long serialVersionUID = 1L;

        /**
         * Constructor.
         *
         * @param <K>
         *            the key type
         * @param key
         *            the key
         */
        public <K> NullSingletonException(final K key) {
            super("newInstance returned null for key " + key);
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Wrapper to allow an object instance to be put into a ConcurrentHashMap using putIfAbsent() without requiring
     * the instance to be initialized first, so that putIfAbsent can be performed without wrapping it with a
     * synchronized lock, and so that initialization work is not wasted if an object is already in the map for the
     * key.
     *
     * @param <V>
     *            the singleton type
     */
    private static class SingletonHolder<V> {
        /** The singleton. */
        @SuppressWarnings("null")
        private volatile V singleton;

        /** Whether or not the singleton has been initialized (the count will have reached 0 if so). */
        private final CountDownLatch initialized = new CountDownLatch(1);

        /**
         * Set the singleton value, and decreases the countdown latch to 0.
         *
         * @param singleton
         *            the singleton
         * @throws IllegalArgumentException
         *             if this method is called more than once (indicating an internal inconsistency).
         */
        void set(final V singleton) throws IllegalArgumentException {
            if (initialized.getCount() < 1) {
                // Should not happen
                throw new IllegalArgumentException("Singleton already initialized");
            }
            this.singleton = singleton;
            initialized.countDown();
            if (initialized.getCount() != 0) {
                // Should not happen
                throw new IllegalArgumentException("Singleton initialized more than once");
            }
        }

        /**
         * Get the singleton value.
         *
         * @return the singleton value.
         * @throws InterruptedException
         *             if the thread was interrupted while waiting for the value to be set.
         */
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
     * @return The singleton instance. This method must either return a non-null value, or throw an exception of
     *         type E.
     * @throws E
     *             If something goes wrong while instantiating the new object instance.
     * @throws InterruptedException
     *             if the thread was interrupted while instantiating the singleton.
     */
    public abstract V newInstance(K key, LogNode log) throws E, InterruptedException;

    /**
     * Check if the given key is in the map, and if so, return the value of {@link #newInstance(Object, LogNode)}
     * for that key, or block on the result of {@link #newInstance(Object, LogNode)} if another thread is currently
     * creating the new instance.
     * 
     * If the given key is not currently in the map, store a placeholder in the map for this key, then run
     * {@link #newInstance(Object, LogNode)} for the key, store the result in the placeholder (which unblocks any
     * other threads waiting for the value), and then return the new instance.
     *
     * @param key
     *            The key for the singleton.
     * @param log
     *            The log.
     * @return The non-null singleton instance, if {@link #newInstance(Object, LogNode)} returned a non-null
     *         instance on this call or a previous call, otherwise throws {@link NullPointerException} if this call
     *         or a previous call to {@link #newInstance(Object, LogNode)} returned null.
     * @throws E
     *             If {@link #newInstance(Object, LogNode)} threw an exception.
     * @throws InterruptedException
     *             if the thread was interrupted while waiting for the singleton to be instantiated by another
     *             thread.
     * @throws NullSingletonException
     *             if {@link #newInstance(Object, LogNode)} returned null.
     */
    public V get(final K key, final LogNode log) throws E, InterruptedException, NullSingletonException {
        final SingletonHolder<V> singletonHolder = map.get(key);
        @SuppressWarnings("null")
        V instance = null;
        if (singletonHolder != null) {
            // There is already a SingletonHolder in the map for this key -- get the value
            instance = singletonHolder.get();
        } else {
            // There is no SingletonHolder in the map for this key, need to create one
            // (need to handle race condition, hence the putIfAbsent call)
            final SingletonHolder<V> newSingletonHolder = new SingletonHolder<>();
            final SingletonHolder<V> oldSingletonHolder = map.putIfAbsent(key, newSingletonHolder);
            if (oldSingletonHolder != null) {
                // There was already a singleton in the map for this key, due to a race condition --
                // return the existing singleton
                instance = oldSingletonHolder.get();
            } else {
                try {
                    // Create a new instance
                    instance = newInstance(key, log);

                } finally {
                    // Initialize newSingletonHolder with the new instance.
                    // Always need to call .set() even if an exception is thrown by newInstance()
                    // or newInstance() returns null, since .set() calls initialized.countDown().
                    // Otherwise threads that call .get() may end up waiting forever.
                    newSingletonHolder.set(instance);
                }
            }
        }
        if (instance == null) {
            throw new NullSingletonException(key);
        } else {
            return instance;
        }
    }

    /**
     * Get all valid singleton values in the map.
     * 
     * @return the singleton values in the map, skipping over any value for which newInstance() threw an exception
     *         or returned null.
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

    /**
     * Returns true if the map is empty.
     *
     * @return true, if the map is empty
     */
    public boolean isEmpty() {
        return map.isEmpty();
    }

    /**
     * Get the map entries.
     *
     * @return the map entries.
     * @throws InterruptedException
     *             if interrupted.
     */
    public List<Entry<K, V>> entries() throws InterruptedException {
        final List<Entry<K, V>> entries = new ArrayList<>(map.size());
        for (final Entry<K, SingletonHolder<V>> ent : map.entrySet()) {
            entries.add(new SimpleEntry<>(ent.getKey(), ent.getValue().get()));
        }
        return entries;
    }

    /**
     * Remove the singleton for a given key.
     *
     * @param key
     *            the key
     * @return the old singleton from the map, if one was present, otherwise null.
     * @throws InterruptedException
     *             if interrupted.
     */
    @SuppressWarnings("null")
    public V remove(final K key) throws InterruptedException {
        final SingletonHolder<V> val = map.remove(key);
        return val == null ? null : val.get();
    }

    /** Clear the map. */
    public void clear() {
        map.clear();
    }
}
