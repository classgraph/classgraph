package io.github.classgraph.vfs.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

/** Tests for {@link Recycler} and {@link RecycleOnClose}. */
public class RecyclerTest {
    /** An instance that records what the recycler did to it. */
    private static final class Recyclable implements Resettable, AutoCloseable {
        /** The number of times this instance has been reset. */
        int numResets;

        /** The number of times this instance has been closed. */
        int numCloses;

        @Override
        public void reset() {
            numResets++;
        }

        @Override
        public void close() {
            numCloses++;
        }
    }

    /** A recycler of instances that record what was done to them. */
    private static final class RecyclableRecycler extends Recycler<Recyclable, RuntimeException> {
        /** The number of instances that have been allocated. */
        final AtomicInteger numAllocated = new AtomicInteger();

        @Override
        public Recyclable newInstance() {
            numAllocated.incrementAndGet();
            return new Recyclable();
        }
    }

    /** An instance is reused once it has been recycled, rather than a new one being allocated. */
    @Test
    public void anInstanceIsReusedOnceItHasBeenRecycled() {
        try (var recycler = new RecyclableRecycler()) {
            final var first = recycler.acquire();
            recycler.recycle(first);
            assertThat(recycler.acquire()).isSameAs(first);
            assertThat(recycler.numAllocated).hasValue(1);
        }
    }

    /** An instance that is still in use is not handed out a second time. */
    @Test
    public void anInstanceThatIsStillInUseIsNotHandedOutAgain() {
        try (var recycler = new RecyclableRecycler()) {
            final var first = recycler.acquire();
            final var second = recycler.acquire();
            assertThat(second).isNotSameAs(first);
            assertThat(recycler.numAllocated).hasValue(2);
        }
    }

    /** An instance acquired in a try-with-resources block is recycled when the block exits. */
    @Test
    public void anInstanceIsRecycledWhenTheBlockThatAcquiredItExits() {
        try (var recycler = new RecyclableRecycler()) {
            final Recyclable instance;
            try (RecycleOnClose<Recyclable, RuntimeException> recycleOnClose = recycler.acquireRecycleOnClose()) {
                instance = recycleOnClose.get();
                assertThat(instance.numResets).isZero();
            }
            // The instance was recycled when the block exited, so it is handed out again rather than reallocated
            assertThat(instance.numResets).isEqualTo(1);
            assertThat(recycler.acquire()).isSameAs(instance);
        }
    }

    /**
     * Closing a {@link RecycleOnClose} twice recycles the instance once, rather than throwing the second time.
     * Closing twice is legal for any {@link AutoCloseable}, and it happens whenever a caller closes one explicitly
     * inside the try-with-resources block that also closes it.
     */
    @Test
    public void closingARecycleOnCloseTwiceRecyclesTheInstanceOnce() {
        try (var recycler = new RecyclableRecycler()) {
            final RecycleOnClose<Recyclable, RuntimeException> recycleOnClose = recycler.acquireRecycleOnClose();
            final var instance = recycleOnClose.get();
            recycleOnClose.close();
            assertThatCode(recycleOnClose::close).doesNotThrowAnyException();
            // The instance went back into the pool once, not twice, so it is only handed out to one caller
            assertThat(instance.numResets).isEqualTo(1);
            assertThat(recycler.acquire()).isSameAs(instance);
            assertThat(recycler.acquire()).isNotSameAs(instance);
        }
    }

    /** A recycled instance is reset before it is handed out again, so that it does not carry over any state. */
    @Test
    public void aRecycledInstanceIsReset() {
        try (var recycler = new RecyclableRecycler()) {
            final var instance = recycler.acquire();
            assertThat(instance.numResets).isZero();
            recycler.recycle(instance);
            assertThat(instance.numResets).isEqualTo(1);
        }
    }

