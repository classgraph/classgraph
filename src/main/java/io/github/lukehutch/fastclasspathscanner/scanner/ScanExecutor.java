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

import io.github.lukehutch.fastclasspathscanner.FastClasspathScanner;
import io.github.lukehutch.fastclasspathscanner.classfileparser.ClassInfo;
import io.github.lukehutch.fastclasspathscanner.classfileparser.ClassfileBinaryParser;
import io.github.lukehutch.fastclasspathscanner.classpath.ClasspathFinder;
import io.github.lukehutch.fastclasspathscanner.scanner.ClasspathResourceQueueProcessor.ClasspathResourceProcessor;
import io.github.lukehutch.fastclasspathscanner.scanner.ClasspathResourceQueueProcessor.EndOfClasspathResourceQueueProcessor;
import io.github.lukehutch.fastclasspathscanner.utils.LoggedThread;
import io.github.lukehutch.fastclasspathscanner.utils.LoggedThread.ThreadLog;

public class ScanExecutor {
    /**
     * Scan the classpath, and call any MatchProcessors on files or classes that match.
     */
    public static Future<ScanResult> scan(final ScanSpec scanSpec, final ExecutorService executorService,
            final int numWorkerThreads) {
        // Get classpath elements
        final long scanStart = System.nanoTime();

        final List<Future<Void>> futures = new ArrayList<>(numWorkerThreads);

        // ---------------------------------------------------------------------------------------------------------
        // Recursively scan classpath
        // ---------------------------------------------------------------------------------------------------------

        // The output of the recursive scan for files that matched requested criteria.
        final LinkedBlockingQueue<ClasspathResource> matchingFiles = new LinkedBlockingQueue<>();

        // The output of the recursive scan for classfiles that matched requested criteria.
        final LinkedBlockingQueue<ClasspathResource> matchingClassfiles = new LinkedBlockingQueue<>();

        // A map from a file to its timestamp at time of scan.
        final Map<File, Long> fileToTimestamp = new HashMap<>();

        final ThreadLog initialLog = new ThreadLog();
        if (FastClasspathScanner.verbose) {
            initialLog.log("Starting scan");
        }
        scanSpec.log(initialLog);

        // Get classpath elements
        final List<File> classpathElts = new ClasspathFinder(scanSpec, initialLog).getUniqueClasspathElements();
        initialLog.flush();

        // Start recursively scanning classpath
        futures.add(executorService.submit(new RecursiveScanner(classpathElts, scanSpec, matchingFiles,
                matchingClassfiles, fileToTimestamp, numWorkerThreads)));

        // ---------------------------------------------------------------------------------------------------------
        // Parse classfile binary headers in parallel
        // ---------------------------------------------------------------------------------------------------------

        // The output of the classfile binary parser
        final LinkedBlockingQueue<ClassInfoUnlinked> classInfoUnlinked = new LinkedBlockingQueue<>();

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
                    final ClassfileBinaryParser classfileBinaryParser = new ClassfileBinaryParser(scanSpec, log);
                    ClasspathResourceQueueProcessor.processClasspathResourceQueue(matchingClassfiles,
                            new ClasspathResourceProcessor() {
                                @Override
                                public void processClasspathResource(final ClasspathResource classpathResource,
                                        final InputStream inputStream, final long inputStreamLength)
                                        throws IOException {
                                    // Parse classpath binary format, creating a ClassInfoUnlinked object
                                    final ClassInfoUnlinked thisClassInfoUnlinked = classfileBinaryParser
                                            .readClassInfoFromClassfileHeader(classpathResource.relativePath,
                                                    inputStream, scanSpec.getClassNameToStaticFinalFieldsToMatch(),
                                                    stringInternMap);
                                    // If class was successfully read, output new ClassInfoUnlinked object
                                    if (thisClassInfoUnlinked != null) {
                                        classInfoUnlinked.add(thisClassInfoUnlinked);
                                        // Log info about class
                                        thisClassInfoUnlinked.logClassInfo(log);
                                    }
                                }
                            }, new EndOfClasspathResourceQueueProcessor() {
                                @Override
                                public void processEndOfQueue() {
                                    // Received ClasspathResource.END_OF_QUEUE poison pill from RecursiveScanner --
                                    // no more work to do. Send poison pill to next stage.
                                    classInfoUnlinked.add(ClassInfoUnlinked.END_OF_QUEUE);
                                }
                            }, log);
                    return null;
                }
            }));
        }

        // ---------------------------------------------------------------------------------------------------------
        // Build class graph
        // ---------------------------------------------------------------------------------------------------------

        final Map<String, ClassInfo> classNameToClassInfo = new HashMap<>();

        // Start final thread that creates cross-linked ClassInfo objects from each ClassInfoUnlinked object
        final Future<Void> linkerFuture = executorService.submit(new LoggedThread<Void>() {
            @Override
            public Void doWork() {
                // Convert ClassInfoUnlinked to linked ClassInfo objects
                for (int threadsStillRunning = numWorkerThreads; threadsStillRunning > 0
                        && !Thread.currentThread().isInterrupted();) {
                    try {
                        final ClassInfoUnlinked c = classInfoUnlinked.take();
                        if (c == ClassInfoUnlinked.END_OF_QUEUE) {
                            --threadsStillRunning;
                        } else {
                            // Create ClassInfo object from ClassInfoUnlinked object, and link into class graph
                            c.link(classNameToClassInfo);
                        }
                    } catch (final InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                return null;
            }
        });
        futures.add(linkerFuture);

        // -----------------------------------------------------------------------------------------------------
        // Wait for worker thread completion, and then flush out worker logs in order
        // -----------------------------------------------------------------------------------------------------

        final Future<ScanResult> scanResult = executorService.submit(new LoggedThread<ScanResult>() {
            @Override
            public ScanResult doWork() throws Exception {
                for (int i = 0; i < futures.size(); i++) {
                    // Wait for worker thread completion
                    futures.get(i).get();
                }

                // Build class graph before calling MatchProcessors, in case they want to refer to the graph
                final ScanResult scanResult = new ScanResult(scanSpec, classpathElts, classNameToClassInfo,
                        fileToTimestamp, log);

                // Call MatchProcessors
                final long startMatchProcessors = System.nanoTime();
                scanSpec.callMatchProcessors(scanResult, matchingFiles, classNameToClassInfo, log);

                if (FastClasspathScanner.verbose) {
                    log.log(1, "Finished calling MatchProcessors", System.nanoTime() - startMatchProcessors);
                    log.log("Finished scan", System.nanoTime() - scanStart);
                }
                return scanResult;
            }
        });

        return scanResult;
    }
}
