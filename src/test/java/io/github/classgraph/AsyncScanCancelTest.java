package io.github.classgraph;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import io.github.classgraph.features.CustomURLScheme;

/**
 * An asynchronous scan whose {@link Future} is canceled while the scan is running closes the {@link ScanResult}
 * that the scan goes on to produce, since a canceled future never hands it to the caller, so nothing else could
 * close it.
 */
public class AsyncScanCancelTest {
    /**
     * List the temporary files that jarfiles have been spilled to.
     *
     * @return the temporary files, whose names start with {@code NestedJarHandler.TEMP_FILENAME_PREFIX}.
     */
    private static Set<String> spilledTempFiles() {
        final Set<String> tempFiles = new HashSet<>();
        final String[] names = new File(System.getProperty("java.io.tmpdir")).list();
        if (names != null) {
            for (final String name : names) {
                if (name.startsWith("ClassGraph--")) {
                    tempFiles.add(name);
                }
            }
        }
        return tempFiles;
    }

    /**
     * The temporary file that a jar downloaded from a URL was spilled to is deleted when the future of the scan is
     * canceled while the scan is running.
     *
     * @throws Exception
     *             if the scan could not be run.
     */
    @Test
    public void theTemporaryFilesOfAScanCanceledWhileRunningAreDeleted() throws Exception {
        final String filePath = getClass().getClassLoader().getResource("nested-jars-level1.zip").getPath();
        final Set<String> tempFilesBefore = spilledTempFiles();
        final CountDownLatch scanStarted = new CountDownLatch(1);
        final CountDownLatch futureCanceled = new CountDownLatch(1);
        // Hold the scan at the download of the jar until the future has been canceled
        CustomURLScheme.onOpenConnection = new Runnable() {
            @Override
            public void run() {
                scanStarted.countDown();
                try {
                    futureCanceled.await();
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        };
        final ExecutorService executorService = Executors.newSingleThreadExecutor();
        try {
            final ClassGraph classGraph = new ClassGraph().enableURLScheme(CustomURLScheme.SCHEME)
                    .overrideClasspath(CustomURLScheme.SCHEME + ":" + filePath);
            classGraph.scanSpec.maxBufferedJarRAMSize = 0;
            final Future<ScanResult> future = classGraph.scanAsync(executorService, 1);
            assertThat(scanStarted.await(60, TimeUnit.SECONDS)).as("the scan started").isTrue();
            // Cancel without interrupting, so that the scan runs to completion and produces a ScanResult
            assertThat(future.cancel(/* mayInterruptIfRunning = */ false)).isTrue();
        } finally {
            // Let the scan run on, whether or not the cancel succeeded
            futureCanceled.countDown();
            executorService.shutdown();
            assertThat(executorService.awaitTermination(60, TimeUnit.SECONDS)).isTrue();
            CustomURLScheme.onOpenConnection = null;
        }
        assertThat(spilledTempFiles()).isEqualTo(tempFilesBefore);
    }
}
