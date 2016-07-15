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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.lukehutch.fastclasspathscanner.FastClasspathScanner;
import io.github.lukehutch.fastclasspathscanner.scanner.ClasspathResourceQueueProcessor.ClasspathResourceProcessor;
import io.github.lukehutch.fastclasspathscanner.utils.LoggedThread;
import io.github.lukehutch.fastclasspathscanner.utils.LoggedThread.ThreadLog;

public class ScanExecutor {

    /**
     * Consume ClasspathResource objects (representing whitelisted classfiles) from the matchingClassfiles queue
     * until the classpathResourceQueueEndMarker object is reached. For each one, call
     * ClassfileBinaryParser#readClassInfoFromClassfileHeader() to generate a ClassInfoUnlinked object. Push this
     * onto the classInfoUnlinked queue. After the classpathResourceQueueEndMarker marker is reached on the input,
     * push a classInfoUnlinkedQueueEndMarker marker to the output.
     */
    private static void processClasspathResourceQueue(
            final LinkedBlockingQueue<ClasspathResource> matchingClassfiles,
            final ClasspathResource classpathResourceQueueEndMarker,
            final LinkedBlockingQueue<ClassInfoUnlinked> classInfoUnlinked, final ScanSpec scanSpec,
            final ConcurrentHashMap<String, String> stringInternMap, final AtomicBoolean killAllThreads,
            final ThreadLog log) throws InterruptedException {
        final ClassfileBinaryParser classfileBinaryParser = new ClassfileBinaryParser(scanSpec, log);
        try {
            ClasspathResourceQueueProcessor.processClasspathResourceQueue(matchingClassfiles,
                    classpathResourceQueueEndMarker, new ClasspathResourceProcessor() {
                        @Override
                        public void processClasspathResource(final ClasspathResource classpathResource,
                                final InputStream inputStream, final long inputStreamLength)
                                throws IOException, InterruptedException {
                            // Parse classpath binary format, creating a ClassInfoUnlinked object
                            final ClassInfoUnlinked thisClassInfoUnlinked = classfileBinaryParser
                                    .readClassInfoFromClassfileHeader(classpathResource.relativePath, inputStream,
                                            scanSpec.getClassNameToStaticFinalFieldsToMatch(), stringInternMap);
                            // If class was successfully read, output new ClassInfoUnlinked object
                            if (thisClassInfoUnlinked != null) {
                                classInfoUnlinked.add(thisClassInfoUnlinked);
                                thisClassInfoUnlinked.logTo(log);
                            }
                            if (killAllThreads.get() || Thread.currentThread().isInterrupted()) {
                                throw new InterruptedException();
                            }
                        }
                    }, log);
        } catch (final InterruptedException e) {
            killAllThreads.set(true);
            throw e;
        }
    }

