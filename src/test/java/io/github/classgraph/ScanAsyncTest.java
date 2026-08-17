package io.github.classgraph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.classgraph.ClassGraph.FailureHandler;
import io.github.classgraph.ClassGraph.ScanResultProcessor;

/** Tests for {@link ClassGraph#scanAsync(ExecutorService, int, ScanResultProcessor, FailureHandler)}. */
public class ScanAsyncTest {
    /**
     * A scan that fails before it starts still calls the failure handler. A classpath element filter is called by
     * the {@link Scanner} constructor, before the scan itself begins, so anything it throws used to be thrown on
     * the {@link ExecutorService}'s thread and lost, leaving the caller waiting forever for a callback that never
     * came.
     */
    @Test
    public void aFailedAsyncScanCallsTheFailureHandler(@TempDir final Path tempDir)
            throws IOException, InterruptedException {
        Files.write(tempDir.resolve("resource.txt"), "resource".getBytes(StandardCharsets.UTF_8));
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final CountDownLatch done = new CountDownLatch(1);
        final ExecutorService executorService = Executors.newFixedThreadPool(3);
        try {
            new ClassGraph().overrideClasspath(tempDir.toString()).filterClasspathElements(path -> {
                throw new IllegalStateException("classpath element filter failed");
            }).scanAsync(executorService, 3, scanResult -> {
                scanResult.close();
                done.countDown();
            }, throwable -> {
                failure.set(throwable);
                done.countDown();
            });
            assertThat(done.await(60, TimeUnit.SECONDS)).as("the failure handler was called").isTrue();
        } finally {
            executorService.shutdown();
        }
        assertThat(failure.get()).isInstanceOf(IllegalStateException.class)
                .hasMessage("classpath element filter failed");
    }

    /**
     * An asynchronous scan closes its {@link ScanResult} even when the {@link ScanResultProcessor} throws an
     * {@link Error} rather than an {@link Exception}, which is what a failing assertion inside a
     * {@link ScanResultProcessor} throws. Nothing else can close it: the {@link ScanResult} is never handed to the
     * {@link FailureHandler}, and the one returned by the {@link Scanner} is discarded.
     */
    @Test
    public void anAsyncScanClosesItsScanResultWhenTheProcessorThrowsAnError(@TempDir final Path tempDir)
            throws IOException, InterruptedException {
        Files.write(tempDir.resolve("resource.txt"), "resource".getBytes(StandardCharsets.UTF_8));
        final AtomicReference<ScanResult> scanResultRef = new AtomicReference<>();
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final CountDownLatch done = new CountDownLatch(1);
        final ExecutorService executorService = Executors.newFixedThreadPool(3);
        try {
            new ClassGraph().overrideClasspath(tempDir.toString()).scanAsync(executorService, 3, scanResult -> {
                scanResultRef.set(scanResult);
                throw new AssertionError("scan result processor failed");
            }, throwable -> {
                failure.set(throwable);
                done.countDown();
            });
            assertThat(done.await(60, TimeUnit.SECONDS)).as("the failure handler was called").isTrue();
        } finally {
            executorService.shutdown();
        }
        assertThat(failure.get()).isInstanceOf(AssertionError.class).hasMessage("scan result processor failed");
        final ScanResult scanResult = scanResultRef.get();
        assertThat(scanResult).isNotNull();
        assertThatThrownBy(scanResult::getAllResources).isInstanceOf(IllegalArgumentException.class);
    }
}
