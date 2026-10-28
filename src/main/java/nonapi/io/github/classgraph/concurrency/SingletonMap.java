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
 * Copyright (c) 2026 Luke Hutchison
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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import nonapi.io.github.classgraph.utils.LogNode;

/**
 * A concurrent map from keys to singleton instances, initialized on demand.
 *
 * @param <K>
 *            The key type.
 * @param <V>
 *            The value type.
 * @param <E>
 *            The exception type that may be thrown during instantiation.
 */
public abstract class SingletonMap<K, V, E extends Exception> {
    /** The map. */
    private final ConcurrentMap<K, SingletonHolder<V>> map = new ConcurrentHashMap<>();

    /** Constructor. */
    public SingletonMap() {
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Thrown when {@link SingletonMap#newInstance(Object, LogNode)} returns null, or when an earlier call failed to
     * create the value.
     */
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

    /**
     * Thrown when {@link SingletonMap#newInstance(Object, LogNode)} throws an exception.
     */
    public static class NewInstanceException extends Exception {
        /** serialVersionUID. */
        static final long serialVersionUID = 1L;

        /**
         * Constructor.
         *
         * @param <K>
         *            the key type
         * @param key
         *            the key
         * @param t
         *            the Throwable that was thrown
         */
        public <K> NewInstanceException(final K key, final Throwable t) {
            super("newInstance threw an exception for key " + key + " : " + t, t);
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Holder for a singleton instance initialized asynchronously via a {@link CountDownLatch}.
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

        /** Constructor. */
        SingletonHolder() {
        }

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

        /**
         * Get the singleton value, waiting until it is initialized or the idle timeout expires.
         *
         * @param key
         *            the key for the value.
         * @param idleTimeout
         *            the idle timeout, or null to wait indefinitely.
         * @return the singleton value.
         * @throws InterruptedException
         *             if the thread was interrupted while waiting for the value to be set.
         * @throws TimeoutException
         *             if the idle timeout expired before the value was set.
         */
        V get(final Object key, final IdleTimeout idleTimeout) throws InterruptedException, TimeoutException {
            if (idleTimeout == null) {
                initialized.await();
            } else {
                while (!initialized.await(idleTimeout.nextWaitNanos(), TimeUnit.NANOSECONDS)) {
                    if (idleTimeout.hasRunOut()) {
                        throw new TimeoutException(
                                "Timed out waiting for another thread to create the value for key " + key);
                    }
                }
            }
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
     * Create a new instance.
     *
     * @param <V>
     *            The instance type.
     * @param <E>
     *            The exception type that may be thrown while creating the instance.
     */
    @FunctionalInterface
    public interface NewInstanceFactory<V, E extends Exception> {
        /**
         * Create a new instance.
         *
         * @return The new instance.
         * @throws E
         *             if the instance could not be created.
         * @throws InterruptedException
         *             if the thread was interrupted while creating the instance.
         */
        public V newInstance() throws E, InterruptedException;
    }

    /**
     * Get the singleton for a key, creating it via {@code newInstanceFactory} or
     * {@link #newInstance(Object, LogNode)} if not yet initialized, or waiting if another thread is currently
     * creating it.
     *
     * @param key
     *            The key for the singleton.
     * @param newInstanceFactory
     *            if non-null, a factory for creating new instances, otherwise {@link #newInstance(Object, LogNode)}
     *            is called instead.
     * @param log
     *            The log.
     * @return The non-null singleton instance.
     * @throws E
     *             If {@link #newInstance(Object, LogNode)} threw an exception.
     * @throws InterruptedException
     *             if the thread was interrupted while creating or waiting for the singleton.
     * @throws NullSingletonException
     *             if {@link #newInstance(Object, LogNode)} returned null on this call or an earlier one.
     * @throws NewInstanceException
     *             if {@link #newInstance(Object, LogNode)} threw an exception.
     */
    public V get(final K key, final LogNode log, final NewInstanceFactory<V, E> newInstanceFactory)
            throws E, InterruptedException, NullSingletonException, NewInstanceException {
        try {
            return get(key, log, newInstanceFactory, /* interruptionChecker = */ null, Long.MAX_VALUE);
        } catch (final TimeoutException e) {
            // Cannot happen, since a wait with no timeout does not time out
            throw new IllegalStateException(e);
        }
    }

    /**
     * Get the singleton for a key, creating it if not yet initialized, or waiting for another thread to create it
     * up to the specified idle timeout.
     *
     * @param key
     *            The key for the singleton.
     * @param log
     *            The log.
     * @param newInstanceFactory
     *            if non-null, a factory for creating new instances, otherwise {@link #newInstance(Object, LogNode)}
     *            is called instead.
     * @param interruptionChecker
     *            The {@link InterruptionChecker} to monitor for progress while waiting, or null.
     * @param timeoutNanos
     *            The maximum idle time in nanoseconds to wait for another thread to create the value, or
     *            {@link Long#MAX_VALUE} to wait indefinitely.
     * @return The non-null singleton instance.
     * @throws E
     *             If {@link #newInstance(Object, LogNode)} threw an exception.
     * @throws InterruptedException
     *             if the thread was interrupted while creating or waiting for the singleton.
     * @throws TimeoutException
     *             if another thread was creating the value and made no progress for the timeout duration.
     * @throws NullSingletonException
     *             if {@link #newInstance(Object, LogNode)} returned null on this call or an earlier one.
     * @throws NewInstanceException
     *             if {@link #newInstance(Object, LogNode)} threw an exception on this call or an earlier one.
     */
    public V get(final K key, final LogNode log, final NewInstanceFactory<V, E> newInstanceFactory,
            final InterruptionChecker interruptionChecker, final long timeoutNanos)
            throws E, InterruptedException, TimeoutException, NullSingletonException, NewInstanceException {
        // One IdleTimeout for the whole call, so that waiting again after retrying an abandoned creation does not
        // restart the timeout
        final IdleTimeout idleTimeout = timeoutNanos == Long.MAX_VALUE ? null
                : new IdleTimeout(interruptionChecker != null ? interruptionChecker : new InterruptionChecker(),
                        timeoutNanos);
        while (true) {
            final SingletonHolder<V> singletonHolder = map.get(key);
            if (singletonHolder != null) {
                // There is already a SingletonHolder in the map for this key -- get the value, waiting if another
                // thread is still creating it, until no thread has made progress for the timeout
                final V instance = singletonHolder.get(key, idleTimeout);
                if (instance != null) {
                    return instance;
                }
                // A null value means one of two things. Either newInstance() failed for this key, and the
                // holder was left in the map so that the failure is remembered rather than uselessly retried;
                // or the thread creating the value was interrupted or hit a VirtualMachineError, and took the
                // holder out of the map, since neither means that the key is bad. Tell the two apart by whether
                // the holder is still in the map, and retry the abandoned creation.
                if (map.get(key) == singletonHolder) {
                    throw new NullSingletonException(key);
                }
            } else {
                // There is no SingletonHolder in the map for this key, need to create one
                // (need to handle race condition, hence the putIfAbsent call)
                final SingletonHolder<V> newSingletonHolder = new SingletonHolder<>();
                if (map.putIfAbsent(key, newSingletonHolder) != null) {
                    // Another thread claimed the key first -- loop back and read its value
                    continue;
                }
                @SuppressWarnings("null")
                V instance = null;
                try {
                    // Create a new instance
                    if (newInstanceFactory != null) {
                        // Call NewInstanceFactory
                        instance = newInstanceFactory.newInstance();
                    } else {
                        // Call overridden newInstance method
                        instance = newInstance(key, log);
                    }

                } catch (final Throwable t) {
                    if (t instanceof InterruptedException) {
                        // The creation was abandoned, not failed, so take the holder back out of the map --
                        // before releasing the waiters, so that no new caller adopts it -- to let a later call
                        // for this key try the creation again
                        map.remove(key, newSingletonHolder);
                        // Always call .set() even on failure, since .set() counts down the latch that waiting
                        // threads block on -- otherwise threads that call .get() may end up waiting forever
                        newSingletonHolder.set(null);
                        // Don't swallow interruption by wrapping it in a NewInstanceException -- restore the
                        // interrupt status (throwing InterruptedException cleared it) and propagate it, so that
                        // a cancelled scan is still seen as cancelled rather than as a failed instantiation
                        Thread.currentThread().interrupt();
                        throw (InterruptedException) t;
                    }
                    if (t instanceof VirtualMachineError) {
                        // An error such as OutOfMemoryError or StackOverflowError says the JVM is in trouble, not
                        // that the key is bad, so as for interruption, take the holder back out of the map before
                        // releasing the waiters, so that a later call (or a waiter, which sees the holder gone)
                        // tries again. Rethrow it as itself rather than wrapping it in a checked exception that
                        // callers would treat as an ordinary failure to open one key
                        map.remove(key, newSingletonHolder);
                        newSingletonHolder.set(null);
                        throw (VirtualMachineError) t;
                    }
                    // Any other failure is remembered: the holder stays in the map holding null, so that later
                    // calls for the same key throw NullSingletonException instead of retrying a creation that
                    // already failed
                    newSingletonHolder.set(null);
                    throw new NewInstanceException(key, t);
                }
                newSingletonHolder.set(instance);
                if (instance == null) {
                    throw new NullSingletonException(key);
                }
                return instance;
            }
        }
    }

    /**
     * Get the singleton for a key, creating it via {@link #newInstance(Object, LogNode)} if not yet initialized, or
     * waiting if another thread is currently creating it.
     *
     * @param key
     *            The key for the singleton.
     * @param log
     *            The log.
     * @return The non-null singleton instance.
     * @throws E
     *             If {@link #newInstance(Object, LogNode)} threw an exception.
     * @throws InterruptedException
     *             if the thread was interrupted while creating or waiting for the singleton.
     * @throws NullSingletonException
     *             if {@link #newInstance(Object, LogNode)} returned null.
     * @throws NewInstanceException
     *             if {@link #newInstance(Object, LogNode)} threw an exception.
     */
    public V get(final K key, final LogNode log)
            throws E, InterruptedException, NullSingletonException, NewInstanceException {
        return get(key, log, null);
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

    /**
     * Remove the singleton entry for a given key without waiting for any in-flight initialization to complete.
     *
     * @param key
     *            the key
     */
    public void discard(final K key) {
        map.remove(key);
    }

    /** Clear the map. */
    public void clear() {
        map.clear();
    }
}