    /**
     * Scan the classpath, and call any MatchProcessors on files or classes that match.
     */
    public static Future<ScanResult> scan(final ScanSpec scanSpec, final List<File> classpathElts,
            final ExecutorService executorService, final int numParallelTasks) {
        if (numParallelTasks < 1) {
            throw new IllegalArgumentException("numParallelTasks < 1");
        }
        return executorService.submit(new LoggedThread<ScanResult>() {
            @Override
            public ScanResult doWork() throws Exception {
                final long scanStart = System.nanoTime();

                // If any thread is interrupted (in particular by calling Future<ScanResult>#cancel(true),
                // interrupt all of the other threads.
                final AtomicBoolean killAllThreads = new AtomicBoolean(false);

                // The list of Futures fulfilled by workers
                final List<Future<?>> workerFutures = new ArrayList<>(numParallelTasks - 1);

                try {
                    // The output of the recursive scan for files that matched requested criteria.
                    final LinkedBlockingQueue<ClasspathResource> matchingFiles = new LinkedBlockingQueue<>();

                    // The output of the recursive scan for classfiles that matched requested criteria.
                    final LinkedBlockingQueue<ClasspathResource> matchingClassfiles = new LinkedBlockingQueue<>();

                    // End of queue marker
                    final ClasspathResource END_OF_CLASSPATH_RESOURCE_QUEUE = new ClasspathResource();

                    // The output of the classfile binary parser
                    final LinkedBlockingQueue<ClassInfoUnlinked> classInfoUnlinked = new LinkedBlockingQueue<>();

                    // A map holding interned strings, to save memory. */
                    final ConcurrentHashMap<String, String> stringInternMap = new ConcurrentHashMap<>();

                    // The total number of items that have been added to the matchingClassfiles queue
                    // but that have not yet been processed by the classfile binary parser
                    final AtomicInteger numMatchingClassfilesToScan = new AtomicInteger();

                    // ---------------------------------------------------------------------------------------------
                    // Start other worker threads (if numParallelTasks > 1)
                    // ---------------------------------------------------------------------------------------------

                    // These workers consume from matchingClassfiles and produce to classInfoUnlinked.
                    // They are started before the recursive scan so that classfile parsing can be pipelined with
                    // recursive scanning. The workers block on input from the recursive scanner via the
                    // matchingClassfiles queue.
                    for (int i = 0, n = numParallelTasks - 1; i < n; i++) {
                        workerFutures.add(executorService.submit(new LoggedThread<Void>() {
                            @Override
                            public Void doWork() throws Exception {
                                processClasspathResourceQueue(matchingClassfiles, END_OF_CLASSPATH_RESOURCE_QUEUE,
                                        classInfoUnlinked, scanSpec, stringInternMap, killAllThreads, this.log);
                                return null;
                            }
                        }));
                    }

                    // ---------------------------------------------------------------------------------------------
                    // Recursively scan classpath
                    // ---------------------------------------------------------------------------------------------

                    // A map from whitelisted files to their timestamp at the time of scan.
                    final Map<File, Long> fileToTimestamp = new HashMap<>();

                    try {
                        // Scan classpath recursively. Creates a queue of classfiles to scan in
                        // matchingClassfiles, and the total number of entries added to that queue
                        // is counted in numMatchingClassfilesToScan.
                        new RecursiveScanner(classpathElts, scanSpec, matchingFiles, matchingClassfiles,
                                numMatchingClassfilesToScan, fileToTimestamp, killAllThreads, log).scan();
                        log.flush();

                    } finally {
                        // Place numWorkerThreads-1 poison pills at end of work queues, whether or not this
                        // thread succeeds (so that the workers do not get stuck blocking) 
                        for (int i = 0; i < numParallelTasks; i++) {
                            matchingClassfiles.add(END_OF_CLASSPATH_RESOURCE_QUEUE);
                            matchingFiles.add(END_OF_CLASSPATH_RESOURCE_QUEUE);
                        }
                    }

                    // ---------------------------------------------------------------------------------------------
                    // Consume ClassInfo objects in this base thread once recursive scanning has completed.
                    // This allows scanning to complete even if numParallelTasks == 1.
                    // ---------------------------------------------------------------------------------------------

                    processClasspathResourceQueue(matchingClassfiles, END_OF_CLASSPATH_RESOURCE_QUEUE,
                            classInfoUnlinked, scanSpec, stringInternMap, killAllThreads, log);

                    // ---------------------------------------------------------------------------------------------
                    // Create ClassInfo object for each class; cross-link the ClassInfo objects with each other;
                    // wait for worker thread completion; create ScanResult
                    // ---------------------------------------------------------------------------------------------

                    // Convert ClassInfoUnlinked to linked ClassInfo objects.
                    final Map<String, ClassInfo> classNameToClassInfo = new HashMap<>();
                    while (numMatchingClassfilesToScan.getAndDecrement() > 0) {
                        final ClassInfoUnlinked c = classInfoUnlinked.take();
                        // Create ClassInfo object from ClassInfoUnlinked object, and link into class graph
                        c.link(classNameToClassInfo);
                        if (killAllThreads.get() || Thread.currentThread().isInterrupted()) {
                            throw new InterruptedException();
                        }
                    }

                    // Barrier -- wait for worker thread completion (they should have already all completed
                    // once this line is reached, since we just consumed all the END_OF_CLASSINFO_UNLINKED_QUEUE
                    // poison pill markers).
                    for (int i = 0; i < workerFutures.size(); i++) {
                        // Will throw ExecutionException if one of the other threads threw an uncaught exception.
                        // This is then passed back to the caller of this Future<ScanResult>#get()
                        workerFutures.get(i).get();
                        if (killAllThreads.get() || Thread.currentThread().isInterrupted()) {
                            throw new InterruptedException();
                        }
                    }

                    // Create the ScanResult, which builds the class graph.
                    // (ClassMatchProcessors need access to the class graph to find matching classes.)
                    final ScanResult scanResult = new ScanResult(scanSpec, classNameToClassInfo, fileToTimestamp,
                            log);

                    // ---------------------------------------------------------------------------------------------
                    // Call MatchProcessors 
                    // ---------------------------------------------------------------------------------------------

                    final long startMatchProcessors = System.nanoTime();
                    scanSpec.callMatchProcessors(scanResult, matchingFiles, END_OF_CLASSPATH_RESOURCE_QUEUE,
                            classNameToClassInfo, log);
                    if (FastClasspathScanner.verbose) {
                        log.log(1, "Finished calling MatchProcessors", System.nanoTime() - startMatchProcessors);
                    }

                    // ---------------------------------------------------------------------------------------------
                    // Complete the Future<ScanResult>
                    // ---------------------------------------------------------------------------------------------

                    if (FastClasspathScanner.verbose) {
                        log.log("Finished scan", System.nanoTime() - scanStart);
                    }
                    return scanResult;

                } finally {
                    // The other threads should have finished their work before this point, so if they are
                    // still running, then this main worker thread threw an exception or was interrupted,
                    // and the workers should be killed. If the workers have already shut down, then the
                    // following will have no effect. 
                    killAllThreads.set(true);
                    for (int i = 0; i < workerFutures.size(); i++) {
                        workerFutures.get(i).cancel(true);
                    }
                }
            }
        });
    }
}
