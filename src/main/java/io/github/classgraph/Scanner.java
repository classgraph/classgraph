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
 * Copyright (c) 2019 Luke Hutchison
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
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLDecoder;
import java.util.AbstractMap.SimpleEntry;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;

import io.github.classgraph.ClassGraph.FailureHandler;
import io.github.classgraph.ClassGraph.ScanResultProcessor;
import io.github.classgraph.Classfile.ClassfileFormatException;
import io.github.classgraph.Classfile.SkipClassException;
import nonapi.io.github.classgraph.classpath.ClasspathFinder;
import nonapi.io.github.classgraph.classpath.ClasspathOrder.ClasspathElementAndClassLoader;
import nonapi.io.github.classgraph.classpath.ModuleFinder;
import nonapi.io.github.classgraph.concurrency.AutoCloseableExecutorService;
import nonapi.io.github.classgraph.concurrency.InterruptionChecker;
import nonapi.io.github.classgraph.concurrency.SingletonMap;
import nonapi.io.github.classgraph.concurrency.SingletonMap.NullSingletonException;
import nonapi.io.github.classgraph.concurrency.WorkQueue;
import nonapi.io.github.classgraph.concurrency.WorkQueue.WorkUnitProcessor;
import nonapi.io.github.classgraph.fastzipfilereader.NestedJarHandler;
import nonapi.io.github.classgraph.scanspec.ScanSpec;
import nonapi.io.github.classgraph.utils.CollectionUtils;
import nonapi.io.github.classgraph.utils.FastPathResolver;
import nonapi.io.github.classgraph.utils.FileUtils;
import nonapi.io.github.classgraph.utils.JarUtils;
import nonapi.io.github.classgraph.utils.LogNode;

/** The classpath scanner. */
class Scanner implements Callable<ScanResult> {

    /** The scan spec. */
    private final ScanSpec scanSpec;

    /** If true, performing a scan. If false, only fetching the classpath. */
    public boolean performScan;

    /** The nested jar handler. */
    private final NestedJarHandler nestedJarHandler;

    /** The executor service. */
    private final ExecutorService executorService;

    /** The interruption checker. */
    private final InterruptionChecker interruptionChecker;

    /** The number of parallel tasks. */
    private final int numParallelTasks;

    /** The scan result processor. */
    private final ScanResultProcessor scanResultProcessor;

    /** The failure handler. */
    private final FailureHandler failureHandler;

    /** The toplevel log. */
    private final LogNode topLevelLog;

    /** The classpath finder. */
    private final ClasspathFinder classpathFinder;

    /** The module order. */
    private final List<ClasspathElementModule> moduleOrder;

    /** The environment classloader order, respecting parent-first or parent-last delegation order. */
    private final ClassLoader[] classLoaderOrderRespectingParentDelegation;

    // -------------------------------------------------------------------------------------------------------------

