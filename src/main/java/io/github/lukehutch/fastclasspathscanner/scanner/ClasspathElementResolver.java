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
import java.nio.file.LinkOption;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.PriorityBlockingQueue;

import io.github.lukehutch.fastclasspathscanner.FastClasspathScanner;
import io.github.lukehutch.fastclasspathscanner.utils.FastManifestParser;
import io.github.lukehutch.fastclasspathscanner.utils.FastPathResolver;
import io.github.lukehutch.fastclasspathscanner.utils.LoggedThread;

public class ClasspathElementResolver extends LoggedThread<List<File>> {
    /** The scanning specification. */
    private final ScanSpec scanSpec;

    /** The executor service. */
    private final ExecutorService executorService;

    /** The number of parallel tasks. */
    private final int numParallelTasks;

    /** The list of raw classpath elements. */
    private final List<String> rawClasspathElements;

    /** The current directory. */
    private final String currentDirURI;

    // -------------------------------------------------------------------------------------------------------------

    public ClasspathElementResolver(final ScanSpec scanSpec, final ExecutorService executorService,
            final int numParallelTasks) {
        this.scanSpec = scanSpec;
        this.executorService = executorService;
        this.numParallelTasks = numParallelTasks;
        try {
            // Get current dir in a canonical form, and remove any trailing slash, if present)
            this.currentDirURI = FastPathResolver.resolve(
                    Paths.get("").toAbsolutePath().normalize().toRealPath(LinkOption.NOFOLLOW_LINKS).toString());
        } catch (final IOException e) {
            throw new RuntimeException(e);
        }
        this.rawClasspathElements = new ClasspathFinder(scanSpec, log).getRawClasspathElements();
    }

    // -------------------------------------------------------------------------------------------------------------

    private static final String zeroPadFormatString(final int maxVal) {
        return "%0" + Integer.toString(maxVal).length() + "d";
    }

    // -------------------------------------------------------------------------------------------------------------

    @Override
    public List<File> doWork() throws Exception {
        final boolean blacklistSystemJars = scanSpec.blacklistSystemJars();
        final ConcurrentHashMap<String, String> knownJREPaths = new ConcurrentHashMap<>();
        final ConcurrentHashMap<String, OrderedClasspathElement> pathToEarliestOrderKey = new ConcurrentHashMap<>();
        final PriorityBlockingQueue<OrderedClasspathElement> uniqueElts = new PriorityBlockingQueue<>();
        try (final WorkQueue<OrderedClasspathElement> workQueue = new WorkQueue<OrderedClasspathElement>() {
            @Override
            public BlockingQueue<OrderedClasspathElement> createQueue() {
                return new PriorityBlockingQueue<>();
            }

            @Override
            public void processWorkUnit(OrderedClasspathElement classpathElt, ThreadLog log) {
                if (FastClasspathScanner.verbose) {
                    log.log("Found classpath element: " + classpathElt.getResolvedPath());
                }

                uniqueElts.add(classpathElt);
                
                // If this classpath element is a jar or zipfile, look for Class-Path entries in the manifest
                // file. OpenJDK scans manifest-defined classpath elements after the jar that listed them, so
                // we recursively call addClasspathElement if needed each time a jar is encountered. 
                if (classpathElt.isFile()) {
                    final File file = classpathElt.getFile();
                    final FastManifestParser manifest = new FastManifestParser(file, log);
                    if (manifest.classPath != null) {
                        final String[] manifestClassPathElts = manifest.classPath.split(" ");
                        if (FastClasspathScanner.verbose) {
                            log.log("Found Class-Path entry in manifest of " + classpathElt.getResolvedPath() + ": "
                                    + manifest.classPath);
                        }

                        // Class-Path entries in the manifest file should be resolved relative to
                        // the dir the manifest's jarfile is contained in.
                        final String parentPath = FastPathResolver.resolve(file.getParent());

                        // Class-Path entries in manifest files are a space-delimited list of URIs.
                        final String fmt = zeroPadFormatString(manifestClassPathElts.length - 1);
                        for (int i = 0; i < manifestClassPathElts.length; i++) {
                            final String manifestClassPathElt = manifestClassPathElts[i];
                            // Give each sub-entry a new lexicographically-increasing order key.
                            // The use of PriorityBlockingQueue ensures that these referenced jarfiles
                            // will be processed next in the queue, reducing the chance of duplicate work,
                            // in the case that the same jarfile is referenced later in the queue.
                            final String orderKey = classpathElt.orderKey + "." + String.format(fmt, i);
                            OrderedClasspathElement linkedClasspathElt = new OrderedClasspathElement(orderKey,
                                    parentPath, manifestClassPathElt);
                            if (linkedClasspathElt.isValid(pathToEarliestOrderKey, blacklistSystemJars,
                                    knownJREPaths, log)) {
                                // Add new work unit at head of priority queue (the new order key is based on the
                                // current order key, which was previously removed from the head of the queue).
                                // This causes the linked jars to be processed as soon as possible, to reduce the
                                // possibility of duplicate work if another link to the jar is found later in the
                                // classpath. (The work would be duplicated if this earlier reference is scanned
                                // later, since this earlier reference has a lower orderKey, so it will override
                                // the later reference.)
                                addWorkUnit(linkedClasspathElt);
                            }
                        }
                    }
                }
            }
        }) {
            // Schedule raw classpath elements as original work units
            final String fmt = zeroPadFormatString(rawClasspathElements.size() - 1);
            for (int i = 0; i < rawClasspathElements.size(); i++) {
                final String rawClasspathElt = rawClasspathElements.get(i);
                final String orderKey = String.format(fmt, i);
                OrderedClasspathElement classpathElt = new OrderedClasspathElement(orderKey, currentDirURI,
                        rawClasspathElt);
                if (classpathElt.isValid(pathToEarliestOrderKey, blacklistSystemJars, knownJREPaths, log)) {
                    workQueue.addWorkUnit(classpathElt);
                }
            }

            // Start workers
            workQueue.startWorkers(executorService, numParallelTasks);

            // Also do work in the main thread
            workQueue.runWorkLoop(log);

            // After work has completed, shut down work queue with Autocloseable
        }
        
        final List<File> classpathElements = new ArrayList<>(uniqueElts.size());
        for (final OrderedClasspathElement elt : uniqueElts) {
            final File eltFile = elt.getFile();
            classpathElements.add(eltFile);
            if (FastClasspathScanner.verbose) {
                log.log("Found unique classpath element: " + eltFile);
            }
        }
        return classpathElements;
    }
}
