package io.github.classgraph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A classpath element that is named twice, once on the classpath and once by the {@code Class-Path} manifest entry
 * of another classpath element, is opened once, by whichever thread reaches it first, and the other thread waits
 * for it. If the thread opening it never finishes, the waiting thread must give up after the worker timeout, as
 * every other wait in a scan does, rather than wait forever.
 */
public class StuckClasspathElementTimeoutTest {
    /**
     * Make a directory classpath element whose manifest has a {@code Class-Path} entry.
     *
     * @param dir
     *            the directory.
     * @param classPath
     *            the value of the {@code Class-Path} entry.
     * @throws IOException
     *             if the directory could not be made.
     */
    private static void makeDirWithClassPath(final Path dir, final String classPath) throws IOException {
        final var manifest = dir.resolve("META-INF/MANIFEST.MF");
        Files.createDirectories(manifest.getParent());
        Files.writeString(manifest, "Manifest-Version: 1.0\nClass-Path: " + classPath + "\n\n");
    }

    /**
     * Get whether a classpath element location names the given directory.
     *
     * @param location
     *            the location.
     * @param dirName
     *            the name of the directory.
     * @return true if the last segment of the location is the directory name.
     */
    private static boolean names(final String location, final String dirName) {
        final var trimmed = location.endsWith("/") ? location.substring(0, location.length() - 1) : location;
        return trimmed.endsWith("/" + dirName) || trimmed.endsWith("\\" + dirName);
    }

    /**
     * The calling thread waits for a worker thread that is stuck opening a classpath element both of them need, and
     * the scan fails with the worker timeout rather than hanging.
     *
     * <p>
     * The worker thread is made to get stuck by a classpath element filter that blocks when it is asked about a
     * child classpath element of the stuck classpath element, which the worker thread asks while opening it. The
     * filter stands in for anything that can block while a classpath element is opened, such as a read from a
     * network filesystem.
     *
     * @param tempDir
     *            the directory to make the classpath elements in.
     * @throws Exception
     *             if the classpath elements could not be made.
     */
    @Test
    public void aThreadWaitingForAStuckClasspathElementTimesOut(@TempDir final Path tempDir) throws Exception {
        // "parent" names "stuck", which names "other". The calling thread opens "parent", and the worker thread
        // opens "stuck", and blocks in the filter when it is asked about "other". The calling thread then finds
        // that "parent" names "stuck", and waits for the worker thread to finish opening it
        makeDirWithClassPath(tempDir.resolve("parent"), "stuck/");
        makeDirWithClassPath(tempDir.resolve("stuck"), "other/");
        Files.createDirectories(tempDir.resolve("other"));
        final var releaseWorker = new CountDownLatch(1);

        // Delay the start of each worker thread, so that the calling thread takes the first work unit, "parent"
        final var executorService = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>()) {
            @Override
            protected void beforeExecute(final Thread t, final Runnable r) {
                try {
                    Thread.sleep(300);
                } catch (final InterruptedException _) {
                    t.interrupt();
                }
            }
        };
        try {
            final var thrown = assertTimeoutPreemptively(Duration.ofSeconds(20),
                    () -> catchThrowable(
                            () -> new ClassGraph()
                                    .enableClasspathEntries(tempDir.resolve("parent").toString(),
                                            tempDir.resolve("stuck").toString())
                                    .filterClasspathElements(location -> {
                                        try {
                                            if (names(location, "stuck")) {
                                                // Hold the calling thread on "parent" until the worker thread has started
                                                // opening "stuck"
                                                Thread.sleep(1000);
                                            } else if (names(location, "other")) {
                                                // The worker thread is stuck opening "stuck"
                                                releaseWorker.await(30, TimeUnit.SECONDS);
                                            }
                                        } catch (final InterruptedException _) {
                                            Thread.currentThread().interrupt();
                                        }
                                        return true;
                                    }).setWorkerTimeout(Duration.ofMillis(500)).scan(executorService, 2).close()));
            assertThat(thrown).isInstanceOf(ClassGraphException.class);
            assertThat(thrown.getCause()).isInstanceOf(IllegalStateException.class)
                    .hasMessageStartingWith("Timed out after 500ms waiting for another thread to open the "
                            + "classpath element " + tempDir.resolve("stuck").toRealPath());
        } finally {
            releaseWorker.countDown();
            executorService.shutdown();
            assertThat(executorService.awaitTermination(20, TimeUnit.SECONDS)).isTrue();
        }
    }
}
