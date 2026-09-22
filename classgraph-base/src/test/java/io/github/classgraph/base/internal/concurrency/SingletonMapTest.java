package io.github.classgraph.base.internal.concurrency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import io.github.classgraph.base.internal.concurrency.SingletonMap.NewInstanceException;
import io.github.classgraph.base.internal.concurrency.SingletonMap.NullSingletonException;
import org.jspecify.annotations.Nullable;

/**
 * Tests {@link SingletonMap}, which is how ClassGraph creates one value per key (one root per directory or jarfile,
 * one classpath element per classpath entry) without holding a lock while the value is being created.
 */
public class SingletonMapTest {
    /** A {@link SingletonMap} that creates each value with a factory whose behavior is supplied per test. */
    private static class TestMap {
        /** The map under test. */
        final SingletonMap<String, String> map;

        /** Counts how many times the factory was called, to check that values really are created once. */
        final AtomicInteger numNewInstanceCalls = new AtomicInteger();

        /** What the factory should do: return a value, return null, or throw. */
        private final NewInstanceBehavior behavior;

        /** What the factory should do for a given key. */
        @FunctionalInterface
        interface NewInstanceBehavior {
            /**
             * @param key
             *            the key
             * @return the new value, or null to make {@code get} throw {@link NullSingletonException}
             * @throws Exception
             *             to make {@code get} throw {@link NewInstanceException}
             */
            @Nullable
            String apply(String key) throws Exception;
        }

        TestMap(final NewInstanceBehavior behavior) {
            this.map = new SingletonMap<>();
            this.behavior = behavior;
        }

        TestMap(final AtomicBoolean closed, final NewInstanceBehavior behavior) {
            this.map = new SingletonMap<>(closed);
            this.behavior = behavior;
        }

        String get(final String key) throws Exception {
            return map.get(key, () -> {
                numNewInstanceCalls.incrementAndGet();
                return behavior.apply(key);
            });
        }
    }

    /** The instance is created once per key, and the same instance is returned by every later call. */
    @Test
    public void instanceIsCreatedOncePerKey() throws Exception {
        // Build a distinct String instance for each call, so that isSameAs below tests instance identity rather
        // than string interning
        final var map = new TestMap(key -> new StringBuilder(key).toString());
        final var first = map.get("a");
        assertThat(map.get("a")).isSameAs(first);
        assertThat(map.get("b")).isEqualTo("b");
        assertThat(map.numNewInstanceCalls).hasValue(2);
    }

    /** A factory that returns null produces a {@link NullSingletonException} naming the key. */
    @Test
    public void nullInstanceThrowsNullSingletonException() {
        final var map = new TestMap(key -> null);
        assertThatThrownBy(() -> map.get("theKey")).isInstanceOf(NullSingletonException.class)
                .hasMessage("No value could be created for key theKey");
    }

    /**
     * Once the factory has returned null for a key, later calls for that key also throw, rather than calling the
     * factory again.
     */
    @Test
    public void nullInstanceIsRememberedForLaterCalls() {
        final var map = new TestMap(key -> null);
        assertThatThrownBy(() -> map.get("theKey")).isInstanceOf(NullSingletonException.class);
        assertThatThrownBy(() -> map.get("theKey")).isInstanceOf(NullSingletonException.class);
        assertThat(map.numNewInstanceCalls).hasValue(1);
    }

    /**
     * An exception thrown by the factory is wrapped in a {@link NewInstanceException} that names the key.
     */
    @Test
    public void thrownExceptionIsWrappedInNewInstanceException() {
        final var cause = new IllegalArgumentException("could not open");
        final var map = new TestMap(key -> {
            throw cause;
        });
        assertThatThrownBy(() -> map.get("theKey")).isInstanceOf(NewInstanceException.class)
                .hasMessageStartingWith("Creating the value for key theKey failed: ").hasCause(cause);
    }

