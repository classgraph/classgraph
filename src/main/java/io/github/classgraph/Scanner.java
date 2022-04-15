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
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.AbstractMap.SimpleEntry;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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
import nonapi.io.github.classgraph.classpath.ClasspathOrder.ClasspathEntry;
import nonapi.io.github.classgraph.classpath.ModuleFinder;
import nonapi.io.github.classgraph.concurrency.AutoCloseableExecutorService;
import nonapi.io.github.classgraph.concurrency.InterruptionChecker;
import nonapi.io.github.classgraph.concurrency.SingletonMap;
import nonapi.io.github.classgraph.concurrency.SingletonMap.NewInstanceFactory;
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
            if (scanSpec.pathAcceptReject != null
                    && scanSpec.packagePrefixAcceptReject.isSpecificallyAccepted("")) {
                topLevelLog.log("Note: There is no need to accept the root package (\"\") -- not accepting "
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

        try {
            this.moduleOrder = new ArrayList<>();

            // Check if modules should be scanned
            final ModuleFinder moduleFinder = classpathFinder.getModuleFinder();
            if (moduleFinder != null) {
                // Add modules to start of classpath order, before traditional classpath
                final List<ModuleRef> systemModuleRefs = moduleFinder.getSystemModuleRefs();
                final ClassLoader[] classLoaderOrderRespectingParentDelegation = classpathFinder
                        .getClassLoaderOrderRespectingParentDelegation();
                final ClassLoader defaultClassLoader = classLoaderOrderRespectingParentDelegation != null
                        && classLoaderOrderRespectingParentDelegation.length != 0
                                ? classLoaderOrderRespectingParentDelegation[0]
                                : null;
                if (systemModuleRefs != null) {
                    for (final ModuleRef systemModuleRef : systemModuleRefs) {
                        final String moduleName = systemModuleRef.getName();
                        if (
                        // If scanning system packages and modules is enabled and accept/reject criteria are empty,
                        // then scan all system modules
                        (scanSpec.enableSystemJarsAndModules
                                && scanSpec.moduleAcceptReject.acceptAndRejectAreEmpty())
                                // Otherwise only scan specifically accepted system modules
                                || scanSpec.moduleAcceptReject.isSpecificallyAcceptedAndNotRejected(moduleName)) {
                            // Create a new ClasspathElementModule
                            final ClasspathElementModule classpathElementModule = new ClasspathElementModule(
                                    systemModuleRef, nestedJarHandler.moduleRefToModuleReaderProxyRecyclerMap,
                                    new ClasspathEntryWorkUnit(null, defaultClassLoader, null, moduleOrder.size(),
                                            ""),
                                    scanSpec);
                            moduleOrder.add(classpathElementModule);
                            // Open the ClasspathElementModule
                            classpathElementModule.open(/* ignored */ null, classpathFinderLog);
                        } else {
                            if (classpathFinderLog != null) {
                                classpathFinderLog
                                        .log("Skipping non-accepted or rejected system module: " + moduleName);
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
                        if (scanSpec.moduleAcceptReject.isAcceptedAndNotRejected(moduleName)) {
                            // Create a new ClasspathElementModule
                            final ClasspathElementModule classpathElementModule = new ClasspathElementModule(
                                    nonSystemModuleRef, nestedJarHandler.moduleRefToModuleReaderProxyRecyclerMap,
                                    new ClasspathEntryWorkUnit(null, defaultClassLoader, null, moduleOrder.size(),
                                            ""),
                                    scanSpec);
                            moduleOrder.add(classpathElementModule);
                            // Open the ClasspathElementModule
                            classpathElementModule.open(/* ignored */ null, classpathFinderLog);
                        } else {
                            if (classpathFinderLog != null) {
                                classpathFinderLog.log("Skipping non-accepted or rejected module: " + moduleName);
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
            // The classpath order requires a preorder traversal of the DAG of classpath dependencies
            if (!currClasspathElement.skipClasspathElement) {
                // Don't add a classpath element if it is marked to be skipped.
                order.add(currClasspathElement);
                // Whether or not a classpath element should be skipped, add any child classpath elements that are
                // not marked to be skipped (i.e. keep recursing below)
            }
            // Sort child elements into correct order, then traverse to them in order
            final List<ClasspathElement> childClasspathElementsSorted = CollectionUtils
                    .sortCopy(currClasspathElement.childClasspathElements);
            for (final ClasspathElement childClasspathElt : childClasspathElementsSorted) {
                findClasspathOrderRec(childClasspathElt, visitedClasspathElts, order);
            }
        }
    }

    /**
     * Recursively perform a depth-first traversal of child classpath elements, breaking cycles if necessary, to
     * determine the final classpath element order. This causes child classpath elements to be inserted in-place in
     * the classpath order, after the parent classpath element that contained them.
     *
     * @param toplevelClasspathElts
     *            the toplevel classpath elts, indexed by order within the toplevel classpath
     * @return the final classpath order, after depth-first traversal of child classpath elements
     */
    private List<ClasspathElement> findClasspathOrder(final Set<ClasspathElement> toplevelClasspathElts) {
        // Sort toplevel classpath elements into their correct order
        final List<ClasspathElement> toplevelClasspathEltsSorted = CollectionUtils.sortCopy(toplevelClasspathElts);

        // Perform a depth-first preorder traversal of the DAG of classpath elements
        final Set<ClasspathElement> visitedClasspathElts = new HashSet<>();
        final List<ClasspathElement> order = new ArrayList<>();
        for (final ClasspathElement elt : toplevelClasspathEltsSorted) {
            findClasspathOrderRec(elt, visitedClasspathElts, order);
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
        /** The classpath entry object (a {@link String} path, {@link Path}, {@link URL} or {@link URI}). */
        Object classpathEntryObj;

        /** The classloader the classpath entry object was obtained from. */
        final ClassLoader classLoader;

        /** The parent classpath element. */
        final ClasspathElement parentClasspathElement;

        /** The order within the parent classpath element. */
        final int classpathElementIdxWithinParent;

        /** The package root prefix (e.g. "BOOT-INF/classes/"). */
        final String packageRootPrefix;

        /**
         * Constructor.
         *
         * @param classpathEntryObj
         *            the raw classpath entry object
         * @param classLoader
         *            the classloader the classpath entry object was obtained from
         * @param parentClasspathElement
         *            the parent classpath element
         * @param classpathElementIdxWithinParent
         *            the order within parent classpath element
         * @param packageRootPrefix
         *            the package root prefix
         */
        public ClasspathEntryWorkUnit(final Object classpathEntryObj, final ClassLoader classLoader,
                final ClasspathElement parentClasspathElement, final int classpathElementIdxWithinParent,
                final String packageRootPrefix) {
            this.classpathEntryObj = classpathEntryObj;
            this.classLoader = classLoader;
            this.parentClasspathElement = parentClasspathElement;
            this.classpathElementIdxWithinParent = classpathElementIdxWithinParent;
            this.packageRootPrefix = packageRootPrefix;
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Normalize a classpath entry object so that it is mapped to a canonical {@link Path} object if possible,
     * falling back to a {@link URL} or {@link URI} if not possible. This is needed to avoid treating
     * "file:///path/to/x.jar" and "/path/to/x.jar" as different classpath elements. Maps URL("jar:file:x.jar!/") to
     * Path("x.jar"), etc.
     * 
     * @param classpathEntObj
     *            The classpath entry object.
     * @return The normalized classpath entry object.
     * @throws IOException
     */
    private static Object normalizeClasspathEntry(final Object classpathEntObj) throws IOException {
        if (classpathEntObj == null) {
            // Should not happen
            throw new IOException("Got null classpath entry object");
        }
        Object classpathEntryObjNormalized = classpathEntObj;

        // Convert URL/URI (or anything other than URL/URI, or Path) into a String.
        // Paths.get fails with "IllegalArgumentException: URI is not hierarchical"
        // for paths like "jar:file:myjar.jar!/" (#625) -- need to strip the "!/" off the end.
        // Also strip any "jar:file:" or "file:" off the beginning.
        // This normalizes "file:x.jar" and "x.jar" to the same string, for example.
        if (!(classpathEntryObjNormalized instanceof Path)) {
            classpathEntryObjNormalized = FastPathResolver.resolve(FileUtils.currDirPath(),
                    classpathEntryObjNormalized.toString());
        }

        // If classpath entry object is a URL-formatted string, convert to (or back to) a URL instance.
        if (classpathEntryObjNormalized instanceof String) {
            String classpathEntStr = (String) classpathEntryObjNormalized;
            final boolean isURL = JarUtils.URL_SCHEME_PATTERN.matcher(classpathEntStr).matches();
            final boolean isMultiSection = classpathEntStr.contains("!");
            if (isURL || isMultiSection) {
                // Convert back to URL (or URI) if this has a URL scheme or if this is a multi-section
                // path (which needs the "jar:file:" scheme)
                if (!isURL) {
                    // Add "file:" scheme if there is no scheme
                    classpathEntStr = "file:" + classpathEntStr;
                }
                if (isMultiSection) {
                    // Multi-section URL strings that do not already have a URL scheme need to
                    // have the "jar:file:" scheme
                    classpathEntStr = "jar:" + classpathEntStr;
                    // Also "jar:" URLs need at least one instance of "!/" -- if only "!" is used
                    // without a subsequent "/", replace it
                    classpathEntStr = classpathEntStr.replaceAll("!([^/])", "!/$1");
                }
                try {
                    // Convert classpath entry to (or back to) a URL.
                    classpathEntryObjNormalized = new URL(classpathEntStr);
                } catch (final MalformedURLException e) {
                    // Try creating URI if URL creation fails, in case there is a URI-only scheme
                    try {
                        classpathEntryObjNormalized = new URI(classpathEntStr);
                    } catch (final URISyntaxException e1) {
                        throw new IOException("Malformed URI: " + classpathEntryObjNormalized + " : " + e1);
                    }
                }
            }
            // Last-ditch effort -- try to convert String to Path
            if (classpathEntryObjNormalized instanceof String) {
                try {
                    classpathEntryObjNormalized = Paths.get((String) classpathEntryObjNormalized);
                } catch (final InvalidPathException e) {
                    throw new IOException("Malformed path: " + classpathEntryObjNormalized + " : " + e);
                }
            }
        }
        // At this point, String is dealt with and String, URL, and URI classpath elements are all
        // normalized together. classpathEntObj is either a URL, URI, or Path.

        // If classpath entry object is a URL or a URI, try seeing if it can be mapped to a Path
        if (classpathEntryObjNormalized instanceof URL) {
            final URL classpathEntryURL = (URL) classpathEntryObjNormalized;
            final String scheme = classpathEntryURL.getProtocol();
            if (!"http".equals(scheme) && !"https".equals(scheme)) {
                try {
                    final URI classpathEntryURI = classpathEntryURL.toURI();
                    // See if the URL resolves to a file or directory via the Path API
                    classpathEntryObjNormalized = Paths.get(classpathEntryURI);
                } catch (final URISyntaxException | IllegalArgumentException | SecurityException e1) {
                    // URI cannot be represented as a URI or as a Path, so it probably is a multi-section URI
                    // (representing a nested jar, or a jar URI with a non-empty package root).
                } catch (final FileSystemNotFoundException e) {
                    // This is a custom URL scheme without a backing FileSystem
                }
            } // else this is a remote jar URL

        } else if (classpathEntryObjNormalized instanceof URI) {
            final URI classpathEntryURI = (URI) classpathEntryObjNormalized;
            final String scheme = classpathEntryURI.getScheme();
            if (!"http".equals(scheme) && !"https".equals(scheme)) {
                try {
                    // See if the URI resolves to a file or directory via the Path API
                    classpathEntryObjNormalized = Paths.get(classpathEntryURI);
                } catch (final IllegalArgumentException | SecurityException e) {
                    // URL cannot be represented as a Path, so it probably is a multi-section URL
                    // (representing a nested jar, or a jar URL with a non-empty package root).
                } catch (final FileSystemNotFoundException e) {
                    // This is a custom URI scheme without a backing FileSystem
                }
            } // else this is a remote jar URL
        }

        // Canonicalize Path objects so the same file is opened only once
        if (classpathEntryObjNormalized instanceof Path) {
            try {
                // Canonicalize path, to avoid duplication
                // Throws  IOException if the file does not exist or an I/O error occurs
                classpathEntryObjNormalized = ((Path) classpathEntryObjNormalized).toRealPath();
            } catch (final SecurityException e) {
                // Ignore
            }
        }

        return classpathEntryObjNormalized;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * A singleton map used to eliminate creation of duplicate {@link ClasspathElement} objects, to reduce the
     * chance that resources are scanned twice, by mapping canonicalized Path objects, URLs, etc. to
     * ClasspathElements.
     */
    private final SingletonMap<Object, ClasspathElement, IOException> //
    classpathEntryObjToClasspathEntrySingletonMap = //
            new SingletonMap<Object, ClasspathElement, IOException>() {
                @Override
                public ClasspathElement newInstance(final Object classpathEntryObj, final LogNode log)
                        throws IOException, InterruptedException {
                    // Overridden by a NewInstanceFactory
                    throw new IOException("Should not reach here");
                }
            };

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Create a WorkUnitProcessor for opening traditional classpath entries (which are mapped to
     * {@link ClasspathElementDir} or {@link ClasspathElementZip} -- {@link ClasspathElementModule is handled
     * separately}).
     *
     * @param allClasspathEltsOut
     *            on exit, the set of all classpath elements
     * @param toplevelClasspathEltsOut
     *            on exit, the toplevel classpath elements
     * @param ClasspathEltOrder
     *            the toplevel classpath elt order
     * @return the work unit processor
     */
    private WorkUnitProcessor<ClasspathEntryWorkUnit> newClasspathEntryWorkUnitProcessor(
            final Set<ClasspathElement> allClasspathEltsOut, final Set<ClasspathElement> toplevelClasspathEltsOut) {
        return new WorkUnitProcessor<ClasspathEntryWorkUnit>() {
            @Override
            public void processWorkUnit(final ClasspathEntryWorkUnit workUnit,
                    final WorkQueue<ClasspathEntryWorkUnit> workQueue, final LogNode log)
                    throws InterruptedException {
                try {
                    // Normalize the classpath entry object
                    workUnit.classpathEntryObj = normalizeClasspathEntry(workUnit.classpathEntryObj);

                    // Determine if classpath entry is a jar or dir
                    boolean isJar = false;
                    if (workUnit.classpathEntryObj instanceof URL || workUnit.classpathEntryObj instanceof URI) {
                        // URLs and URIs always point to jars
                        isJar = true;
                    } else if (workUnit.classpathEntryObj instanceof Path) {
                        final Path path = (Path) workUnit.classpathEntryObj;
                        if (FileUtils.canReadAndIsFile(path)) {
                            // classpathEntObj is a Path which points to a file, so it must be a jar
                            isJar = true;
                        } else if (FileUtils.canReadAndIsDir(path)) {
                            if ("JrtFileSystem".equals(path.getFileSystem().getClass().getSimpleName())) {
                                // Ignore JrtFileSystem (#553) -- paths are of form:
                                // /modules/java.base/module-info.class
                                throw new IOException("Ignoring JrtFS filesystem path " + workUnit.classpathEntryObj
                                        + " (modules are scanned using the JPMS API)");
                            }
                            // classpathEntObj is a Path which points to a dir
                        } else if (!FileUtils.canRead(path)) {
                            throw new IOException("Cannot read path: " + path);
                        }
                    } else {
                        // Should not happen
                        throw new IOException("Got unexpected classpath entry object type "
                                + workUnit.classpathEntryObj.getClass().getName() + " : "
                                + workUnit.classpathEntryObj);
                    }

                    // Create a ClasspathElementZip or ClasspathElementDir from the classpath entry
                    // Use a singleton map to ensure that classpath elements are only opened once
                    // per unique Path, URL, or URI
                    final boolean isJarFinal = isJar;
                    classpathEntryObjToClasspathEntrySingletonMap.get(workUnit.classpathEntryObj, log,
                            // A NewInstanceFactory is used here because workUnit has to be passed in,
                            // and the standard newInstance API doesn't support an extra parameter like this
                            new NewInstanceFactory<ClasspathElement, IOException>() {
                                @Override
                                public ClasspathElement newInstance() throws IOException, InterruptedException {
                                    final ClasspathElement cpElt = isJarFinal
                                            ? new ClasspathElementZip(workUnit.classpathEntryObj, workUnit,
                                                    nestedJarHandler, scanSpec)
                                            : new ClasspathElementDir((Path) workUnit.classpathEntryObj, workUnit,
                                                    nestedJarHandler, scanSpec);

                                    allClasspathEltsOut.add(cpElt);

                                    // Run open() on the ClasspathElement
                                    final LogNode subLog = log == null ? null
                                            : log.log("Opening classpath element " + cpElt);

                                    // Check if the classpath element is valid (classpathElt.skipClasspathElement
                                    // will be set if not). In case of ClasspathElementZip, open or extract nested
                                    // jars as LogicalZipFile instances. Read manifest files for jarfiles to look
                                    // for Class-Path manifest entries. Adds extra classpath elements to the work
                                    // queue if they are found.
                                    cpElt.open(workQueue, subLog);

                                    if (workUnit.parentClasspathElement != null) {
                                        // Link classpath element to its parent, if it is not a toplevel element
                                        workUnit.parentClasspathElement.childClasspathElements.add(cpElt);
                                    } else {
                                        toplevelClasspathEltsOut.add(cpElt);
                                    }

                                    return cpElt;
                                }
                            });

                } catch (final Exception e) {
                    if (log != null) {
                        log.log("Skipping invalid classpath entry " + workUnit.classpathEntryObj + " : "
                                + (e.getCause() == null ? e : e.getCause()));
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
         * The names of accepted classes found in the classpath while scanning paths within classpath elements.
         */
        private final Set<String> acceptedClassNamesFound;

        /**
         * The names of external (non-accepted) classes scheduled for extended scanning (where scanning is extended
         * upwards to superclasses, interfaces and annotations).
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
         * @param acceptedClassNamesFound
         *            the names of accepted classes found in the classpath while scanning paths within classpath
         *            elements.
         * @param scannedClassfiles
         *            the {@link Classfile} objects created by scanning classfiles
         */
        public ClassfileScannerWorkUnitProcessor(final ScanSpec scanSpec,
                final List<ClasspathElement> classpathOrder, final Set<String> acceptedClassNamesFound,
                final Queue<Classfile> scannedClassfiles) {
            this.scanSpec = scanSpec;
            this.classpathOrder = classpathOrder;
            this.acceptedClassNamesFound = acceptedClassNamesFound;
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
                        acceptedClassNamesFound, classNamesScheduledForExtendedScanning,
                        workUnit.classfileResource.getPath(), workUnit.classfileResource, workUnit.isExternalClass,
                        stringInternMap, workQueue, scanSpec, subLog);

                // Enqueue the classfile for linking
                scannedClassfiles.add(classfile);

                if (subLog != null) {
                    subLog.addElapsedTime();
                }
            } catch (final SkipClassException e) {
                if (subLog != null) {
                    subLog.log(workUnit.classfileResource.getPath(), "Skipping classfile: " + e.getMessage());
                    subLog.addElapsedTime();
                }
            } catch (final ClassfileFormatException e) {
                if (subLog != null) {
                    subLog.log(workUnit.classfileResource.getPath(), "Invalid classfile: " + e.getMessage());
                    subLog.addElapsedTime();
                }
            } catch (final IOException e) {
                if (subLog != null) {
                    subLog.log(workUnit.classfileResource.getPath(), "Could not read classfile: " + e);
                    subLog.addElapsedTime();
                }
            } catch (final Exception e) {
                if (subLog != null) {
                    subLog.log(workUnit.classfileResource.getPath(), "Could not read classfile", e);
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
                // Separate out ClasspathElementFileDir and ClasspathElementPathDir elements from other types
                final File file = classpathElt.getFile();
                final String path = file == null ? classpathElt.toString() : file.getPath();
                classpathEltDirs.add(new SimpleEntry<>(path, classpathElt));

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
        final Set<String> acceptedClasspathRelativePathsFound = new HashSet<>();
        for (int classpathIdx = 0; classpathIdx < classpathElementOrder.size(); classpathIdx++) {
            final ClasspathElement classpathElement = classpathElementOrder.get(classpathIdx);
            classpathElement.maskClassfiles(classpathIdx, acceptedClasspathRelativePathsFound, maskLog);
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
     * @param classpathFinder
     *            the {@link ClasspathFinder}
     * @return the scan result
     * @throws InterruptedException
     *             if the scan was interrupted
     * @throws ExecutionException
     *             if the scan threw an uncaught exception
     */
    private ScanResult performScan(final List<ClasspathElement> finalClasspathEltOrder,
            final List<String> finalClasspathEltOrderStrs, final ClasspathFinder classpathFinder)
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
            // Get accepted classfile order
            final List<ClassfileScanWorkUnit> classfileScanWorkItems = new ArrayList<>();
            final Set<String> acceptedClassNamesFound = new HashSet<>();
            for (final ClasspathElement classpathElement : finalClasspathEltOrder) {
                // Get classfile scan order across all classpath elements
                for (final Resource resource : classpathElement.acceptedClassfileResources) {
                    // Create a set of names of all accepted classes found in classpath element paths,
                    // and double-check that a class is not going to be scanned twice
                    final String className = JarUtils.classfilePathToClassName(resource.getPath());
                    if (!acceptedClassNamesFound.add(className) && !className.equals("module-info")
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
                            Collections.unmodifiableSet(acceptedClassNamesFound), scannedClassfiles);
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
        return new ScanResult(scanSpec, finalClasspathEltOrder, finalClasspathEltOrderStrs, classpathFinder,
                classNameToClassInfo, packageNameToPackageInfo, moduleNameToModuleInfo, fileToLastModified,
                nestedJarHandler, topLevelLog);
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
        final List<ClasspathEntry> rawClasspathOrder = classpathFinder.getClasspathOrder().getOrder();
        for (final ClasspathEntry rawClasspathEntry : rawClasspathOrder) {
            rawClasspathEntryWorkUnits.add(new ClasspathEntryWorkUnit(rawClasspathEntry.classpathEntryObj,
                    rawClasspathEntry.classLoader, /* parentClasspathElement = */ null,
                    // classpathElementIdxWithinParent is the original classpath index,
                    // for toplevel classpath elements
                    /* classpathElementIdxWithinParent = */ rawClasspathEntryWorkUnits.size(),
                    /* packageRootPrefix = */ ""));
        }

        // In parallel, create a ClasspathElement singleton for each classpath element, then call open()
        // on each ClasspathElement object, which in the case of jarfiles will cause LogicalZipFile instances
        // to be created for each (possibly nested) jarfile, then will read the manifest file and zip entries.
        final Set<ClasspathElement> allClasspathElts = Collections
                .newSetFromMap(new ConcurrentHashMap<ClasspathElement, Boolean>());
        final Set<ClasspathElement> toplevelClasspathElts = Collections
                .newSetFromMap(new ConcurrentHashMap<ClasspathElement, Boolean>());
        processWorkUnits(rawClasspathEntryWorkUnits,
                topLevelLog == null ? null : topLevelLog.log("Opening classpath elements"),
                newClasspathEntryWorkUnitProcessor(allClasspathElts, toplevelClasspathElts));

        // Determine total ordering of classpath elements, inserting jars referenced in manifest Class-Path
        // entries in-place into the ordering, if they haven't been listed earlier in the classpath already.
        final List<ClasspathElement> classpathEltOrder = findClasspathOrder(toplevelClasspathElts);

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

        // In parallel, scan paths within each classpath element, comparing them against accept/reject
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

        // Filter out classpath elements that do not contain required accepted paths.
        List<ClasspathElement> finalClasspathEltOrderFiltered = finalClasspathEltOrder;
        if (!scanSpec.classpathElementResourcePathAcceptReject.acceptIsEmpty()) {
            finalClasspathEltOrderFiltered = new ArrayList<>(finalClasspathEltOrder.size());
            for (final ClasspathElement classpathElement : finalClasspathEltOrder) {
                if (classpathElement.containsSpecificallyAcceptedClasspathElementResourcePath) {
                    finalClasspathEltOrderFiltered.add(classpathElement);
                }
            }
        }

        if (performScan) {
            // Scan classpath / modules, producing a ScanResult.
            return performScan(finalClasspathEltOrderFiltered, finalClasspathEltOrderStrs, classpathFinder);
        } else {
            // Only getting classpath -- return a placeholder ScanResult to hold classpath elements
            if (topLevelLog != null) {
                topLevelLog.log("Only returning classpath elements (not performing a scan)");
            }
            return new ScanResult(scanSpec, finalClasspathEltOrderFiltered, finalClasspathEltOrderStrs,
                    classpathFinder, /* classNameToClassInfo = */ null, /* packageNameToPackageInfo = */ null,
                    /* moduleNameToModuleInfo = */ null, /* fileToLastModified = */ null, nestedJarHandler,
                    topLevelLog);
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
                } catch (final Exception e) {
                    scanResult.close();
                    throw new ExecutionException(e);
                }
                scanResult.close();
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
                if (removeTemporaryFilesAfterScan) {
                    // If removeTemporaryFilesAfterScan was set, remove temp files and close resources,
                    // zipfiles and modules
                    nestedJarHandler.close(topLevelLog);
                }
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
                    if (removeTemporaryFilesAfterScan) {
                        // If removeTemporaryFilesAfterScan was set, remove temp files and close resources,
                        // zipfiles and modules
                        nestedJarHandler.close(topLevelLog);
                    }
                    // Throw a new ExecutionException (although this will probably be ignored,
                    // since any job with a FailureHandler was started with ExecutorService::execute
                    // rather than ExecutorService::submit)  
                    throw failureHandlerException;
                }
            }
        }

        if (removeTemporaryFilesAfterScan) {
            // If removeTemporaryFilesAfterScan was set, remove temp files and close resources,
            // zipfiles and modules
            nestedJarHandler.close(topLevelLog);
        }
        return scanResult;
    }
}
