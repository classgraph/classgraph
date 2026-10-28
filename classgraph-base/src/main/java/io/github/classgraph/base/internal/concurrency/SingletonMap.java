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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import org.jspecify.annotations.Nullable;

/**
 * A concurrent map from keys to lazily initialized singleton values. If a key is absent, the first caller creates
 * the value via a {@link NewInstanceFactory} while concurrent callers for the same key wait for initialization to
 * complete.
 *
 * @param <K>
 *            The key type.
 * @param <V>
 *            The value type.
 */
public class SingletonMap<K, V> {
    /** The backing map. */
    private final ConcurrentMap<K, SingletonHolder<V>> map = new ConcurrentHashMap<>();

    /** Flag indicating whether the owner of this map has been closed, or {@code null} if unowned. */
    private final @Nullable AtomicBoolean closed;

    /** Construct a map with no owner close flag. */
    public SingletonMap() {
        this.closed = null;
    }

    /**
     * Construct a map associated with an owner's closed flag.
     *
     * @param closed
     *            the flag set to {@code true} when the owner of this map is closed.
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

    /** Thrown when a {@link NewInstanceFactory} returns {@code null}. */
    public static class NullSingletonException extends Exception {
        /** serialVersionUID. */
        @Serial
        private static final long serialVersionUID = 1L;

        /**
         * Construct the exception.
         *
         * @param key
         *            the key for which the factory returned {@code null}.
         */
        public NullSingletonException(final Object key) {
            super("No value could be created for key " + key);
        }
    }

    /**
     * Thrown when a {@link NewInstanceFactory} throws an exception other than {@link InterruptedException} or
     * {@link VirtualMachineError}.
     */
    public static class NewInstanceException extends Exception {
        /** serialVersionUID. */
        @Serial
        private static final long serialVersionUID = 1L;

        /**
         * Construct the exception.
         *
         * @param key
         *            the key for which value creation failed.
         * @param t
         *            the exception thrown by the factory.
         */
        public NewInstanceException(final Object key, final Throwable t) {
            super("Creating the value for key " + key + " failed: " + t, t);
        }
    }

    /**
     * Factory for creating a singleton value.
     *
     * @param <V>
     *            The value type.
     */
    @FunctionalInterface
    public interface NewInstanceFactory<V> {
        /**
         * Create a new value instance.
         *
         * @return The new value instance.
         * @throws InterruptedException
         *             if the thread was interrupted while creating the value.
         * @throws Exception
         *             if the value could not be created.
         */
        @Nullable
        V newInstance() throws Exception;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Holder for a lazily initialized singleton value and its initialization latch.
     *
     * @param <V>
     *            the value type
     */
    private static class SingletonHolder<V> {
        /** The value, or {@code null} if not yet created or if creation failed. */
        private volatile @Nullable V singleton;

        /** The exception thrown by the factory, or {@code null} if none. */
        private volatile @Nullable Throwable failure;

        /** Latch counted down once initialization finishes. */
        private final CountDownLatch initialized = new CountDownLatch(1);

        /** Construct an uninitialized holder. */
        SingletonHolder() {
            // Empty
        }

        /**
         * Record a factory failure and release waiting threads.
         *
         * @param t
         *            the exception thrown by the factory.
         * @throws IllegalStateException
         *             if the holder has already been initialized.
         */
        void fail(final Throwable t) {
            this.failure = t;
            set(null);
        }

        /**
         * Return the exception thrown by the factory, or {@code null} if none.
         *
         * @return the exception thrown by the factory, or {@code null}.
         */
        @Nullable
        Throwable failure() {
            return failure;
        }

        /**
         * Check whether initialization completed without producing a value.
         *
         * @return {@code true} if initialization completed and no value was produced.
         */
        boolean failed() {
            return initialized.getCount() == 0 && singleton == null;
        }

        /**
         * Set the value and release waiting threads.
         *
         * @param singleton
         *            the value, or {@code null} if creation failed.
         * @throws IllegalStateException
         *             if the holder has already been initialized.
         */
        void set(final @Nullable V singleton) {
            if (initialized.getCount() == 0) {
                throw new IllegalStateException("Singleton already set");
            }
            this.singleton = singleton;
            initialized.countDown();
        }

        /**
         * Return the value, waiting for initialization to complete if necessary.
         *
         * @param key
         *            the key associated with this value.
         * @param idleTimeout
         *            the idle timeout for the wait, or {@code null} to wait indefinitely.
         * @return the value, or {@code null} if creation failed.
         * @throws InterruptedException
         *             if the thread was interrupted while waiting.
         * @throws TimeoutException
         *             if the idle timeout expired while waiting.
         */
        @Nullable
        V get(final Object key, final @Nullable IdleTimeout idleTimeout)
                throws InterruptedException, TimeoutException {
            if (idleTimeout == null) {
                initialized.await();
            } else {
                while (!initialized.await(idleTimeout.nextWaitNanos(), TimeUnit.NANOSECONDS)) {
                    if (idleTimeout.hasRunOut()) {
                        throw new TimeoutException("Timed out after "
                                + TimeUnit.NANOSECONDS.toMillis(idleTimeout.getTimeoutNanos())
                                + "ms with no progress, waiting for another thread to create the value for key "
                                + key);
                    }
                }
            }
            return singleton;
        }

