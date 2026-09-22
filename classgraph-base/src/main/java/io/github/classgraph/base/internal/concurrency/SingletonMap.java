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
package io.github.classgraph.base.internal.concurrency;

import java.io.IOException;
import java.io.Serial;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

import org.jspecify.annotations.Nullable;

/**
 * A map from keys to values that are each created once, on demand. Works like
 * {@code concurrentMap.computeIfAbsent(key, key -> factory.newInstance())}, except that the factory may throw a
 * checked exception or be interrupted, and no lock is held while it runs: a second thread that asks for the same
 * key waits for the first thread's value, and a thread asking for a different key is not held up at all.
 *
 * <p>
 * A map may be given the {@link AtomicBoolean} that whatever owns it sets when it is closed, in which case a lookup
 * made after the owner was closed throws {@link IOException}, rather than building and caching a value that nothing
 * would ever release again.
 *
 * @param <K>
 *            The key type.
 * @param <V>
 *            The value type.
 */
public class SingletonMap<K, V> {
    /** The map. */
    private final ConcurrentMap<K, SingletonHolder<V>> map = new ConcurrentHashMap<>();

    /**
     * The flag that whatever owns this map sets when it is closed, or null if this map has no owner that can be
     * closed.
     */
    private final @Nullable AtomicBoolean closed;

    /** Create a map that has no owner that can be closed, so that a lookup is always allowed. */
    public SingletonMap() {
        this.closed = null;
    }

    /**
     * Create a map owned by something that can be closed.
     *
     * @param closed
     *            the flag that the owner of this map sets when it is closed. The flag is held, not copied, so this
     *            map sees the close the moment the owner marks it, and the owner's closed state is not tracked in a
     *            second place that could disagree with it.
     */
    public SingletonMap(final AtomicBoolean closed) {
        this.closed = closed;
    }

