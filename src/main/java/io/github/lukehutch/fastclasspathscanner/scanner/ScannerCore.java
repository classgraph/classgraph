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
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.LinkOption;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import io.github.lukehutch.fastclasspathscanner.FastClasspathScanner;
import io.github.lukehutch.fastclasspathscanner.scanner.ClasspathElementVerifierWorkQueue.ClasspathElementSingleton;
import io.github.lukehutch.fastclasspathscanner.scanner.ClasspathElementVerifierWorkQueue.ClasspathElementSingletonMap;
import io.github.lukehutch.fastclasspathscanner.scanner.RecursiveScanner.RecursiveScanResult;
import io.github.lukehutch.fastclasspathscanner.utils.FastPathResolver;
import io.github.lukehutch.fastclasspathscanner.utils.LoggedThread;
import io.github.lukehutch.fastclasspathscanner.utils.Recycler;
import io.github.lukehutch.fastclasspathscanner.utils.WorkQueue;
import io.github.lukehutch.fastclasspathscanner.utils.ZipFileRecycler;
import io.github.lukehutch.fastclasspathscanner.utils.ZipFileRecycler.ZipFileCacheEntry;

public class ScannerCore extends LoggedThread<ScanResult> {
    /** The scanning specification. */
    private final ScanSpec scanSpec;

    /** The executor service. */
    private final ExecutorService executorService;

    /** The number of parallel tasks. */
    private final int numParallelTasks;

    /** Whether to recursively scan contents of classpath elements. */
    private final boolean enableRecursiveScanning;

    /**
     * The number of files within a given classpath element (directory or zipfile) to send in a chunk to the workers
     * that are calling the classfile binary parser.
     */
    private static final int NUM_FILES_PER_CHUNK = 100;  // TODO: using multiple concurrent ZipFile readers does not seem to be efficient 

    // -------------------------------------------------------------------------------------------------------------