        /**
         * Return the value if initialization has completed, without waiting.
         *
         * @return the value, or {@code null} if not yet initialized or if creation failed.
         */
        @Nullable
        V peek() {
            return initialized.getCount() == 0 ? singleton : null;
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Get the value for a key, creating and caching it via {@code newInstanceFactory} if absent, or waiting if
     * another thread is currently creating it.
     *
     * <p>
     * Creation failures (other than {@link InterruptedException} and {@link VirtualMachineError}) are cached so
     * subsequent lookups for the same key rethrow the same exception type until cleared with
     * {@link #discardFailed(Object)}.
     *
     * @param key
     *            The key.
     * @param newInstanceFactory
     *            The factory used to create the value if absent.
     * @return The singleton value.
     * @throws IOException
     *             if the owner of this map has been closed.
     * @throws InterruptedException
     *             if the thread was interrupted while creating or waiting for the value.
     * @throws NullSingletonException
     *             if the factory returned {@code null}.
     * @throws NewInstanceException
     *             if the factory threw an exception.
     */
    public V get(final K key, final NewInstanceFactory<V> newInstanceFactory)
            throws IOException, InterruptedException, NullSingletonException, NewInstanceException {
        try {
            return get(key, newInstanceFactory, /* interruptionChecker = */ null, Long.MAX_VALUE);
        } catch (final TimeoutException e) {
            // Cannot happen, since a wait with no timeout does not time out
            throw new IllegalStateException(e);
        }
    }

    /**
     * Get the value for a key as in {@link #get(Object, NewInstanceFactory)}, timing out if another thread is
     * creating the value and reports no progress to {@code interruptionChecker} for {@code timeoutNanos}.
     *
     * @param key
     *            The key.
     * @param newInstanceFactory
     *            The factory used to create the value if absent.
     * @param interruptionChecker
     *            The {@link InterruptionChecker} to which progress is reported, or {@code null}.
     * @param timeoutNanos
     *            The maximum idle wait time in nanoseconds when waiting for another thread, or
     *            {@link Long#MAX_VALUE} to wait indefinitely.
     * @return The singleton value.
     * @throws IOException
     *             if the owner of this map has been closed.
     * @throws InterruptedException
     *             if the thread was interrupted while creating or waiting for the value.
     * @throws TimeoutException
     *             if another thread was creating the value and made no progress for the full timeout duration.
     * @throws NullSingletonException
     *             if the factory returned {@code null}.
     * @throws NewInstanceException
     *             if the factory threw an exception.
     */
    public V get(final K key, final NewInstanceFactory<V> newInstanceFactory,
            final @Nullable InterruptionChecker interruptionChecker, final long timeoutNanos) throws IOException,
            InterruptedException, TimeoutException, NullSingletonException, NewInstanceException {
        final var idleTimeout = timeoutNanos == Long.MAX_VALUE ? null
                : new IdleTimeout(interruptionChecker != null ? interruptionChecker : new InterruptionChecker(),
                        timeoutNanos);
        while (true) {
            checkNotClosed();
            final var singletonHolder = map.get(key);
            if (singletonHolder != null) {
                // There is already a SingletonHolder in the map for this key -- get the value, waiting while the
                // other thread makes progress if it is still creating it (the IdleTimeout is made once, so that
                // waiting again after retrying an abandoned creation does not restart the timeout)
                final var instance = singletonHolder.get(key, idleTimeout);
                if (instance != null) {
                    return instance;
                }
                // A null value means one of two things. Either the factory failed for this key, and the
                // holder was left in the map so that the failure is remembered rather than uselessly retried;
                // or the thread creating the value was interrupted or hit a VirtualMachineError, and took the
                // holder out of the map, since neither means that the key is bad. Tell the two apart by whether
                // the holder is still in the map, and retry the abandoned creation.
                if (map.get(key) == singletonHolder) {
                    final var failure = singletonHolder.failure();
                    if (failure != null) {
                        throw new NewInstanceException(key, failure);
                    }
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
                    if (t instanceof final VirtualMachineError vmError) {
                        // An error such as OutOfMemoryError or StackOverflowError says the JVM is in trouble, not
                        // that the key is bad, so as for interruption, take the holder back out of the map before
                        // releasing the waiters, so that a later call (or a waiter, which sees the holder gone)
                        // tries again. Rethrow it as itself rather than wrapping it in a checked exception that
                        // callers would treat as an ordinary failure to open one key
                        map.remove(key, newSingletonHolder);
                        newSingletonHolder.set(null);
                        throw vmError;
                    }
                    // Any other failure is remembered, with its cause: the holder stays in the map holding null,
                    // so that later calls for the same key, and threads waiting for this creation, throw
                    // NewInstanceException with the same cause instead of retrying a creation that already failed
                    newSingletonHolder.fail(t);
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
     * Return all values in the map whose initialization has completed.
     *
     * @return the values whose initialization has completed.
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
     * Store a value for a key if the key is not already present and the owner of this map has not been closed.
     *
     * @param key
     *            the key.
     * @param value
     *            the value to store.
     * @return {@code true} if the value was stored, or {@code false} if the key was already present or the owner
     *         has been closed.
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
     * Remove a cached creation failure for a key so a subsequent lookup will retry creation.
     *
     * @param key
     *            the key.
     */
    public void discardFailed(final K key) {
        map.computeIfPresent(key, (k, holder) -> holder.failed() ? null : holder);
    }

    /**
     * Remove the entry for a key if it is currently mapped to the given value.
     *
     * @param key
     *            the key.
     * @param value
     *            the expected value to discard.
     */
    public void discard(final K key, final V value) {
        final var holder = map.get(key);
        if (holder != null && holder.peek() == value) {
            map.remove(key, holder);
        }
    }

    /**
     * Remove all entries currently mapped to the given value.
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
