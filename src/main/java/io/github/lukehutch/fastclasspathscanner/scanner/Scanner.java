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
import java.util.AbstractMap.SimpleEntry;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
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

import io.github.lukehutch.fastclasspathscanner.utils.InterruptionChecker;
import io.github.lukehutch.fastclasspathscanner.utils.JarUtils;
import io.github.lukehutch.fastclasspathscanner.utils.LogNode;
import io.github.lukehutch.fastclasspathscanner.utils.NestedJarHandler;
import io.github.lukehutch.fastclasspathscanner.utils.Recycler;
import io.github.lukehutch.fastclasspathscanner.utils.WorkQueue;
import io.github.lukehutch.fastclasspathscanner.utils.WorkQueue.WorkQueuePreStartHook;
import io.github.lukehutch.fastclasspathscanner.utils.WorkQueue.WorkUnitProcessor;

/** The classpath scanner. */
public class Scanner implements Callable<ScanResult> {
    private final ScanSpec scanSpec;
    private final ExecutorService executorService;
    private final int numParallelTasks;
    private final boolean enableRecursiveScanning;
    private final InterruptionChecker interruptionChecker = new InterruptionChecker();
    private final ScanResultProcessor scanResultProcessor;
    private final FailureHandler failureHandler;
    private final LogNode log;
    private NestedJarHandler nestedJarHandler;

    /**
     * The number of files within a given classpath element (directory or zipfile) to send in a chunk to the workers
     * that are calling the classfile binary parser. The smaller this number is, the better the load leveling at the
     * end of the scan, but the higher the overhead in re-opening the same ZipFile in different worker threads.
     */
    private static final int NUM_FILES_PER_CHUNK = 200;

