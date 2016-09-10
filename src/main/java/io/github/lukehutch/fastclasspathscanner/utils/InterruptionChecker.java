/*
 * This file is part of FastClasspathScanner.
 * 
 * Author: Luke Hutchison
 * 
 * Hosted at: https://github.com/lukehutch/fast-classpath-scanner
 * 
 * --
 *
 * The MIT License (MIT)
 *
 * Copyright (c) 2016 Luke Hutchison
 * 
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
 * documentation files (the "Software"), to deal in the Software without restriction, including without
 * limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of
 * the Software, and to permit persons to whom the Software is furnished to do so, subject to the following
 * conditions:
 * 
 * The above copyright notice and this permission notice shall be included in all copies or substantial
 * portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT
 * LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO
 * EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN
 * AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE
 * OR OTHER DEALINGS IN THE SOFTWARE.
 */
package io.github.lukehutch.fastclasspathscanner.utils;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Check if this thread or any other thread that shares this InterruptionChecker instance has been interrupted or
 * has thrown an exception.
 */
public class InterruptionChecker {
    private final AtomicBoolean interrupted = new AtomicBoolean(false);
    private ExecutionException executionException;

    /** Interrupt all threads that share this InterruptionChecker. */
    public void interrupt() {
        interrupted.set(true);
    }

    /**
     * Check if this thread or any other thread that shares this InterruptionChecker instance has been interrupted
     * or has thrown an exception, and if so, return true, else return false.
     */
    public boolean checkAndReturn() {
        if (Thread.currentThread().isInterrupted()) {
            interrupt();
        }
        return interrupted.get() || executionException != null;
    }

    /**
     * Check if this thread or any other thread that shares this InterruptionChecker instance has been interrupted
     * or has thrown an exception, and if so, throw InterruptedException.
     */
    public void check() throws InterruptedException, ExecutionException {
        if (checkAndReturn()) {
            if (executionException != null) {
                throw executionException;
            } else {
                throw new InterruptedException();
            }
        }
    }

    /** Stop all threads that share this InterruptionChecker due to an exception being thrown in one of them. */
    public ExecutionException executionException(final Exception e) {
        final ExecutionException newExecutionException = e instanceof ExecutionException ? (ExecutionException) e
                : new ExecutionException(e);
        executionException = newExecutionException;
        return executionException;
    }

}
