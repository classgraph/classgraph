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
import java.net.URL;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;

import io.github.lukehutch.fastclasspathscanner.utils.FastPathResolver;
import io.github.lukehutch.fastclasspathscanner.utils.InterruptionChecker;
import io.github.lukehutch.fastclasspathscanner.utils.JarUtils;
import io.github.lukehutch.fastclasspathscanner.utils.LogNode;
import io.github.lukehutch.fastclasspathscanner.utils.NestedJarHandler;
import io.github.lukehutch.fastclasspathscanner.utils.Recycler;
import io.github.lukehutch.fastclasspathscanner.utils.WorkQueue;
import io.github.lukehutch.fastclasspathscanner.utils.WorkQueue.WorkUnitProcessor;

/** The classpath scanner. */
public class Scanner implements Callable<ScanResult> {
    private final boolean removeTemporaryFilesAfterScan;
    private final ScanSpec scanSpec;
    private final ExecutorService executorService;
    private final int numParallelTasks;
    private final boolean enableRecursiveScanning;
    private final InterruptionChecker interruptionChecker = new InterruptionChecker();
    private final ScanResultProcessor scanResultProcessor;
    private final FailureHandler failureHandler;
    private final LogNode log;

    /**
     * The number of files within a given classpath element (directory or zipfile) to send in a chunk to the workers
     * that are calling the classfile binary parser. The smaller this number is, the better the load leveling at the
     * end of the scan, but the higher the overhead in re-opening the same ZipFile in different worker threads.
     */
    private static final int NUM_FILES_PER_CHUNK = 200;

