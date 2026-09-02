package nonapi.io.github.classgraph.concurrency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import nonapi.io.github.classgraph.concurrency.SingletonMap.NewInstanceException;
import nonapi.io.github.classgraph.concurrency.SingletonMap.NullSingletonException;
import nonapi.io.github.classgraph.utils.LogNode;

/**
 * Tests {@link SingletonMap}, which is how ClassGraph creates one instance per key (one open zipfile per jar, one
 * module reader per module) without holding a lock while the instance is being created.
 */
public class SingletonMapTest {
    /** A {@link SingletonMap} whose {@code newInstance} behavior is supplied per test. */
    private static class TestMap extends SingletonMap<String, String, Exception> {
        /** Counts how many times {@code newInstance} was called, to check that instances really are singletons. */
        final AtomicInteger numNewInstanceCalls = new AtomicInteger();

        /** What {@code newInstance} should do: return a value, return null, or throw. */
        private final NewInstanceBehavior behavior;

        /** What {@code newInstance} should do for a given key. */
        @FunctionalInterface
        interface NewInstanceBehavior {
            /**
             * @param key
             *            the key
             * @return the new instance, or null to make {@code get} throw {@link NullSingletonException}
             * @throws Exception
             *             to make {@code get} throw {@link NewInstanceException}
             */
            String apply(String key) throws Exception;
        }

        TestMap(final NewInstanceBehavior behavior) {
            this.behavior = behavior;
        }

        @Override
        public String newInstance(final String key, final LogNode log) throws Exception {
            numNewInstanceCalls.incrementAndGet();
            return behavior.apply(key);
        }
    }

    /** The instance is created once per key, and the same instance is returned by every later call. */
    @Test
    public void instanceIsCreatedOncePerKey() throws Exception {
        // Build a distinct String instance for each call, so that isSameAs below tests instance identity rather
        // than string interning
        final TestMap map = new TestMap(key -> new StringBuilder(key).toString());
        final String first = map.get("a", null);
        assertThat(map.get("a", null)).isSameAs(first);
        assertThat(map.get("b", null)).isEqualTo("b");
        assertThat(map.numNewInstanceCalls).hasValue(2);
    }

    /** A {@code newInstance} that returns null produces a {@link NullSingletonException} naming the key. */
    @Test
    public void nullInstanceThrowsNullSingletonException() {
        final TestMap map = new TestMap(key -> null);
        assertThatThrownBy(() -> map.get("theKey", null)).isInstanceOf(NullSingletonException.class)
                .hasMessage("newInstance returned null for key theKey");
    }

    /**
     * Once {@code newInstance} has returned null for a key, later calls for that key must also throw, rather than
     * returning null or blocking forever on the latch that {@code newInstance} was supposed to count down.
     */
    @Test
    public void nullInstanceIsRememberedForLaterCalls() {
        final TestMap map = new TestMap(key -> null);
        assertThatThrownBy(() -> map.get("theKey", null)).isInstanceOf(NullSingletonException.class);
        assertThatThrownBy(() -> map.get("theKey", null)).isInstanceOf(NullSingletonException.class);
        assertThat(map.numNewInstanceCalls).hasValue(1);
    }

    /**
     * An exception thrown by {@code newInstance} is wrapped in a {@link NewInstanceException} that names the key.
     */
    @Test
    public void thrownExceptionIsWrappedInNewInstanceException() {
        final IllegalArgumentException cause = new IllegalArgumentException("could not open");
        final TestMap map = new TestMap(key -> {
            throw cause;
        });
        assertThatThrownBy(() -> map.get("theKey", null)).isInstanceOf(NewInstanceException.class)
                .hasMessageStartingWith("newInstance threw an exception for key theKey").hasCause(cause);
    }

