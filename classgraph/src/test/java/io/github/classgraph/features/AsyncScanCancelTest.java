package io.github.classgraph.features;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.ScanResult;

/**
 * An asynchronous scan whose {@link java.util.concurrent.Future} is canceled while the scan is running closes the
 * {@link ScanResult} that the scan goes on to produce, since a canceled future never hands it to the caller, so
 * nothing else could close it.
 */
public class AsyncScanCancelTest {
    /**
     * List the temporary files that jarfiles have been spilled to.
     *
     * @return the paths of the temporary files, whose names start with {@code PathSyntax.TEMP_FILENAME_PREFIX}.
     * @throws IOException
     *             if the temporary directory could not be listed.
     */
    private static Set<Path> spilledTempFiles() throws IOException {
        try (var paths = Files.list(Path.of(System.getProperty("java.io.tmpdir")))) {
            return paths.filter(path -> path.getFileName().toString().startsWith("ClassGraph--"))
                    .collect(Collectors.toSet());
        }
    }

    /**
     * The temporary file that a jar downloaded from a URL was spilled to is deleted when the future of the scan is
     * canceled while the scan is running.
     *
     * @throws Exception
     *             if the scan could not be run, or the temporary directory could not be listed.
     */
    @Test
    public void theTemporaryFilesOfAScanCanceledWhileRunningAreDeleted() throws Exception {
        CustomURLScheme.register();
        final var filePath = getClass().getClassLoader().getResource("nested-jars-level1.zip").getPath();
        final var tempFilesBefore = spilledTempFiles();
        final var scanStarted = new CountDownLatch(1);
        final var futureCanceled = new CountDownLatch(1);
        // Hold the scan at the download of the jar until the future has been canceled
        CustomURLScheme.onOpenConnection = () -> {
            scanStarted.countDown();
            try {
                futureCanceled.await();
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
        final var executorService = Executors.newSingleThreadExecutor();
        try {
            final var future = new ClassGraph().allowURLScheme(CustomURLScheme.SCHEME).setMaxBufferedJarRAMSize(0)
                    .enableClasspathEntries(CustomURLScheme.SCHEME + ":" + filePath).scanAsync(executorService, 1);
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
