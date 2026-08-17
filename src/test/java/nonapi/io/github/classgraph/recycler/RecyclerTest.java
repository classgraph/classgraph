package nonapi.io.github.classgraph.recycler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.File;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.Resource;
import io.github.classgraph.ScanResult;

/** Tests that a force-closed {@link Recycler} closes an instance handed back to it, rather than pooling it. */
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

    /**
     * An instance borrowed before the force-close and handed back afterwards is closed, and handing it back does
     * not throw: it is normally handed back by the close() method of whatever borrowed it, and a close() must not
     * fail because the pool went first.
     */
    @Test
    public void anInstanceHandedBackAfterAForceCloseIsClosed() {
        final RecyclableRecycler recycler = new RecyclableRecycler();
        final Recyclable instance = recycler.acquire();
        recycler.forceClose();
        assertThat(instance.numCloses).isEqualTo(1);
        assertThatCode(() -> recycler.recycle(instance)).doesNotThrowAnyException();
        // Closed by the force-close itself, and not closed a second time by the recycle
        assertThat(instance.numCloses).isEqualTo(1);
        assertThat(instance.numResets).isZero();
    }

    /** An instance borrowed after the force-close is closed when it is handed back, rather than pooled. */
    @Test
    public void anInstanceAcquiredAfterAForceCloseIsClosedWhenItIsHandedBack() {
        final RecyclableRecycler recycler = new RecyclableRecycler();
        recycler.forceClose();
        final Recyclable instance = recycler.acquire();
        assertThat(instance.numCloses).isZero();
        recycler.recycle(instance);
        assertThat(instance.numCloses).isEqualTo(1);
        // A pooled instance would be handed out again; a closed one is not
        assertThat(recycler.acquire()).isNotSameAs(instance);
        assertThat(recycler.numAllocated).hasValue(2);
    }

    /** A try-with-resources block that outlives the force-close still closes its instance, and does not throw. */
    @Test
    public void aRecycleOnCloseBlockThatOutlivesAForceCloseDoesNotThrow() {
        final RecyclableRecycler recycler = new RecyclableRecycler();
        final RecycleOnClose<Recyclable, RuntimeException> recycleOnClose = recycler.acquireRecycleOnClose();
        final Recyclable instance = recycleOnClose.get();
        recycler.forceClose();
        assertThatCode(recycleOnClose::close).doesNotThrowAnyException();
        assertThat(instance.numCloses).isEqualTo(1);
    }

    /**
     * Closing the stream of a resource that the {@link ScanResult} did not close for itself does not throw, even
     * though the inflater recycler behind the stream was force-closed when the {@link ScanResult} was closed.
     */
    @Test
    public void closingAResourceStreamAfterTheScanResultDoesNotThrow() throws Exception {
        final File jarFile = new File("src/test/resources/record.jar");
        final InputStream inputStream;
        try (ScanResult scanResult = new ClassGraph().overrideClasspath(jarFile.getPath()).enableClassInfo()
                .scan()) {
            // ScanResult#close() closes the resources it cached for itself, but the classfile resource is only
            // cached if the resource list was fetched, and this scan never fetches it
            final Resource classfileResource = scanResult.getAllClasses().get(0).getResource();
            inputStream = classfileResource.open();
            // Read a byte, so that the inflater is really in use
            assertThat(inputStream.read()).isEqualTo(0xca);
        }
        assertThatCode(inputStream::close).doesNotThrowAnyException();
    }

    /** Without a force-close, recycling an instance that is not in use is still rejected. */
    @Test
    public void recyclingAnInstanceThatIsNotInUseIsStillRejected() {
        final RecyclableRecycler recycler = new RecyclableRecycler();
        final Recyclable instance = recycler.acquire();
        recycler.recycle(instance);
        assertThatThrownBy(() -> recycler.recycle(instance)).isInstanceOf(IllegalArgumentException.class);
        assertThat(instance.numResets).isEqualTo(1);
    }
}