    /**
     * An {@link InterruptedException} thrown by {@code newInstance} is propagated as itself rather than wrapped in
     * a {@link NewInstanceException}, and the interrupt status is restored, so that a cancelled scan is still seen
     * as cancelled rather than as a failed instantiation.
     */
    @Test
    public void interruptionIsPropagatedAndInterruptStatusIsRestored() {
        final TestMap map = new TestMap(key -> {
            throw new InterruptedException();
        });
        try {
            assertThatThrownBy(() -> map.get("theKey", null)).isInstanceOf(InterruptedException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            // Don't leak the interrupt status into the next test in this thread
            Thread.interrupted();
        }
    }

    /**
     * Interruption says the calling thread was cancelled, not that the key is bad, so unlike a {@code newInstance}
     * failure it must not be remembered against the key: a later call for the same key retries the creation
     * instead of throwing {@link NullSingletonException} forever.
     */
    @Test
    public void interruptionDoesNotPoisonTheKey() throws Exception {
        final TestMap map = new TestMap(key -> {
            throw new InterruptedException();
        });
        assertThatThrownBy(() -> map.get("theKey", null)).isInstanceOf(InterruptedException.class);
        // Clear the interrupt status that the map restored, so the retry below is not itself interrupted
        assertThat(Thread.interrupted()).isTrue();

        assertThat(map.get("theKey", null, () -> "value")).isEqualTo("value");
    }

    /**
     * A thread waiting for a value whose creation another thread abandoned (because it was interrupted) retries the
     * creation itself, rather than mistaking the abandoned creation for a {@code newInstance} that failed.
     */
    @Test
    public void aWaiterOnAnInterruptedCreationRetriesTheCreation() throws Exception {
        final CountDownLatch creatorInNewInstance = new CountDownLatch(1);
        final CountDownLatch letCreatorFail = new CountDownLatch(1);
        final TestMap map = new TestMap(key -> {
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

        final Thread creator = new Thread(() -> {
            try {
                map.get("theKey", null);
            } catch (final Exception expected) {
                // The creator is expected to fail with InterruptedException
            }
        });
        creator.start();
        assertThat(creatorInNewInstance.await(5, TimeUnit.SECONDS)).isTrue();

        final AtomicReference<Object> waiterResult = new AtomicReference<>();
        final Thread waiter = new Thread(() -> {
            try {
                waiterResult.set(map.get("theKey", null));
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
        final TestMap map = new TestMap(key -> new StringBuilder(key).toString());
        final String first = map.get("a", null);

        map.discard("a");
        final String second = map.get("a", null);
        assertThat(second).isEqualTo(first).isNotSameAs(first);
        assertThat(map.numNewInstanceCalls).hasValue(2);

        // Discarding a key that is not in the map is allowed, so a close path can call it unconditionally
        map.discard("notInTheMap");
    }

    /** {@code values()} and {@code entries()} report what is in the map, and {@code values()} skips null values. */
    @Test
    public void valuesAndEntriesReportTheMapContents() throws Exception {
        final TestMap map = new TestMap(key -> "value".equals(key) ? key : null);
        assertThat(map.isEmpty()).isTrue();
        assertThat(map.get("value", null)).isEqualTo("value");
        assertThatThrownBy(() -> map.get("null", null)).isInstanceOf(NullSingletonException.class);

        assertThat(map.isEmpty()).isFalse();
        // The null singleton is still a map entry, but is not a value
        assertThat(map.values()).containsExactly("value");
        assertThat(map.entries()).hasSize(2);
    }

    /** {@code remove()} returns the removed singleton, and {@code clear()} empties the map. */
    @Test
    public void removeAndClear() throws Exception {
        final TestMap map = new TestMap(key -> key);
        assertThat(map.get("a", null)).isEqualTo("a");
        assertThat(map.get("b", null)).isEqualTo("b");

        assertThat(map.remove("a")).isEqualTo("a");
        assertThat(map.remove("a")).isNull();
        assertThat(map.values()).containsExactly("b");

        map.clear();
        assertThat(map.isEmpty()).isTrue();
    }

    /**
     * The per-call instance factory overrides {@code newInstance}, so that instance creation can be overridden for
     * one key without subclassing the map.
     */
    @Test
    public void newInstanceFactoryOverridesNewInstance() throws Exception {
        final TestMap map = new TestMap(key -> "fromNewInstance");
        assertThat(map.get("a", null, () -> "fromFactory")).isEqualTo("fromFactory");
        assertThat(map.numNewInstanceCalls).hasValue(0);
    }
}
