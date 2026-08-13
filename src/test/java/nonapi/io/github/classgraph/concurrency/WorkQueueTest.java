package nonapi.io.github.classgraph.concurrency;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import nonapi.io.github.classgraph.concurrency.WorkQueue.WorkUnitProcessor;
import nonapi.io.github.classgraph.utils.LogNode;

/** Tests for {@link WorkQueue}. */
public class WorkQueueTest {
    /**
     * The work is all done even when the {@link java.util.concurrent.ExecutorService} has no free thread to run any
     * worker on, which is the case when the work queue is started by a task that is itself running on a
     * single-threaded {@link java.util.concurrent.ExecutorService}. The thread that starts the work queue processes
     * the work units itself in that case, and must not then wait for workers that can never start.
     *
     * @throws Exception
     *             if the work queue failed.
     */
    @Test
    public void workIsCompletedWhenTheExecutorServiceHasNoFreeThread() throws Exception {
        final List<Integer> workUnits = java.util.Arrays.asList(0, 1, 2, 3, 4, 5, 6, 7);
        final Set<Integer> processed = ConcurrentHashMap.newKeySet();

        try (AutoCloseableExecutorService executorService = new AutoCloseableExecutorService(1)) {
            // Run the work queue on the executor's only thread, asking for more tasks than it has threads
            final Future<?> workQueueTask = executorService.submit(new Callable<Void>() {
                @Override
                public Void call() throws Exception {
                    WorkQueue.runWorkQueue(workUnits, executorService, executorService.interruptionChecker,
                            /* numParallelTasks = */ 4, /* log = */ (LogNode) null,
                            new WorkUnitProcessor<Integer>() {
                                @Override
                                public void processWorkUnit(final Integer workUnit,
                                        final WorkQueue<Integer> workQueue, final LogNode log) {
                                    processed.add(workUnit);
                                }
                            });
                    return null;
                }
            });
            assertThat(workQueueTask.get(30, TimeUnit.SECONDS)).isNull();
        }

        assertThat(processed).containsExactlyInAnyOrderElementsOf(workUnits);
    }
}
