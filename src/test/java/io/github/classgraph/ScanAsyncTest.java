package io.github.classgraph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.classgraph.ClassGraph.FailureHandler;
import io.github.classgraph.ClassGraph.ScanResultProcessor;

/** Tests for {@link ClassGraph#scanAsync(ExecutorService, int, ScanResultProcessor, FailureHandler)}. */
public class ScanAsyncTest {
    /**
     * An asynchronous scan searches the context classloader of the thread that called it, not that of the worker
     * thread that the scan happens to be run on. The {@link Scanner} is constructed inside the task submitted to
     * the {@link ExecutorService}, so the context classloader used to be read on a worker thread, whose context
     * classloader is whatever the {@link ExecutorService}'s owner gave it.
     *
     * @throws Exception
     *             if the classloaders could not be set up, or the wait for the scan was interrupted
     */
    @Test
    public void anAsyncScanSearchesTheContextClassLoaderOfTheCaller() throws Exception {
        final AtomicReference<List<String>> classNames = new AtomicReference<>();
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final CountDownLatch done = new CountDownLatch(1);
        // Give the worker threads a context classloader that cannot see the jar, which is what an ExecutorService
        // supplied by a container looks like
        final ExecutorService executorService = Executors.newFixedThreadPool(3, new ThreadFactory() {
            @Override
            public Thread newThread(final Runnable runnable) {
                final Thread thread = new Thread(runnable);
                thread.setContextClassLoader(new URLClassLoader(new URL[0], /* parent = */ null));
                return thread;
            }
        });
        final ClassLoader previousContextClassLoader = Thread.currentThread().getContextClassLoader();
        final URL jarURL = new File("src/test/resources/issue797.jar").toURI().toURL();
        try (URLClassLoader callerClassLoader = new URLClassLoader(new URL[] { jarURL }, /* parent = */ null)) {
            Thread.currentThread().setContextClassLoader(callerClassLoader);
            new ClassGraph().acceptPackages("io.github.classgraph.issues.issue797").scanAsync(executorService, 3,
                    scanResult -> {
                        try {
                            classNames.set(scanResult.getAllClasses().getNames());
                        } finally {
                            scanResult.close();
                        }
                        done.countDown();
                    }, throwable -> {
                        failure.set(throwable);
                        done.countDown();
                    });
            assertThat(done.await(60, TimeUnit.SECONDS)).as("the scan completed").isTrue();
        } finally {
            Thread.currentThread().setContextClassLoader(previousContextClassLoader);
            executorService.shutdown();
        }
        assertThat(failure.get()).isNull();
        assertThat(classNames.get()).contains("io.github.classgraph.issues.issue797.Bar");
    }

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