    /**
     * An {@link InterruptedException} thrown by the factory is propagated as itself rather than wrapped in a
     * {@link NewInstanceException}, and the interrupt status is restored, so that a cancelled scan is still seen as
     * cancelled rather than as a failed instantiation.
     */
    @Test
    public void interruptionIsPropagatedAndInterruptStatusIsRestored() {
        final var map = new TestMap(key -> {
            throw new InterruptedException();
        });
        try {
            assertThatThrownBy(() -> map.get("theKey")).isInstanceOf(InterruptedException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            // Don't leak the interrupt status into the next test in this thread
            Thread.interrupted();
        }
    }

    /**
     * Interruption says the calling thread was cancelled, not that the key is bad, so unlike a failure of the
     * factory it must not be remembered against the key: a later call for the same key on the still-open map
     * retries the creation instead of throwing {@link NullSingletonException} forever.
     */
    @Test
    public void interruptionDoesNotPoisonTheKey() throws Exception {
        final var map = new TestMap(key -> {
            throw new InterruptedException();
        });
        assertThatThrownBy(() -> map.get("theKey")).isInstanceOf(InterruptedException.class);
        // Clear the interrupt status that the map restored, so the retry below is not itself interrupted
        assertThat(Thread.interrupted()).isTrue();

        assertThat(map.map.get("theKey", () -> "value")).isEqualTo("value");
    }

    /**
     * A thread waiting for a value whose creation another thread abandoned (because it was interrupted) retries the
     * creation itself, rather than mistaking the abandoned creation for a factory that failed.
     */
    @Test
    public void aWaiterOnAnInterruptedCreationRetriesTheCreation() throws Exception {
        final var creatorInNewInstance = new CountDownLatch(1);
        final var letCreatorFail = new CountDownLatch(1);
        final var map = new TestMap(key -> {
            if (creatorInNewInstance.getCount() > 0) {
                // First call: the creator thread. Hold the creation open until the waiter is waiting on it,
                // then abandon it by throwing InterruptedException
                creatorInNewInstance.countDown();
                letCreatorFail.await();
                throw new InterruptedException();
            }
            // Second call: the retry
            return "value";
        });

        final var creator = new Thread(() -> {
            try {
                map.get("theKey");
            } catch (final Exception expected) {
                // The creator is expected to fail with InterruptedException
            }
        });
        creator.start();
        assertThat(creatorInNewInstance.await(5, TimeUnit.SECONDS)).isTrue();

        final AtomicReference<Object> waiterResult = new AtomicReference<>();
        final var waiter = new Thread(() -> {
            try {
                waiterResult.set(map.get("theKey"));
            } catch (final Exception e) {
                waiterResult.set(e);
            }
        });
        waiter.start();
        // Give the waiter time to block on the creator's unfinished value, then abandon the creation
        Thread.sleep(100);
        letCreatorFail.countDown();

        creator.join(5000);
        waiter.join(5000);
        assertThat(waiterResult.get()).isEqualTo("value");
        assertThat(map.numNewInstanceCalls).hasValue(2);
    }

    /**
     * {@code discard()} takes the singleton for a key out of the map without waiting, so that a later lookup
     * rebuilds it. This is how a closed object leaves the cache that holds it, so that the cache does not keep
     * handing out an instance that can no longer be used.
     */
    @Test
    public void discardAllowsTheValueToBeRebuilt() throws Exception {
        final var map = new TestMap(key -> new StringBuilder(key).toString());
        final var first = map.get("a");

        map.map.discard("a");
        final var second = map.get("a");
        assertThat(second).isEqualTo(first).isNotSameAs(first);
        assertThat(map.numNewInstanceCalls).hasValue(2);

        // Discarding a key that is not in the map is allowed, so a close path can call it unconditionally
        map.map.discard("notInTheMap");
    }

    /** {@code completedValues()} reports the values that were created, skipping a key whose creation failed. */
    @Test
    public void completedValuesSkipsAFailedCreation() throws Exception {
        final var map = new TestMap(key -> "value".equals(key) ? key : null);
        assertThat(map.map.completedValues()).isEmpty();
        assertThat(map.get("value")).isEqualTo("value");
        assertThatThrownBy(() -> map.get("null")).isInstanceOf(NullSingletonException.class);
        assertThat(map.map.completedValues()).containsExactly("value");

        map.map.clear();
        assertThat(map.map.completedValues()).isEmpty();
    }

    /**
     * {@code putIfAbsent()} publishes a value under a key that has none, and leaves a key that has a value alone,
     * so that two names for the same thing stay consistent.
     */
    @Test
    public void putIfAbsentOnlyPutsIntoAnEmptyKey() throws Exception {
        final var map = new TestMap(key -> key);
        assertThat(map.map.putIfAbsent("a", "put")).isTrue();
        assertThat(map.get("a")).isEqualTo("put");
        assertThat(map.map.putIfAbsent("a", "putAgain")).isFalse();
        assertThat(map.get("a")).isEqualTo("put");
        assertThat(map.numNewInstanceCalls).hasValue(0);
    }

    /**
     * {@code discard(key, value)} only takes out the given value, so that a stale close does not discard a fresh
     * value that has since replaced it; {@code discardValue(value)} takes the value out under every key.
     */
    @Test
    public void discardByValueOnlyDiscardsThatValue() throws Exception {
        final var map = new TestMap(key -> new StringBuilder(key).toString());
        final var first = map.get("a");
        map.map.discard("a", new StringBuilder("a").toString());
        assertThat(map.get("a")).isSameAs(first);
        map.map.discard("a", first);
        assertThat(map.get("a")).isNotSameAs(first);

        final var value = map.get("b");
        assertThat(map.map.putIfAbsent("alias", value)).isTrue();
        map.map.discardValue(value);
        assertThat(map.map.completedValues()).doesNotContain(value);
    }

    /** A per-call factory can create a value for one key with whatever it needs, not only from the key. */
    @Test
    public void theFactoryIsCalledOnlyForAKeyWithNoValue() throws Exception {
        final var map = new TestMap(key -> "fromTestMap");
        assertThat(map.map.get("a", () -> "fromFactory")).isEqualTo("fromFactory");
        assertThat(map.get("a")).isEqualTo("fromFactory");
        assertThat(map.numNewInstanceCalls).hasValue(0);
    }

    /**
     * A map that was given the closed flag of its owner turns a lookup away once the owner sets the flag, without
     * creating an instance that nothing would ever release. The flag is held rather than copied, so the map sees a
     * close that happens after it was built.
     */
    @Test
    public void closedFlagTurnsAwayALookup() throws Exception {
        final var closed = new AtomicBoolean(false);
        final var map = new TestMap(closed, key -> key);
        assertThat(map.get("a")).isEqualTo("a");

        closed.set(true);
        assertThatThrownBy(() -> map.get("b")).isInstanceOf(IOException.class).hasMessage("Already closed");
        // Even a key that is already in the map is turned away, since the value it holds is about to be released
        assertThatThrownBy(() -> map.get("a")).isInstanceOf(IOException.class);
        assertThatThrownBy(() -> map.map.get("b", () -> "fromFactory")).isInstanceOf(IOException.class);
        assertThat(map.numNewInstanceCalls).hasValue(1);
    }

    /** {@code putIfAbsent()} puts nothing once the owner is closed, since nothing would release the value. */
    @Test
    public void closedFlagTurnsAwayAPut() {
        final var closed = new AtomicBoolean(true);
        final var map = new TestMap(closed, key -> key);
        assertThat(map.map.putIfAbsent("a", "a")).isFalse();
        assertThat(map.map.completedValues()).isEmpty();
    }
}
