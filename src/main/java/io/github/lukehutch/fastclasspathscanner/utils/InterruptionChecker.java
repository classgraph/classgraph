package io.github.lukehutch.fastclasspathscanner.utils;

import java.util.concurrent.atomic.AtomicBoolean;

import io.github.lukehutch.fastclasspathscanner.FastClasspathScanner;
import io.github.lukehutch.fastclasspathscanner.scanner.ScanInterruptedException;

/**
 * Utility class for checking if this thread or any other thread sharing this InterruptChecker has been interrupted,
 * and if so, die by throwing ScanInterruptedException. An instance of this class should be shared between all
 * threads that should be interrupted together if any one of the threads is interrupted.
 */
public class InterruptionChecker {
    private AtomicBoolean interrupted = new AtomicBoolean(false);

    /**
     * Check if this thread or any other thread sharing this InterruptChecker has been interrupted, and if so, throw
     * ScanInterruptedException.
     */
    public void check() {
        if (Thread.interrupted() || interrupted.get()) {
            interrupt();
        }
    }

    /**
     * Interrupt all threads sharing this InterruptChecker, and throw ScanInterruptedException in this thread.
     */
    public void interrupt() {
        Thread.currentThread().interrupt();
        // Tell all other threads to die
        interrupted.set(true);
        String msg = "Thread " + Thread.currentThread().getName() + " was interrupted";
        if (FastClasspathScanner.verbose) {
            Log.log(msg);
        }
        throw new ScanInterruptedException(msg);
    }
}
