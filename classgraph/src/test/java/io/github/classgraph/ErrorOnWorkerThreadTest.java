package io.github.classgraph;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.Serial;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

/**
 * Tests that an {@link Error} thrown by a worker thread of a scan reaches the caller unwrapped, as the same
 * {@link Error} does when the calling thread throws it, rather than wrapped in a {@link ClassGraphException}.
 */
public class ErrorOnWorkerThreadTest {
    /** The {@link Error} that the worker throws. */
    private static final class TestError extends Error {
        /** serialVersionUID. */
        @Serial
        private static final long serialVersionUID = 1L;

        /** Constructor. */
        TestError() {
            super("thrown by the worker");
        }
    }

    /**
     * A worker that throws an {@link Error} after doing its share of the work makes the scan throw that same
     * {@link Error}, so that a caller that catches {@link ClassGraphException} cannot swallow an
     * {@link OutOfMemoryError} or {@link StackOverflowError} that happened to be thrown on a worker thread.
     *
     * <p>
     * The worker is run to completion inside {@code submit}, so that it has finished, and its {@link Future} holds
     * the {@link Error}, before the scan waits for it. A {@link FutureTask} records a thrown {@link Error} as the
     * cause of an {@link java.util.concurrent.ExecutionException}, just as it does on a pool thread.
     */
    @Test
    public void anErrorOnAWorkerThreadIsRethrownUnwrapped() {
        final ExecutorService executorService = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>()) {
            @Override
            public <T> Future<T> submit(final Callable<T> task) {
                final var future = new FutureTask<T>(() -> {
                    task.call();
                    throw new TestError();
                });
                future.run();
                return future;
            }
        };
        try {
            // Two parallel tasks, so that the scan starts a worker
            assertThatThrownBy(() -> new ClassGraph().enableClasspath().scan(executorService, 2))
                    .isExactlyInstanceOf(TestError.class).hasMessage("thrown by the worker");
        } finally {
            executorService.shutdown();
        }
    }
}