    public ScannerCore(final ScanSpec scanSpec, final ExecutorService executorService, final int numParallelTasks,
            final boolean enableRecursiveScanning) {
        this.scanSpec = scanSpec;
        this.executorService = executorService;
        this.numParallelTasks = Math.max(numParallelTasks, 1);
        this.enableRecursiveScanning = enableRecursiveScanning;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Recursively perform a depth-first search of jar interdependencies, breaking cycles if necessary, to determine
     * the final classpath element order.
     */
    private static void findClasspathOrder(final String canonicalPath,
            final ConcurrentHashMap<String, List<String>> canonicalPathToChildCanonicalPaths,
            final ClasspathElementSingletonMap canonicalPathToClasspathElementSingleton,
            final HashSet<String> visitedCanonicalPaths, final ArrayList<ClasspathElement> order) {
        final List<String> childPaths = canonicalPathToChildCanonicalPaths.get(canonicalPath);
        if (childPaths != null) {
            for (final String childPath : childPaths) {
                if (visitedCanonicalPaths.add(childPath)) {
                    final ClasspathElementSingleton childPathClasspathElementSingleton = //
                            canonicalPathToClasspathElementSingleton.get(childPath);
                    if (childPathClasspathElementSingleton != null) {
                        order.add(childPathClasspathElementSingleton.classpathElt);
                        findClasspathOrder(childPath, canonicalPathToChildCanonicalPaths,
                                canonicalPathToClasspathElementSingleton, visitedCanonicalPaths, order);
                    }
                }
            }
        }
    }

    /**
     * Recursively perform a depth-first search of jar interdependencies, breaking cycles if necessary, to determine
     * the final classpath element order.
     */
    private static List<ClasspathElement> findClasspathOrder(
            final ConcurrentHashMap<String, List<String>> canonicalPathToChildCanonicalPaths,
            final ClasspathElementSingletonMap canonicalPathToClasspathElementSingleton) {
        final HashSet<String> visitedCanonicalPaths = new HashSet<>();
        visitedCanonicalPaths.add("");
        final ArrayList<ClasspathElement> order = new ArrayList<>();
        findClasspathOrder("", canonicalPathToChildCanonicalPaths, canonicalPathToClasspathElementSingleton,
                visitedCanonicalPaths, order);
        return order;
    }

    // -------------------------------------------------------------------------------------------------------------

    /** Parse the binary headers of a chunk of classfiles within a classpath resource (dir or zipfile). */
    private static class ClassfileParserChunkWorkQueue extends WorkQueue<ClassfileParserChunk> {
        private final ScanSpec scanSpec;
        private final ZipFileRecycler zipFileCache;
        private final Recycler<ClassfileBinaryParser> classfileBinaryParserRecycler;
        private final ConcurrentHashMap<String, String> stringInternMap;
        private final ConcurrentLinkedQueue<ClassInfoUnlinked> classInfoUnlinked;

        public ClassfileParserChunkWorkQueue(final ScanSpec scanSpec, final ZipFileRecycler zipFileCache,
                final Recycler<ClassfileBinaryParser> classfileBinaryParserRecycler,
                final ConcurrentHashMap<String, String> stringInternMap,
                final ConcurrentLinkedQueue<ClassInfoUnlinked> classInfoUnlinked, final ThreadLog log) {
            super(log);
            this.scanSpec = scanSpec;
            this.zipFileCache = zipFileCache;
            this.classfileBinaryParserRecycler = classfileBinaryParserRecycler;
            this.stringInternMap = stringInternMap;
            this.classInfoUnlinked = classInfoUnlinked;
        }

        @Override
        public BlockingQueue<ClassfileParserChunk> createQueue() {
            return new LinkedBlockingQueue<>();
        }

        @Override
        public void processWorkUnit(final ClassfileParserChunk chunk) throws InterruptedException {
            final ClasspathElement classpathElt = chunk.classpathElementSingleton.classpathElt;
            final boolean isJar = classpathElt.isFile();
            final List<ClasspathResource> classfileMatches = chunk.classpathElementSingleton //
                    .recursiveScanResult.classfileMatches;
            ZipFileCacheEntry zipFileCacheEntry = null;
            ZipFile zipFile = null;
            try {
                // If this is a jarfile
                if (isJar) {
                    // Acquire or open a ZipFile object for this classpath element
                    zipFileCacheEntry = zipFileCache.get(classpathElt.getCanonicalPath());
                    zipFile = zipFileCacheEntry.acquire();
                }
                // Run through the relative paths in this chunk
                for (int i = chunk.classfileStartIdx; i < chunk.classfileEndIdx; i++) {
                    // Get resource within classpath element
                    final ClasspathResource classpathResource = classfileMatches.get(i);
                    // Create InputStream for resource
                    InputStream inputStream = null;
                    ClassfileBinaryParser classfileBinaryParser = null;
                    try {
                        if (isJar) {
                            final ZipEntry zipEntry = zipFile.getEntry(classpathResource.relativePath);
                            inputStream = zipFile.getInputStream(zipEntry);
                        } else {
                            inputStream = new FileInputStream(classpathResource.relativePathFile);
                        }

                        // Parse classpath binary format, creating a ClassInfoUnlinked object
                        classfileBinaryParser = classfileBinaryParserRecycler.acquire();
                        final ClassInfoUnlinked thisClassInfoUnlinked = classfileBinaryParser
                                .readClassInfoFromClassfileHeader(classpathResource.relativePath, inputStream,
                                        scanSpec.getClassNameToStaticFinalFieldsToMatch(), stringInternMap);
                        // If class was successfully read, output new ClassInfoUnlinked object
                        if (thisClassInfoUnlinked != null) {
                            classInfoUnlinked.add(thisClassInfoUnlinked);
                            thisClassInfoUnlinked.logTo(log);
                        }
                        if (interrupted()) {
                            throw new InterruptedException();
                        }
                    } catch (final IOException e) {
                        if (FastClasspathScanner.verbose) {
                            log.log("Exception while trying to open " + classpathElt.getCanonicalPath()
                                    + (isJar ? "!" : "/") + classpathElt.relativePath, e);
                        }
                    } finally {
                        classfileBinaryParserRecycler.release(classfileBinaryParser);
                        classfileBinaryParser = null;
                        if (inputStream != null) {
                            try {
                                inputStream.close();
                            } catch (final IOException e) {
                                if (FastClasspathScanner.verbose) {
                                    log.log("Exception while trying to close " + classpathElt.getCanonicalPath()
                                            + (isJar ? "!" : "/") + classpathElt.relativePath, e);
                                }
                            }
                            inputStream = null;
                        }
                    }
                }
            } finally {
                if (zipFileCacheEntry != null) {
                    zipFileCacheEntry.release(zipFile);
                    zipFile = null;
                }
            }
        }
    }

    /** Holds range limits for chunks of classpath files that need to be scanned in a given classpath element. */
    private static class ClassfileParserChunk {
        private final ClasspathElementSingleton classpathElementSingleton;
        private final int classfileStartIdx;
        private final int classfileEndIdx;

        public ClassfileParserChunk(final ClasspathElementSingleton classpathElementSingleton,
                final int classfileStartIdx, final int classfileEndIdx) {
            this.classpathElementSingleton = classpathElementSingleton;
            this.classfileStartIdx = classfileStartIdx;
            this.classfileEndIdx = classfileEndIdx;
        }
    }

    /**
     * Break the classfiles that need to be scanned in each classpath element into chunks of approximately
     * NUM_FILES_PER_CHUNK files.
     */
    private static List<ClassfileParserChunk> getClassfileParserChunks(
            final List<ClasspathElementSingleton> classpathSingletonOrder) {
        final List<ClassfileParserChunk> chunks = new ArrayList<>();
        for (final ClasspathElementSingleton classpathElementSingleton : classpathSingletonOrder) {
            final List<ClasspathResource> classfileMatches = //
                    classpathElementSingleton.recursiveScanResult.classfileMatches;
            final int numClassfiles = classfileMatches.size();
            if (numClassfiles > 0) {
                final int numChunks = (int) Math.ceil((float) numClassfiles / (float) NUM_FILES_PER_CHUNK);
                final float filesPerChunk = (float) numClassfiles / (float) numChunks;
                for (int i = 0; i < numChunks; i++) {
                    final int classfileStartIdx = (int) (i * filesPerChunk);
                    final int classfileEndIdx = (int) ((i + 1) * filesPerChunk);
                    chunks.add(new ClassfileParserChunk(classpathElementSingleton, classfileStartIdx,
                            classfileEndIdx));
                }
            }
        }
        return chunks;
    }

    // -------------------------------------------------------------------------------------------------------------

    /** Determine and return the unique ordered classpath elements. */
    @Override
    public ScanResult doWork() throws InterruptedException {
        final long scanStart = System.nanoTime();

        final List<File> classpathElementFilesOrdered = new ArrayList<>();

        // Get raw classpath elements
        final List<String> rawClasspathElementPaths = new ClasspathFinder(scanSpec, log).getRawClasspathElements();

        // Get current dir (without resolving symlinks), and normalize path by calling FastPathResolver.resolve()
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
        canonicalPathToChildCanonicalPaths.put("", rawClasspathElementPaths);

        // Map to hold a singleton object for each canonical path in the classpath, so that directories/zipfiles
        // only get scanned once.
        final ClasspathElementSingletonMap canonicalPathToClasspathElementSingleton = //
                new ClasspathElementSingletonMap();

        // Recycle object instances across threads for efficiency
        try (ZipFileRecycler zipFileRecycler = new ZipFileRecycler(log);
                Recycler<ClassfileBinaryParser> classfileBinaryParserRecycler = //
                        new Recycler<ClassfileBinaryParser>() {
                            @Override
                            public ClassfileBinaryParser newInstance() {
                                return new ClassfileBinaryParser(scanSpec, log);
                            }
                        }) {
            // In parallel, resolve classpath elements to canonical paths, creating a ClasspathElementSingleton
            // for each unique canonical path, and if the elements are jarfiles, read the manifest file if present.
            // If enableRecursiveScanning is true, also recursively scan files in each classpath element, looking
            // for file path matches.
            try (final ClasspathElementVerifierWorkQueue workQueue = new ClasspathElementVerifierWorkQueue(scanSpec,
                    canonicalPathToChildCanonicalPaths, canonicalPathToClasspathElementSingleton, zipFileRecycler,
                    enableRecursiveScanning, log)) {

                // Create initial work units from raw classpath elements
                for (int eltIdx = 0; eltIdx < rawClasspathElementPaths.size(); eltIdx++) {
                    final String rawClasspathElementPath = rawClasspathElementPaths.get(eltIdx);
                    final ClasspathElement rawClasspathElt = new ClasspathElement(currentDirPath, currentDirPath,
                            rawClasspathElementPath);
                    workQueue.addWorkUnit(rawClasspathElt);
                }
                // Start workers
                workQueue.startWorkers(executorService, numParallelTasks);
                // Also do work in the main thread -- returns after all work units have been completed
                workQueue.runWorkLoop();
                // Check if work termination was due to interruption
                if (workQueue.interrupted()) {
                    throw new InterruptedException();
                }
            }

            // Determine total ordering of classpath elements, inserting jars referenced in manifest Class-Path
            // entries in-place into the ordering, if they haven't been listed earlier in the classpath already.
            final List<ClasspathElement> classpathOrder = findClasspathOrder(canonicalPathToChildCanonicalPaths,
                    canonicalPathToClasspathElementSingleton);

            // For all ClasspathElements in classpathOrder, extract final classpath entries by filtering out
            // jarfiles that are invalid for some reason (e.g. they don't match whitelist/blacklist criteria).
            final List<ClasspathElementSingleton> classpathSingletonOrder = new ArrayList<>(classpathOrder.size());
            final HashSet<String> classpathRelativePathsFound = new HashSet<>();
            for (final ClasspathElement classpathElt : classpathOrder) {
                final ClasspathElementSingleton classpathElementSingleton = canonicalPathToClasspathElementSingleton
                        .get(classpathElt.getCanonicalPath());
                if (classpathElementSingleton == null) {
                    // Should not happen
                    throw new RuntimeException("Could not find ClasspathElementSingleton");
                }
                // Filter out invalid classpath elements
                if (classpathElementSingleton.isValid) {
                    // Store classpath element order
                    classpathSingletonOrder.add(classpathElementSingleton);
                    classpathElementFilesOrdered.add(classpathElt.getFile());
                }
            }

            ScanResult scanResult;
            if (enableRecursiveScanning) {
                // Determine if any relative paths later in the classpath are masked by relative paths
                // earlier in the classpath
                for (final ClasspathElementSingleton classpathElementSingleton : classpathSingletonOrder) {
                    // Implement classpath masking -- if the same relative path occurs multiple times in the
                    // classpath, ignore (remove) the second and subsequent occurrences.
                    classpathElementSingleton.recursiveScanResult.maskFiles(classpathRelativePathsFound);
                }

                // Build map from file to timestamp, so it is possible to check for changes in the classpath
                final Map<File, Long> fileToLastModified = new HashMap<>();
                for (final ClasspathElementSingleton classpathElementSingleton : classpathSingletonOrder) {
                    final RecursiveScanResult recursiveScanResult = classpathElementSingleton.recursiveScanResult;
                    for (final ClasspathResource fileMatch : recursiveScanResult.fileMatches) {
                        // ZipFiles have relativePathFile == null; don't need to timestamp resources inside ZipFiles
                        if (fileMatch.relativePathFile != null) {
                            fileToLastModified.put(fileMatch.relativePathFile, fileMatch.lastModifiedTime);
                        }
                    }
                    for (final ClasspathResource classfileMatch : recursiveScanResult.classfileMatches) {
                        if (classfileMatch.relativePathFile != null) {
                            fileToLastModified.put(classfileMatch.relativePathFile,
                                    classfileMatch.lastModifiedTime);
                        }
                    }
                    for (final ClasspathResource dirOrZip : recursiveScanResult.whitelistedDirectoriesAndZipfiles) {
                        fileToLastModified.put(dirOrZip.relativePathFile, dirOrZip.lastModifiedTime);
                    }
                }

                // Scan classfile binary headers in parallel
                final ConcurrentLinkedQueue<ClassInfoUnlinked> classInfoUnlinked = new ConcurrentLinkedQueue<>();
                final ConcurrentHashMap<String, String> stringInternMap = new ConcurrentHashMap<>();
                try (ClassfileParserChunkWorkQueue workQueue = new ClassfileParserChunkWorkQueue(scanSpec,
                        zipFileRecycler, classfileBinaryParserRecycler, stringInternMap, classInfoUnlinked, log)) {
                    // Break lists of relative paths of classfiles within each classpath element into chunks,
                    // and add the chunks as work units
                    workQueue.addWorkUnits(getClassfileParserChunks(classpathSingletonOrder));
                    // Start workers
                    workQueue.startWorkers(executorService, numParallelTasks);
                    // Also do work in the main thread -- returns after all work units have been completed
                    workQueue.runWorkLoop();
                    // Check if work termination was due to interruption
                    if (workQueue.interrupted()) {
                        throw new InterruptedException();
                    }
                }

                // Build the class graph: convert ClassInfoUnlinked to linked ClassInfo objects.
                final Map<String, ClassInfo> classNameToClassInfo = new HashMap<>();
                for (final ClassInfoUnlinked c : classInfoUnlinked) {
                    // Create ClassInfo object from ClassInfoUnlinked object, and link into class graph
                    c.link(classNameToClassInfo);
                    if (Thread.currentThread().isInterrupted()) {
                        throw new InterruptedException();
                    }
                }
                final ClassGraphBuilder classGraphBuilder = new ClassGraphBuilder(classNameToClassInfo);

                // Create ScanResult
                scanResult = new ScanResult(scanSpec, classpathElementFilesOrdered, classGraphBuilder,
                        fileToLastModified, log);

                // Call MatchProcessors 
                final long startMatchProcessors = System.nanoTime();
                final List<ClasspathResource> fileMatches = new ArrayList<>();
                for (final ClasspathElementSingleton classpathElementSingleton : classpathSingletonOrder) {
                    fileMatches.addAll(classpathElementSingleton.recursiveScanResult.fileMatches);
                }
                scanSpec.callMatchProcessors(scanResult, fileMatches, classNameToClassInfo, zipFileRecycler, log);
                if (FastClasspathScanner.verbose) {
                    log.log(1, "Finished calling MatchProcessors", System.nanoTime() - startMatchProcessors);
                }
            } else {
                // This is the result of a call to FastClasspathScanner#getUniqueClasspathElementsAsync(), so
                // just create placeholder ScanResult to contain classpathElementFilesOrdered.
                scanResult = new ScanResult(scanSpec, classpathElementFilesOrdered, /* classGraphBuilder = */ null,
                        /* fileToLastModified = */ null, log);
            }

            if (FastClasspathScanner.verbose) {
                log.log("Finished scan", System.nanoTime() - scanStart);
            }
            return scanResult;
        }
    }
}
