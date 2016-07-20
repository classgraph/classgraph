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
import java.util.HashSet;
import java.util.List;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.zip.ZipFile;

import io.github.lukehutch.fastclasspathscanner.FastClasspathScanner;
import io.github.lukehutch.fastclasspathscanner.utils.FastManifestParser;
import io.github.lukehutch.fastclasspathscanner.utils.FastPathResolver;
import io.github.lukehutch.fastclasspathscanner.utils.LoggedThread;
import io.github.lukehutch.fastclasspathscanner.utils.WorkQueue;

public class ClasspathElementResolver extends LoggedThread<List<File>> {
    /** The scanning specification. */
    private final ScanSpec scanSpec;

    /** The executor service. */
    private final ExecutorService executorService;

    /** The number of parallel tasks. */
    private final int numParallelTasks;

    // -------------------------------------------------------------------------------------------------------------

    public ClasspathElementResolver(final ScanSpec scanSpec, final ExecutorService executorService,
            final int numParallelTasks) {
        this.scanSpec = scanSpec;
        this.executorService = executorService;
        this.numParallelTasks = numParallelTasks;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Used for opening a ZipFile object and parsing its manifest, given a canonical path. Also used as a
     * placeholder for non-jar (directory) classpath entries.
     */
    private static class ClasspathElementOpener {
        private final ClasspathElement classpathElt;
        private final ThreadLog log;

        /** ZipFile, may be null if the ZipFile could not be opened. */
        public ZipFile jarFile;

        /** Manifest parser, may be null if the ZipFile could not be opened. */
        public FastManifestParser manifestParser;

        public ClasspathElementOpener(final ClasspathElement classpathElt, final ThreadLog log) {
            this.classpathElt = classpathElt;
            this.log = log;
        }

        public void open() {
            if (classpathElt.isFile()) {
                final File file = classpathElt.getFile();
                try {
                    // Open the ZipFile, and read the list of files
                    jarFile = new ZipFile(file);
                    // Parse the manifest, if present
                    manifestParser = new FastManifestParser(jarFile, log);
                } catch (final Exception e) {
                    if (FastClasspathScanner.verbose) {
                        log.log("Exception while opening zipfile " + file, e);
                    }
                }
            }
        }

        public void close() {
            if (jarFile != null) {
                try {
                    jarFile.close();
                } catch (final IOException e) {
                    if (FastClasspathScanner.verbose) {
                        log.log("Exception while closing zipfile " + classpathElt.getFile(), e);
                    }
                }
                jarFile = null;
            }
        }
    }

    /** A singleton map that maps a canonical path to a ClasspathElementOpener. */
    public static class ClasspathElementOpenerMap {
        private final ConcurrentMap<String, ClasspathElementOpener> map = new ConcurrentHashMap<>();
        private final ConcurrentMap<String, Object> keyToLock = new ConcurrentHashMap<>();
        private final ThreadLog log;

        public ClasspathElementOpenerMap(final ThreadLog log) {
            this.log = log;
        }

        private Object getLock(final String key) {
            final Object object = new Object();
            Object lock = keyToLock.putIfAbsent(key, object);
            if (lock == null) {
                lock = object;
            }
            return lock;
        }

        public boolean initializeIfFirst(final String canonicalPath, final ClasspathElement classpathElt) {
            synchronized (getLock(canonicalPath)) {
                ClasspathElementOpener elementOpener = map.get(canonicalPath);
                if (elementOpener == null) {
                    map.put(canonicalPath, elementOpener = new ClasspathElementOpener(classpathElt, log));
                    return true;
                }
                return false;
            }
        }

        public ClasspathElementOpener get(final String canonicalPath) {
            return map.get(canonicalPath);
        }

        public Set<Entry<String, ClasspathElementOpener>> entrySet() {
            return map.entrySet();
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * A work queue that takes classpath entries and determines if they are valid (i.e. that they exist, and if they
     * are jarfiles, that they can be opened). For jarfiles, looks for Class-Path manifest entries and adds them to
     * the classpath in the current order position.
     */
    private static class ClasspathElementProcessor extends WorkQueue<ClasspathElement> {
        private final ScanSpec scanSpec;

        /** A cache of known JRE paths. */
        private final ConcurrentHashMap<String, String> knownJREPaths = new ConcurrentHashMap<>();

        private final ClasspathElementOpenerMap canonicalPathToClasspathElementOpener;

        private final ConcurrentHashMap<String, List<String>> canonicalPathToChildCanonicalPaths;

        public ClasspathElementProcessor(final ScanSpec scanSpec,
                final ConcurrentHashMap<String, List<String>> canonicalPathToChildCanonicalPaths,
                final ClasspathElementOpenerMap canonicalPathToClasspathElementOpener) {
            this.scanSpec = scanSpec;
            this.canonicalPathToChildCanonicalPaths = canonicalPathToChildCanonicalPaths;
            this.canonicalPathToClasspathElementOpener = canonicalPathToClasspathElementOpener;
        }

        @Override
        public BlockingQueue<ClasspathElement> createQueue() {
            return new LinkedBlockingQueue<>();
        }

        @Override
        public void processWorkUnit(final ClasspathElement classpathElt, final ThreadLog log) {
            // Check the classpath entry exists and is not a blacklisted system jar
            if (!classpathElt.isValid(scanSpec, knownJREPaths, log)) {
                return;
            }

            // Get canonical path
            final String canonicalPath = classpathElt.getCanonicalPath();
            if (canonicalPath == null) {
                if (FastClasspathScanner.verbose) {
                    log.log("Could not canonicalize path: " + classpathElt.getResolvedPath());
                }
                return;
            }

            // Open a single ZipFile per canonical path
            final boolean firstOccurrenceOfCanonicalPath = canonicalPathToClasspathElementOpener
                    .initializeIfFirst(canonicalPath, classpathElt);
            if (firstOccurrenceOfCanonicalPath) {
                if (FastClasspathScanner.verbose) {
                    log.log("Found classpath element: " + classpathElt.getResolvedPath());
                }
                // isValid() above determined that if this is a file, it also has a jar extension
                if (classpathElt.isFile()) {
                    // Open ZipFile and read manifest
                    final ClasspathElementOpener classpathElementOpener = canonicalPathToClasspathElementOpener
                            .get(canonicalPath);
                    classpathElementOpener.open();

                    // If there was an exception opening the ZipFile, or there was no manifest, then
                    // zipFileOpener.manifestParser will be null
                    if (classpathElementOpener.manifestParser != null) {
                        // If this jar is not a blacklisted system jar, and has a Class-Path entry
                        if ((!scanSpec.blacklistSystemJars() || !classpathElementOpener.manifestParser.isSystemJar)
                                && classpathElementOpener.manifestParser.classPath != null) {
                            if (FastClasspathScanner.verbose) {
                                log.log("Found Class-Path entry in manifest of " + classpathElt.getResolvedPath()
                                        + ": " + classpathElementOpener.manifestParser.classPath);
                            }
                            // Get the classpath elements from the Class-Path manifest entry
                            // (these are space-delimited).
                            final String[] manifestClassPathElts = //
                                    classpathElementOpener.manifestParser.classPath.split(" ");

                            // Class-Path entries in the manifest file are resolved relative to
                            // the dir the manifest's jarfile is contained in. Get the parent path.
                            final String pathOfContainingDir = FastPathResolver
                                    .resolve(classpathElt.getFile().getParent());

                            // Enqueue child classpath elements
                            final List<String> resolvedChildPaths = new ArrayList<>(manifestClassPathElts.length);
                            for (int i = 0; i < manifestClassPathElts.length; i++) {
                                final String manifestClassPathElt = manifestClassPathElts[i];
                                final ClasspathElement linkedClasspathElt = new ClasspathElement(
                                        classpathElt.getCanonicalPath(), pathOfContainingDir, manifestClassPathElt);
                                resolvedChildPaths.add(linkedClasspathElt.getCanonicalPath());
                                // Add new work unit at head of queue
                                addWorkUnit(linkedClasspathElt);
                            }

                            // Store the ordering of the child elements relative to this canonical path
                            canonicalPathToChildCanonicalPaths.put(canonicalPath, resolvedChildPaths);
                        }
                    }
                }
            }
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Recursively perform a depth-first search of jar interdependencies, breaking cycles if necessary, to determine
     * the final classpath element order.
     */
    private void findClasspathOrder(final String canonicalPath,
            final ConcurrentHashMap<String, List<String>> canonicalPathToChildCanonicalPaths,
            final ClasspathElementOpenerMap canonicalPathToClasspathElementOpener,
            final HashSet<String> visitedCanonicalPaths, final ArrayList<ClasspathElement> order) {
        if (visitedCanonicalPaths.add(canonicalPath)) {
            final List<String> childPaths = canonicalPathToChildCanonicalPaths.get(canonicalPath);
            if (childPaths != null) {
                for (final String childPath : childPaths) {
                    final ClasspathElementOpener childPathClasspathElementOpener = canonicalPathToClasspathElementOpener
                            .get(childPath);
                    if (childPathClasspathElementOpener != null) {
                        order.add(childPathClasspathElementOpener.classpathElt);
                        findClasspathOrder(childPath, canonicalPathToChildCanonicalPaths,
                                canonicalPathToClasspathElementOpener, visitedCanonicalPaths, order);
                    }
                }
            }
        }
    }

    /**
     * Recursively perform a depth-first search of jar interdependencies, breaking cycles if necessary, to determine
     * the final classpath element order.
     */
    private List<ClasspathElement> findClasspathOrder(final String currentDirPath,
            final ConcurrentHashMap<String, List<String>> canonicalPathToChildCanonicalPaths,
            final ClasspathElementOpenerMap canonicalPathToClasspathElementOpener) {
        final HashSet<String> visitedCanonicalPaths = new HashSet<>();
        final ArrayList<ClasspathElement> order = new ArrayList<>();
        findClasspathOrder(currentDirPath, canonicalPathToChildCanonicalPaths,
                canonicalPathToClasspathElementOpener, visitedCanonicalPaths, order);
        return order;
    }

    // -------------------------------------------------------------------------------------------------------------

    /** Determine and return the unique ordered classpath elements. */
    @Override
    public List<File> doWork() throws InterruptedException {
        final List<File> classpathElementsOrdered = new ArrayList<>();

        // Get raw classpath elements
        final List<String> rawClasspathElementPaths = new ClasspathFinder(scanSpec, log).getRawClasspathElements();

        // Get current dir in a canonical form, and remove any trailing slash, if present)
        String currentDirPath;
        try {
            currentDirPath = FastPathResolver.resolve(
                    Paths.get("").toAbsolutePath().normalize().toRealPath(LinkOption.NOFOLLOW_LINKS).toString());
        } catch (final IOException e) {
            throw new RuntimeException(e);
        }

        // A map from canonical path to child canonical paths.
        final ConcurrentHashMap<String, List<String>> canonicalPathToChildCanonicalPaths = //
                new ConcurrentHashMap<>();
        canonicalPathToChildCanonicalPaths.put(currentDirPath, rawClasspathElementPaths);

        // Map to hold ZipFiles, with atomic creation of entries, so that ZipFiles only get opened once
        // (they are somewhat expensive to open).
        final ClasspathElementOpenerMap canonicalPathToClasspathElementOpener = new ClasspathElementOpenerMap(log);
        try {
            // Create the work queue -- after work has completed, any workers that didn't start up will be killed
            try (final WorkQueue<ClasspathElement> workQueue = new ClasspathElementProcessor(scanSpec,
                    canonicalPathToChildCanonicalPaths, canonicalPathToClasspathElementOpener)) {
                // Create initial work units from raw classpath elements
                for (int eltIdx = 0; eltIdx < rawClasspathElementPaths.size(); eltIdx++) {
                    final String rawClasspathElementPath = rawClasspathElementPaths.get(eltIdx);
                    final ClasspathElement rawClasspathElt = new ClasspathElement(currentDirPath, currentDirPath,
                            rawClasspathElementPath);
                    workQueue.addWorkUnit(rawClasspathElt);
                }
                // Start workers
                workQueue.startWorkers(executorService, numParallelTasks);
                // Also do work in the main thread, until all work units have been completed
                workQueue.runWorkLoop(log);
                // Check if work termination was due to interruption
                if (Thread.currentThread().isInterrupted()) {
                    throw new InterruptedException();
                }
            }
            
            // Figure out total ordering of classpath elements
            final List<ClasspathElement> classpathOrder = findClasspathOrder(currentDirPath,
                    canonicalPathToChildCanonicalPaths, canonicalPathToClasspathElementOpener);

            // TODO: scan files hierarchically before closing zipfiles
            for (final ClasspathElement elt : classpathOrder) {
                classpathElementsOrdered.add(elt.getFile());
            }

        } finally {
            // Close any zipfiles that were opened
            for (final Entry<String, ClasspathElementOpener> ent : canonicalPathToClasspathElementOpener
                    .entrySet()) {
                ent.getValue().close();
            }
        }
        return classpathElementsOrdered;
    }
}