    /**
     * Check that the owner of this map has not been closed.
     *
     * @throws IOException
     *             if the owner of this map has been closed.
     */
    private void checkNotClosed() throws IOException {
        if (closed != null && closed.get()) {
            throw new IOException("Already closed");
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /** Thrown when the factory that creates a value returns null. */
    public static class NullSingletonException extends Exception {
        /** serialVersionUID. */
        @Serial
        private static final long serialVersionUID = 1L;

        /**
         * Create the exception.
         *
         * @param key
         *            the key the value was being created for.
         */
        public NullSingletonException(final Object key) {
            super("No value could be created for key " + key);
        }
    }

    /** Thrown when the factory that creates a value throws an exception. */
    public static class NewInstanceException extends Exception {
        /** serialVersionUID. */
        @Serial
        private static final long serialVersionUID = 1L;

        /**
         * Create the exception.
         *
         * @param key
         *            the key the value was being created for.
         * @param t
         *            the exception the factory threw.
         */
        public NewInstanceException(final Object key, final Throwable t) {
            super("Creating the value for key " + key + " failed: " + t, t);
        }
    }

    /**
     * Creates the value for a key.
     *
     * @param <V>
     *            The value type.
     * @param <E>
     *            The exception type that may be thrown while creating the value.
     */
    @FunctionalInterface
    public interface NewInstanceFactory<V, E extends Exception> {
        /**
         * Create the value.
         *
         * @return The value, which should not be null.
         * @throws E
         *             if the value could not be created.
         * @throws InterruptedException
         *             if the thread was interrupted while creating the value.
         */
        @Nullable
        V newInstance() throws E, InterruptedException;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Holds the value for one key. The holder is put into the map before the value is created, so that a second
     * thread asking for the same key finds it and waits for the value, rather than creating a second one.
     *
     * @param <V>
     *            the value type
     */
    private static class SingletonHolder<V> {
        /** The value, or null if it could not be created. */
        private volatile @Nullable V singleton;

        /** Counted down once the value has been set. */
        private final CountDownLatch initialized = new CountDownLatch(1);

        /**
         * Set the value, and release the threads waiting for it.
         *
         * @param singleton
         *            the value, or null if it could not be created.
         * @throws IllegalStateException
         *             if the value was already set.
         */
        void set(final @Nullable V singleton) {
            if (initialized.getCount() == 0) {
                throw new IllegalStateException("Singleton already set");
            }
            this.singleton = singleton;
            initialized.countDown();
        }

        /**
         * Get the value, waiting for it to be set if it has not been set yet.
         *
         * @return the value, or null if it could not be created.
         * @throws InterruptedException
         *             if the thread was interrupted while waiting for the value to be set.
         */
        @Nullable
        V get() throws InterruptedException {
            initialized.await();
            return singleton;
        }

        /**
         * Get the value if it has already been set, without waiting for it.
         *
         * @return the value, or null if the value has not been set yet, or if it could not be created.
         */
        @Nullable
        V peek() {
            return initialized.getCount() == 0 ? singleton : null;
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Get the value for a key. If there is none yet, create it with the given factory and store it; if another
     * thread is creating it, wait for that thread's value.
     *
     * <p>
     * A failure to create the value is remembered, so that a later call for the same key throws
     * {@link NullSingletonException} rather than trying again. An interruption is not remembered, since it means
     * the creating thread was cancelled, not that the key is bad, so a later call tries again.
     *
     * @param key
     *            The key.
     * @param newInstanceFactory
     *            Creates the value, if there is no value for the key yet.
     * @return The value.
     * @throws IOException
     *             if the owner of this map has been closed.
     * @throws InterruptedException
     *             if the thread was interrupted while creating the value, or while waiting for another thread to
     *             create it.
     * @throws NullSingletonException
     *             if the factory returned null, on this call or an earlier one, or threw an exception on an earlier
     *             call.
     * @throws NewInstanceException
     *             if the factory threw an exception on this call.
     */
    public V get(final K key, final NewInstanceFactory<V, ?> newInstanceFactory)
            throws IOException, InterruptedException, NullSingletonException, NewInstanceException {
        while (true) {
            checkNotClosed();
            final var singletonHolder = map.get(key);
            if (singletonHolder != null) {
                // There is already a SingletonHolder in the map for this key -- get the value
                final var instance = singletonHolder.get();
                if (instance != null) {
                    return instance;
                }
                // A null value means one of two things. Either the factory failed for this key, and the
                // holder was left in the map so that the failure is remembered rather than uselessly retried;
                // or the thread creating the value was interrupted and took the holder out of the map, since
                // interruption means that thread was cancelled, not that the key is bad. Tell the two apart by
                // whether the holder is still in the map, and retry the abandoned creation.
                if (map.get(key) == singletonHolder) {
                    throw new NullSingletonException(key);
                }
            } else {
                // There is no SingletonHolder in the map for this key, need to create one (need to handle race
                // condition, hence the putIfAbsent call)
                final SingletonHolder<V> newSingletonHolder = new SingletonHolder<>();
                if (map.putIfAbsent(key, newSingletonHolder) != null) {
                    // Another thread claimed the key first -- loop back and read its value
                    continue;
                }
                @Nullable
                V instance = null;
                try {
                    instance = newInstanceFactory.newInstance();
                } catch (final Throwable t) {
                    if (t instanceof final InterruptedException interruptedException) {
                        // The creation was abandoned, not failed, so take the holder back out of the map --
                        // before releasing the waiters, so that no new caller adopts it -- to let a later call
                        // for this key try the creation again
                        map.remove(key, newSingletonHolder);
                        // Always call .set() even on failure, since .set() counts down the latch that waiting
                        // threads block on -- otherwise threads that call .get() may end up waiting forever
                        newSingletonHolder.set(null);
                        // Don't swallow interruption by wrapping it in a NewInstanceException -- restore the
                        // interrupt status (throwing InterruptedException cleared it) and propagate it, so that a
                        // cancelled scan is still seen as cancelled rather than as a failed instantiation
                        Thread.currentThread().interrupt();
                        throw interruptedException;
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
     * Get the values in the map whose creation has already completed, without blocking. A value that another thread
     * is still creating is skipped, as is a value whose creation failed. Reading is allowed whether or not the
     * owner of this map has been closed, so the caller decides what a read during a close should see.
     *
     * @return the values whose creation has completed.
     */
    public List<V> completedValues() {
        final List<V> completed = new ArrayList<>(map.size());
        for (final SingletonHolder<V> holder : map.values()) {
            final var value = holder.peek();
            if (value != null) {
                completed.add(value);
            }
        }
        return completed;
    }

    /**
     * Put a value into the map for a key that has no value yet, without running a factory. This is how a value
     * built under one key is published under a second name for the same thing; the value the map already holds wins
     * any race, so two names that resolve to each other stay consistent. Nothing is put once the owner of this map
     * has been closed, since the value would never be released again.
     *
     * @param key
     *            the key.
     * @param value
     *            the value to put.
     * @return true if the value was put into the map, or false if the map already held a value (or a creation in
     *         flight) for the key, or the owner of this map has been closed.
     */
    public boolean putIfAbsent(final K key, final V value) {
        if (closed != null && closed.get()) {
            return false;
        }
        final SingletonHolder<V> newSingletonHolder = new SingletonHolder<>();
        newSingletonHolder.set(value);
        return map.putIfAbsent(key, newSingletonHolder) == null;
    }

    /**
     * Discard the singleton for a given key, if there is one, so that a later lookup for the key rebuilds the value
     * rather than getting the discarded instance. This is how a closed object leaves the cache that holds it. Does
     * not wait for a value that another thread is still creating, so it can be called from a close path that must
     * not block; a discarded in-flight creation still completes for the thread creating it, but the value is no
     * longer in the map.
     *
     * @param key
     *            the key
     */
    public void discard(final K key) {
        map.remove(key);
    }

    /**
     * Discard the singleton for a given key, but only if the map still holds the given value for it. This is how a
     * closed object leaves a cache that may since have been given a fresh value for the same key: the fresh value
     * is left alone. Like {@link #discard(Object)}, never blocks, so it can be called from a close path.
     *
     * @param key
     *            the key.
     * @param value
     *            the value to discard, if the map still holds it for the key.
     */
    public void discard(final K key, final V value) {
        final var holder = map.get(key);
        if (holder != null && holder.peek() == value) {
            map.remove(key, holder);
        }
    }

    /**
     * Discard the given value from the map, under every key that holds it, so that a later lookup for any of those
     * keys rebuilds the value rather than getting the discarded instance. A creation of the same value still in
     * flight is left alone, since its creator has not published it yet. Never blocks, so it can be called from a
     * close path.
     *
     * @param value
     *            the value to discard.
     */
    public void discardValue(final V value) {
        map.values().removeIf(holder -> holder.peek() == value);
    }

    /** Clear the map. */
    public void clear() {
        map.clear();
    }
}
