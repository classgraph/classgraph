/*
 * This file is part of ClassGraph.
 *
 * Author: Luke Hutchison
 *
 * Hosted at: https://github.com/classgraph/classgraph
 *
 * --
 *
 * The MIT License (MIT)
 *
 * Copyright (c) 2018 Luke Hutchison
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
package io.github.classgraph;

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
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;

import io.github.classgraph.ClassGraph.FailureHandler;
import io.github.classgraph.ClassGraph.ScanResultProcessor;
import io.github.classgraph.utils.ClassLoaderAndModuleFinder;
import io.github.classgraph.utils.ClasspathFinder;
import io.github.classgraph.utils.ClasspathOrModulePathEntry;
import io.github.classgraph.utils.InterruptionChecker;
import io.github.classgraph.utils.JarUtils;
import io.github.classgraph.utils.LogNode;
import io.github.classgraph.utils.NestedJarHandler;
import io.github.classgraph.utils.SingletonMap;
import io.github.classgraph.utils.WorkQueue;
import io.github.classgraph.utils.WorkQueue.WorkQueuePreStartHook;
import io.github.classgraph.utils.WorkQueue.WorkUnitProcessor;

/** The classpath scanner. */
class Scanner implements Callable<ScanResult> {
    private final ScanSpec scanSpec;
    private final ExecutorService executorService;
    private final int numParallelTasks;
    private final InterruptionChecker interruptionChecker = new InterruptionChecker();
    private final ScanResultProcessor scanResultProcessor;
    private final FailureHandler failureHandler;
    private final LogNode topLevelLog;
    private NestedJarHandler nestedJarHandler;

    /**
     * The number of files within a given classpath element (directory or zipfile) to send in a chunk to the workers
     * that are calling the classfile binary parser. The smaller this number is, the better the load leveling at the
     * end of the scan, but the higher the overhead in re-opening the same ZipFile or module in different worker
     * threads.
     */
    private static final int NUM_FILES_PER_CHUNK = 32;

    /** The classpath scanner. */
    Scanner(final ScanSpec scanSpec, final ExecutorService executorService, final int numParallelTasks,
            final ScanResultProcessor scannResultProcessor, final FailureHandler failureHandler,
            final LogNode log) {
        this.scanSpec = scanSpec;
        scanSpec.sortPrefixes();

        this.executorService = executorService;
        this.numParallelTasks = numParallelTasks;
        this.scanResultProcessor = scannResultProcessor;
        this.failureHandler = failureHandler;
        this.topLevelLog = log;

        // Add ScanSpec to beginning of log
        scanSpec.log(log);
    }

    // -------------------------------------------------------------------------------------------------------------