    /** The classpath scanner. */
    public Scanner(final ScanSpec scanSpec, final ExecutorService executorService, final int numParallelTasks,
            final boolean enableRecursiveScanning, final ScanResultProcessor scannResultProcessor,
            final FailureHandler failureHandler, final LogNode log) {
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
            final RelativePathToElementMap classpathElementMap,
            final HashSet<ClasspathElement> visitedClasspathElts, final ArrayList<ClasspathElement> order)
            throws InterruptedException {
        if (visitedClasspathElts.add(currSingleton)) {
            order.add(currSingleton);
            if (currSingleton.childClasspathElts != null) {
                for (final RelativePath childClasspathElt : currSingleton.childClasspathElts) {
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
    private static List<ClasspathElement> findClasspathOrder(final List<RelativePath> rawClasspathElements,
            final RelativePathToElementMap classpathElementMap) throws InterruptedException {
        // Recurse from toplevel classpath elements to determine a total ordering of classpath elements
        // (jars with Class-Path entries in their manifest file should have those child resources included
        // in-place in the classpath).
        final HashSet<ClasspathElement> visitedClasspathElts = new HashSet<>();
        final ArrayList<ClasspathElement> order = new ArrayList<>();
        for (final RelativePath toplevelClasspathElt : rawClasspathElements) {
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
        this.nestedJarHandler = new NestedJarHandler(interruptionChecker, classpathFinderLog);
        try {
            final long scanStart = System.nanoTime();

            // Get raw classpath elements
            final LogNode getRawElementsLog = classpathFinderLog == null ? null
                    : classpathFinderLog.log("Getting raw classpath elements");
            final ClasspathFinder classpathFinder = new ClasspathFinder(scanSpec, nestedJarHandler,
                    getRawElementsLog);
            final List<RelativePath> rawClasspathEltPathsDedupd = classpathFinder.getRawClasspathElements();
            final ClassLoader[] classLoaderOrder = classpathFinder.getClassLoaderOrder();

            // In parallel, resolve raw classpath elements to canonical paths, creating a ClasspathElement
            // singleton for each unique canonical path. Also check jars against jar whitelist/blacklist.a
            final LogNode preScanLog = classpathFinderLog == null ? null
                    : classpathFinderLog.log("Searching for \"Class-Path:\" entries within manifest files");
            final RelativePathToElementMap classpathElementMap = new RelativePathToElementMap(
                    enableRecursiveScanning, scanSpec, nestedJarHandler, interruptionChecker, preScanLog);
            WorkQueue.runWorkQueue(rawClasspathEltPathsDedupd, executorService, numParallelTasks,
                    new WorkUnitProcessor<RelativePath>() {
                        @Override
                        public void processWorkUnit(final RelativePath rawClasspathEltPath) throws Exception {
                            // Check if classpath element is already in the singleton map -- saves needlessly
                            // repeating work in isValidClasspathElement() and createSingleton()
                            // (need to check for duplicates again, even though we checked above, since
                            // additonal classpath entries can come from Class-Path entries in manifests)
                            if (classpathElementMap.get(rawClasspathEltPath) != null) {
                                if (preScanLog != null) {
                                    preScanLog.log("Ignoring duplicate classpath element: " + rawClasspathEltPath);
                                }
                            } else if (rawClasspathEltPath.isValidClasspathElement(scanSpec, preScanLog)) {
                                try {
                                    final boolean isFile = rawClasspathEltPath.isFile();
                                    final boolean isDir = rawClasspathEltPath.isDirectory();
                                    if (isFile && !scanSpec.scanJars) {
                                        if (preScanLog != null) {
                                            preScanLog.log("Ignoring because jar scanning has been disabled: "
                                                    + rawClasspathEltPath);
                                        }
                                    } else if (isFile
                                            && !scanSpec.jarIsWhitelisted(rawClasspathEltPath.toString())) {
                                        if (preScanLog != null) {
                                            preScanLog
                                                    .log("Ignoring jarfile that is blacklisted or not whitelisted: "
                                                            + rawClasspathEltPath);
                                        }
                                    } else if (isDir && !scanSpec.scanDirs) {
                                        if (preScanLog != null) {
                                            preScanLog.log("Ignoring because directory scanning has been disabled: "
                                                    + rawClasspathEltPath);
                                        }
                                    } else {
                                        // Classpath element is valid, add as a singleton.
                                        // This will trigger calling the ClasspathElementZip constructor in the
                                        // case of jarfiles, which will check the manifest file for Class-Path
                                        // entries, and if any are found, additional work units will be added
                                        // to the work queue to scan those jarfiles too. If Class-Path entries
                                        // are found, they are added as child elements of the current classpath
                                        // element, so that they can be inserted at the correct location in the
                                        // classpath order.
                                        classpathElementMap.createSingleton(rawClasspathEltPath);
                                    }
                                } catch (final Exception e) {
                                    // Could not create singleton, probably due to path canonicalization problem
                                    preScanLog.log("Classpath element " + rawClasspathEltPath + " is not valid ("
                                            + e + ") -- skipping");
                                }
                            }
                        }
                    }, new WorkQueuePreStartHook<RelativePath>() {
                        @Override
                        public void processWorkQueueRef(final WorkQueue<RelativePath> workQueue) {
                            // Store a ref back to the work queue in the classpath element map,
                            // because some classpath elements will need to schedule additional
                            // classpath elements for scanning, e.g. "Class-Path:" refs in jar
                            // manifest files
                            classpathElementMap.setWorkQueue(workQueue);
                        }
                    }, interruptionChecker, preScanLog);

            // Determine total ordering of classpath elements, inserting jars referenced in manifest Class-Path
            // entries in-place into the ordering, if they haven't been listed earlier in the classpath already.
            final List<ClasspathElement> classpathOrder = findClasspathOrder(rawClasspathEltPathsDedupd,
                    classpathElementMap);

            // Print final classpath element order, after inserting Class-Path entries from manifest files 
            if (classpathFinderLog != null) {
                final LogNode logNode = classpathFinderLog.log("Final classpath element order:");
                for (int i = 0; i < classpathOrder.size(); i++) {
                    final ClasspathElement classpathElt = classpathOrder.get(i);
                    logNode.log(i + ": " + classpathElt);
                }
            }

            ScanResult scanResult;
            if (enableRecursiveScanning) {

                // Find classpath elements that are path prefixes of other classpath elements
                final List<SimpleEntry<String, ClasspathElement>> classpathEltResolvedPathToElement = new ArrayList<>();
                for (int i = 0; i < classpathOrder.size(); i++) {
                    final ClasspathElement classpathElement = classpathOrder.get(i);
                    classpathEltResolvedPathToElement.add(new SimpleEntry<>(
                            classpathElement.classpathEltPath.getResolvedPath(), classpathElement));
                }
                Collections.sort(classpathEltResolvedPathToElement,
                        new Comparator<SimpleEntry<String, ClasspathElement>>() {
                            // Sort classpath elements into lexicographic order
                            @Override
                            public int compare(final SimpleEntry<String, ClasspathElement> o1,
                                    final SimpleEntry<String, ClasspathElement> o2) {
                                // Path strings will all be unique
                                return o1.getKey().compareTo(o2.getKey());
                            }
                        });
                LogNode nestedClasspathRootNode = null;
                for (int i = 0; i < classpathEltResolvedPathToElement.size(); i++) {
                    // See if each classpath element is a prefix of any others (if so, they will immediately follow
                    // in lexicographic order)
                    final SimpleEntry<String, ClasspathElement> ei = classpathEltResolvedPathToElement.get(i);
                    final String basePath = ei.getKey();
                    final int basePathLen = basePath.length();
                    for (int j = i + 1; j < classpathEltResolvedPathToElement.size(); j++) {
                        final SimpleEntry<String, ClasspathElement> ej = classpathEltResolvedPathToElement.get(j);
                        final String comparePath = ej.getKey();
                        final int comparePathLen = comparePath.length();
                        boolean foundNestedClasspathRoot = false;
                        if (comparePath.startsWith(basePath) && comparePathLen > basePathLen) {
                            // Require a separator after the prefix
                            final char nextChar = comparePath.charAt(basePathLen);
                            if (nextChar == '/' || nextChar == '!') {
                                // basePath is a path prefix of comparePath.
                                // Ensure that the nested classpath does not contain another '!' zip-separator
                                // (since classpath scanning does not recurse to jars-within-jars unless they
                                // are explicitly listed on the classpath)
                                final String nestedClasspathRelativePath = comparePath.substring(basePathLen + 1);
                                if (nestedClasspathRelativePath.indexOf('!') < 0) {
                                    // Ensure that the nested classpath is not a jar, since we only care
                                    // about cases where the nested classpath root is a dir, whether or
                                    // not the outer classpath element is a dir or jar
                                    if (!JarUtils.isJar(nestedClasspathRelativePath)) {
                                        // Found a nested classpath root
                                        foundNestedClasspathRoot = true;
                                        // Store link from prefix element to nested elements
                                        final ClasspathElement baseElement = ei.getValue();
                                        if (baseElement.nestedClasspathRoots == null) {
                                            baseElement.nestedClasspathRoots = new HashSet<>();
                                        }
                                        baseElement.nestedClasspathRoots.add(nestedClasspathRelativePath + "/");
                                        if (classpathFinderLog != null) {
                                            if (nestedClasspathRootNode == null) {
                                                nestedClasspathRootNode = classpathFinderLog
                                                        .log("Found nested classpath elements");
                                            }
                                            nestedClasspathRootNode.log(
                                                    basePath + " is a prefix of the nested element " + comparePath);
                                        }
                                    }
                                }
                            }
                        }
                        if (!foundNestedClasspathRoot) {
                            // After the first non-match, there can be no more prefix matches in the sorted order
                            break;
                        }
                    }
                }

                // Scan for matching classfiles / files, looking only at filenames / file paths, and not contents
                final LogNode pathScanLog = classpathFinderLog == null ? null
                        : classpathFinderLog.log("Scanning filenames within classpath elements");
                WorkQueue.runWorkQueue(classpathOrder, executorService, numParallelTasks,
                        new WorkUnitProcessor<ClasspathElement>() {
                            @Override
                            public void processWorkUnit(final ClasspathElement classpathElement) throws Exception {
                                // Scan the paths within a directory or jar
                                classpathElement.scanPaths(pathScanLog);
                            }
                        }, interruptionChecker, pathScanLog);

                // Implement classpath masking -- if the same relative classfile  path occurs multiple times
                // in the classpath, ignore (remove) the second and subsequent occurrences. Note that
                // classpath masking is performed whether or not a jar is whitelisted, and whether or not
                // jar or dir scanning is enabled, in order to ensure that class references passed into
                // MatchProcessors are the same as those that would be loaded by standard classloading.
                // (See bug #100.)
                final LogNode maskLog = log == null ? null : log.log("Masking classpath files");
                final HashSet<String> classpathRelativePathsFound = new HashSet<>();
                for (int classpathIdx = 0; classpathIdx < classpathOrder.size(); classpathIdx++) {
                    final ClasspathElement classpathElement = classpathOrder.get(classpathIdx);
                    classpathElement.maskFiles(classpathIdx, classpathRelativePathsFound, maskLog);
                }

                // Merge the maps from file to timestamp across all classpath elements
                // (there will be no overlap in keyspace, since file masking was already performed)
                final Map<File, Long> fileToLastModified = new HashMap<>();
                for (final ClasspathElement classpathElement : classpathOrder) {
                    fileToLastModified.putAll(classpathElement.fileToLastModified);
                }

                // Scan classfile binary headers in parallel
                final ConcurrentLinkedQueue<ClassInfoUnlinked> classInfoUnlinked = //
                        new ConcurrentLinkedQueue<>();
                final ConcurrentHashMap<String, String> stringInternMap = new ConcurrentHashMap<>();
                final LogNode classfileScanLog = log == null ? null : log.log("Scanning classfile binary headers");
                try (final Recycler<ClassfileBinaryParser, RuntimeException> classfileBinaryParserRecycler = //
                        new Recycler<ClassfileBinaryParser, RuntimeException>() {
                            @Override
                            public ClassfileBinaryParser newInstance() {
                                return new ClassfileBinaryParser();
                            }
                        }) {
                    final List<ClassfileParserChunk> classfileParserChunks = getClassfileParserChunks(
                            classpathOrder);
                    WorkQueue.runWorkQueue(classfileParserChunks, executorService, numParallelTasks,
                            new WorkUnitProcessor<ClassfileParserChunk>() {
                                @Override
                                public void processWorkUnit(final ClassfileParserChunk chunk) throws Exception {
                                    ClassfileBinaryParser classfileBinaryParser = null;
                                    try {
                                        classfileBinaryParser = classfileBinaryParserRecycler.acquire();
                                        chunk.classpathElement.parseClassfiles(classfileBinaryParser,
                                                chunk.classfileStartIdx, chunk.classfileEndIdx, stringInternMap,
                                                classInfoUnlinked, classfileScanLog);
                                    } finally {
                                        classfileBinaryParserRecycler.release(classfileBinaryParser);
                                        classfileBinaryParser = null;
                                    }
                                }
                            }, interruptionChecker, classfileScanLog);
                }

                // Build the class graph: convert ClassInfoUnlinked to linked ClassInfo objects.
                final LogNode classGraphLog = log == null ? null : log.log("Building class graph");
                final Map<String, ClassInfo> classNameToClassInfo = new HashMap<>();
                for (final ClassInfoUnlinked c : classInfoUnlinked) {
                    // Need to do two passes, so that annotation default parameter vals are available when linking
                    // non-attribute classes. In first pass, link annotations with default parameter vals.
                    if (c.annotationParamDefaultValues != null) {
                        c.link(scanSpec, classNameToClassInfo, classGraphLog);
                    }
                }
                for (final ClassInfoUnlinked c : classInfoUnlinked) {
                    // In second pass, link everything else.
                    if (c.annotationParamDefaultValues == null) {
                        // Create ClassInfo object from ClassInfoUnlinked object, and link into class graph
                        c.link(scanSpec, classNameToClassInfo, classGraphLog);
                    }
                }
                final ClassGraphBuilder classGraphBuilder = new ClassGraphBuilder(scanSpec, classNameToClassInfo);
                if (classGraphLog != null) {
                    classGraphLog.addElapsedTime();
                }

                // Create ScanResult
                scanResult = new ScanResult(scanSpec, classpathOrder, classLoaderOrder, classGraphBuilder,
                        fileToLastModified, nestedJarHandler, interruptionChecker, log);

                // Run scanResultProcessor in the current thread
                if (scanResultProcessor != null) {
                    scanResultProcessor.processScanResult(scanResult);
                }

            } else {
                // This is the result of a call to FastClasspathScanner#getUniqueClasspathElementsAsync(), so
                // just create placeholder ScanResult to contain classpathElementFilesOrdered.
                scanResult = new ScanResult(scanSpec, classpathOrder, classLoaderOrder,
                        /* classGraphBuilder = */ null, /* fileToLastModified = */ null, nestedJarHandler,
                        interruptionChecker, log);
            }
            if (log != null) {
                log.log("Completed scan", System.nanoTime() - scanStart);
            }

            // No exceptions were thrown -- return scan result
            return scanResult;

        } catch (final Throwable e) {
            // Remove temporary files if an exception was thrown
            if (this.nestedJarHandler != null) {
                this.nestedJarHandler.close(log);
            }
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
