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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;

import io.github.lukehutch.fastclasspathscanner.utils.FastPathResolver;
import io.github.lukehutch.fastclasspathscanner.utils.InterruptionChecker;
import io.github.lukehutch.fastclasspathscanner.utils.LogNode;
import io.github.lukehutch.fastclasspathscanner.utils.Recycler;
import io.github.lukehutch.fastclasspathscanner.utils.WorkQueue;
import io.github.lukehutch.fastclasspathscanner.utils.WorkQueue.WorkUnitProcessor;

public class ScannerCore implements Callable<ScanResult> {
    private final ScanSpec scanSpec;
    private final ExecutorService executorService;
    private final int numParallelTasks;
    private final boolean scanFiles;
    private final LogNode log;

    /**
     * The number of files within a given classpath element (directory or zipfile) to send in a chunk to the workers
     * that are calling the classfile binary parser. The smaller this number is, the better the load leveling at the
     * end of the scan, but the higher the overhead in re-opening the same ZipFile in different worker threads.
     */
    private static final int NUM_FILES_PER_CHUNK = 200;

    public ScannerCore(final ScanSpec scanSpec, final ExecutorService executorService, final int numParallelTasks,
            final boolean enableRecursiveScanning, final LogNode log) {
        this.scanSpec = scanSpec;
        this.executorService = executorService;
        this.numParallelTasks = numParallelTasks;
        this.scanFiles = enableRecursiveScanning;
        this.log = log;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Recursively perform a depth-first search of jar interdependencies, breaking cycles if necessary, to determine
     * the final classpath element order.
     */
    private static void findClasspathOrder(final ClasspathElement currSingleton,
            final ClasspathRelativePathToElementMap classpathElementMap,
            final HashSet<ClasspathElement> visitedClasspathElts, final ArrayList<ClasspathElement> order)
            throws InterruptedException {
        if (visitedClasspathElts.add(currSingleton)) {
            order.add(currSingleton);
            if (currSingleton.childClasspathElts != null) {
                for (final ClasspathRelativePath childClasspathElt : currSingleton.childClasspathElts) {
                    final ClasspathElement childSingleton = classpathElementMap.get(childClasspathElt);
                    if (childSingleton != null && !childSingleton.ioExceptionOnOpen) {
                        findClasspathOrder(childSingleton, classpathElementMap, visitedClasspathElts, order);
                    }
                }
            }
        }
    }

    /**
     * Recursively perform a depth-first search of jar interdependencies, breaking cycles if necessary, to determine
     * the final classpath element order.
     */
    private static List<ClasspathElement> findClasspathOrder(final List<ClasspathRelativePath> rawClasspathElements,
            final ClasspathRelativePathToElementMap classpathElementMap) throws InterruptedException {
        // Recurse from toplevel classpath elements to determine a total ordering of classpath elements
        // (jars with Class-Path entries in their manifest file should have those child resources included
        // in-place in the classpath).
        final HashSet<ClasspathElement> visitedClasspathElts = new HashSet<>();
        final ArrayList<ClasspathElement> order = new ArrayList<>();
        for (final ClasspathRelativePath toplevelClasspathElt : rawClasspathElements) {
            final ClasspathElement toplevelSingleton = classpathElementMap.get(toplevelClasspathElt);
            if (toplevelSingleton != null && !toplevelSingleton.ioExceptionOnOpen) {
                findClasspathOrder(toplevelSingleton, classpathElementMap, visitedClasspathElts, order);
            }
        }
        return order;
    }

    // -------------------------------------------------------------------------------------------------------------

    /** Holds range limits for chunks of classpath files that need to be scanned in a given classpath element. */
    private static class ClassfileParserChunk {
        private final ClasspathElement classpathElement;
        private final int classfileStartIdx;
        private final int classfileEndIdx;

        public ClassfileParserChunk(final ClasspathElement classpathElementSingleton, final int classfileStartIdx,
                final int classfileEndIdx) {
            this.classpathElement = classpathElementSingleton;
            this.classfileStartIdx = classfileStartIdx;
            this.classfileEndIdx = classfileEndIdx;
        }
    }

    /**
     * Break the classfiles that need to be scanned in each classpath element into chunks of approximately
     * NUM_FILES_PER_CHUNK files.
     */
    private static List<ClassfileParserChunk> getClassfileParserChunks(
            final List<ClasspathElement> classpathOrder) {
        final List<ClassfileParserChunk> chunks = new ArrayList<>();
        for (final ClasspathElement classpathElement : classpathOrder) {
            final int numClassfileMatches = classpathElement.getNumClassfileMatches();
            if (numClassfileMatches > 0) {
                final int numChunks = (int) Math.ceil((float) numClassfileMatches / (float) NUM_FILES_PER_CHUNK);
                final float filesPerChunk = (float) numClassfileMatches / (float) numChunks;
                for (int i = 0; i < numChunks; i++) {
                    final int classfileStartIdx = (int) (i * filesPerChunk);
                    final int classfileEndIdx = i < numChunks - 1 ? (int) ((i + 1) * filesPerChunk)
                            : numClassfileMatches;
                    if (classfileEndIdx > classfileStartIdx) {
                        chunks.add(new ClassfileParserChunk(classpathElement, classfileStartIdx, classfileEndIdx));
                    }
                }
            }
        }
        return chunks;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Determine the unique ordered classpath elements, and run a scan looking for file or classfile matches if
     * necessary.
     */
    @Override
    public ScanResult call() throws InterruptedException, ExecutionException {
        try {
            final long scanStart = System.nanoTime();

            // Get current dir (without resolving symlinks), and normalize path by calling
            // FastPathResolver.resolve()
            String currentDirPath;
            try {
                currentDirPath = FastPathResolver.resolve(Paths.get("").toAbsolutePath().normalize()
                        .toRealPath(LinkOption.NOFOLLOW_LINKS).toString());
            } catch (final IOException e) {
                throw new RuntimeException(e);
            }

            final List<File> classpathElementFilesOrdered = new ArrayList<>();

            // Get raw classpath elements
            final List<String> rawClasspathElementPathStrs = new ClasspathFinder(scanSpec, log)
                    .getRawClasspathElements();

            // Create ClasspathElement objects for each raw classpath element path
            final List<ClasspathRelativePath> rawClasspathElements = new ArrayList<>();
            for (final String rawClasspathElementPathStr : rawClasspathElementPathStrs) {
                // Resolve classpath elements relative to current dir, so that paths like "." are handled.
                final ClasspathRelativePath classpathElt = new ClasspathRelativePath(currentDirPath,
                        rawClasspathElementPathStr);
                rawClasspathElements.add(classpathElt);
            }

            final InterruptionChecker interruptionChecker = new InterruptionChecker();

            // Recycle object instances across threads for efficiency
            try (final Recycler<ClassfileBinaryParser, RuntimeException> classfileBinaryParserRecycler = //
                    new Recycler<ClassfileBinaryParser, RuntimeException>() {
                        @Override
                        public ClassfileBinaryParser newInstance() {
                            return new ClassfileBinaryParser(scanSpec);
                        }
                    }) {
                // In parallel, resolve classpath elements to canonical paths, creating a ClasspathElement
                // singleton for each unique canonical path, and if the elements are jarfiles, read the manifest
                // file if present. If enableRecursiveScanning is true, also recursively scan files in each
                // classpath element, looking for file path matches.
                final ClasspathRelativePathToElementMap classpathElementMap = new ClasspathRelativePathToElementMap(
                        scanFiles, scanSpec, interruptionChecker, log);
                final ConcurrentHashMap<String, String> knownJREPaths = new ConcurrentHashMap<>();
                final ConcurrentHashMap<String, String> knownNonJREPaths = new ConcurrentHashMap<>();
                try (WorkQueue<ClasspathRelativePath> workQueue = new WorkQueue<>(rawClasspathElements,
                        new WorkUnitProcessor<ClasspathRelativePath>() {
                            @Override
                            public void processWorkUnit(ClasspathRelativePath rawClasspathElt) throws Exception {
                                if (rawClasspathElt.isValid(scanSpec, knownJREPaths, knownNonJREPaths,
                                        classpathElementMap, log)) {
                                    classpathElementMap.createSingleton(rawClasspathElt);
                                }
                            }
                        }, interruptionChecker, log)) {
                    classpathElementMap.setWorkQueue(workQueue);
                    // Start workers, then use this thread to do work too, in case there is only one thread
                    // available in the ExecutorService
                    workQueue.startWorkers(executorService, numParallelTasks - 1, log);
                    workQueue.runWorkLoop();
                }

                // Determine total ordering of classpath elements, inserting jars referenced in manifest Class-Path
                // entries in-place into the ordering, if they haven't been listed earlier in the classpath already.
                final List<ClasspathElement> classpathOrder = findClasspathOrder(rawClasspathElements,
                        classpathElementMap);
                final HashSet<String> classpathRelativePathsFound = new HashSet<>();
                for (final ClasspathElement singleton : classpathOrder) {
                    classpathElementFilesOrdered.add(singleton.classpathElementFile);
                }
                if (log != null) {
                    final LogNode logNode = log.log("Classpath element order:");
                    for (int i = 0; i < classpathOrder.size(); i++) {
                        final ClasspathElement classpathElt = classpathOrder.get(i);
                        logNode.log(i + ": " + classpathElt);
                    }
                }

                ScanResult scanResult;
                if (scanFiles) {
                    // Determine if any relative paths later in the classpath are masked by relative paths
                    // earlier in the classpath
                    for (final ClasspathElement classpathElement : classpathOrder) {
                        // Implement classpath masking -- if the same relative path occurs multiple times in the
                        // classpath, ignore (remove) the second and subsequent occurrences.
                        classpathElement.maskFiles(classpathRelativePathsFound, log);
                    }

                    // Merge the maps from file to timestamp across all classpath elements
                    final Map<File, Long> fileToLastModified = new HashMap<>();
                    for (final ClasspathElement classpathElement : classpathOrder) {
                        fileToLastModified.putAll(classpathElement.fileToLastModified);
                    }

                    // Scan classfile binary headers in parallel
                    final ConcurrentLinkedQueue<ClassInfoUnlinked> classInfoUnlinked = //
                            new ConcurrentLinkedQueue<>();
                    final ConcurrentHashMap<String, String> stringInternMap = new ConcurrentHashMap<>();
                    try (WorkQueue<ClassfileParserChunk> workQueue = new WorkQueue<>(
                            getClassfileParserChunks(classpathOrder), //
                            new WorkUnitProcessor<ClassfileParserChunk>() {
                                @Override
                                public void processWorkUnit(ClassfileParserChunk chunk)
                                        throws InterruptedException, ExecutionException {
                                    ClassfileBinaryParser classfileBinaryParser = null;
                                    try {
                                        classfileBinaryParser = classfileBinaryParserRecycler.acquire();
                                        chunk.classpathElement.parseClassfiles(classfileBinaryParser,
                                                chunk.classfileStartIdx, chunk.classfileEndIdx, stringInternMap,
                                                classInfoUnlinked, log);
                                    } finally {
                                        classfileBinaryParserRecycler.release(classfileBinaryParser);
                                        classfileBinaryParser = null;
                                    }
                                }
                            }, interruptionChecker, log)) {
                        workQueue.startWorkers(executorService, numParallelTasks - 1, log);
                        workQueue.runWorkLoop();
                    }

                    // Build the class graph: convert ClassInfoUnlinked to linked ClassInfo objects.
                    final LogNode classGraphLog = log == null ? null : log.log("Building class graph");
                    final Map<String, ClassInfo> classNameToClassInfo = new HashMap<>();
                    for (final ClassInfoUnlinked c : classInfoUnlinked) {
                        // Create ClassInfo object from ClassInfoUnlinked object, and link into class graph
                        c.link(classNameToClassInfo, classGraphLog);
                    }
                    final ClassGraphBuilder classGraphBuilder = new ClassGraphBuilder(classNameToClassInfo);
                    if (classGraphLog != null) {
                        classGraphLog.addElapsedTime();
                    }

                    // Create ScanResult
                    scanResult = new ScanResult(scanSpec, classpathElementFilesOrdered, classGraphBuilder,
                            fileToLastModified);

                    // Call MatchProcessors 
                    scanSpec.callMatchProcessors(scanResult, classpathOrder, classNameToClassInfo,
                            interruptionChecker, log);
                } else {
                    // This is the result of a call to FastClasspathScanner#getUniqueClasspathElementsAsync(), so
                    // just create placeholder ScanResult to contain classpathElementFilesOrdered.
                    scanResult = new ScanResult(scanSpec, classpathElementFilesOrdered,
                            /* classGraphBuilder = */ null, /* fileToLastModified = */ null);
                }

                if (log != null) {
                    log.log("Completed scan", System.nanoTime() - scanStart);
                }
                return scanResult;
            }
        } finally {
            if (log != null) {
                log.flush();
            }
        }
    }
}