    /** A map from relative path to classpath element singleton. */
    private static class ClasspathOrModulePathEntryToClasspathElementMap
            extends SingletonMap<ClasspathOrModulePathEntry, ClasspathElement> {
        private final ScanSpec scanSpec;
        private final NestedJarHandler nestedJarHandler;
        private WorkQueue<ClasspathOrModulePathEntry> workQueue;

        /** A map from relative path to classpath element singleton. */
        ClasspathOrModulePathEntryToClasspathElementMap(final ScanSpec scanSpec,
                final NestedJarHandler nestedJarHandler) {
            this.scanSpec = scanSpec;
            this.nestedJarHandler = nestedJarHandler;
        }

        /**
         * Work queue -- needs to be set for zipfiles, but not for directories, since zipfiles can contain
         * Class-Path manifest entries, which require the adding of additional work units to the scanning work
         * queue.
         */
        void setWorkQueue(final WorkQueue<ClasspathOrModulePathEntry> workQueue) {
            this.workQueue = workQueue;
        }

        /** Create a new classpath element singleton instance. */
        @Override
        public ClasspathElement newInstance(final ClasspathOrModulePathEntry classpathElt, final LogNode log) {
            final LogNode jarLog = log == null ? null : log.log("Reading " + classpathElt.getResolvedPath());
            if (classpathElt.isValidClasspathElement(scanSpec, jarLog)) {
                try {
                    final boolean isModule = classpathElt.getModuleRef() != null;
                    final boolean isFile = !isModule && classpathElt.isFile(jarLog);
                    final boolean isDir = !isModule && classpathElt.isDirectory(jarLog);
                    if (isFile && !scanSpec.scanJars) {
                        if (jarLog != null) {
                            jarLog.log("Skipping because jar scanning has been disabled: " + classpathElt);
                        }
                    } else if (isFile && !scanSpec.jarWhiteBlackList
                            .isWhitelistedAndNotBlacklisted(classpathElt.getCanonicalPath(jarLog))) {
                        if (jarLog != null) {
                            jarLog.log("Skipping jarfile that is blacklisted or not whitelisted: " + classpathElt);
                        }
                    } else if (isDir && !scanSpec.scanDirs) {
                        if (jarLog != null) {
                            jarLog.log("Skipping because directory scanning has been disabled: " + classpathElt);
                        }
                    } else if (isModule && !scanSpec.scanModules) {
                        if (jarLog != null) {
                            jarLog.log("Skipping because module scanning has been disabled: " + classpathElt);
                        }
                    } else {
                        // Classpath element is valid => add as a singleton. This will trigger
                        // calling the ClasspathElementZip constructor in the case of jarfiles,
                        // which will check the manifest file for Class-Path entries, and if
                        // any are found, additional work units will be added to the work queue
                        // to scan those jarfiles too. If Class-Path entries are found, they
                        // are added as child elements of the current classpath element, so
                        // that they can be inserted at the correct location in the classpath
                        // order.
                        return ClasspathElement.newInstance(classpathElt, scanSpec, nestedJarHandler, workQueue,
                                jarLog);
                    }
                } catch (final Exception e) {
                    if (jarLog != null) {
                        // Could not create singleton, possibly due to canonicalization problem
                        jarLog.log("Skipping invalid classpath element " + classpathElt + " : " + e);
                    }
                }
            }
            // Return null if classpath element is not valid.
            // This will cause SingletonMap to throw IlegalArgumentException.
            return null;
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Recursively perform a depth-first search of jar interdependencies, breaking cycles if necessary, to determine
     * the final classpath element order.
     */
    private static void findClasspathOrder(final ClasspathElement currClasspathElement,
            final ClasspathOrModulePathEntryToClasspathElementMap classpathElementMap,
            final HashSet<ClasspathElement> visitedClasspathElts, final ArrayList<ClasspathElement> order)
            throws InterruptedException {
        if (visitedClasspathElts.add(currClasspathElement)) {
            if (!currClasspathElement.skipClasspathElement) {
                // Don't add a classpath element if it is marked to be skipped.
                order.add(currClasspathElement);
            }
            // Whether or not a classpath element should be skipped, add any child classpath elements that are
            // not marked to be skipped (i.e. keep recursing)
            if (currClasspathElement.childClasspathElts != null) {
                for (final ClasspathOrModulePathEntry childClasspathElt : currClasspathElement.childClasspathElts) {
                    final ClasspathElement childSingleton = classpathElementMap.get(childClasspathElt);
                    if (childSingleton != null) {
                        findClasspathOrder(childSingleton, classpathElementMap, visitedClasspathElts, order);
                    }
                }
            }
            if (currClasspathElement.skipClasspathElement) {
                // If classpath element is marked to be skipped, close it (it will not be used again).
                currClasspathElement.closeRecyclers();
            }
        }
    }

    /**
     * Recursively perform a depth-first search of jar interdependencies, breaking cycles if necessary, to determine
     * the final classpath element order.
     */
    private static List<ClasspathElement> findClasspathOrder(
            final List<ClasspathOrModulePathEntry> rawClasspathElements,
            final ClasspathOrModulePathEntryToClasspathElementMap classpathElementMap) throws InterruptedException {
        // Recurse from toplevel classpath elements to determine a total ordering of classpath elements (jars with
        // Class-Path entries in their manifest file should have those child resources included in-place in the
        // classpath).
        final HashSet<ClasspathElement> visitedClasspathElts = new HashSet<>();
        final ArrayList<ClasspathElement> order = new ArrayList<>();
        for (final ClasspathOrModulePathEntry toplevelClasspathElt : rawClasspathElements) {
            final ClasspathElement toplevelSingleton = classpathElementMap.get(toplevelClasspathElt);
            if (toplevelSingleton != null) {
                findClasspathOrder(toplevelSingleton, classpathElementMap, visitedClasspathElts, order);
            }
        }
        return order;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Holds range limits for chunks of classpath files that need to be scanned in a given classpath element.
     */
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
        // There should be no overlap between the relative paths in any of the chunks, because classpath masking has
        // already been applied, so these chunks can be scanned in any order. But since a ZipFile instance can only
        // be used by one thread at a time, we want to space the chunks for a given ZipFile as far apart as possible
        // in the work queue to minimize the chance that two threads will try to open the same ZipFile at the same
        // time, as this will cause a second copy of the ZipFile to have to be opened by the ZipFile recycler. The
        // combination of chunking and interleaving therefore lets us achieve load leveling without work stealing or
        // other more complex mechanism.
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
        final LogNode classpathFinderLog = topLevelLog == null ? null
                : topLevelLog.log("Finding classpath entries");
        this.nestedJarHandler = new NestedJarHandler(scanSpec, classpathFinderLog);
        final ClasspathOrModulePathEntryToClasspathElementMap classpathElementMap = //
                new ClasspathOrModulePathEntryToClasspathElementMap(scanSpec, nestedJarHandler);
        try {
            final long scanStart = System.nanoTime();

            // Get classpath finder
            final LogNode getRawElementsLog = classpathFinderLog == null ? null
                    : classpathFinderLog.log("Getting raw classpath elements");
            final ClasspathFinder classpathFinder = new ClasspathFinder(scanSpec, nestedJarHandler,
                    getRawElementsLog);
            final ClassLoaderAndModuleFinder classLoaderAndModuleFinder = classpathFinder
                    .getClassLoaderAndModuleFinder();
            final ClassLoader[] classLoaderOrder = classLoaderAndModuleFinder.getClassLoaders();
            final List<ClasspathOrModulePathEntry> rawClasspathEltOrder = new ArrayList<>();

            if (scanSpec.overrideClasspath == null && scanSpec.overrideClassLoaders == null) {
                // Add modules to start of classpath order, before traditional classpath
                final List<ModuleRef> systemModules = classLoaderAndModuleFinder.getSystemModuleRefs();
                if (systemModules != null) {
                    for (final ModuleRef systemModule : systemModules) {
                        final String moduleName = systemModule.getName();
                        if ((!scanSpec.blacklistSystemJarsOrModules
                                || !JarUtils.isInSystemPackageOrModule(moduleName))
                                && scanSpec.overrideModuleLayers == null) {
                            if (scanSpec.moduleWhiteBlackList.isWhitelistedAndNotBlacklisted(moduleName)) {
                                rawClasspathEltOrder.add(new ClasspathOrModulePathEntry(systemModule,
                                        nestedJarHandler, getRawElementsLog));
                            } else {
                                if (classpathFinderLog != null) {
                                    classpathFinderLog.log(
                                            "Skipping non-whitelisted or blacklisted system module: " + moduleName);
                                }
                            }
                        } else {
                            if (classpathFinderLog != null) {
                                classpathFinderLog.log("Skipping system module: " + moduleName);
                            }
                        }
                    }
                }
                final List<ModuleRef> nonSystemModules = classLoaderAndModuleFinder.getNonSystemModuleRefs();
                if (nonSystemModules != null) {
                    for (final ModuleRef nonSystemModule : nonSystemModules) {
                        final String moduleName = nonSystemModule.getName();
                        if (scanSpec.moduleWhiteBlackList.isWhitelistedAndNotBlacklisted(moduleName)) {
                            rawClasspathEltOrder.add(new ClasspathOrModulePathEntry(nonSystemModule,
                                    nestedJarHandler, getRawElementsLog));
                        } else {
                            if (classpathFinderLog != null) {
                                classpathFinderLog
                                        .log("Skipping non-whitelisted or blacklisted module: " + moduleName);
                            }
                        }
                    }
                }
            }

            // Add traditional classpath entries to the classpath order
            rawClasspathEltOrder.addAll(classpathFinder.getRawClasspathElements());

            final List<String> rawClasspathEltOrderStrs = new ArrayList<>(rawClasspathEltOrder.size());
            for (final ClasspathOrModulePathEntry entry : rawClasspathEltOrder) {
                rawClasspathEltOrderStrs.add(entry.getResolvedPath());
            }

            // In parallel, resolve raw classpath elements to canonical paths, creating a ClasspathElement singleton
            // for each unique canonical path. Also check jars against jar whitelist/blacklist.
            final LogNode preScanLog = classpathFinderLog == null ? null
                    : classpathFinderLog.log("Reading jarfile metadata");
            WorkQueue.runWorkQueue(rawClasspathEltOrder, executorService, numParallelTasks,
                    new WorkUnitProcessor<ClasspathOrModulePathEntry>() {
                        @Override
                        public void processWorkUnit(final ClasspathOrModulePathEntry rawClasspathEltPath)
                                throws Exception {
                            try {
                                // Add the classpath element as a singleton. This will trigger calling
                                // the ClasspathElementZip constructor in the case of jarfiles, which
                                // will check the manifest file for Class-Path entries, and if any are
                                // found, additional work units will be added to the work queue to scan
                                // those jarfiles too. If Class-Path entries are found, they are added
                                // as child elements of the current classpath element, so that they can
                                // be inserted at the correct location in the classpath order.
                                classpathElementMap.getOrCreateSingleton(rawClasspathEltPath, preScanLog);
                            } catch (final IllegalArgumentException e) {
                                // Thrown if classpath element is invalid (already logged)
                            }
                        }
                    }, new WorkQueuePreStartHook<ClasspathOrModulePathEntry>() {
                        @Override
                        public void processWorkQueueRef(final WorkQueue<ClasspathOrModulePathEntry> workQueue) {
                            // Store a ref back to the work queue in the classpath element map, because some
                            // classpath elements will need to schedule additional classpath elements for scanning,
                            // e.g. "Class-Path:" refs in jar manifest files
                            classpathElementMap.setWorkQueue(workQueue);
                        }
                    }, interruptionChecker, preScanLog);

            // Determine total ordering of classpath elements, inserting jars referenced in manifest Class-Path
            // entries in-place into the ordering, if they haven't been listed earlier in the classpath already.
            final List<ClasspathElement> classpathOrder = findClasspathOrder(rawClasspathEltOrder,
                    classpathElementMap);

            // Log final classpath element order, after inserting Class-Path entries from manifest files
            if (classpathFinderLog != null) {
                final LogNode logNode = classpathFinderLog.log("Final classpath element order:");
                for (int i = 0; i < classpathOrder.size(); i++) {
                    final ClasspathElement classpathElt = classpathOrder.get(i);
                    final ModuleRef classpathElementModuleRef = classpathElt.getClasspathElementModuleRef();
                    if (classpathElementModuleRef != null) {
                        logNode.log(i + ": module " + classpathElementModuleRef.getName() + " ; module location: "
                                + classpathElementModuleRef.getLocationStr());
                    } else {
                        final String classpathEltStr = classpathElt.toString();
                        final String classpathEltFileStr = "" + classpathElt.getClasspathElementFile(logNode);
                        final String packageRoot = classpathElt.getJarfilePackageRoot();
                        logNode.log(i + ": " + (classpathEltStr.equals(classpathEltFileStr) && packageRoot.isEmpty()
                                ? classpathEltStr
                                : classpathElt + " -> " + classpathEltFileStr
                                        + (packageRoot.isEmpty() ? "" : " ; package root: " + packageRoot)));
                    }
                }
            }

            ScanResult scanResult;
            if (!scanSpec.performScan) {
                if (topLevelLog != null) {
                    topLevelLog.log("Only returning classpath elements (not performing a scan)");
                }
                // This is the result of a call to ClassGraph#getUniqueClasspathElements(), so just
                // create placeholder ScanResult to contain classpathElementFilesOrdered.
                scanResult = new ScanResult(scanSpec, classpathOrder, rawClasspathEltOrderStrs, classLoaderOrder,
                        /* classNameToClassInfo = */ null, /* fileToLastModified = */ null, nestedJarHandler,
                        topLevelLog);

            } else {
                // Perform scan of the classpath

                // Find classpath elements that are path prefixes of other classpath elements
                final List<SimpleEntry<String, ClasspathElement>> classpathEltResolvedPathToElement = //
                        new ArrayList<>();
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
                                // basePath is a path prefix of comparePath. Ensure that the nested classpath does
                                // not contain another '!' zip-separator (since classpath scanning does not recurse
                                // to jars-within-jars unless they are explicitly listed on the classpath)
                                final String nestedClasspathRelativePath = comparePath.substring(basePathLen + 1);
                                if (nestedClasspathRelativePath.indexOf('!') < 0) {
                                    // Found a nested classpath root
                                    foundNestedClasspathRoot = true;
                                    // Store link from prefix element to nested elements
                                    final ClasspathElement baseElement = ei.getValue();
                                    if (baseElement.nestedClasspathRootPrefixes == null) {
                                        baseElement.nestedClasspathRootPrefixes = new ArrayList<>();
                                    }
                                    baseElement.nestedClasspathRootPrefixes.add(nestedClasspathRelativePath + "/");
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

                // Implement classpath masking -- if the same relative classfile path occurs multiple times in the
                // classpath, ignore (remove) the second and subsequent occurrences. Note that classpath masking is
                // performed whether or not a jar is whitelisted, and whether or not jar or dir scanning is enabled,
                // in order to ensure that class references passed into MatchProcessors are the same as those that
                // would be loaded by standard classloading. (See bug #100.)
                final LogNode maskLog = topLevelLog == null ? null : topLevelLog.log("Masking classpath files");
                final HashSet<String> classpathRelativePathsFound = new HashSet<>();
                for (int classpathIdx = 0; classpathIdx < classpathOrder.size(); classpathIdx++) {
                    final ClasspathElement classpathElement = classpathOrder.get(classpathIdx);
                    classpathElement.maskFiles(classpathIdx, classpathRelativePathsFound, maskLog);
                }

                // Merge the maps from file to timestamp across all classpath elements (there will be no overlap in
                // keyspace, since file masking was already performed)
                final Map<File, Long> fileToLastModified = new HashMap<>();
                for (final ClasspathElement classpathElement : classpathOrder) {
                    fileToLastModified.putAll(classpathElement.fileToLastModified);
                }

                final Map<String, ClassInfo> classNameToClassInfo = new HashMap<>();
                if (!scanSpec.enableClassInfo) {
                    if (topLevelLog != null) {
                        topLevelLog.log("Classfile scanning is disabled");
                    }
                } else {
                    // Scan classfile binary headers in parallel
                    final ConcurrentLinkedQueue<ClassInfoUnlinked> classInfoUnlinked = //
                            new ConcurrentLinkedQueue<>();
                    final LogNode classfileScanLog = topLevelLog == null ? null
                            : topLevelLog.log("Scanning classfile binary headers");
                    final List<ClassfileParserChunk> classfileParserChunks = getClassfileParserChunks(
                            classpathOrder);
                    WorkQueue.runWorkQueue(classfileParserChunks, executorService, numParallelTasks,
                            new WorkUnitProcessor<ClassfileParserChunk>() {
                                @Override
                                public void processWorkUnit(final ClassfileParserChunk chunk) throws Exception {
                                    chunk.classpathElement.parseClassfiles(chunk.classfileStartIdx,
                                            chunk.classfileEndIdx, classInfoUnlinked, classfileScanLog);
                                    interruptionChecker.check();
                                }
                            }, interruptionChecker, classfileScanLog);
                    if (classfileScanLog != null) {
                        classfileScanLog.addElapsedTime();
                    }

                    // Build the class graph: convert ClassInfoUnlinked to linked ClassInfo objects.
                    final LogNode classGraphLog = topLevelLog == null ? null
                            : topLevelLog.log("Building class graph");
                    for (final ClassInfoUnlinked c : classInfoUnlinked) {
                        c.link(scanSpec, classNameToClassInfo, classGraphLog);
                    }

                    // Uncomment the following code to create placeholder external classes for any classes
                    // referenced in type descriptors or type signatures, so that a ClassInfo object can be
                    // obtained for those class references. This will cause all type descriptors and type
                    // signatures to be parsed, and class names extracted from them. This will add some
                    // overhead to the scanning time, and the only benefit is that
                    // ClassRefTypeSignature.getClassInfo() and AnnotationClassRef.getClassInfo() will never
                    // return null, since all external classes found in annotation class refs will have a
                    // placeholder ClassInfo object created for them. This is obscure enough that it is
                    // probably not worth slowing down scanning for all other usecases, by forcibly parsing
                    // all type descriptors and type signatures before returning the ScanResult.
                    // With this code commented out, type signatures and type descriptors are only parsed
                    // lazily, on demand.

                    //    final Set<String> referencedClassNames = new HashSet<>();
                    //    for (final ClassInfo classInfo : classNameToClassInfo.values()) {
                    //        classInfo.getClassNamesFromTypeDescriptors(referencedClassNames);
                    //    }
                    //    for (final String referencedClass : referencedClassNames) {
                    //        ClassInfo.getOrCreateClassInfo(referencedClass, /* modifiers = */ 0, scanSpec,
                    //                classNameToClassInfo);
                    //    }

                    if (classGraphLog != null) {
                        classGraphLog.addElapsedTime();
                    }
                }

                // Create ScanResult
                scanResult = new ScanResult(scanSpec, classpathOrder, rawClasspathEltOrderStrs, classLoaderOrder,
                        classNameToClassInfo, fileToLastModified, nestedJarHandler, topLevelLog);

            }
            if (topLevelLog != null) {
                topLevelLog.log("Completed", System.nanoTime() - scanStart);
            }

            // Run scanResultProcessor in the current thread
            if (scanResultProcessor != null) {
                try {
                    scanResultProcessor.processScanResult(scanResult);
                } catch (final Throwable e) {
                    throw new ExecutionException("Exception while calling scan result processor", e);
                }
            }

            // No exceptions were thrown -- return scan result
            return scanResult;

        } catch (final Throwable e) {
            // Remove temporary files if an exception was thrown
            if (this.nestedJarHandler != null) {
                this.nestedJarHandler.close(topLevelLog);
            }
            if (topLevelLog != null) {
                topLevelLog.log(e);
            }
            if (failureHandler != null) {
                try {
                    failureHandler.onFailure(e);
                    // The return value is discarded when using a scanResultProcessor and failureHandler
                    return null;
                } catch (final Throwable t) {
                    throw new ExecutionException("Exception while calling failure handler", t);
                }
            } else {
                throw new ExecutionException("Exception while scanning", e);
            }

        } finally {
            if (scanSpec.removeTemporaryFilesAfterScan) {
                // If requested, remove temporary files and close zipfile/module recyclers
                nestedJarHandler.close(topLevelLog);
            } else {
                // Don't delete temporary files yet, but close zipfile/module recyclers
                nestedJarHandler.closeRecyclers();
            }

            // Close ClasspathElement recyclers
            for (final ClasspathElement elt : classpathElementMap.values()) {
                if (elt != null) {
                    elt.closeRecyclers();
                }
            }

            if (topLevelLog != null) {
                topLevelLog.flush();
            }
        }
    }
}
