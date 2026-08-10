package nonapi.io.github.classgraph.concurrency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import nonapi.io.github.classgraph.concurrency.SingletonMap.NewInstanceException;
import nonapi.io.github.classgraph.concurrency.SingletonMap.NullSingletonException;
import nonapi.io.github.classgraph.utils.LogNode;
import org.jspecify.annotations.Nullable;

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
            @Nullable
            String apply(String key) throws Exception;
        }

        TestMap(final NewInstanceBehavior behavior) {
            this.behavior = behavior;
        }

        @Override
        public String newInstance(final String key, final @Nullable LogNode log) throws Exception {
            numNewInstanceCalls.incrementAndGet();
            return behavior.apply(key);
        }
    }

    /** The instance is created once per key, and the same instance is returned by every later call. */
    @Test
    public void instanceIsCreatedOncePerKey() throws Exception {
        // Build a distinct String instance for each call, so that isSameAs below tests instance identity rather
        // than string interning
        final var map = new TestMap(key -> new StringBuilder(key).toString());
        final var first = map.get("a", null);
        assertThat(map.get("a", null)).isSameAs(first);
        assertThat(map.get("b", null)).isEqualTo("b");
        assertThat(map.numNewInstanceCalls).hasValue(2);
    }

    /** A {@code newInstance} that returns null produces a {@link NullSingletonException} naming the key. */
    @Test
    public void nullInstanceThrowsNullSingletonException() {
        final var map = new TestMap(key -> null);
        assertThatThrownBy(() -> map.get("theKey", null)).isInstanceOf(NullSingletonException.class)
                .hasMessage("newInstance returned null for key theKey");
    }

    /**
     * Once {@code newInstance} has returned null for a key, later calls for that key must also throw, rather than
     * returning null or blocking forever on the latch that {@code newInstance} was supposed to count down.
     */
    @Test
    public void nullInstanceIsRememberedForLaterCalls() {
        final var map = new TestMap(key -> null);
        assertThatThrownBy(() -> map.get("theKey", null)).isInstanceOf(NullSingletonException.class);
        assertThatThrownBy(() -> map.get("theKey", null)).isInstanceOf(NullSingletonException.class);
        assertThat(map.numNewInstanceCalls).hasValue(1);
    }

    /**
     * An exception thrown by {@code newInstance} is wrapped in a {@link NewInstanceException} that names the key.
     */
    @Test
    public void thrownExceptionIsWrappedInNewInstanceException() {
        final var cause = new IllegalArgumentException("could not open");
        final var map = new TestMap(key -> {
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
        final var map = new TestMap(key -> {
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

    /** {@code values()} and {@code entries()} report what is in the map, and {@code values()} skips null values. */
    @Test
    public void valuesAndEntriesReportTheMapContents() throws Exception {
        final var map = new TestMap(key -> "value".equals(key) ? key : null);
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
        final var map = new TestMap(key -> key);
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
        final var map = new TestMap(key -> "fromNewInstance");
        assertThat(map.get("a", null, () -> "fromFactory")).isEqualTo("fromFactory");
        assertThat(map.numNewInstanceCalls).hasValue(0);
    }
}