    /**
     * The classpath scanner. Scanning is started by calling {@link #call()} on this object.
     * 
     * @param performScan
     *            If true, performing a scan. If false, only fetching the classpath.
     * @param scanSpec
     *            the scan spec
     * @param executorService
     *            the executor service
     * @param numParallelTasks
     *            the num parallel tasks
     * @param scanResultProcessor
     *            the scan result processor
     * @param failureHandler
     *            the failure handler
     * @param topLevelLog
     *            the log
     *
     * @throws InterruptedException
     *             if interrupted
     */
    Scanner(final boolean performScan, final ScanSpec scanSpec, final ExecutorService executorService,
            final int numParallelTasks, final ScanResultProcessor scanResultProcessor,
            final FailureHandler failureHandler, final LogNode topLevelLog) throws InterruptedException {
        this.scanSpec = scanSpec;
        this.performScan = performScan;
        scanSpec.sortPrefixes();
        scanSpec.log(topLevelLog);
        if (topLevelLog != null) {
            if (scanSpec.pathWhiteBlackList != null
                    && scanSpec.packagePrefixWhiteBlackList.isSpecificallyWhitelisted("")) {
                topLevelLog.log("Note: There is no need to whitelist the root package (\"\") -- not whitelisting "
                        + "anything will have the same effect of causing all packages to be scanned");
            }
            topLevelLog.log("Number of worker threads: " + numParallelTasks);
        }

        this.executorService = executorService;
        this.interruptionChecker = executorService instanceof AutoCloseableExecutorService
                ? ((AutoCloseableExecutorService) executorService).interruptionChecker
                : new InterruptionChecker();
        this.nestedJarHandler = new NestedJarHandler(scanSpec, interruptionChecker);
        this.numParallelTasks = numParallelTasks;
        this.scanResultProcessor = scanResultProcessor;
        this.failureHandler = failureHandler;
        this.topLevelLog = topLevelLog;

        final LogNode classpathFinderLog = topLevelLog == null ? null : topLevelLog.log("Finding classpath");
        this.classpathFinder = new ClasspathFinder(scanSpec, classpathFinderLog);
        this.classLoaderOrderRespectingParentDelegation = classpathFinder
                .getClassLoaderOrderRespectingParentDelegation();

        try {
            this.moduleOrder = new ArrayList<>();

            // Check if modules should be scanned
            final ModuleFinder moduleFinder = classpathFinder.getModuleFinder();
            if (moduleFinder != null) {
                // Add modules to start of classpath order, before traditional classpath
                final List<ModuleRef> systemModuleRefs = moduleFinder.getSystemModuleRefs();
                final ClassLoader defaultClassLoader = classLoaderOrderRespectingParentDelegation != null
                        && classLoaderOrderRespectingParentDelegation.length != 0
                                ? classLoaderOrderRespectingParentDelegation[0]
                                : null;
                if (systemModuleRefs != null) {
                    for (final ModuleRef systemModuleRef : systemModuleRefs) {
                        final String moduleName = systemModuleRef.getName();
                        if (
                        // If scanning system packages and modules is enabled and white/blacklist is empty,
                        // then scan all system modules
                        (scanSpec.enableSystemJarsAndModules
                                && scanSpec.moduleWhiteBlackList.whitelistAndBlacklistAreEmpty())
                                // Otherwise only scan specifically whitelisted system modules
                                || scanSpec.moduleWhiteBlackList
                                        .isSpecificallyWhitelistedAndNotBlacklisted(moduleName)) {
                            // Create a new ClasspathElementModule
                            final ClasspathElementModule classpathElementModule = new ClasspathElementModule(
                                    systemModuleRef, defaultClassLoader,
                                    nestedJarHandler.moduleRefToModuleReaderProxyRecyclerMap, scanSpec);
                            moduleOrder.add(classpathElementModule);
                            // Open the ClasspathElementModule
                            classpathElementModule.open(/* ignored */ null, classpathFinderLog);
                        } else {
                            if (classpathFinderLog != null) {
                                classpathFinderLog.log(
                                        "Skipping non-whitelisted or blacklisted system module: " + moduleName);
                            }
                        }
                    }
                }
                final List<ModuleRef> nonSystemModuleRefs = moduleFinder.getNonSystemModuleRefs();
                if (nonSystemModuleRefs != null) {
                    for (final ModuleRef nonSystemModuleRef : nonSystemModuleRefs) {
                        String moduleName = nonSystemModuleRef.getName();
                        if (moduleName == null) {
                            moduleName = "";
                        }
                        if (scanSpec.moduleWhiteBlackList.isWhitelistedAndNotBlacklisted(moduleName)) {
                            // Create a new ClasspathElementModule
                            final ClasspathElementModule classpathElementModule = new ClasspathElementModule(
                                    nonSystemModuleRef, defaultClassLoader,
                                    nestedJarHandler.moduleRefToModuleReaderProxyRecyclerMap, scanSpec);
                            moduleOrder.add(classpathElementModule);
                            // Open the ClasspathElementModule
                            classpathElementModule.open(/* ignored */ null, classpathFinderLog);
                        } else {
                            if (classpathFinderLog != null) {
                                classpathFinderLog
                                        .log("Skipping non-whitelisted or blacklisted module: " + moduleName);
                            }
                        }
                    }
                }
            }
        } catch (final InterruptedException e) {
            nestedJarHandler.close(/* log = */ null);
            throw e;
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Recursively perform a depth-first search of jar interdependencies, breaking cycles if necessary, to determine
     * the final classpath element order.
     *
     * @param currClasspathElement
     *            the current classpath element
     * @param visitedClasspathElts
     *            visited classpath elts
     * @param order
     *            the classpath element order
     */
    private static void findClasspathOrderRec(final ClasspathElement currClasspathElement,
            final Set<ClasspathElement> visitedClasspathElts, final List<ClasspathElement> order) {
        if (visitedClasspathElts.add(currClasspathElement)) {
            if (!currClasspathElement.skipClasspathElement) {
                // Don't add a classpath element if it is marked to be skipped.
                order.add(currClasspathElement);
            }
            // Whether or not a classpath element should be skipped, add any child classpath elements that are
            // not marked to be skipped (i.e. keep recursing)
            for (final ClasspathElement childClasspathElt : currClasspathElement.childClasspathElementsOrdered) {
                findClasspathOrderRec(childClasspathElt, visitedClasspathElts, order);
            }
        }
    }

    /** Comparator used to sort ClasspathElement values into increasing order of integer index key. */
    private static final Comparator<Entry<Integer, ClasspathElement>> INDEXED_CLASSPATH_ELEMENT_COMPARATOR = //
            new Comparator<Map.Entry<Integer, ClasspathElement>>() {
                @Override
                public int compare(final Entry<Integer, ClasspathElement> o1,
                        final Entry<Integer, ClasspathElement> o2) {
                    return o1.getKey() - o2.getKey();
                }
            };

    /**
     * Sort a collection of indexed ClasspathElements into increasing order of integer index key.
     *
     * @param classpathEltsIndexed
     *            the indexed classpath elts
     * @return the classpath elements, ordered by index
     */
    private static List<ClasspathElement> orderClasspathElements(
            final Collection<Entry<Integer, ClasspathElement>> classpathEltsIndexed) {
        final List<Entry<Integer, ClasspathElement>> classpathEltsIndexedOrdered = new ArrayList<>(
                classpathEltsIndexed);
        CollectionUtils.sortIfNotEmpty(classpathEltsIndexedOrdered, INDEXED_CLASSPATH_ELEMENT_COMPARATOR);
        final List<ClasspathElement> classpathEltsOrdered = new ArrayList<>(classpathEltsIndexedOrdered.size());
        for (final Entry<Integer, ClasspathElement> ent : classpathEltsIndexedOrdered) {
            classpathEltsOrdered.add(ent.getValue());
        }
        return classpathEltsOrdered;
    }

    /**
     * Recursively perform a depth-first traversal of child classpath elements, breaking cycles if necessary, to
     * determine the final classpath element order. This causes child classpath elements to be inserted in-place in
     * the classpath order, after the parent classpath element that contained them.
     *
     * @param uniqueClasspathElements
     *            the unique classpath elements
     * @param toplevelClasspathEltsIndexed
     *            the toplevel classpath elts, indexed by order within the toplevel classpath
     * @return the final classpath order, after depth-first traversal of child classpath elements
     */
    private List<ClasspathElement> findClasspathOrder(final Set<ClasspathElement> uniqueClasspathElements,
            final Queue<Entry<Integer, ClasspathElement>> toplevelClasspathEltsIndexed) {
        final List<ClasspathElement> toplevelClasspathEltsOrdered = orderClasspathElements(
                toplevelClasspathEltsIndexed);
        for (final ClasspathElement classpathElt : uniqueClasspathElements) {
            classpathElt.childClasspathElementsOrdered = orderClasspathElements(
                    classpathElt.childClasspathElementsIndexed);
        }
        final Set<ClasspathElement> visitedClasspathElts = new HashSet<>();
        final List<ClasspathElement> order = new ArrayList<>();
        for (final ClasspathElement toplevelClasspathElt : toplevelClasspathEltsOrdered) {
            findClasspathOrderRec(toplevelClasspathElt, visitedClasspathElts, order);
        }
        return order;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Process work units.
     *
     * @param <W>
     *            the work unit type
     * @param workUnits
     *            the work units
     * @param log
     *            the log entry text to group work units under
     * @param workUnitProcessor
     *            the work unit processor
     * @throws InterruptedException
     *             if a worker was interrupted.
     * @throws ExecutionException
     *             If a worker threw an uncaught exception.
     */
    private <W> void processWorkUnits(final Collection<W> workUnits, final LogNode log,
            final WorkUnitProcessor<W> workUnitProcessor) throws InterruptedException, ExecutionException {
        WorkQueue.runWorkQueue(workUnits, executorService, interruptionChecker, numParallelTasks, log,
                workUnitProcessor);
        if (log != null) {
            log.addElapsedTime();
        }
        // Throw InterruptedException if any of the workers failed
        interruptionChecker.check();
    }

    // -------------------------------------------------------------------------------------------------------------

    /** Used to enqueue classpath elements for opening. */
    static class ClasspathEntryWorkUnit {
        /** The raw classpath entry and associated {@link ClassLoader}. */
        private final ClasspathElementAndClassLoader rawClasspathEntry;

        /** The parent classpath element. */
        private final ClasspathElement parentClasspathElement;

        /** The order within the parent classpath element. */
        private final int orderWithinParentClasspathElement;

        /**
         * Constructor.
         *
         * @param rawClasspathEntry
         *            the raw classpath entry path and the classloader it was obtained from
         * @param parentClasspathElement
         *            the parent classpath element
         * @param orderWithinParentClasspathElement
         *            the order within parent classpath element
         */
        public ClasspathEntryWorkUnit(final ClasspathElementAndClassLoader rawClasspathEntry,
                final ClasspathElement parentClasspathElement, final int orderWithinParentClasspathElement) {
            this.rawClasspathEntry = rawClasspathEntry;
            this.parentClasspathElement = parentClasspathElement;
            this.orderWithinParentClasspathElement = orderWithinParentClasspathElement;
        }
    }

    /**
     * The classpath element singleton map. For each classpath element path, canonicalize path, and create a
     * ClasspathElement singleton.
     */
    private final SingletonMap<ClasspathElementAndClassLoader, ClasspathElement, IOException> //
    classpathEntryToClasspathElementSingletonMap = //
            new SingletonMap<ClasspathElementAndClassLoader, ClasspathElement, IOException>() {
                @Override
                public ClasspathElement newInstance(final ClasspathElementAndClassLoader classpathEntry,
                        final LogNode log) throws IOException, InterruptedException {
                    final Object classpathEntryObj = classpathEntry.classpathElement;
                    String classpathEntryPath;
                    if (classpathEntryObj instanceof URL) {
                        URL classpathEntryURL = (URL) classpathEntryObj;
                        String scheme = classpathEntryURL.getProtocol();
                        if ("jar".equals(scheme)) {
                            // Strip off "jar:" scheme prefix
                            try {
                                classpathEntryURL = new URL(
                                        URLDecoder.decode(classpathEntryURL.toString(), "UTF-8").substring(4));
                                scheme = classpathEntryURL.getProtocol();
                            } catch (final MalformedURLException e) {
                                throw new IOException("Could not strip 'jar:' prefix from " + classpathEntryObj, e);
                            }
                        }
                        if ("file".equals(scheme)) {
                            // Extract file path, and use below as a path string to determine if this
                            // classpath element is a file (jar) or directory
                            classpathEntryPath = URLDecoder.decode(classpathEntryURL.getPath(), "UTF-8");
                        } else if ("http".equals(scheme) || "https".equals(scheme)) {
                            // Jar URL or URI (remote URLs/URIs must be jars)
                            return new ClasspathElementZip(classpathEntryURL, classpathEntry.classLoader,
                                    nestedJarHandler, scanSpec);
                        } else {
                            // For custom URL schemes, assume it must be for a jar, not a directory
                            return new ClasspathElementZip(classpathEntryURL, classpathEntry.classLoader,
                                    nestedJarHandler, scanSpec);
                        }
                    } else {
                        // classpathEntryObj is a string
                        classpathEntryPath = classpathEntryObj.toString();
                    }

                    // Normalize path -- strip off any leading "jar:" / "file:", and normalize separators
                    final String pathNormalized = FastPathResolver.resolve(FileUtils.CURR_DIR_PATH,
                            classpathEntryPath);

                    // "http:", "https:" or any other URL/URI scheme must indicate a jar
                    if (JarUtils.URL_SCHEME_PATTERN.matcher(pathNormalized).matches()) {
                        return new ClasspathElementZip(classpathEntryPath, classpathEntry.classLoader,
                                nestedJarHandler, scanSpec);
                    }

                    // Strip everything after first "!", to get path of base jarfile or dir
                    final int plingIdx = pathNormalized.indexOf('!');
                    final String pathToCanonicalize = plingIdx < 0 ? pathNormalized
                            : pathNormalized.substring(0, plingIdx);
                    // Canonicalize base jarfile or dir (may throw IOException)
                    final File fileCanonicalized = new File(pathToCanonicalize).getCanonicalFile();
                    // Test if base file or dir exists (and is a standard file or dir)
                    if (!fileCanonicalized.exists()) {
                        throw new FileNotFoundException();
                    }
                    if (!FileUtils.canRead(fileCanonicalized)) {
                        throw new IOException("Cannot read file or directory");
                    }
                    boolean isJar = classpathEntryPath.regionMatches(true, 0, "jar:", 0, 4) || plingIdx > 0;
                    if (fileCanonicalized.isFile()) {
                        // If a file, must be a jar
                        isJar = true;
                    } else if (fileCanonicalized.isDirectory()) {
                        if (isJar) {
                            throw new IOException("Expected jar, found directory");
                        }
                    } else {
                        throw new IOException("Not a normal file or directory");
                    }
                    // Check if canonicalized path is the same as pre-canonicalized path
                    final String baseFileCanonicalPathNormalized = FastPathResolver.resolve(FileUtils.CURR_DIR_PATH,
                            fileCanonicalized.getPath());
                    final String canonicalPathNormalized = plingIdx < 0 ? baseFileCanonicalPathNormalized
                            : baseFileCanonicalPathNormalized + pathNormalized.substring(plingIdx);
                    if (!canonicalPathNormalized.equals(pathNormalized)) {
                        // If canonicalized path is not the same as pre-canonicalized path, need to recurse
                        // to map non-canonicalized path to singleton for canonicalized path (this should
                        // only recurse once, since File::getCanonicalFile and FastPathResolver::resolve are
                        // idempotent)
                        try {
                            return this.get(new ClasspathElementAndClassLoader(canonicalPathNormalized,
                                    classpathEntry.classLoader), log);
                        } catch (final NullSingletonException e) {
                            throw new IOException("Cannot get classpath element for canonical path "
                                    + canonicalPathNormalized + " : " + e);
                        }
                    } else {
                        // Otherwise path is already canonical, and this is the first time this path has
                        // been seen -- instantiate a ClasspathElementZip or ClasspathElementDir singleton
                        // for the classpath element path
                        return isJar
                                ? new ClasspathElementZip(canonicalPathNormalized, classpathEntry.classLoader,
                                        nestedJarHandler, scanSpec)
                                : new ClasspathElementDir(fileCanonicalized, classpathEntry.classLoader,
                                        nestedJarHandler, scanSpec);
                    }
                }
            };

    /**
     * Create a WorkUnitProcessor for opening traditional classpath entries (which are mapped to
     * {@link ClasspathElementDir} or {@link ClasspathElementZip} -- {@link ClasspathElementModule is handled
     * separately}).
     *
     * @param openedClasspathElementsSet
     *            the opened classpath elements set
     * @param toplevelClasspathEltOrder
     *            the toplevel classpath elt order
     * @return the work unit processor
     */
    private WorkUnitProcessor<ClasspathEntryWorkUnit> newClasspathEntryWorkUnitProcessor(
            final Set<ClasspathElement> openedClasspathElementsSet,
            final Queue<Entry<Integer, ClasspathElement>> toplevelClasspathEltOrder) {
        return new WorkUnitProcessor<ClasspathEntryWorkUnit>() {
            @Override
            public void processWorkUnit(final ClasspathEntryWorkUnit workUnit,
                    final WorkQueue<ClasspathEntryWorkUnit> workQueue, final LogNode log)
                    throws InterruptedException {
                try {
                    // Create a ClasspathElementZip or ClasspathElementDir for each entry in the classpath
                    ClasspathElement classpathElt;
                    try {
                        classpathElt = classpathEntryToClasspathElementSingletonMap.get(workUnit.rawClasspathEntry,
                                log);
                    } catch (final NullSingletonException e) {
                        throw new IOException("Cannot get classpath element for classpath entry "
                                + workUnit.rawClasspathEntry + " : " + e);
                    }

                    // Only run open() once per ClasspathElement (it is possible for there to be
                    // multiple classpath elements with different non-canonical paths that map to
                    // the same canonical path, i.e. to the same ClasspathElement)
                    if (openedClasspathElementsSet.add(classpathElt)) {
                        // Check if the classpath element is valid (classpathElt.skipClasspathElement
                        // will be set if not). In case of ClasspathElementZip, open or extract nested
                        // jars as LogicalZipFile instances. Read manifest files for jarfiles to look
                        // for Class-Path manifest entries. Adds extra classpath elements to the work
                        // queue if they are found.
                        classpathElt.open(workQueue, log);

                        // Create a new tuple consisting of the order of the new classpath element
                        // within its parent, and the new classpath element.
                        // N.B. even if skipClasspathElement is true, still possibly need to scan child
                        // classpath elements (so still need to connect parent to child here)
                        final SimpleEntry<Integer, ClasspathElement> classpathEltEntry = //
                                new SimpleEntry<>(workUnit.orderWithinParentClasspathElement, classpathElt);
                        if (workUnit.parentClasspathElement != null) {
                            // Link classpath element to its parent, if it is not a toplevel element
                            workUnit.parentClasspathElement.childClasspathElementsIndexed.add(classpathEltEntry);
                        } else {
                            // Record toplevel elements
                            toplevelClasspathEltOrder.add(classpathEltEntry);
                        }
                    }
                } catch (final IOException | SecurityException e) {
                    if (log != null) {
                        log.log("Skipping invalid classpath element " + workUnit.rawClasspathEntry.classpathElement
                                + " : " + e);
                    }
                }
            }
        };
    }

    // -------------------------------------------------------------------------------------------------------------

    /** Used to enqueue classfiles for scanning. */
    static class ClassfileScanWorkUnit {

        /** The classpath element. */
        private final ClasspathElement classpathElement;

        /** The classfile resource. */
        private final Resource classfileResource;

        /** True if this is an external class. */
        private final boolean isExternalClass;

        /**
         * Constructor.
         *
         * @param classpathElement
         *            the classpath element
         * @param classfileResource
         *            the classfile resource
         * @param isExternalClass
         *            the is external class
         */
        ClassfileScanWorkUnit(final ClasspathElement classpathElement, final Resource classfileResource,
                final boolean isExternalClass) {
            this.classpathElement = classpathElement;
            this.classfileResource = classfileResource;
            this.isExternalClass = isExternalClass;
        }
    }

    /** WorkUnitProcessor for scanning classfiles. */
    private static class ClassfileScannerWorkUnitProcessor implements WorkUnitProcessor<ClassfileScanWorkUnit> {
        /** The scan spec. */
        private final ScanSpec scanSpec;

        /** The classpath order. */
        private final List<ClasspathElement> classpathOrder;

        /**
         * The names of whitelisted classes found in the classpath while scanning paths within classpath elements.
         */
        private final Set<String> whitelistedClassNamesFound;

        /**
         * The names of external (non-whitelisted) classes scheduled for extended scanning (where scanning is
         * extended upwards to superclasses, interfaces and annotations).
         */
        private final Set<String> classNamesScheduledForExtendedScanning = Collections
                .newSetFromMap(new ConcurrentHashMap<String, Boolean>());

        /** The valid {@link Classfile} objects created by scanning classfiles. */
        private final Queue<Classfile> scannedClassfiles;

        /** The string intern map. */
        private final ConcurrentHashMap<String, String> stringInternMap = new ConcurrentHashMap<>();

        /**
         * Constructor.
         *
         * @param scanSpec
         *            the scan spec
         * @param classpathOrder
         *            the classpath order
         * @param whitelistedClassNamesFound
         *            the names of whitelisted classes found in the classpath while scanning paths within classpath
         *            elements.
         * @param scannedClassfiles
         *            the {@link Classfile} objects created by scanning classfiles
         */
        public ClassfileScannerWorkUnitProcessor(final ScanSpec scanSpec,
                final List<ClasspathElement> classpathOrder, final Set<String> whitelistedClassNamesFound,
                final Queue<Classfile> scannedClassfiles) {
            this.scanSpec = scanSpec;
            this.classpathOrder = classpathOrder;
            this.whitelistedClassNamesFound = whitelistedClassNamesFound;
            this.scannedClassfiles = scannedClassfiles;
        }

        /**
         * Process work unit.
         *
         * @param workUnit
         *            the work unit
         * @param workQueue
         *            the work queue
         * @param log
         *            the log
         * @throws InterruptedException
         *             the interrupted exception
         */
        /* (non-Javadoc)
         * @see nonapi.io.github.classgraph.concurrency.WorkQueue.WorkUnitProcessor#processWorkUnit(
         * java.lang.Object, nonapi.io.github.classgraph.concurrency.WorkQueue)
         */
        @Override
        public void processWorkUnit(final ClassfileScanWorkUnit workUnit,
                final WorkQueue<ClassfileScanWorkUnit> workQueue, final LogNode log) throws InterruptedException {
            // Classfile scan log entries are listed inline below the entry that was added to the log
            // when the path of the corresponding resource was found, by using the LogNode stored in
            // Resource#scanLog. This allows the path scanning and classfile scanning logs to be
            // merged into a single tree, rather than having them appear as two separate trees.
            final LogNode subLog = workUnit.classfileResource.scanLog == null ? null
                    : workUnit.classfileResource.scanLog.log(workUnit.classfileResource.getPath(),
                            "Parsing classfile");
            try {
                // Parse classfile binary format, creating a Classfile object
                final Classfile classfile = new Classfile(workUnit.classpathElement, classpathOrder,
                        whitelistedClassNamesFound, classNamesScheduledForExtendedScanning,
                        workUnit.classfileResource.getPath(), workUnit.classfileResource, workUnit.isExternalClass,
                        stringInternMap, workQueue, scanSpec, subLog);

                // Enqueue the classfile for linking
                scannedClassfiles.add(classfile);

            } catch (final SkipClassException e) {
                if (subLog != null) {
                    subLog.log(workUnit.classfileResource.getPath(), "Skipping classfile: " + e.getMessage());
                }
            } catch (final ClassfileFormatException e) {
                if (subLog != null) {
                    subLog.log(workUnit.classfileResource.getPath(), "Invalid classfile: " + e.getMessage());
                }
            } catch (final IOException e) {
                if (subLog != null) {
                    subLog.log(workUnit.classfileResource.getPath(), "Could not read classfile: " + e);
                }
            } finally {
                if (subLog != null) {
                    subLog.addElapsedTime();
                }
            }
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Find classpath elements whose path is a prefix of another classpath element, and record the nesting.
     *
     * @param classpathElts
     *            the classpath elements
     * @param log
     *            the log
     */
    private void findNestedClasspathElements(final List<SimpleEntry<String, ClasspathElement>> classpathElts,
            final LogNode log) {
        // Sort classpath elements into lexicographic order
        CollectionUtils.sortIfNotEmpty(classpathElts, new Comparator<SimpleEntry<String, ClasspathElement>>() {
            @Override
            public int compare(final SimpleEntry<String, ClasspathElement> o1,
                    final SimpleEntry<String, ClasspathElement> o2) {
                return o1.getKey().compareTo(o2.getKey());
            }
        });
        // Find any nesting of elements within other elements
        for (int i = 0; i < classpathElts.size(); i++) {
            // See if each classpath element is a prefix of any others (if so, they will immediately follow
            // in lexicographic order)
            final SimpleEntry<String, ClasspathElement> ei = classpathElts.get(i);
            final String basePath = ei.getKey();
            final int basePathLen = basePath.length();
            for (int j = i + 1; j < classpathElts.size(); j++) {
                final SimpleEntry<String, ClasspathElement> ej = classpathElts.get(j);
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
                            if (log != null) {
                                log.log(basePath + " is a prefix of the nested element " + comparePath);
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
    }

    /**
     * Find classpath elements whose path is a prefix of another classpath element, and record the nesting.
     *
     * @param finalTraditionalClasspathEltOrder
     *            the final traditional classpath elt order
     * @param classpathFinderLog
     *            the classpath finder log
     */
    private void preprocessClasspathElementsByType(final List<ClasspathElement> finalTraditionalClasspathEltOrder,
            final LogNode classpathFinderLog) {
        final List<SimpleEntry<String, ClasspathElement>> classpathEltDirs = new ArrayList<>();
        final List<SimpleEntry<String, ClasspathElement>> classpathEltZips = new ArrayList<>();
        for (final ClasspathElement classpathElt : finalTraditionalClasspathEltOrder) {
            if (classpathElt instanceof ClasspathElementDir) {
                // Separate out ClasspathElementDir elements from other types
                classpathEltDirs.add(
                        new SimpleEntry<>(((ClasspathElementDir) classpathElt).getFile().getPath(), classpathElt));

            } else if (classpathElt instanceof ClasspathElementZip) {
                // Separate out ClasspathElementZip elements from other types
                final ClasspathElementZip classpathEltZip = (ClasspathElementZip) classpathElt;
                classpathEltZips.add(new SimpleEntry<>(classpathEltZip.getZipFilePath(), classpathElt));

                // Handle module-related manifest entries
                if (classpathEltZip.logicalZipFile != null) {
                    // From JEP 261:
                    // "A <module>/<package> pair in the value of an Add-Exports attribute has the same
                    // meaning as the command-line option --add-exports <module>/<package>=ALL-UNNAMED. 
                    // A <module>/<package> pair in the value of an Add-Opens attribute has the same 
                    // meaning as the command-line option --add-opens <module>/<package>=ALL-UNNAMED."
                    if (classpathEltZip.logicalZipFile.addExportsManifestEntryValue != null) {
                        for (final String addExports : JarUtils.smartPathSplit(
                                classpathEltZip.logicalZipFile.addExportsManifestEntryValue, ' ', scanSpec)) {
                            scanSpec.modulePathInfo.addExports.add(addExports + "=ALL-UNNAMED");
                        }
                    }
                    if (classpathEltZip.logicalZipFile.addOpensManifestEntryValue != null) {
                        for (final String addOpens : JarUtils.smartPathSplit(
                                classpathEltZip.logicalZipFile.addOpensManifestEntryValue, ' ', scanSpec)) {
                            scanSpec.modulePathInfo.addOpens.add(addOpens + "=ALL-UNNAMED");
                        }
                    }
                    // Retrieve Automatic-Module-Name manifest entry, if present
                    if (classpathEltZip.logicalZipFile.automaticModuleNameManifestEntryValue != null) {
                        classpathEltZip.moduleNameFromManifestFile = //
                                classpathEltZip.logicalZipFile.automaticModuleNameManifestEntryValue;
                    }
                }
            }
            // (Ignore ClasspathElementModule, no preprocessing to perform)
        }
        // Find nested classpath elements (writes to ClasspathElement#nestedClasspathRootPrefixes)
        findNestedClasspathElements(classpathEltDirs, classpathFinderLog);
        findNestedClasspathElements(classpathEltZips, classpathFinderLog);
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Perform classpath masking of classfiles. If the same relative classfile path occurs multiple times in the
     * classpath, causes the second and subsequent occurrences to be ignored (removed).
     * 
     * @param classpathElementOrder
     *            the classpath element order
     * @param maskLog
     *            the mask log
     */
    private void maskClassfiles(final List<ClasspathElement> classpathElementOrder, final LogNode maskLog) {
        final Set<String> whitelistedClasspathRelativePathsFound = new HashSet<>();
        for (int classpathIdx = 0; classpathIdx < classpathElementOrder.size(); classpathIdx++) {
            final ClasspathElement classpathElement = classpathElementOrder.get(classpathIdx);
            classpathElement.maskClassfiles(classpathIdx, whitelistedClasspathRelativePathsFound, maskLog);
        }
        if (maskLog != null) {
            maskLog.addElapsedTime();
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Scan the classpath and/or visible modules.
     *
     * @param finalClasspathEltOrder
     *            the final classpath elt order
     * @param finalClasspathEltOrderStrs
     *            the final classpath elt order strs
     * @param classLoaderOrderRespectingParentDelegation
     *            the environment classloader order, respecting parent-first or parent-last delegation order
     * @return the scan result
     * @throws InterruptedException
     *             if the scan was interrupted
     * @throws ExecutionException
     *             if the scan threw an uncaught exception
     */
    private ScanResult performScan(final List<ClasspathElement> finalClasspathEltOrder,
            final List<String> finalClasspathEltOrderStrs,
            final ClassLoader[] classLoaderOrderRespectingParentDelegation)
            throws InterruptedException, ExecutionException {
        // Mask classfiles (remove any classfile resources that are shadowed by an earlier definition
        // of the same class)
        if (scanSpec.enableClassInfo) {
            maskClassfiles(finalClasspathEltOrder,
                    topLevelLog == null ? null : topLevelLog.log("Masking classfiles"));
        }

        // Merge the file-to-timestamp maps across all classpath elements
        final Map<File, Long> fileToLastModified = new HashMap<>();
        for (final ClasspathElement classpathElement : finalClasspathEltOrder) {
            fileToLastModified.putAll(classpathElement.fileToLastModified);
        }

        // Scan classfiles, if scanSpec.enableClassInfo is true.
        // (classNameToClassInfo is a ConcurrentHashMap because it can be modified by
        // ArrayTypeSignature.getArrayClassInfo() after scanning is complete)
        final Map<String, ClassInfo> classNameToClassInfo = new ConcurrentHashMap<>();
        final Map<String, PackageInfo> packageNameToPackageInfo = new HashMap<>();
        final Map<String, ModuleInfo> moduleNameToModuleInfo = new HashMap<>();
        if (scanSpec.enableClassInfo) {
            // Get whitelisted classfile order
            final List<ClassfileScanWorkUnit> classfileScanWorkItems = new ArrayList<>();
            final Set<String> whitelistedClassNamesFound = new HashSet<String>();
            for (final ClasspathElement classpathElement : finalClasspathEltOrder) {
                // Get classfile scan order across all classpath elements
                for (final Resource resource : classpathElement.whitelistedClassfileResources) {
                    // Create a set of names of all whitelisted classes found in classpath element paths,
                    // and double-check that a class is not going to be scanned twice
                    final String className = JarUtils.classfilePathToClassName(resource.getPath());
                    if (!whitelistedClassNamesFound.add(className) && !className.equals("module-info")
                            && !className.equals("package-info") && !className.endsWith(".package-info")) {
                        // The class should not be scheduled more than once for scanning, since classpath
                        // masking was already applied
                        throw new IllegalArgumentException("Class " + className
                                + " should not have been scheduled more than once for scanning due to classpath"
                                + " masking -- please report this bug at:"
                                + " https://github.com/classgraph/classgraph/issues");
                    }
                    // Schedule class for scanning
                    classfileScanWorkItems
                            .add(new ClassfileScanWorkUnit(classpathElement, resource, /* isExternal = */ false));
                }
            }

            // Scan classfiles in parallel
            final Queue<Classfile> scannedClassfiles = new ConcurrentLinkedQueue<>();
            final ClassfileScannerWorkUnitProcessor classfileWorkUnitProcessor = //
                    new ClassfileScannerWorkUnitProcessor(scanSpec, finalClasspathEltOrder,
                            Collections.unmodifiableSet(whitelistedClassNamesFound), scannedClassfiles);
            processWorkUnits(classfileScanWorkItems,
                    topLevelLog == null ? null : topLevelLog.log("Scanning classfiles"),
                    classfileWorkUnitProcessor);

            // Link the Classfile objects to produce ClassInfo objects. This needs to be done from a single thread.
            final LogNode linkLog = topLevelLog == null ? null : topLevelLog.log("Linking related classfiles");
            while (!scannedClassfiles.isEmpty()) {
                final Classfile c = scannedClassfiles.remove();
                c.link(classNameToClassInfo, packageNameToPackageInfo, moduleNameToModuleInfo);
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
            //        classInfo.findReferencedClassNames(referencedClassNames);
            //    }
            //    for (final String referencedClass : referencedClassNames) {
            //        ClassInfo.getOrCreateClassInfo(referencedClass, /* modifiers = */ 0, scanSpec,
            //                classNameToClassInfo);
            //    }

            if (linkLog != null) {
                linkLog.addElapsedTime();
            }
        } else {
            if (topLevelLog != null) {
                topLevelLog.log("Classfile scanning is disabled");
            }
        }

        // Return a new ScanResult
        return new ScanResult(scanSpec, finalClasspathEltOrder, finalClasspathEltOrderStrs,
                classLoaderOrderRespectingParentDelegation, classNameToClassInfo, packageNameToPackageInfo,
                moduleNameToModuleInfo, fileToLastModified, nestedJarHandler, topLevelLog);
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Open each of the classpath elements, looking for additional child classpath elements that need scanning (e.g.
     * {@code Class-Path} entries in jar manifest files), then perform the scan if {@link ScanSpec#performScan} is
     * true, or just get the classpath if {@link ScanSpec#performScan} is false.
     *
     * @return the scan result
     * @throws InterruptedException
     *             if the scan was interrupted
     * @throws ExecutionException
     *             if a worker threw an uncaught exception
     */
    private ScanResult openClasspathElementsThenScan() throws InterruptedException, ExecutionException {
        // Get order of elements in traditional classpath
        final List<ClasspathEntryWorkUnit> rawClasspathEntryWorkUnits = new ArrayList<>();
        for (final ClasspathElementAndClassLoader rawClasspathEntry : classpathFinder.getClasspathOrder()
                .getOrder()) {
            rawClasspathEntryWorkUnits
                    .add(new ClasspathEntryWorkUnit(rawClasspathEntry, /* parentClasspathElement = */ null,
                            /* orderWithinParentClasspathElement = */ rawClasspathEntryWorkUnits.size()));
        }

        // In parallel, create a ClasspathElement singleton for each classpath element, then call open()
        // on each ClasspathElement object, which in the case of jarfiles will cause LogicalZipFile instances
        // to be created for each (possibly nested) jarfile, then will read the manifest file and zip entries.
        final Set<ClasspathElement> openedClasspathEltsSet = Collections
                .newSetFromMap(new ConcurrentHashMap<ClasspathElement, Boolean>());
        final Queue<Entry<Integer, ClasspathElement>> toplevelClasspathEltOrder = new ConcurrentLinkedQueue<>();
        processWorkUnits(rawClasspathEntryWorkUnits,
                topLevelLog == null ? null : topLevelLog.log("Opening classpath elements"),
                newClasspathEntryWorkUnitProcessor(openedClasspathEltsSet, toplevelClasspathEltOrder));

        // Determine total ordering of classpath elements, inserting jars referenced in manifest Class-Path
        // entries in-place into the ordering, if they haven't been listed earlier in the classpath already.
        final List<ClasspathElement> classpathEltOrder = findClasspathOrder(openedClasspathEltsSet,
                toplevelClasspathEltOrder);

        // Find classpath elements that are path prefixes of other classpath elements, and for
        // ClasspathElementZip, get module-related manifest entry values
        preprocessClasspathElementsByType(classpathEltOrder,
                topLevelLog == null ? null : topLevelLog.log("Finding nested classpath elements"));

        // Order modules before classpath elements from traditional classpath 
        final LogNode classpathOrderLog = topLevelLog == null ? null
                : topLevelLog.log("Final classpath element order:");
        final int numElts = moduleOrder.size() + classpathEltOrder.size();
        final List<ClasspathElement> finalClasspathEltOrder = new ArrayList<>(numElts);
        final List<String> finalClasspathEltOrderStrs = new ArrayList<>(numElts);
        int classpathOrderIdx = 0;
        for (final ClasspathElementModule classpathElt : moduleOrder) {
            classpathElt.classpathElementIdx = classpathOrderIdx++;
            finalClasspathEltOrder.add(classpathElt);
            finalClasspathEltOrderStrs.add(classpathElt.toString());
            if (classpathOrderLog != null) {
                final ModuleRef moduleRef = classpathElt.getModuleRef();
                classpathOrderLog.log(moduleRef.toString());
            }
        }
        for (final ClasspathElement classpathElt : classpathEltOrder) {
            classpathElt.classpathElementIdx = classpathOrderIdx++;
            finalClasspathEltOrder.add(classpathElt);
            finalClasspathEltOrderStrs.add(classpathElt.toString());
            if (classpathOrderLog != null) {
                classpathOrderLog.log(classpathElt.toString());
            }
        }

        // In parallel, scan paths within each classpath element, comparing them against whitelist/blacklist
        processWorkUnits(finalClasspathEltOrder,
                topLevelLog == null ? null : topLevelLog.log("Scanning classpath elements"),
                new WorkUnitProcessor<ClasspathElement>() {
                    @Override
                    public void processWorkUnit(final ClasspathElement classpathElement,
                            final WorkQueue<ClasspathElement> workQueueIgnored, final LogNode pathScanLog)
                            throws InterruptedException {
                        // Scan the paths within the classpath element
                        classpathElement.scanPaths(pathScanLog);
                    }
                });

        // Filter out classpath elements that do not contain required whitelisted paths.
        List<ClasspathElement> finalClasspathEltOrderFiltered = finalClasspathEltOrder;
        if (!scanSpec.classpathElementResourcePathWhiteBlackList.whitelistIsEmpty()) {
            finalClasspathEltOrderFiltered = new ArrayList<>(finalClasspathEltOrder.size());
            for (final ClasspathElement classpathElement : finalClasspathEltOrder) {
                if (classpathElement.containsSpecificallyWhitelistedClasspathElementResourcePath) {
                    finalClasspathEltOrderFiltered.add(classpathElement);
                }
            }
        }

        if (performScan) {
            // Scan classpath / modules, producing a ScanResult.
            return performScan(finalClasspathEltOrderFiltered, finalClasspathEltOrderStrs,
                    classLoaderOrderRespectingParentDelegation);
        } else {
            // Only getting classpath -- return a placeholder ScanResult to hold classpath elements
            if (topLevelLog != null) {
                topLevelLog.log("Only returning classpath elements (not performing a scan)");
            }
            return new ScanResult(scanSpec, finalClasspathEltOrderFiltered, finalClasspathEltOrderStrs,
                    classLoaderOrderRespectingParentDelegation, /* classNameToClassInfo = */ null,
                    /* packageNameToPackageInfo = */ null, /* moduleNameToModuleInfo = */ null,
                    /* fileToLastModified = */ null, nestedJarHandler, topLevelLog);
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Determine the unique ordered classpath elements, and run a scan looking for file or classfile matches if
     * necessary.
     *
     * @return the scan result
     * @throws InterruptedException
     *             if scanning was interrupted
     * @throws CancellationException
     *             if scanning was cancelled
     * @throws ExecutionException
     *             if a worker threw an uncaught exception
     */
    @Override
    public ScanResult call() throws InterruptedException, CancellationException, ExecutionException {
        ScanResult scanResult = null;
        final long scanStart = System.currentTimeMillis();
        boolean removeTemporaryFilesAfterScan = scanSpec.removeTemporaryFilesAfterScan;
        try {
            // Perform the scan
            scanResult = openClasspathElementsThenScan();

            // Log total time after scan completes, and flush log
            if (topLevelLog != null) {
                topLevelLog.log("~",
                        String.format("Total time: %.3f sec", (System.currentTimeMillis() - scanStart) * .001));
                topLevelLog.flush();
            }

            // Call the ScanResultProcessor, if one was provided
            if (scanResultProcessor != null) {
                try {
                    scanResultProcessor.processScanResult(scanResult);
                } finally {
                    scanResult.close();
                }
            }

        } catch (final Throwable e) {
            if (topLevelLog != null) {
                topLevelLog.log("~",
                        e instanceof InterruptedException || e instanceof CancellationException
                                ? "Scan interrupted or canceled"
                                : e instanceof ExecutionException || e instanceof RuntimeException
                                        ? "Uncaught exception during scan"
                                        : e.getMessage(),
                        InterruptionChecker.getCause(e));
                // Flush the log
                topLevelLog.flush();
            }

            // Since an exception was thrown, remove temporary files
            removeTemporaryFilesAfterScan = true;

            // Stop any running threads (should not be needed, threads should already be quiescent)
            interruptionChecker.interrupt();

            if (failureHandler == null) {
                // If there is no failure handler set, re-throw the exception
                throw e;
            } else {
                // Otherwise, call the failure handler
                try {
                    failureHandler.onFailure(e);
                } catch (final Exception f) {
                    // The failure handler failed
                    if (topLevelLog != null) {
                        topLevelLog.log("~", "The failure handler threw an exception:", f);
                        topLevelLog.flush();
                    }
                    // Group the two exceptions into one, using the suppressed exception mechanism
                    // to show the scan exception below the failure handler exception
                    final ExecutionException failureHandlerException = new ExecutionException(
                            "Exception while calling failure handler", f);
                    failureHandlerException.addSuppressed(e);
                    // Throw a new ExecutionException (although this will probably be ignored,
                    // since any job with a FailureHandler was started with ExecutorService::execute
                    // rather than ExecutorService::submit)  
                    throw failureHandlerException;
                }
            }

        } finally {
            if (removeTemporaryFilesAfterScan) {
                // If removeTemporaryFilesAfterScan was set, remove temp files and close resources,
                // zipfiles and modules
                nestedJarHandler.close(topLevelLog);
            }
        }
        return scanResult;
    }
}
