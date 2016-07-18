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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

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
            this.currentDirURI = FastPathResolver.resolve(null,
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

    private static boolean isEarliestOccurrenceOfPath(final String canonicalPath,
            final OrderedClasspathElement orderedElement,
            final ConcurrentHashMap<String, OrderedClasspathElement> pathToEarliestOrderedElement) {
        OrderedClasspathElement olderOrderedElement = pathToEarliestOrderedElement.put(canonicalPath,
                orderedElement);
        if (olderOrderedElement == null) {
            // First occurrence of this path
            return true;
        }
        final int diff = olderOrderedElement.compareTo(orderedElement);
        if (diff == 0) {
            // Should not happen, because relative paths are unique within a given filesystem or jar
            return false;
        } else if (diff < 0) {
            // olderOrderKey comes before orderKey, so this relative path is masked by an earlier one.
            // Need to put older order key back in map, avoiding race condition
            for (;;) {
                final OrderedClasspathElement nextOlderOrderedElt = pathToEarliestOrderedElement.put(canonicalPath,
                        olderOrderedElement);
                if (nextOlderOrderedElt.compareTo(olderOrderedElement) <= 0) {
                    break;
                }
                olderOrderedElement = nextOlderOrderedElt;
            }
            return false;
        } else {
            // orderKey comes before olderOrderKey, so this relative path masks an earlier one.
            return true;
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /** Returns true if the path ends with a JAR extension, matching case. */
    private static boolean isJarMatchCase(final String path) {
        return path.length() > 4 && path.charAt(path.length() - 4) == '.' // 
                && path.endsWith(".jar") || path.endsWith(".zip") || path.endsWith(".war") || path.endsWith(".car");
    }

    /** Returns true if the path ends with a JAR extension, ignoring case. */
    private static boolean isJar(final String path) {
        return isJarMatchCase(path) || isJarMatchCase(path.toLowerCase());
    }

    /**
     * Recursively search within ancestral directories of a jarfile to see if rt.jar is present, in order to
     * determine if the given jarfile is part of the JRE. This would typically be called with an initial
     * ancestralScandepth of 2, since JRE jarfiles can be in the lib or lib/ext directories of the JRE.
     */
    private static boolean isJREJar(final File file, final int ancestralScanDepth,
            final ConcurrentHashMap<String, String> knownJREPaths, final ThreadLog log) {
        if (ancestralScanDepth == 0) {
            return false;
        } else {
            final File parent = file.getParentFile();
            if (parent == null) {
                return false;
            }
            final String parentPathStr = parent.getPath();
            if (knownJREPaths.containsKey(parentPathStr)) {
                return true;
            }
            File rt = new File(parent, "rt.jar");
            if (!rt.exists()) {
                rt = new File(new File(parent, "lib"), "rt.jar");
                if (!rt.exists()) {
                    rt = new File(new File(new File(parent, "jre"), "lib.jar"), "rt.jar");
                }
            }
            if (rt.exists()) {
                // Found rt.jar; check its manifest file to make sure it's the JRE's rt.jar and not something else 
                final FastManifestParser manifest = new FastManifestParser(rt, log);
                if (manifest.isSystemJar) {
                    // Found the JRE's rt.jar
                    knownJREPaths.put(parentPathStr, parentPathStr);
                    return true;
                }
            }
            return isJREJar(parent, ancestralScanDepth - 1, knownJREPaths, log);
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    private static void processWorkQueue(final PriorityBlockingQueue<OrderedClasspathElement> orderedWorkUnits,
            final AtomicInteger numWorkUnitsRemaining,
            final ConcurrentHashMap<String, OrderedClasspathElement> pathToEarliestOrderKey,
            final boolean blacklistSystemJars, final ConcurrentHashMap<String, String> knownJREPaths,
            final PriorityBlockingQueue<OrderedClasspathElement> uniqueValidCanonicalClasspathEltsOut,
            final AtomicBoolean killAllThreads, final ThreadLog log) throws InterruptedException {
        // Get next OrderedClasspathEntry from priority queue
        while (numWorkUnitsRemaining.get() > 0) {
            OrderedClasspathElement classpathElt = null;
            while (numWorkUnitsRemaining.get() > 0) {
                if (Thread.currentThread().isInterrupted() || killAllThreads.get()) {
                    killAllThreads.set(true);
                    throw new InterruptedException();
                }
                // Busy-wait on last numParallelTasks work units, in case additional work units are generated
                // from jarfiles with Class-Path entries
                classpathElt = orderedWorkUnits.poll();
                if (classpathElt != null) {
                    // Got a work unit
                    break;
                }
            }
            if (classpathElt == null) {
                // No work units remaining
                return;
            }

            // Got a work unit -- hold numWorkUnitsRemaining high until work is complete, and decrement it in the
            // finally block at the end of the work unit (so that at the instant the work unit was removed from
            // the queue, numWorkUnitsRemaining will be one more than the length of the queue). This prevents any
            // threads from exiting until the work queue is empty *and* all threads have finished processing the
            // work units they removed from the queue). This is needed because a given work unit can add more work
            // units to the queue while it is processing, and the work is not finished even though the queue
            // may be empty.
            try {
                // Get absolute URI and File for classpathElt
                final String path = classpathElt.getResolvedPath();
                if (path == null) {
                    // Got an http: or https: URI as a classpath element
                    if (FastClasspathScanner.verbose) {
                        log.log("Skipping non-local classpath element: " + classpathElt.relativePath);
                    }
                    continue;
                }
                final File file = classpathElt.getFile();
                if (!file.exists()) {
                    if (FastClasspathScanner.verbose) {
                        log.log("Classpath element does not exist: " + file);
                    }
                    continue;
                }
                // Check that this classpath element is the earliest instance of the same canonical path
                // on the classpath (i.e. only scan a classpath element once
                String canonicalPath;
                try {
                    canonicalPath = classpathElt.getCanonicalPath();
                } catch (final IOException e) {
                    if (FastClasspathScanner.verbose) {
                        log.log("Could not canonicalize path: " + file);
                    }
                    continue;
                }
                if (!isEarliestOccurrenceOfPath(canonicalPath, classpathElt, pathToEarliestOrderKey)) {
                    if (FastClasspathScanner.verbose) {
                        log.log("Ignoring duplicate classpath element: " + file);
                    }
                    continue;
                }
                final boolean isFile = file.isFile();
                final boolean isDirectory = file.isDirectory();
                if (!isFile && !isDirectory) {
                    if (FastClasspathScanner.verbose) {
                        log.log("Ignoring invalid classpath element: " + path);
                    }
                    continue;
                }
                if (isFile && !isJar(file.getPath())) {
                    if (FastClasspathScanner.verbose) {
                        log.log("Ignoring non-jar file on classpath: " + path);
                    }
                    continue;
                }
                if (isFile && blacklistSystemJars
                        && isJREJar(file, /* ancestralScanDepth = */2, knownJREPaths, log)) {
                    // Don't scan system jars if they are blacklisted
                    if (FastClasspathScanner.verbose) {
                        log.log("Skipping JRE jar: " + path);
                    }
                    continue;
                }

                // Classpath element is valid
                uniqueValidCanonicalClasspathEltsOut.add(classpathElt);
                if (FastClasspathScanner.verbose) {
                    log.log("Found classpath element: " + path);
                }

                // If this classpath element is a jar or zipfile, look for Class-Path entries in the manifest
                // file. OpenJDK scans manifest-defined classpath elements after the jar that listed them, so
                // we recursively call addClasspathElement if needed each time a jar is encountered. 
                if (isFile) {
                    final FastManifestParser manifest = new FastManifestParser(file, log);
                    if (manifest.classPath != null) {
                        final String[] manifestClassPathElts = manifest.classPath.split(" ");
                        if (FastClasspathScanner.verbose) {
                            log.log("Found Class-Path entry in manifest of " + file + ": " + manifest.classPath);
                        }

                        // Class-Path entries in the manifest file should be resolved relative to
                        // the dir the manifest's jarfile is contained in.
                        final String parentPath = FastPathResolver.resolve(null, file.getParent());

                        // Class-Path entries in manifest files are a space-delimited list of URIs.
                        final String fmt = zeroPadFormatString(manifestClassPathElts.length - 1);
                        for (int i = 0; i < manifestClassPathElts.length; i++) {
                            final String manifestClassPathElt = manifestClassPathElts[i];
                            // Give each sub-entry a new lexicographically-increasing order key.
                            // The use of PriorityBlockingQueue ensures that these referenced jarfiles
                            // will be processed next in the queue, reducing the chance of duplicate work,
                            // in the case that the same jarfile is referenced later in the queue.
                            final String orderKey = classpathElt.orderKey + "." + String.format(fmt, i);
                            // Add new work unit at head of priority queue (the new order key is based on the
                            // current order key, which was previously removed from the head of the queue). This
                            // causes the linked jars to be processed as soon as possible, to reduce the possibility
                            // of duplicate work if another link to the jar is found later in the classpath. (The
                            // work would be duplicated if this earlier reference is scanned later, since this
                            // earlier reference has a lower orderKey, so it will override the later reference.)
                            numWorkUnitsRemaining.incrementAndGet();
                            orderedWorkUnits
                                    .add(new OrderedClasspathElement(orderKey, parentPath, manifestClassPathElt));
                        }
                    }
                }
            } finally {
                numWorkUnitsRemaining.decrementAndGet();
            }
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    @Override
    public List<File> doWork() throws Exception {
        // Schedule raw classpath elements as original work units
        final PriorityBlockingQueue<OrderedClasspathElement> orderedWorkUnits = new PriorityBlockingQueue<>();
        final String fmt = zeroPadFormatString(rawClasspathElements.size() - 1);
        for (int i = 0; i < rawClasspathElements.size(); i++) {
            final String rawClasspathElt = rawClasspathElements.get(i);
            final String orderKey = String.format(fmt, i);
            orderedWorkUnits.add(new OrderedClasspathElement(orderKey, currentDirURI, rawClasspathElt));
        }
        final AtomicInteger numWorkUnitsRemaining = new AtomicInteger(rawClasspathElements.size());

        // Resolve classpath elements: check raw classpath elements exist; check that their canonical
        // path is unique; in the case of jarfiles, check for nested Class-Path manifest entries.
        final ConcurrentHashMap<String, OrderedClasspathElement> pathToEarliestOrderKey = new ConcurrentHashMap<>();
        // The set of JRE paths found so far in the classpath, cached for speed.
        final ConcurrentHashMap<String, String> knownJREPaths = new ConcurrentHashMap<>();
        final PriorityBlockingQueue<OrderedClasspathElement> uniqueValidCanonicalClasspathElts = //
                new PriorityBlockingQueue<>();
        final AtomicBoolean killAllThreads = new AtomicBoolean(false);
        final List<Future<Void>> futures = new ArrayList<>();
        for (int i = 0; i < numParallelTasks - 1; i++) {
            futures.add(executorService.submit(new LoggedThread<Void>() {
                @Override
                public Void doWork() throws Exception {
                    processWorkQueue(orderedWorkUnits, numWorkUnitsRemaining, pathToEarliestOrderKey,
                            scanSpec.blacklistSystemJars(), knownJREPaths, uniqueValidCanonicalClasspathElts,
                            killAllThreads, log);
                    return null;
                }
            }));
        }

        // Process work queue in this thread too, in case there is only one thread in ExecutorService
        processWorkQueue(orderedWorkUnits, numWorkUnitsRemaining, pathToEarliestOrderKey,
                scanSpec.blacklistSystemJars(), knownJREPaths, uniqueValidCanonicalClasspathElts, killAllThreads,
                log);
        log.flush();

        // Barrier to wait for task completion. Tasks should have all completed by this point, since all work
        // work units have been processed. If any tasks have not been started, cancel them, because they never
        // started. (This can happen if numParallelTasks is greater than the number of threads available in the
        // ExecutorService.)
        killAllThreads.set(true);
        for (final Future<Void> future : futures) {
            future.cancel(true);
        }

        // uniqueValidCanonicalClasspathElts now holds the unique classpath elements        
        final List<File> classpathElements = new ArrayList<>(uniqueValidCanonicalClasspathElts.size());
        for (final OrderedClasspathElement elt : uniqueValidCanonicalClasspathElts) {
            final File eltFile = elt.getFile();
            classpathElements.add(eltFile);
            if (FastClasspathScanner.verbose) {
                log.log("Found unique classpath element: " + eltFile);
            }
        }
        return classpathElements;
    }
}
