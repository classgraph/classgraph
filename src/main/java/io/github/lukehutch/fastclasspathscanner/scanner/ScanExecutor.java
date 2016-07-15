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
package io.github.lukehutch.fastclasspathscanner.scanner;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.lukehutch.fastclasspathscanner.FastClasspathScanner;
import io.github.lukehutch.fastclasspathscanner.scanner.ClasspathResourceQueueProcessor.ClasspathResourceProcessor;
import io.github.lukehutch.fastclasspathscanner.utils.LoggedThread;

public class ScanExecutor {
    /**
     * Scan the classpath, and call any MatchProcessors on files or classes that match.
     */
    public static Future<ScanResult> scan(final ScanSpec scanSpec, final List<File> classpathElts,
            final ExecutorService executorService, final int numParallelTasks) {
        // Get classpath elements
        final long scanStart = System.nanoTime();

        // Two threads are used for recursive scanning and class graph building, the rest are used as
        // worker threads for parallel classfile binary format parsing 
        int numWorkerThreads = Math.max(1, numParallelTasks - 2);
        final List<Future<Void>> futures = new ArrayList<>(numWorkerThreads);

        // ---------------------------------------------------------------------------------------------------------
        // Recursively scan classpath
        // ---------------------------------------------------------------------------------------------------------

        // The output of the recursive scan for files that matched requested criteria.
        final LinkedBlockingQueue<ClasspathResource> matchingFiles = new LinkedBlockingQueue<>();

        // The output of the recursive scan for classfiles that matched requested criteria.
        final LinkedBlockingQueue<ClasspathResource> matchingClassfiles = new LinkedBlockingQueue<>();

        // End of queue marker
        final ClasspathResource END_OF_CLASSPATH_RESOURCE_QUEUE = new ClasspathResource();

        // A map from a file to its timestamp at time of scan.
        final Map<File, Long> fileToTimestamp = new HashMap<>();

        // If any thread is interrupted (in particular by calling ScanResult#cancel(true), interrupt all of them
        final AtomicBoolean killAllThreads = new AtomicBoolean(false);

        // Start recursively scanning classpath
        futures.add(executorService.submit(new LoggedThread<Void>() {
            @Override
            public Void doWork() throws Exception {
                try {
                    // Scan classpath recursively
                    new RecursiveScanner(classpathElts, scanSpec, matchingFiles, matchingClassfiles,
                            fileToTimestamp, killAllThreads, log).scan();
                } finally {
                    if (Thread.currentThread().isInterrupted()) {
                        // Signal to other threads that they should shut down
                        killAllThreads.set(true);
                    }
                    // Place numWorkerThreads poison pills at end of work queues, whether or not this thread
                    // succeeds (so that the workers in the next stage do not get stuck blocking) 
                    for (int i = 0; i < numWorkerThreads; i++) {
                        matchingClassfiles.add(END_OF_CLASSPATH_RESOURCE_QUEUE);
                        matchingFiles.add(END_OF_CLASSPATH_RESOURCE_QUEUE);
                    }
                }
                return null;
            }
        }));

        // ---------------------------------------------------------------------------------------------------------
        // Parse classfile binary headers in parallel, producing ClassInfoUnlinked object for each class
        // ---------------------------------------------------------------------------------------------------------

        // The output of the classfile binary parser
        final LinkedBlockingQueue<ClassInfoUnlinked> classInfoUnlinked = new LinkedBlockingQueue<>();

        // End of queue marker
        final ClassInfoUnlinked END_OF_CLASSINFO_UNLINKED_QUEUE = new ClassInfoUnlinked();

        // A map holding interned strings, to save memory. */
        final ConcurrentHashMap<String, String> stringInternMap = new ConcurrentHashMap<>();

        // Start classfile parser threads -- these consume ClasspathResource objects from the matchingClassfiles
        // queue, and map them to ClassInfoUnlinked objects in the classInfoUnlinked queue. 
        for (int i = 0; i < numWorkerThreads; i++) {
            // Create and start a new ClassfileBinaryParserCaller thread that consumes entries from
            // the classpathResourcesToScan queue and creates objects in the classInfoUnlinked queue
            futures.add(executorService.submit(new LoggedThread<Void>() {
                @Override
                public Void doWork() throws Exception {
                    try {
                        final ClassfileBinaryParser classfileBinaryParser = new ClassfileBinaryParser(scanSpec,
                                log);
                        ClasspathResourceQueueProcessor.processClasspathResourceQueue(matchingClassfiles,
                                END_OF_CLASSPATH_RESOURCE_QUEUE, new ClasspathResourceProcessor() {
                                    @Override
                                    public void processClasspathResource(final ClasspathResource classpathResource,
                                            final InputStream inputStream, final long inputStreamLength)
                                            throws IOException, InterruptedException {
                                        // Parse classpath binary format, creating a ClassInfoUnlinked object
                                        final ClassInfoUnlinked thisClassInfoUnlinked = classfileBinaryParser
                                                .readClassInfoFromClassfileHeader(classpathResource.relativePath,
                                                        inputStream,
                                                        scanSpec.getClassNameToStaticFinalFieldsToMatch(),
                                                        stringInternMap);
                                        // If class was successfully read, output new ClassInfoUnlinked object
                                        if (thisClassInfoUnlinked != null) {
                                            classInfoUnlinked.add(thisClassInfoUnlinked);
                                            // Log info about class
                                            thisClassInfoUnlinked.logTo(log);
                                        }
                                        if (killAllThreads.get() || Thread.currentThread().isInterrupted()) {
                                            throw new InterruptedException();
                                        }
                                    }
                                }, log);
                    } catch (final InterruptedException e) {
                        // Signal to other threads that they should shut down
                        killAllThreads.set(true);
                    } finally {
                        if (Thread.currentThread().isInterrupted()) {
                            killAllThreads.set(true);
                        }
                        // Place poison pill at end of work queues, whether or not this thread succeeds
                        // (so that the thread in the next stage does not get stuck blocking) 
                        classInfoUnlinked.add(END_OF_CLASSINFO_UNLINKED_QUEUE);
                    }
                    return null;
                }
            }));
        }

        // ---------------------------------------------------------------------------------------------------------
        // Create ClassInfo object for each class; cross-link the ClassInfo objects with each other;
        // wait for worker thread completion; create ScanResult; call MatchProcessors; return ScanResult
        // ---------------------------------------------------------------------------------------------------------

        // Start final thread that creates cross-linked ClassInfo objects from each ClassInfoUnlinked object
        final Future<ScanResult> scanResult = executorService.submit(new LoggedThread<ScanResult>() {
            @Override
            public ScanResult doWork() throws InterruptedException, ExecutionException {
                try {
                    // Convert ClassInfoUnlinked to linked ClassInfo objects
                    final Map<String, ClassInfo> classNameToClassInfo = new HashMap<>();
                    for (int threadsStillRunning = numWorkerThreads; threadsStillRunning > 0;) {
                        final ClassInfoUnlinked c = classInfoUnlinked.take();
                        if (c == END_OF_CLASSINFO_UNLINKED_QUEUE) {
                            --threadsStillRunning;
                        } else {
                            // Create ClassInfo object from ClassInfoUnlinked object, and link into class graph
                            c.link(classNameToClassInfo);
                        }
                        if (Thread.currentThread().isInterrupted()) {
                            throw new InterruptedException();
                        }
                    }

                    // Barrier -- wait for worker thread completion (they should have already all completed
                    // if this line is reached).
                    for (int i = 0; i < futures.size(); i++) {
                        // Will throw ExecutionException if one of the other threads threw an uncaught exception.
                        // This is then passed back to the caller of this Future<ScanResult>#get()
                        futures.get(i).get();
                        if (killAllThreads.get() || Thread.currentThread().isInterrupted()) {
                            throw new InterruptedException();
                        }
                    }

                    // Create the ScanResult, which builds the class graph.
                    // (ClassMatchProcessors need access to the class graph to find matching classes.)
                    final ScanResult scanResult = new ScanResult(scanSpec, classNameToClassInfo, fileToTimestamp,
                            log);

                    // Call MatchProcessors
                    final long startMatchProcessors = System.nanoTime();
                    scanSpec.callMatchProcessors(scanResult, matchingFiles, END_OF_CLASSPATH_RESOURCE_QUEUE,
                            classNameToClassInfo, log);

                    if (FastClasspathScanner.verbose) {
                        log.log(1, "Finished calling MatchProcessors", System.nanoTime() - startMatchProcessors);
                        log.log("Finished scan", System.nanoTime() - scanStart);
                    }
                    return scanResult;
                } finally {
                    // In case an exception was thrown in this thread, or an ExecutionException is thrown
                    // during the futures.get(i).get() call, kill the other threads.
                    killAllThreads.set(true);
                    for (int i = 0; i < futures.size(); i++) {
                        // cancel(true) will do nothing if the Future has already completed
                        futures.get(i).cancel(true);
                    }
                }
            }
        });
        return scanResult;
    }
}