    /** Recycling an instance that is not in use is a caller error, and so is recycling one twice. */
    @Test
    public void recyclingAnInstanceThatIsNotInUseIsRejected() {
        try (var recycler = new RecyclableRecycler()) {
            assertThatThrownBy(() -> recycler.recycle(new Recyclable()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Tried to recycle an instance that was not in use");

            final var instance = recycler.acquire();
            recycler.recycle(instance);
            assertThatThrownBy(() -> recycler.recycle(instance)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    /** Recycling null does nothing, so that a caller does not have to null-check first. */
    @Test
    public void recyclingNullDoesNothing() {
        try (var recycler = new RecyclableRecycler()) {
            recycler.recycle(null);
            assertThat(recycler.acquire()).isNotNull();
            assertThat(recycler.numAllocated).hasValue(1);
        }
    }

    /** An instance cannot be allocated as null, since every caller of {@code acquire()} dereferences the result. */
    @Test
    public void allocatingANullInstanceIsRejected() {
        try (var recycler = new Recycler<Object, RuntimeException>() {
            @Override
            public Object newInstance() {
                return null;
            }
        }) {
            assertThatThrownBy(recycler::acquire).isInstanceOf(NullPointerException.class)
                    .hasMessage("Failed to allocate a new recyclable instance");
        }
    }

    /** An exception thrown while allocating an instance reaches the caller unwrapped. */
    @Test
    public void anExceptionThrownWhileAllocatingAnInstanceReachesTheCaller() {
        final var cause = new IOException("the reason");
        try (var recycler = new Recycler<Object, IOException>() {
            @Override
            public Object newInstance() throws IOException {
                throw cause;
            }
        }) {
            assertThatThrownBy(recycler::acquire).isSameAs(cause);
            assertThatThrownBy(recycler::acquireRecycleOnClose).isSameAs(cause);
        }
    }

    /** Closing the recycler closes the instances that are not in use, and it can be used again afterwards. */
    @Test
    public void closingTheRecyclerClosesTheInstancesThatAreNotInUse() {
        final var recycler = new RecyclableRecycler();
        final var instance = recycler.acquire();
        recycler.recycle(instance);

        recycler.close();
        assertThat(instance.numCloses).isEqualTo(1);

        // The recycler can still be used, but the instance it closed has been discarded rather than reused
        final var reacquired = recycler.acquire();
        assertThat(reacquired).isNotSameAs(instance);
        recycler.recycle(reacquired);
        recycler.close();
        assertThat(instance.numCloses).isEqualTo(1);
        assertThat(reacquired.numCloses).isEqualTo(1);
    }

    /**
     * Closing the recycler leaves the instances that are still in use alone, since another thread may still be
     * reading through them. Force-closing it closes those too.
     */
    @Test
    public void onlyForceCloseClosesTheInstancesThatAreStillInUse() {
        final var recycler = new RecyclableRecycler();
        final var inUse = recycler.acquire();

        recycler.close();
        assertThat(inUse.numCloses).isZero();

        recycler.forceClose();
        assertThat(inUse.numCloses).isEqualTo(1);
        // A force-closed instance is not reset, since it is discarded rather than recycled
        assertThat(inUse.numResets).isZero();
    }

    /**
     * An instance handed back after a force-close does not throw, since an instance is normally handed back by the
     * close() method of whatever borrowed it, and a close() must not fail just because the pool went first.
     */
    @Test
    public void anInstanceHandedBackAfterAForceCloseDoesNotThrow() {
        final var recycler = new RecyclableRecycler();
        final var instance = recycler.acquire();

        recycler.forceClose();
        assertThat(instance.numCloses).isEqualTo(1);

        assertThatCode(() -> recycler.recycle(instance)).doesNotThrowAnyException();
        // Closed by the force-close itself, and not closed a second time by the recycle
        assertThat(instance.numCloses).isEqualTo(1);
        assertThat(instance.numResets).isZero();
    }

    /**
     * A force-close is terminal, so an instance acquired afterwards is closed when it is handed back rather than
     * being pooled in a recycler that nothing would ever drain again.
     */
    @Test
    public void anInstanceAcquiredAfterAForceCloseIsClosedWhenItIsHandedBack() {
        final var recycler = new RecyclableRecycler();
        recycler.forceClose();

        final var instance = recycler.acquire();
        assertThat(instance.numCloses).isZero();
        recycler.recycle(instance);
        assertThat(instance.numCloses).isEqualTo(1);

        // A pooled instance would be handed out again, whereas a closed one is discarded
        assertThat(recycler.acquire()).isNotSameAs(instance);
        assertThat(recycler.numAllocated).hasValue(2);
    }

    /** A try-with-resources block that outlives a force-close still closes its instance, and does not throw. */
    @Test
    public void aRecycleOnCloseBlockThatOutlivesAForceCloseDoesNotThrow() {
        final var recycler = new RecyclableRecycler();
        final RecycleOnClose<Recyclable, RuntimeException> recycleOnClose = recycler.acquireRecycleOnClose();
        final var instance = recycleOnClose.get();

        recycler.forceClose();

        assertThatCode(recycleOnClose::close).doesNotThrowAnyException();
        assertThat(instance.numCloses).isEqualTo(1);
    }

    /** An instance that throws while it is being closed does not stop the other instances from being closed. */
    @Test
    public void anInstanceThatThrowsWhileItIsClosedIsIgnored() {
        final var numCloses = new AtomicInteger();
        try (var recycler = new Recycler<AutoCloseable, RuntimeException>() {
            @Override
            public AutoCloseable newInstance() {
                return () -> {
                    numCloses.incrementAndGet();
                    throw new IOException("the reason");
                };
            }
        }) {
            // Both instances are acquired before either is recycled, so that two of them are allocated
            final var first = recycler.acquire();
            final var second = recycler.acquire();
            recycler.recycle(first);
            recycler.recycle(second);
        }
        assertThat(numCloses).hasValue(2);
    }

    /**
     * Two threads are never handed the same instance at the same time, since the instances are stateful.
     *
     * @throws Exception
     *             if a thread failed.
     */
    @Test
    public void twoThreadsAreNeverHandedTheSameInstance() throws Exception {
        final var numThreads = 4;
        final var acquired = new ArrayList<Recyclable>();
        // Every thread acquires an instance, and only recycles it once all of them are holding one
        final var allAcquired = new CyclicBarrier(numThreads);
        try (var recycler = new RecyclableRecycler()) {
            final List<Thread> threads = new ArrayList<>();
            for (var i = 0; i < numThreads; i++) {
                final var thread = new Thread(() -> {
                    for (var j = 0; j < 100; j++) {
                        try (var recycleOnClose = recycler.acquireRecycleOnClose()) {
                            synchronized (acquired) {
                                acquired.add(recycleOnClose.get());
                            }
                            allAcquired.await();
                        } catch (final Exception e) {
                            throw new RuntimeException(e);
                        }
                    }
                });
                threads.add(thread);
                thread.start();
            }
            for (final Thread thread : threads) {
                thread.join();
            }
            // Every instance that was held at the same time as another one is a different instance
            assertThat(acquired).hasSize(numThreads * 100);
            assertThat(recycler.numAllocated).hasValue(numThreads);
            for (var round = 0; round < 100; round++) {
                assertThat(acquired.subList(round * numThreads, (round + 1) * numThreads)).doesNotHaveDuplicates();
            }
        }
    }
}