    /** The classpath scanner. */
    public Scanner(final ScanSpec scanSpec, final ExecutorService executorService, final int numParallelTasks,
            final boolean enableRecursiveScanning, final boolean removeTemporaryFilesAfterScan,
            final ScanResultProcessor scannResultProcessor, final FailureHandler failureHandler,
            final LogNode log) {
        this.removeTemporaryFilesAfterScan = removeTemporaryFilesAfterScan;
        this.scanSpec = scanSpec;
        this.executorService = executorService;
        this.numParallelTasks = numParallelTasks;
        this.enableRecursiveScanning = enableRecursiveScanning;
        this.scanResultProcessor = scannResultProcessor;
        this.failureHandler = failureHandler;
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
     * NUM_FILES_PER_CHUNK files. This helps with load leveling so that the worker threads all complete their work
     * at approximately the same time.
     */
    private static List<ClassfileParserChunk> getClassfileParserChunks(
            final List<ClasspathElement> classpathOrder) {
        LinkedList<LinkedList<ClassfileParserChunk>> chunks = new LinkedList<>();
        for (final ClasspathElement classpathElement : classpathOrder) {
            final LinkedList<ClassfileParserChunk> chunksForClasspathElt = new LinkedList<>();
            final int numClassfileMatches = classpathElement.getNumClassfileMatches();
            if (numClassfileMatches > 0) {
                final int numChunks = (int) Math.ceil((float) numClassfileMatches / (float) NUM_FILES_PER_CHUNK);
                final float filesPerChunk = (float) numClassfileMatches / (float) numChunks;
                for (int i = 0; i < numChunks; i++) {
                    final int classfileStartIdx = (int) (i * filesPerChunk);
                    final int classfileEndIdx = i < numChunks - 1 ? (int) ((i + 1) * filesPerChunk)
                            : numClassfileMatches;
                    if (classfileEndIdx > classfileStartIdx) {
                        chunksForClasspathElt.add(
                                new ClassfileParserChunk(classpathElement, classfileStartIdx, classfileEndIdx));
                    }
                }
            }
            chunks.add(chunksForClasspathElt);
        }
        // There should be no overlap between the relative paths in any of the chunks, because classpath masking
        // has already been applied, so these chunks can be scanned in any order. But since a ZipFile instance
        // can only be used by one thread at a time, we want to space the chunks for a given ZipFile as far apart
        // as possible in the work queue to minimize the chance that two threads will try to open the same ZipFile
        // at the same time, as this will cause a second copy of the ZipFile to have to be opened by the ZipFile
        // recycler. The combination of chunking and interleaving therefore lets us achieve load leveling without
        // work stealing or other more complex mechanism.
        final List<ClassfileParserChunk> interleavedChunks = new ArrayList<>();
        while (!chunks.isEmpty()) {
            final LinkedList<LinkedList<ClassfileParserChunk>> nextChunks = new LinkedList<>();
            for (final LinkedList<ClassfileParserChunk> chunksForClasspathElt : chunks) {
                if (!chunksForClasspathElt.isEmpty()) {
                    final ClassfileParserChunk head = chunksForClasspathElt.remove();
                    interleavedChunks.add(head);
                    if (!chunksForClasspathElt.isEmpty()) {
                        nextChunks.add(chunksForClasspathElt);
                    }
                }
            }
            chunks = nextChunks;
        }
        return interleavedChunks;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Determine the unique ordered classpath elements, and run a scan looking for file or classfile matches if
     * necessary.
     */
    @Override
    public ScanResult call() throws InterruptedException, ExecutionException {
        final LogNode classpathFinderLog = log == null ? null : log.log("Finding classpath entries");
        try (NestedJarHandler nestedJarHandler = new NestedJarHandler(removeTemporaryFilesAfterScan,
                interruptionChecker, classpathFinderLog)) {
            final long scanStart = System.nanoTime();

            // Get current dir (without resolving symlinks), and normalize path by calling
            // FastPathResolver.resolve()
            String currDirPathStr = "";
            try {
                // Done in steps, so we can provide fine-grained debug info if the current dir doesn't exist (#109)
                Path currDirPath = Paths.get("").toAbsolutePath();
                currDirPathStr = currDirPath.toString();
                currDirPath = currDirPath.normalize();
                currDirPathStr = currDirPath.toString();
                currDirPath = currDirPath.toRealPath(LinkOption.NOFOLLOW_LINKS);
                currDirPathStr = currDirPath.toString();
                currDirPathStr = FastPathResolver.resolve(currDirPathStr);
            } catch (final IOException e) {
                throw new RuntimeException("Could not resolve current directory: " + currDirPathStr, e);
            }

            // Get raw classpath elements
            final List<String> rawClasspathElementPathStrs = new ClasspathFinder(scanSpec, classpathFinderLog)
                    .getRawClasspathElements();

            // Create ClasspathElement objects for each raw classpath element path
            final List<ClasspathRelativePath> rawClasspathElements = new ArrayList<>();
            for (final String rawClasspathElementPathStr : rawClasspathElementPathStrs) {
                // Resolve classpath elements relative to current dir, so that paths like "." are handled.
                final ClasspathRelativePath classpathElt = new ClasspathRelativePath(currDirPathStr,
                        rawClasspathElementPathStr, nestedJarHandler);
                rawClasspathElements.add(classpathElt);
            }

            // In parallel, resolve raw classpath elements to canonical paths, creating a ClasspathElement
            // singleton for each unique canonical path.
            final ClasspathRelativePathToElementMap classpathElementMap = new ClasspathRelativePathToElementMap(
                    enableRecursiveScanning, scanSpec, nestedJarHandler, interruptionChecker, classpathFinderLog);
            try (WorkQueue<ClasspathRelativePath> workQueue = new WorkQueue<>(rawClasspathElements,
                    new WorkUnitProcessor<ClasspathRelativePath>() {
                        @Override
                        public void processWorkUnit(ClasspathRelativePath rawClasspathElt) throws Exception {
                            // Check if classpath element is already in the singleton map -- saves needlessly
                            // repeating work in isValidClasspathElement() and createSingleton()
                            if (classpathElementMap.get(rawClasspathElt) != null) {
                                if (classpathFinderLog != null) {
                                    classpathFinderLog.log("Ignoring duplicate classpath element: "
                                            + rawClasspathElt.getResolvedPath());
                                }
                            } else if (rawClasspathElt.isValidClasspathElement(scanSpec, classpathFinderLog)) {
                                try {
                                    classpathElementMap.createSingleton(rawClasspathElt);
                                } catch (Exception e) {
                                    // Could not create singleton, probably due to path canonicalization problem
                                    classpathFinderLog.log("Classpath element " + rawClasspathElt
                                            + " is not valid (" + e + ") -- skipping");
                                }
                            }
                        }
                    }, interruptionChecker, classpathFinderLog)) {
                classpathElementMap.setWorkQueue(workQueue);
                // Start workers, then use this thread to do work too, in case there is only one thread
                // available in the ExecutorService
                workQueue.startWorkers(executorService, numParallelTasks - 1, classpathFinderLog);
                workQueue.runWorkLoop();
            }

            // Determine total ordering of classpath elements, inserting jars referenced in manifest Class-Path
            // entries in-place into the ordering, if they haven't been listed earlier in the classpath already.
            List<ClasspathElement> classpathOrder = findClasspathOrder(rawClasspathElements, classpathElementMap);

            // If system jars are not blacklisted, need to manually add rt.jar at the beginning of the classpath,
            // because it is included implicitly by the JVM.
            if (!scanSpec.blacklistSystemJars()) {
                // There should only be zero or one of these.
                final String rtJarPath = JarUtils.getRtJarPath();
                if (rtJarPath != null) {
                    // Insert rt.jar as the zeroth entry in the classpath.
                    classpathOrder.add(0,
                            ClasspathElement.newInstance(
                                    new ClasspathRelativePath(currDirPathStr, rtJarPath, nestedJarHandler),
                                    enableRecursiveScanning, scanSpec, nestedJarHandler, /* workQueue = */ null,
                                    interruptionChecker, classpathFinderLog));
                }
            }

            if (enableRecursiveScanning) {
                final HashSet<String> classpathRelativePathsFound = new HashSet<>();
                final ArrayList<ClasspathElement> classpathOrderFiltered = new ArrayList<>();
                final ArrayList<URL> classpathOrderURLsFiltered = new ArrayList<>();
                for (int classpathIdx = 0; classpathIdx < classpathOrder.size(); classpathIdx++) {
                    final ClasspathElement classpathElement = classpathOrder.get(classpathIdx);
                    // Implement classpath masking -- if the same relative path occurs multiple times in the
                    // classpath, ignore (remove) the second and subsequent occurrences. Note that classpath
                    // masking is performed whether or not a jar is whitelisted, and whether or not jar or
                    // dir scanning is enabled, in order to ensure that class references passed into
                    // MatchProcessors are the same as those that would be loaded by standard classloading.
                    // (See bug #100.)
                    classpathElement.maskFiles(classpathIdx, classpathRelativePathsFound, log);

                    // Check whether a given classpath element should be scheduled for scanning or not.
                    // A classpath element is not scanned if (1) it is a jar, and jar scanning is disabled,
                    // or a jar whitelist was provided in the scan spec, and a given jar is not whitelisted;
                    // (2) it is a directory, and directory scanning is disabled. 
                    if (classpathElement.classpathElementFile.isFile() && !scanSpec.scanJars) {
                        if (log != null) {
                            log.log(String.format("%06d-2", classpathIdx),
                                    "Ignoring jarfile, because jar scanning has been disabled: "
                                            + classpathElement.classpathElementFile);
                        }
                    } else if (classpathElement.classpathElementFile.isFile()
                            && !scanSpec.jarIsWhitelisted(classpathElement.classpathElementFile.getName())) {
                        if (log != null) {
                            log.log(String.format("%06d-2", classpathIdx),
                                    "Ignoring jarfile, because it is not whitelisted: "
                                            + classpathElement.classpathElementFile);
                        }
                    } else if (classpathElement.classpathElementFile.isDirectory() && !scanSpec.scanDirs) {
                        if (log != null) {
                            log.log(String.format("%06d-2", classpathIdx),
                                    "Ignoring directory, because directory scanning has been disabled: "
                                            + classpathElement.classpathElementFile);
                        }
                    } else {
                        classpathOrderFiltered.add(classpathElement);
                        classpathOrderURLsFiltered.add(classpathElement.classpathElementURL);
                    }
                }
                classpathOrder = classpathOrderFiltered;
            }

            if (log != null) {
                final LogNode logNode = log.log("Classpath element order:");
                for (int i = 0; i < classpathOrder.size(); i++) {
                    final ClasspathElement classpathElt = classpathOrder.get(i);
                    logNode.log(i + ": " + classpathElt);
                }
            }

            ScanResult scanResult;
            if (enableRecursiveScanning) {
                // Merge the maps from file to timestamp across all classpath elements
                final Map<File, Long> fileToLastModified = new HashMap<>();
                for (final ClasspathElement classpathElement : classpathOrder) {
                    fileToLastModified.putAll(classpathElement.fileToLastModified);
                }

                // Scan classfile binary headers in parallel
                final ConcurrentLinkedQueue<ClassInfoUnlinked> classInfoUnlinked = //
                        new ConcurrentLinkedQueue<>();
                final ConcurrentHashMap<String, String> stringInternMap = new ConcurrentHashMap<>();
                try (final Recycler<ClassfileBinaryParser, RuntimeException> classfileBinaryParserRecycler = //
                        new Recycler<ClassfileBinaryParser, RuntimeException>() {
                            @Override
                            public ClassfileBinaryParser newInstance() {
                                return new ClassfileBinaryParser();
                            }
                        };
                        WorkQueue<ClassfileParserChunk> workQueue = new WorkQueue<>(
                                getClassfileParserChunks(classpathOrder), //
                                new WorkUnitProcessor<ClassfileParserChunk>() {
                                    @Override
                                    public void processWorkUnit(ClassfileParserChunk chunk) throws Exception {
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
                    c.link(scanSpec, classNameToClassInfo, classGraphLog);
                }
                final ClassGraphBuilder classGraphBuilder = new ClassGraphBuilder(scanSpec, classNameToClassInfo);
                if (classGraphLog != null) {
                    classGraphLog.addElapsedTime();
                }

                // Create ScanResult
                scanResult = new ScanResult(scanSpec, classpathOrder, classGraphBuilder, fileToLastModified,
                        interruptionChecker, log);

                // Run scanResultProcessor in the current thread
                if (scanResultProcessor != null) {
                    scanResultProcessor.processScanResult(scanResult);
                }

            } else {
                // This is the result of a call to FastClasspathScanner#getUniqueClasspathElementsAsync(), so
                // just create placeholder ScanResult to contain classpathElementFilesOrdered.
                scanResult = new ScanResult(scanSpec, classpathOrder, /* classGraphBuilder = */ null,
                        /* fileToLastModified = */ null, interruptionChecker, log);
            }
            if (log != null) {
                log.log("Completed scan", System.nanoTime() - scanStart);
            }

            // No exceptions were thrown -- return scan result
            return scanResult;

        } catch (final Throwable e) {
            if (log != null) {
                log.log(e);
            }
            if (failureHandler == null) {
                throw e;
            } else {
                failureHandler.onFailure(e);
                // Return null from the Future if a FailureHandler was added and there was an exception
                return null;
            }

        } finally {
            if (log != null) {
                log.flush();
            }
        }
    }
}
