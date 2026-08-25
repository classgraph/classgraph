package io.github.classgraph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import java.net.URL;
import java.net.URLClassLoader;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

/**
 * Tests that a blocking scan runs on the calling thread, so that the classes the scan needs are not loaded on
 * another thread while the calling thread is blocked waiting for the scan to finish (#933).
 */
public class ScanOnCallingThreadTest {
    /** How long to wait for a scan that should not be able to block. */
    private static final long TIMEOUT_MILLIS = 60_000L;

    /** An {@link ExecutorService} that counts the tasks submitted to it, and runs them on a real thread pool. */
    private static class TaskCountingExecutorService extends AbstractExecutorService {
        /** The number of tasks that have been submitted. */
        final AtomicInteger numTasksSubmitted = new AtomicInteger();

        /** The executor service that the submitted tasks are actually run by. */
        private final ExecutorService delegate = Executors.newCachedThreadPool(new ThreadFactory() {
            @Override
            public Thread newThread(final Runnable runnable) {
                final Thread thread = new Thread(runnable, "ScanOnCallingThreadTest-worker");
                thread.setDaemon(true);
                return thread;
            }
        });

        @Override
        public void execute(final Runnable command) {
            numTasksSubmitted.incrementAndGet();
            delegate.execute(command);
        }

        @Override
        public void shutdown() {
            delegate.shutdown();
        }

        @Override
        public List<Runnable> shutdownNow() {
            return delegate.shutdownNow();
        }

        @Override
        public boolean isShutdown() {
            return delegate.isShutdown();
        }

        @Override
        public boolean isTerminated() {
            return delegate.isTerminated();
        }

        @Override
        public boolean awaitTermination(final long timeout, final TimeUnit unit) throws InterruptedException {
            return delegate.awaitTermination(timeout, unit);
        }
    }

    /**
     * A classloader that holds a lock while it loads a class, as a classloader that runs during a host's startup
     * may do. The lock is an ordinary object, so that a thread that is blocked on it if this test fails cannot
     * block anything outside this test.
     */
    private static class LockingClassLoader extends URLClassLoader {
        /** The lock to hold while loading a class. */
        private final Object lock;

        /**
         * Constructor.
         *
         * @param urls
         *            the URLs to load classes from
         * @param lock
         *            the lock to hold while loading a class
         */
        LockingClassLoader(final URL[] urls, final Object lock) {
            // The bootstrap classloader cannot see ClassGraph's own classes, so they are loaded by this classloader
            // rather than reached by delegation
            super(urls, null);
            this.lock = lock;
        }

        @Override
        protected Class<?> loadClass(final String name, final boolean resolve) throws ClassNotFoundException {
            if (name.startsWith("io.github.classgraph")) {
                synchronized (lock) {
                    return super.loadClass(name, resolve);
                }
            }
            return super.loadClass(name, resolve);
        }
    }

    /**
     * Runs a scan while holding the lock that {@link LockingClassLoader} takes to load a class. Loaded by that
     * classloader, so that ClassGraph's own classes are loaded through it during the scan.
     */
    public static class Driver implements Callable<Object> {
        /** The lock to hold during the scan. Set by the thread that runs the scan, before it runs it. */
        public static Object lock;

        @Override
        public Object call() {
            synchronized (lock) {
                try (ScanResult scanResult = new ClassGraph().acceptPackages("io.github.classgraph").scan(1)) {
                    return scanResult.getClasspathURIs().size();
                }
            }
        }
    }

    /**
     * A scan with one thread submits no task to the {@link ExecutorService}, so nothing the scan does can be
     * blocked by another thread.
     */
    @Test
    public void aSingleThreadedScanSubmitsNoTasks() {
        final TaskCountingExecutorService executorService = new TaskCountingExecutorService();
        try {
            try (ScanResult scanResult = new ClassGraph().acceptPackages("io.github.classgraph")
                    .scan(executorService, 1)) {
                assertThat(scanResult.getClasspathURIs()).isNotEmpty();
            }
            assertThat(executorService.numTasksSubmitted).hasValue(0);
        } finally {
            executorService.shutdownNow();
        }
    }

    /**
     * A single-threaded scan completes even when the thread that starts it holds a lock that the classloader also
     * takes, since no other thread has to load a class for the scan to finish.
     *
     * @throws Exception
     *             if the classloader could not be set up, or the scanning thread was interrupted
     */
    @Test
    public void aSingleThreadedScanCompletesWhileTheCallerHoldsTheClassLoadersLock() throws Exception {
        final Object lock = new Object();
        final URL[] classpathEntries = classpathEntryURLs();
        final AtomicReference<Throwable> thrown = new AtomicReference<>();
        final AtomicReference<Object> result = new AtomicReference<>();
        final LockingClassLoader classLoader = new LockingClassLoader(classpathEntries, lock);
        // The scan is run on a daemon thread that is not waited for indefinitely, so that if this test fails, it
        // fails rather than blocking the test run forever
        final Thread scanThread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final Class<?> driverClass = classLoader.loadClass(Driver.class.getName());
                    // The Driver class that this classloader defines is a different class from this one, so its
                    // lock field is a different field, which has to be set by reflection
                    driverClass.getField("lock").set(null, lock);
                    result.set(((Callable<?>) driverClass.newInstance()).call());
                } catch (final Throwable e) {
                    thrown.set(e);
                }
            }
        }, "ScanOnCallingThreadTest-scan");
        scanThread.setDaemon(true);
        scanThread.start();
        scanThread.join(TIMEOUT_MILLIS);

        if (scanThread.isAlive()) {
            fail("The scan did not finish within " + TIMEOUT_MILLIS + "ms while the calling thread held the lock "
                    + "that the classloader takes to load a class");
        }
        if (thrown.get() != null) {
            throw new AssertionError("The scan threw an exception", thrown.get());
        }
        // The scan is of ClassGraph's own package, loaded by the classloader that holds the lock, so it finds
        // classpath elements
        assertThat((Integer) result.get()).isPositive();
        classLoader.close();
    }

    /**
     * Get the URLs of the classpath entries to give to {@link LockingClassLoader}, so that it can load ClassGraph's
     * classes itself rather than delegating to the classloader that already loaded them.
     *
     * @return the URL of each entry of the classpath of the classloader that loaded this test
     */
    private static URL[] classpathEntryURLs() {
        try (ScanResult scanResult = new ClassGraph().scan(1)) {
            final List<URL> classpathURLs = scanResult.getClasspathURLs();
            return classpathURLs.toArray(new URL[0]);
        }
    }
}
