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
 * Copyright (c) 2026 Luke Hutchison
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
import java.lang.module.ModuleReference;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.AbstractMap.SimpleEntry;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;

import io.github.classgraph.Classfile.ClassfileFormatException;
import io.github.classgraph.Classfile.SkipClassException;
import io.github.classgraph.WorkQueue.WorkUnitProcessor;
import io.github.classgraph.base.LogNode;
import io.github.classgraph.base.internal.concurrency.InterruptionChecker;
import io.github.classgraph.base.internal.concurrency.SingletonMap;
import io.github.classgraph.base.internal.path.FastPathResolver;
import io.github.classgraph.base.internal.path.FileUtils;
import io.github.classgraph.base.internal.path.PathList;
import io.github.classgraph.base.internal.path.PathSyntax;
import io.github.classgraph.base.internal.utils.CollectionUtils;
import io.github.classgraph.classpath.ClassLoaderHandler;
import io.github.classgraph.classpath.internal.ScanSourceSpec;
import io.github.classgraph.classpath.internal.CallStackInfo;
import io.github.classgraph.classpath.internal.ClassLoaderProbe;
import io.github.classgraph.vfs.Vfs;
import org.jspecify.annotations.Nullable;

/** The classpath scanner. */
class Scanner implements Callable<ScanResult> {

    /** The scan spec. */
    private final ScanSpec scanSpec;

    /** If true, performing a scan. If false, only fetching the classpath. */
    private final boolean performScan;

    /** The virtual filesystem that everything on the classpath and the module path is read through. */
    private final Vfs vfs;

    /** The executor service. */
    private final ExecutorService executorService;

    /** The interruption checker. */
    private final InterruptionChecker interruptionChecker;

    /** The number of parallel tasks. */
    private final int numParallelTasks;

    /** The scan result processor, or null if none was provided. */
    private final @Nullable Consumer<ScanResult> scanResultProcessor;

    /** The failure handler, or null if none was provided. */
    private final @Nullable Consumer<Throwable> failureHandler;

    /** The toplevel log. */
    private final @Nullable LogNode topLevelLog;

    /**
     * The toplevel classpath entries found by the {@link ClassLoaderProbe}, in classpath order, ready to be opened.
     * These are extracted from the {@link ClassLoaderProbe} in the constructor so that the {@link ClassLoaderProbe}
     * and everything it holds (in particular its strong references to classloaders) can be discarded as soon as the
     * classpath has been found.
     */
    private final List<ClasspathEntryWorkUnit> rawClasspathEntryWorkUnits;

    /** The module order. */
    private final List<ClasspathElementModule> moduleOrder;

    /**
     * The modules that are not being scanned, but whose classfiles may still be read in order to complete the class
     * graph above an accepted class.
     */
    private final UnscannedModules unscannedModules;

    // -------------------------------------------------------------------------------------------------------------

    /**
     * The classpath scanner. Scanning is started by calling {@link #call()} on this object.
     *
     * @param performScan
     *            If true, performing a scan. If false, only fetching the classpath.
     * @param callStackInfo
     *            the call stack of the thread that asked for the scan, read by that thread before the scan started
     * @param scanSpec
     *            the scan spec
     * @param scanSourceSpec
     *            the places that classpath elements and modules are looked for
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
     */
    Scanner(final boolean performScan, final CallStackInfo callStackInfo, final ScanSpec scanSpec,
            final ScanSourceSpec scanSourceSpec, final ExecutorService executorService, final int numParallelTasks,
            final @Nullable Consumer<ScanResult> scanResultProcessor,
            final @Nullable Consumer<Throwable> failureHandler, final @Nullable LogNode topLevelLog) {
        this.scanSpec = scanSpec;
        this.performScan = performScan;
        scanSpec.sortPrefixes();
        scanSpec.log(topLevelLog);
        scanSourceSpec.log(topLevelLog);
        if (topLevelLog != null) {
            if (scanSpec.packagePrefixAcceptReject.isSpecificallyAccepted("")) {
                topLevelLog.log("Note: There is no need to accept the root package (\"\") -- not accepting "
                        + "anything will have the same effect of causing all packages to be scanned");
            }
            topLevelLog.log("Number of worker threads: " + numParallelTasks);
        }

        this.executorService = executorService;
        this.interruptionChecker = executorService instanceof final AutoCloseableExecutorService autoCloseableExecSvc
                ? autoCloseableExecSvc.interruptionChecker
                : new InterruptionChecker();
        // The virtual filesystem owns the file handles, memory mappings and temporary files that everything read
        // during the scan is backed by. It is given no log node of its own, since each part of the scan passes the
        // log node that what it reads should be logged under.
        this.vfs = new Vfs(scanSpec.vfsSpec, interruptionChecker);
        this.numParallelTasks = numParallelTasks;
        this.scanResultProcessor = scanResultProcessor;
        this.failureHandler = failureHandler;
        this.topLevelLog = topLevelLog;

        final var classLoaderProbeLog = topLevelLog == null ? null : topLevelLog.log("Finding classpath");

        // Nothing closes the virtual filesystem if the constructor does not return, since the caller is never
        // handed the Scanner, so anything opened before the failure (a module, or a classpath element filter's
        // classpath element) has to be released here. Caller-supplied code runs during construction -- a
        // ClassLoaderHandler, a classpath element filter -- so the failure can be of any type
        try {
            // The ClassLoaderProbe is deliberately not stored in a field: it holds the classloaders that were used
            // to find the classpath, and those must not be kept alive for the duration of the scan. It is the last
            // thing in a scan to hold a classloader at all -- from here on, only the string form of each
            // classloader is kept
            final var classLoaderProbe = new ClassLoaderProbe(callStackInfo, scanSpec.classpathSpec, scanSourceSpec,
                    classLoaderProbeLog);

            this.moduleOrder = new ArrayList<>();
            final List<ModuleReference> unscannedModuleReferences = new ArrayList<>();

            // Add modules to start of classpath order, before traditional classpath
            final var defaultClassLoaderStr = Objects.toString(classLoaderProbe.getDefaultClassLoader(), null);
            final var moduleFinder = classLoaderProbe.getModuleFinder();
            if (moduleFinder != null) {
                addModules(moduleFinder.getSystemModuleReferences(), /* isSystemModules = */ true,
                        defaultClassLoaderStr, unscannedModuleReferences, classLoaderProbeLog);
                addModules(moduleFinder.getNonSystemModuleReferences(), /* isSystemModules = */ false,
                        defaultClassLoaderStr, unscannedModuleReferences, classLoaderProbeLog);
            } else {
                // No module source was enabled, so no modules were looked for, and none are scanned -- but the
                // classfile of a class in a module of the boot layer can still be read, in order to complete the
                // class graph above an accepted class (#902), e.g. so that java.lang.Object is still found at the
                // top of every superclass chain
                for (final var resolvedModule : ModuleLayer.boot().configuration().modules()) {
                    final var moduleReference = resolvedModule.reference();
                    if (!scanSpec.classpathSpec.moduleAcceptReject
                            .isRejected(moduleReference.descriptor().name())) {
                        unscannedModuleReferences.add(moduleReference);
                    }
                }
                if (classLoaderProbeLog != null) {
                    classLoaderProbeLog.log("No module source was enabled, so no module is scanned, but the "
                            + "classfiles of the " + unscannedModuleReferences.size()
                            + " modules of the boot layer can still be read, in order to complete the class graph "
                            + "above an accepted class");
                }
            }
            this.unscannedModules = new UnscannedModules(unscannedModuleReferences, defaultClassLoaderStr, vfs,
                    scanSpec);

            // Turn the toplevel classpath entries into work units, so that the ClassLoaderProbe (and the classloader
            // references it holds) can be discarded now that the classpath has been found
            this.rawClasspathEntryWorkUnits = new ArrayList<>();
            for (final var rawClasspathEntry : classLoaderProbe.getClasspathOrder().getOrder()) {
                rawClasspathEntryWorkUnits.add(new ClasspathEntryWorkUnit(rawClasspathEntry.classpathEntryObj,
                        rawClasspathEntry.getClassLoaderString(), /* parentClasspathElement = */ null,
                        // classpathElementIdxWithinParent is the original classpath index, for toplevel classpath
                        // elements
                        /* classpathElementIdxWithinParent = */ rawClasspathEntryWorkUnits.size(),
                        /* packageRootPrefix = */ "", rawClasspathEntry.packageRootPrefixes,
                        rawClasspathEntry.libDirPrefixes));
            }
        } catch (final Throwable e) {
            vfs.close();
            throw e;
        }
    }

    /**
     * Add each accepted module to {@link #moduleOrder} as an open {@link ClasspathElementModule}, and add each
     * module that is neither accepted nor rejected to {@code unscannedModuleReferences}.
     *
     * @param moduleReferences
     *            the modules, or null if none were found
     * @param isSystemModules
     *            true if these are the system modules
     * @param defaultClassLoaderStr
     *            the string form of the classloader to record for each module, or null if there is none
     * @param unscannedModuleReferences
     *            the list to add non-accepted, non-rejected modules to
     * @param classLoaderProbeLog
     *            the log node, or null to skip logging
     */
    private void addModules(final @Nullable List<ModuleReference> moduleReferences, final boolean isSystemModules,
            final @Nullable String defaultClassLoaderStr, final List<ModuleReference> unscannedModuleReferences,
            final @Nullable LogNode classLoaderProbeLog) {
        if (moduleReferences == null) {
            return;
        }
        for (final ModuleReference moduleReference : moduleReferences) {
            final var moduleName = moduleReference.descriptor().name();
            // A module of a kind that is not being scanned is only listed, so that the classfile of a class in it
            // can still be read to complete the class graph above an accepted class (#902). A module of a kind that
            // is being scanned follows the accept/reject rule, so rejecting one module leaves the rest scannable
            // (#658).
            final var isAccepted = (isSystemModules ? scanSpec.classpathSpec.scanSystemModules
                    : scanSpec.classpathSpec.scanNonSystemModules)
                    && scanSpec.classpathSpec.moduleAcceptReject.isAcceptedAndNotRejected(moduleName);
            if (isAccepted) {
                // Create a new ClasspathElementModule
                final var classpathElementModule = new ClasspathElementModule(moduleReference, vfs,
                        new ClasspathEntryWorkUnit(null, defaultClassLoaderStr, null, moduleOrder.size(), "",
                                ClassLoaderHandler.NO_PACKAGE_ROOT_PREFIXES,
                                ClassLoaderHandler.NO_LIB_DIR_PREFIXES),
                        /* isLookupOnly = */ false, scanSpec);
                moduleOrder.add(classpathElementModule);
                // Open the ClasspathElementModule
                classpathElementModule.open(/* ignored */ null, classLoaderProbeLog);
            } else {
                // A module that is not being scanned can still have the classfiles of individual classes read from
                // it, in order to complete the class graph above an accepted class -- but not if the module was
                // rejected (#902)
                if (!scanSpec.classpathSpec.moduleAcceptReject.isRejected(moduleName)) {
                    unscannedModuleReferences.add(moduleReference);
                }
                if (classLoaderProbeLog != null) {
                    classLoaderProbeLog.log("Skipping non-accepted or rejected "
                            + (isSystemModules ? "system module: " : "module: ") + moduleName);
                }
            }
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
            // Sort child elements into the order they were listed in by this classpath element, then traverse to
            // them in order
            final var childClasspathElementsSorted = CollectionUtils
                    .sortCopy(currClasspathElement.childClasspathElements);
            for (final ClasspathElement.ChildClasspathElement childClasspathElt : childClasspathElementsSorted) {
                findClasspathOrderRec(childClasspathElt.classpathElement(), visitedClasspathElts, order);
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
    private static List<ClasspathElement> findClasspathOrder(final Set<ClasspathElement> toplevelClasspathElts) {
        // Sort toplevel classpath elements into their correct order
        final var toplevelClasspathEltsSorted = CollectionUtils.sortCopy(toplevelClasspathElts);

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
    private <W> void processWorkUnits(final Collection<W> workUnits, final @Nullable LogNode log,
            final WorkUnitProcessor<W> workUnitProcessor) throws InterruptedException, ExecutionException {
        WorkQueue.runWorkQueue(workUnits, executorService, interruptionChecker, numParallelTasks,
                scanSpec.getWorkerTimeoutNanos(), log, workUnitProcessor);
        if (log != null) {
            log.addElapsedTime();
        }
        // Throw InterruptedException if any of the workers failed
        interruptionChecker.check();
    }

    // -------------------------------------------------------------------------------------------------------------

    /** Used to enqueue classpath elements for opening. */
    static class ClasspathEntryWorkUnit {
        /**
         * The classpath entry object (a {@link String} path, {@link Path}, {@link URL} or {@link URI}), or null for
         * module classpath entries.
         */
        @Nullable
        Object classpathEntryObj;

        /**
         * The string form of the classloader the classpath entry object was obtained from, or null if unknown. Only
         * the string is kept, not the classloader itself, so that scanning does not keep a classloader alive.
         */
        final @Nullable String classLoaderStr;

        /** The parent classpath element, or null if this is a toplevel entry. */
        final @Nullable ClasspathElement parentClasspathElement;

        /** The order within the parent classpath element. */
        final int classpathElementIdxWithinParent;

        /** The package root prefix (e.g. "BOOT-INF/classes/"). */
        final String packageRootPrefix;

        /**
         * The automatic package root prefixes to look for within this classpath element, as declared by the
         * {@code ClassLoaderHandler} that found it.
         */
        final List<String> packageRootPrefixes;

        /**
         * The lib dirs (e.g. {@code "BOOT-INF/lib/"}) whose jarfiles are to be added to the classpath if they are
         * present within this classpath element, as declared by the {@code ClassLoaderHandler} that found it.
         */
        final List<String> libDirPrefixes;

        /**
         * Constructor.
         *
         * @param classpathEntryObj
         *            the raw classpath entry object
         * @param classLoaderStr
         *            the string form of the classloader the classpath entry object was obtained from
         * @param parentClasspathElement
         *            the parent classpath element
         * @param classpathElementIdxWithinParent
         *            the order within parent classpath element
         * @param packageRootPrefix
         *            the package root prefix
         * @param packageRootPrefixes
         *            the automatic package root prefixes to look for within this classpath element
         * @param libDirPrefixes
         *            the lib dirs whose jarfiles are to be added to the classpath, within this classpath element
         */
        public ClasspathEntryWorkUnit(final @Nullable Object classpathEntryObj,
                final @Nullable String classLoaderStr, final @Nullable ClasspathElement parentClasspathElement,
                final int classpathElementIdxWithinParent, final String packageRootPrefix,
                final List<String> packageRootPrefixes, final List<String> libDirPrefixes) {
            this.classpathEntryObj = classpathEntryObj;
            this.classLoaderStr = classLoaderStr;
            this.parentClasspathElement = parentClasspathElement;
            this.classpathElementIdxWithinParent = classpathElementIdxWithinParent;
            this.packageRootPrefix = packageRootPrefix;
            this.packageRootPrefixes = packageRootPrefixes;
            this.libDirPrefixes = libDirPrefixes;
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Normalize a classpath entry object so that it is mapped to a canonical {@link Path} object if possible,
     * falling back to a {@link URL} or {@link URI} if not possible. This is needed to avoid treating
     * "file:///path/to/x.jar" and "/path/to/x.jar" as different classpath elements. Maps URL("jar:file:x.jar!/") to
     * Path("x.jar"), etc.
     *
     * @param classpathEntryObj
     *            The classpath entry object.
     * @return The normalized classpath entry object.
     * @throws IOException
     *             if the classpath entry object is null, or could not be normalized.
     */
    private static Object normalizeClasspathEntry(final @Nullable Object classpathEntryObj) throws IOException {
        if (classpathEntryObj == null) {
            // Should not happen
            throw new IOException("Got null classpath entry object");
        }
        var classpathEntryObjNormalized = classpathEntryObj;

        // Convert URL/URI (or anything other than URL/URI, or Path) into a String. Paths.get fails with
        // "IllegalArgumentException: URI is not hierarchical" for paths like "jar:file:myjar.jar!/" (#625) -- need
        // to strip the "!/" off the end. Also strip any "jar:file:" or "file:" off the beginning. This normalizes
        // "file:x.jar" and "x.jar" to the same string, for example.
        if (!(classpathEntryObjNormalized instanceof Path)) {
            classpathEntryObjNormalized = FastPathResolver.resolveFilePath(FileUtils.currDirPath(),
                    classpathEntryObjNormalized.toString());
        }

        // If classpath entry object is a URL-formatted string, convert to (or back to) a URL instance.
        if (classpathEntryObjNormalized instanceof final String classpathEntStr) {
            final var isURL = PathSyntax.hasURLScheme(classpathEntStr);
            // A '!' is only a nested jar separator if the path before it names an existing jarfile -- it is
            // otherwise a legal filename character, and must not be treated as a separator (#903)
            final var isMultiSection = PathSyntax.indexOfNestedJarSeparator(classpathEntStr) >= 0;
            if (isURL || isMultiSection) {
                // A "jar:" URL needs every separator spelled "!/". ClassGraph accepts a bare '!' as well, so put
                // back any '/' that is missing before the string is turned into a URL -- but only after a '!' that
                // really is a separator, since '!' is also a legal character in a file or entry name (#903)
                classpathEntryObjNormalized = normalizeUrlFormattedClasspathEntry(
                        isMultiSection ? PathSyntax.toJarUrlSeparators(classpathEntStr) : classpathEntStr, isURL,
                        isMultiSection);
            }
            // Last-ditch effort -- try to convert String to Path
            if (classpathEntryObjNormalized instanceof final String pathStr) {
                try {
                    classpathEntryObjNormalized = new File(pathStr).toPath();
                } catch (final Exception e) {
                    try {
                        classpathEntryObjNormalized = Path.of(pathStr);
                    } catch (final InvalidPathException e2) {
                        throw new IOException("Malformed path: " + classpathEntryObj + " : " + e2);
                    }
                }
            }
        }
        // At this point, classpathEntryObjNormalized is either a Path wherever possible (where the classpath entry
        // pointed to a jarfile or directory) or a URL/URI (for multi-section "jar:" URLs with "!" separators,
        // custom URL schemes without backing filesystems, or URLs that can't be turned into a Path for any other
        // reason).

        // Canonicalize Path objects so the same file is opened only once
        if (classpathEntryObjNormalized instanceof final Path normalizedPath) {
            try {
                classpathEntryObjNormalized = FileUtils.canonicalize(normalizedPath);
            } catch (final IOException | SecurityException e) {
                // The path could not be canonicalized -- use it as given
            }
        }

        return classpathEntryObjNormalized;
    }

    /**
     * Convert a classpath entry string that has a URL scheme, or that is a multi-section path containing one or
     * more {@code '!'} nested jar separators, to a {@link Path} if it names a file or directory that the
     * {@link Path} API can reach, and to a {@link URL} or {@link URI} otherwise.
     *
     * @param classpathEntryStr
     *            the classpath entry string, after resolution by {@link FastPathResolver}.
     * @param isURL
     *            whether the classpath entry string already has a URL scheme.
     * @param isMultiSection
     *            whether the classpath entry string contains a nested jar separator.
     * @return the classpath entry as a {@link Path}, {@link URL} or {@link URI}, or the unchanged classpath entry
     *         string if it could not be converted to any of them.
     * @throws IOException
     *             if the classpath entry string is neither a valid URL nor a valid URI.
     */
    private static Object normalizeUrlFormattedClasspathEntry(final String classpathEntryStr, final boolean isURL,
            final boolean isMultiSection) throws IOException {
        Object classpathEntryObjNormalized = classpathEntryStr;

        // Encode spaces and hash symbols in classpath entry as they potentially can be invalid when converted to a
        // URL/URI
        var classpathEntStr = classpathEntryStr.replace(" ", "%20").replace("#", "%23");
        // Convert back to URL (or URI) if this has a URL scheme or if this is a multi-section path (which needs the
        // "jar:file:" scheme)
        if (!isURL) {
            // Add "file:" scheme if there is no scheme
            classpathEntStr = "file:" + classpathEntStr;
        }
        if (isMultiSection) {
            // Multi-section URL strings that do not already have a URL scheme need to have the "jar:file:" scheme
            classpathEntStr = "jar:" + classpathEntStr;
        }
        try {
            // Convert classpath entry to (or back to) a URL.
            final var classpathEntryURL = new URL(classpathEntStr);
            classpathEntryObjNormalized = classpathEntryURL;

            // If this is not a multi-section URL, try converting URL to a Path
            if (!isMultiSection) {
                try {
                    final var scheme = classpathEntryURL.getProtocol();
                    if (!"http".equals(scheme) && !"https".equals(scheme)) {
                        final var classpathEntryURI = classpathEntryURL.toURI();
                        // See if the URL resolves to a file or directory via the Path API
                        classpathEntryObjNormalized = Path.of(classpathEntryURI);
                    }
                } catch (final URISyntaxException | IllegalArgumentException | SecurityException
                        | FileSystemNotFoundException e) {
                    // This is a custom URL scheme without a backing FileSystem
                }
            } // else this is a remote jar URL

        } catch (final MalformedURLException e) {
            // Try creating URI if URL creation fails, in case there is a URI-only scheme
            try {
                final var classpathEntryURI = new URI(classpathEntStr);
                classpathEntryObjNormalized = classpathEntryURI;

                final var scheme = classpathEntryURI.getScheme();
                if (!"http".equals(scheme) && !"https".equals(scheme)) {
                    // See if the URI resolves to a file or directory via the Path API
                    classpathEntryObjNormalized = Path.of(classpathEntryURI);
                } // else this is a remote jar URI

            } catch (final URISyntaxException e1) {
                throw new IOException("Malformed URI: " + classpathEntryObjNormalized + " : " + e1);
            } catch (final IllegalArgumentException | SecurityException | FileSystemNotFoundException e1) {
                // This is a custom URI scheme without a backing FileSystem
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
            new SingletonMap<>() {
                @Override
                public ClasspathElement newInstance(final Object classpathEntryObj, final @Nullable LogNode log)
                        throws IOException, InterruptedException {
                    // Overridden by a NewInstanceFactory
                    throw new IOException("Should not reach here");
                }
            };

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Determine whether a normalized classpath entry object names a jar (or other zipfile) rather than a directory.
     *
     * @param classpathEntryObj
     *            the normalized classpath entry object
     * @return true if the classpath entry is a jar, false if it is a directory
     * @throws IOException
     *             if the classpath entry is unreadable, or is of a type that cannot be scanned
     */
    private static boolean classpathEntryIsJar(final Object classpathEntryObj) throws IOException {
        if (classpathEntryObj instanceof URL || classpathEntryObj instanceof URI) {
            // URLs and URIs always point to jars
            return true;
        }
        if (!(classpathEntryObj instanceof final Path path)) {
            // Should not happen
            throw new IOException("Got unexpected classpath entry object type "
                    + classpathEntryObj.getClass().getName() + " : " + classpathEntryObj);
        }
        if ("JrtFileSystem".equals(path.getFileSystem().getClass().getSimpleName())) {
            // Ignore JrtFileSystem (#553) -- paths are of form: /modules/java.base/module-info.class
            throw new IOException(
                    "Ignoring JrtFS filesystem path (modules are scanned using the JPMS API): " + path);
        }
        if (!FileUtils.canRead(path)) {
            throw new IOException("Cannot read path: " + path);
        }
        final var attributes = Files.readAttributes(path, BasicFileAttributes.class);
        if (attributes.isRegularFile()) {
            // The Path points to a file, so it must be a jar
            return true;
        }
        if (attributes.isDirectory()) {
            return false;
        }
        throw new IOException("Not a file or directory: " + path);
    }

    /**
     * Create a WorkUnitProcessor for opening traditional classpath entries (which are mapped to
     * {@link ClasspathElementDir} or {@link ClasspathElementZip} -- {@link ClasspathElementModule} is handled
     * separately).
     *
     * @param allClasspathEltsOut
     *            on exit, the set of all classpath elements
     * @param toplevelClasspathEltsOut
     *            on exit, the toplevel classpath elements
     * @return the work unit processor
     */
    private WorkUnitProcessor<ClasspathEntryWorkUnit> newClasspathEntryWorkUnitProcessor(
            final Set<ClasspathElement> allClasspathEltsOut, final Set<ClasspathElement> toplevelClasspathEltsOut) {
        return (workUnit, workQueue, log) -> {
            try {
                // Normalize the classpath entry object, and update it in the work unit
                final var classpathEntryObj = normalizeClasspathEntry(workUnit.classpathEntryObj);
                workUnit.classpathEntryObj = classpathEntryObj;

                // Determine if classpath entry is a jar or dir
                final var isJar = classpathEntryIsJar(classpathEntryObj);

                // Create a ClasspathElementZip or ClasspathElementDir from the classpath entry. Use a singleton map
                // to ensure that classpath elements are only opened once per unique Path, URL, or URI
                final var classpathElement = classpathEntryObjToClasspathEntrySingletonMap.get(classpathEntryObj,
                        log,
                        // A NewInstanceFactory is used here because workUnit has to be passed in, and the standard
                        // newInstance API doesn't support an extra parameter like this
                        () -> {
                            final ClasspathElement classpathElt = isJar
                                    ? new ClasspathElementZip(workUnit, vfs, scanSpec)
                                    : new ClasspathElementDir(workUnit, vfs, scanSpec);

                            allClasspathEltsOut.add(classpathElt);

                            // Run open() on the ClasspathElement
                            final LogNode subLog = log == null ? null
                                    : log.log(classpathElt.getURI().toString(),
                                            "Opening classpath element " + classpathElt);

                            // Check if the classpath element is valid (classpathElt.skipClasspathElement will be
                            // set if not). In case of ClasspathElementZip, open or extract nested jars as
                            // LogicalZipFile instances. Read manifest files for jarfiles to look for Class-Path
                            // manifest entries. Adds extra classpath elements to the work queue if they are found.
                            classpathElt.open(workQueue, subLog);

                            return classpathElt;
                        });

                // Register this work unit's reference to the classpath element. This has to be done for every work
                // unit that references the classpath element, rather than only within newInstance() above, because
                // the same classpath element can be referenced both from the toplevel classpath and from the
                // Class-Path manifest entry of another classpath element, and which of those work units wins the
                // race to create the singleton is nondeterministic (#810).
                final var parentClasspathElement = workUnit.parentClasspathElement;
                if (parentClasspathElement == null) {
                    toplevelClasspathEltsOut.add(classpathElement);
                } else {
                    // Link classpath element to its parent, if it is not a toplevel element. The index is recorded
                    // on the edge from the parent, since the same classpath element can be named by the Class-Path
                    // manifest entries of two different jarfiles, at a different position within each of them (#810)
                    parentClasspathElement.childClasspathElements.add(new ClasspathElement.ChildClasspathElement(
                            workUnit.classpathElementIdxWithinParent, classpathElement));
                }
                classpathElement.addReference(parentClasspathElement == null,
                        workUnit.classpathElementIdxWithinParent, workUnit.classLoaderStr);

            } catch (final InterruptedException e) {
                // Don't swallow interruption in the catch-all handler below
                throw e;
            } catch (final Exception e) {
                if (log != null) {
                    log.log("Skipping invalid classpath entry " + workUnit.classpathEntryObj + " : "
                            + (e.getCause() == null ? e : e.getCause()));
                }
            }
        };
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Used to enqueue classfiles for scanning.
     *
     * @param classpathElement
     *            the classpath element
     * @param classfileResource
     *            the classfile resource
     * @param isExternalClass
     *            true if this is an external class
     */
    record ClassfileScanWorkUnit(ClasspathElement classpathElement, Resource classfileResource,
            boolean isExternalClass) {
    }

    /** WorkUnitProcessor for scanning classfiles. */
    private static class ClassfileScannerWorkUnitProcessor implements WorkUnitProcessor<ClassfileScanWorkUnit> {
        /** The scan spec. */
        private final ScanSpec scanSpec;

        /** The classpath order. */
        private final List<ClasspathElement> classpathOrder;

        /**
         * The modules that are not being scanned, but whose classfiles may still be read in order to complete the
         * class graph above an accepted class.
         */
        private final UnscannedModules unscannedModules;

        /**
         * The names of accepted classes found in the classpath while scanning paths within classpath elements.
         */
        private final Set<String> acceptedClassNamesFound;

        /**
         * The names of external (non-accepted) classes scheduled for extended scanning (where scanning is extended
         * upwards to superclasses, interfaces and annotations).
         */
        private final Set<String> classNamesScheduledForExtendedScanning = Collections
                .newSetFromMap(new ConcurrentHashMap<>());

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
         * @param unscannedModules
         *            the modules that are not being scanned, but whose classfiles may still be read in order to
         *            complete the class graph above an accepted class
         * @param acceptedClassNamesFound
         *            the names of accepted classes found in the classpath while scanning paths within classpath
         *            elements.
         * @param scannedClassfiles
         *            the {@link Classfile} objects created by scanning classfiles
         */
        public ClassfileScannerWorkUnitProcessor(final ScanSpec scanSpec,
                final List<ClasspathElement> classpathOrder, final UnscannedModules unscannedModules,
                final Set<String> acceptedClassNamesFound, final Queue<Classfile> scannedClassfiles) {
            this.scanSpec = scanSpec;
            this.classpathOrder = classpathOrder;
            this.unscannedModules = unscannedModules;
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
         *            the log node, or null to skip logging
         * @throws InterruptedException
         *             the interrupted exception
         */
        @Override
        public void processWorkUnit(final ClassfileScanWorkUnit workUnit,
                final WorkQueue<ClassfileScanWorkUnit> workQueue, final @Nullable LogNode log)
                throws InterruptedException {
            // Classfile scan log entries are listed inline below the entry that was added to the log when the path
            // of the corresponding resource was found, by using the LogNode stored in Resource#scanLog. This allows
            // the path scanning and classfile scanning logs to be merged into a single tree, rather than having
            // them appear as two separate trees.
            final var classfileResource = workUnit.classfileResource();
            final var subLog = classfileResource.scanLog == null ? null
                    : classfileResource.scanLog.log(classfileResource.getPath(), "Parsing classfile");

            try {
                // Parse classfile binary format, creating a Classfile object
                final var classfile = new Classfile(workUnit.classpathElement(), classpathOrder, unscannedModules,
                        acceptedClassNamesFound, classNamesScheduledForExtendedScanning,
                        classfileResource.getPath(), classfileResource, workUnit.isExternalClass(), stringInternMap,
                        workQueue, scanSpec, subLog);

                // Enqueue the classfile for linking
                scannedClassfiles.add(classfile);

                if (subLog != null) {
                    subLog.addElapsedTime();
                }
            } catch (final InterruptedException e) {
                // Don't swallow interruption in the catch-all handler below
                throw e;
            } catch (final SkipClassException e) {
                if (subLog != null) {
                    subLog.log(classfileResource.getPath(), "Skipping classfile: " + e.getMessage());
                    subLog.addElapsedTime();
                }
            } catch (final ClassfileFormatException e) {
                if (subLog != null) {
                    subLog.log(classfileResource.getPath(), "Invalid classfile: " + e.getMessage());
                    subLog.addElapsedTime();
                }
            } catch (final IOException e) {
                if (subLog != null) {
                    subLog.log(classfileResource.getPath(), "Could not read classfile: " + e);
                    subLog.addElapsedTime();
                }
            } catch (final Exception e) {
                if (subLog != null) {
                    subLog.log(classfileResource.getPath(), "Could not read classfile", e);
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
     *            the log node, or null to skip logging
     */
    private static void findNestedClasspathElements(final List<SimpleEntry<String, ClasspathElement>> classpathElts,
            final @Nullable LogNode log) {
        // Sort classpath elements into lexicographic order
        CollectionUtils.sortIfNotEmpty(classpathElts,
                Comparator.comparing(SimpleEntry<String, ClasspathElement>::getKey));
        // Find any nesting of elements within other elements
        for (var i = 0; i < classpathElts.size(); i++) {
            // See if each classpath element is a prefix of any others (if so, they will immediately follow in
            // lexicographic order)
            final var ei = classpathElts.get(i);
            final var basePath = ei.getKey();
            final var basePathLen = basePath.length();
            for (var j = i + 1; j < classpathElts.size(); j++) {
                final var ej = classpathElts.get(j);
                final var comparePath = ej.getKey();
                final var comparePathLen = comparePath.length();
                var foundNestedClasspathRoot = false;
                if (comparePath.startsWith(basePath) && comparePathLen > basePathLen) {
                    // Require a separator after the prefix
                    final var nextChar = comparePath.charAt(basePathLen);
                    if (nextChar == '/' || nextChar == '!') {
                        // basePath is a path prefix of comparePath. Ensure that the nested classpath does not
                        // contain another '!' zip-separator (since classpath scanning does not recurse to
                        // jars-within-jars unless they are explicitly listed on the classpath)
                        // A '!' zip-separator is always followed by '/', and zip entry names never start with
                        // '/', so both separator characters have to be skipped for the stored prefix to be able
                        // to match a zip entry name
                        final var separatorLen = comparePath.startsWith("!/", basePathLen) ? 2 : 1;
                        final var nestedClasspathRelativePath = comparePath.substring(basePathLen + separatorLen);
                        if (nestedClasspathRelativePath.indexOf('!') < 0) {
                            // Found a nested classpath root
                            foundNestedClasspathRoot = true;
                            // Store link from prefix element to nested elements
                            final var baseElement = ei.getValue();
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

    /** The manifest attribute that lists the packages a jarfile needs exported to it, from JEP 261. */
    private static final String ADD_EXPORTS_KEY = "Add-Exports";

    /** The manifest attribute that lists the packages a jarfile needs opened to it, from JEP 261. */
    private static final String ADD_OPENS_KEY = "Add-Opens";

    /**
     * Find classpath elements whose path is a prefix of another classpath element, and record the nesting.
     *
     * @param finalTraditionalClasspathEltOrder
     *            the final traditional classpath elt order
     * @param classLoaderProbeLog
     *            the classpath finder log
     */
    private void preprocessClasspathElementsByType(final List<ClasspathElement> finalTraditionalClasspathEltOrder,
            final @Nullable LogNode classLoaderProbeLog) {
        final List<SimpleEntry<String, ClasspathElement>> classpathEltDirs = new ArrayList<>();
        final List<SimpleEntry<String, ClasspathElement>> classpathEltZips = new ArrayList<>();
        for (final ClasspathElement classpathElt : finalTraditionalClasspathEltOrder) {
            if (classpathElt instanceof ClasspathElementDir) {
                // Separate out ClasspathElementFileDir and ClasspathElementPathDir elements from other types
                final var file = classpathElt.getFile();
                // File#getPath() uses the platform separator, so on Windows the path is separated by '\', but
                // nested classpath root prefixes are matched against '/'-separated relative paths
                final var path = file == null ? classpathElt.toString()
                        : file.getPath().replace(File.separatorChar, '/');
                classpathEltDirs.add(new SimpleEntry<>(path, classpathElt));

            } else if (classpathElt instanceof final ClasspathElementZip classpathEltZip) {
                // Separate out ClasspathElementZip elements from other types
                classpathEltZips.add(new SimpleEntry<>(classpathEltZip.getZipFilePath(), classpathElt));

                // Handle module-related manifest entries
                final var zipRoot = classpathEltZip.vfsRoot;
                if (zipRoot != null) {
                    try {
                        // From JEP 261: "A <module>/<package> pair in the value of an Add-Exports attribute has the
                        // same meaning as the command-line option --add-exports <module>/<package>=ALL-UNNAMED. A
                        // <module>/<package> pair in the value of an Add-Opens attribute has the same meaning as the
                        // command-line option --add-opens <module>/<package>=ALL-UNNAMED."
                        final var addExportsManifestValue = zipRoot.getManifestEntry(ADD_EXPORTS_KEY);
                        if (addExportsManifestValue != null) {
                            for (final String addExports : PathList.split(addExportsManifestValue, ' ',
                                    scanSpec.classpathSpec.allowedURLSchemes)) {
                                scanSpec.classpathSpec.modulePathInfo.addExportsEntry(addExports + "=ALL-UNNAMED");
                            }
                        }
                        final var addOpensManifestValue = zipRoot.getManifestEntry(ADD_OPENS_KEY);
                        if (addOpensManifestValue != null) {
                            for (final String addOpens : PathList.split(addOpensManifestValue, ' ',
                                    scanSpec.classpathSpec.allowedURLSchemes)) {
                                scanSpec.classpathSpec.modulePathInfo.addOpensEntry(addOpens + "=ALL-UNNAMED");
                            }
                        }
                        // Retrieve Automatic-Module-Name manifest entry, if present
                        final var moduleName = zipRoot.getModuleName();
                        if (moduleName != null) {
                            classpathEltZip.moduleNameFromManifestFile = moduleName;
                        }
                    } catch (final IOException e) {
                        if (classLoaderProbeLog != null) {
                            classLoaderProbeLog
                                    .log("Could not read the manifest of " + classpathEltZip + " : " + e);
                        }
                    }
                }
            }
            // (Ignore ClasspathElementModule, no preprocessing to perform)
        }
        // Find nested classpath elements (writes to ClasspathElement#nestedClasspathRootPrefixes)
        findNestedClasspathElements(classpathEltDirs, classLoaderProbeLog);
        findNestedClasspathElements(classpathEltZips, classLoaderProbeLog);
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
    private static void maskClassfiles(final List<ClasspathElement> classpathElementOrder,
            final @Nullable LogNode maskLog) {
        final Set<String> acceptedClasspathRelativePathsFound = new HashSet<>();
        for (var classpathIdx = 0; classpathIdx < classpathElementOrder.size(); classpathIdx++) {
            final var classpathElement = classpathElementOrder.get(classpathIdx);
            classpathElement.maskClassfiles(classpathIdx, acceptedClasspathRelativePathsFound, maskLog);
        }
        if (maskLog != null) {
            maskLog.addElapsedTime();
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Remove resources that refer to the same file as a resource found earlier in the classpath / module path, so
     * that one file is not returned twice.
     *
     * @param classpathElementOrder
     *            the classpath element order
     * @param maskLog
     *            the mask log
     */
    // #704
    private static void maskDuplicateResources(final List<ClasspathElement> classpathElementOrder,
            final @Nullable LogNode maskLog) {
        // Only a relative path that occurs more than once can be a duplicate of the same file, and computing the
        // URI of a resource is not free (for modules it requires a reflective call to ModuleReader#find), so find
        // the colliding relative paths first, and only compare URIs for those.
        final Set<String> relativePathsFound = new HashSet<>();
        final Set<String> collidingRelativePaths = new HashSet<>();
        for (final ClasspathElement classpathElement : classpathElementOrder) {
            for (final Resource res : classpathElement.acceptedResources) {
                if (!relativePathsFound.add(res.getPath())) {
                    collidingRelativePaths.add(res.getPath());
                }
            }
        }
        if (!collidingRelativePaths.isEmpty()) {
            final Set<String> fileIdentityKeysFound = new HashSet<>();
            final Map<String, String> canonicalPathCache = new HashMap<>();
            for (var classpathIdx = 0; classpathIdx < classpathElementOrder.size(); classpathIdx++) {
                classpathElementOrder.get(classpathIdx).maskDuplicateResources(classpathIdx, collidingRelativePaths,
                        fileIdentityKeysFound, canonicalPathCache, maskLog);
            }
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
     * @return the scan result
     * @throws InterruptedException
     *             if the scan was interrupted
     * @throws ExecutionException
     *             if the scan threw an uncaught exception
     */
    private ScanResult performScan(final List<ClasspathElement> finalClasspathEltOrder)
            throws InterruptedException, ExecutionException {
        // Mask duplicate resources (remove any resource that is the same file as a resource that was already found
        // in an earlier classpath element)
        maskDuplicateResources(finalClasspathEltOrder,
                topLevelLog == null ? null : topLevelLog.log("Masking duplicate resources"));

        // Mask classfiles (remove any classfile resources that are shadowed by an earlier definition of the same
        // class)
        if (scanSpec.enableClassInfo) {
            maskClassfiles(finalClasspathEltOrder,
                    topLevelLog == null ? null : topLevelLog.log("Masking classfiles"));
        }

        // Merge the file-to-timestamp maps across all classpath elements
        final Map<File, Long> fileToLastModified = new HashMap<>();
        for (final ClasspathElement classpathElement : finalClasspathEltOrder) {
            fileToLastModified.putAll(classpathElement.fileToLastModified);
        }

        // Scan classfiles, if scanSpec.enableClassInfo is true. (classNameToClassInfo is a ConcurrentHashMap
        // because it can be modified by ArrayTypeSignature.getArrayClassInfo() after scanning is complete)
        final Map<String, ClassInfo> classNameToClassInfo = new ConcurrentHashMap<>();
        final Map<String, PackageInfo> packageNameToPackageInfo = new HashMap<>();
        final Map<String, ModuleInfo> moduleNameToModuleInfo = new HashMap<>();
        if (scanSpec.enableClassInfo) {
            scanClassfiles(finalClasspathEltOrder, classNameToClassInfo, packageNameToPackageInfo,
                    moduleNameToModuleInfo);
        } else if (topLevelLog != null) {
            topLevelLog.log("Classfile scanning is disabled");
        }

        // Return a new ScanResult
        final var scanResult = new ScanResult(scanSpec, finalClasspathEltOrder, classNameToClassInfo,
                packageNameToPackageInfo, moduleNameToModuleInfo, fileToLastModified, vfs, topLevelLog);

        // Set the ScanResult in each classpath element, so that the classpath elements can determine when the
        // ScanResult is closed
        for (final ClasspathElement classpathElement : finalClasspathEltOrder) {
            classpathElement.setScanResult(scanResult);
        }
        // The modules that were only looked in, to complete the class graph above an accepted class, are not in the
        // classpath order, but the resources read from them still need to know when the ScanResult is closed
        for (final ClasspathElement classpathElement : unscannedModules.getClasspathElements()) {
            classpathElement.setScanResult(scanResult);
        }

        return scanResult;
    }

    /**
     * Scan all accepted classfiles in parallel, then link the resulting {@link Classfile} objects into
     * {@link ClassInfo}, {@link PackageInfo} and {@link ModuleInfo} objects.
     *
     * @param finalClasspathEltOrder
     *            the final classpath element order
     * @param classNameToClassInfo
     *            the map from class name to {@link ClassInfo}, to add scanned classes to
     * @param packageNameToPackageInfo
     *            the map from package name to {@link PackageInfo}, to add scanned packages to
     * @param moduleNameToModuleInfo
     *            the map from module name to {@link ModuleInfo}, to add scanned modules to
     * @throws InterruptedException
     *             if the scan was interrupted
     * @throws ExecutionException
     *             if the scan threw an uncaught exception
     */
    private void scanClassfiles(final List<ClasspathElement> finalClasspathEltOrder,
            final Map<String, ClassInfo> classNameToClassInfo,
            final Map<String, PackageInfo> packageNameToPackageInfo,
            final Map<String, ModuleInfo> moduleNameToModuleInfo) throws InterruptedException, ExecutionException {
        // Get accepted classfile order
        final List<ClassfileScanWorkUnit> classfileScanWorkItems = new ArrayList<>();
        final Set<String> acceptedClassNamesFound = new HashSet<>();
        for (final ClasspathElement classpathElement : finalClasspathEltOrder) {
            // Get classfile scan order across all classpath elements
            for (final Resource resource : classpathElement.acceptedClassfileResources) {
                // Create a set of names of all accepted classes found in classpath element paths, and double-check
                // that a class is not going to be scanned twice
                final var className = ClassNames.classfilePathToClassName(resource.getPath());
                if (!acceptedClassNamesFound.add(className) && !"module-info".equals(className)
                        && !"package-info".equals(className) && !className.endsWith(".package-info")) {
                    // The class should not be scheduled more than once for scanning, since classpath masking was
                    // already applied
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
        final var classfileWorkUnitProcessor = new ClassfileScannerWorkUnitProcessor(scanSpec,
                finalClasspathEltOrder, unscannedModules, Collections.unmodifiableSet(acceptedClassNamesFound),
                scannedClassfiles);
        processWorkUnits(classfileScanWorkItems,
                topLevelLog == null ? null : topLevelLog.log("Scanning classfiles"), classfileWorkUnitProcessor);

        // Link the Classfile objects to produce ClassInfo objects. This needs to be done from a single thread.
        final var linkLog = topLevelLog == null ? null : topLevelLog.log("Linking related classfiles");
        while (!scannedClassfiles.isEmpty()) {
            final var c = scannedClassfiles.remove();
            c.link(classNameToClassInfo, packageNameToPackageInfo, moduleNameToModuleInfo);
        }

        // A ClassInfo object is created for every class named as a superclass, interface or annotation of a scanned
        // class, and scanning is extended upwards to those classes, so the class graph above a scanned class is
        // complete. A ClassInfo object is deliberately not created for every class named in a type descriptor or
        // type signature, since that would require every type descriptor and type signature to be parsed before the
        // ScanResult can be returned, rather than lazily on demand, which would slow down every scan. The
        // consequence is that ClassRefTypeSignature#getClassInfo() and AnnotationClassRef#getClassInfo() return null
        // for a class that was not scanned. Call ClassGraph#enableInterClassDependencies() to get the classes
        // referenced by a scanned class. (#902)

        if (linkLog != null) {
            linkLog.addElapsedTime();
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Open each of the classpath elements, looking for additional child classpath elements that need scanning (e.g.
     * {@code Class-Path} entries in jar manifest files), then perform the scan if {@link #performScan} is true, or
     * just get the classpath if {@link #performScan} is false.
     *
     * @return the scan result
     * @throws InterruptedException
     *             if the scan was interrupted
     * @throws ExecutionException
     *             if a worker threw an uncaught exception
     */
    private ScanResult openClasspathElementsThenScan() throws InterruptedException, ExecutionException {
        // In parallel, create a ClasspathElement singleton for each classpath element, then call open() on each
        // ClasspathElement object, which in the case of jarfiles will cause LogicalZipFile instances to be created
        // for each (possibly nested) jarfile, then will read the manifest file and zip entries.
        final Set<ClasspathElement> allClasspathElts = Collections.newSetFromMap(new ConcurrentHashMap<>());
        final Set<ClasspathElement> toplevelClasspathElts = Collections.newSetFromMap(new ConcurrentHashMap<>());
        processWorkUnits(rawClasspathEntryWorkUnits,
                topLevelLog == null ? null : topLevelLog.log("Opening classpath elements"),
                newClasspathEntryWorkUnitProcessor(allClasspathElts, toplevelClasspathElts));

        // Determine total ordering of classpath elements, inserting jars referenced in manifest Class-Path entries
        // in-place into the ordering, if they haven't been listed earlier in the classpath already.
        final var classpathEltOrder = findClasspathOrder(toplevelClasspathElts);

        // Find classpath elements that are path prefixes of other classpath elements, and for ClasspathElementZip,
        // get module-related manifest entry values
        preprocessClasspathElementsByType(classpathEltOrder,
                topLevelLog == null ? null : topLevelLog.log("Finding nested classpath elements"));

        // Order modules before classpath elements from traditional classpath. The same jar or directory can be
        // reached both as a module and as a classpath element, if it is on both the module path and the classpath,
        // or if it is spliced into a module with --patch-module. The module takes precedence, since that is where
        // the JVM loads the classes from, so any classpath element that refers to the same file as a module (or as
        // an earlier classpath element, e.g. through a symlink) is dropped -- otherwise the same file would be
        // listed twice by ScanResult#getClasspathURIs() etc., and would be scanned twice.
        final var classpathOrderLog = topLevelLog == null ? null
                : topLevelLog.log("Final classpath element order:");
        final var numElts = moduleOrder.size() + classpathEltOrder.size();
        final List<ClasspathElement> finalClasspathEltOrder = new ArrayList<>(numElts);
        final Set<String> fileIdentityKeys = new HashSet<>();
        final Map<String, String> canonicalPathCache = new HashMap<>();
        var classpathOrderIdx = 0;
        for (final ClasspathElementModule classpathElt : moduleOrder) {
            final var fileIdentityKey = classpathElt.getFileIdentityKey(canonicalPathCache);
            if (fileIdentityKey != null) {
                fileIdentityKeys.add(fileIdentityKey);
            }
            classpathElt.classpathElementIdx = classpathOrderIdx++;
            finalClasspathEltOrder.add(classpathElt);
            if (classpathOrderLog != null) {
                classpathOrderLog.log(classpathElt.getModuleReference().toString());
            }
        }
        for (final ClasspathElement classpathElt : classpathEltOrder) {
            final var fileIdentityKey = classpathElt.getFileIdentityKey(canonicalPathCache);
            if (fileIdentityKey != null && !fileIdentityKeys.add(fileIdentityKey)) {
                if (classpathOrderLog != null) {
                    classpathOrderLog.log("Ignoring duplicate classpath element, which is the same file or "
                            + "directory as an element found earlier in the module path or classpath: "
                            + classpathElt);
                }
                continue;
            }
            classpathElt.classpathElementIdx = classpathOrderIdx++;
            finalClasspathEltOrder.add(classpathElt);
            if (classpathOrderLog != null) {
                classpathOrderLog.log(classpathElt.toString());
            }
        }

        // In parallel, scan paths within each classpath element, comparing them against accept/reject
        processWorkUnits(finalClasspathEltOrder,
                topLevelLog == null ? null : topLevelLog.log("Scanning classpath elements"),
                // Scan the paths within the classpath element
                (classpathElement, workQueueIgnored, pathScanLog) -> classpathElement.scanPaths(pathScanLog));

        // Filter out classpath elements that contain a rejected resource path, or that do not contain a required
        // accepted resource path
        var finalClasspathEltOrderFiltered = finalClasspathEltOrder;
        if (!scanSpec.classpathElementResourcePathAcceptReject.acceptAndRejectAreEmpty()) {
            final var acceptIsEmpty = scanSpec.classpathElementResourcePathAcceptReject.acceptIsEmpty();
            finalClasspathEltOrderFiltered = new ArrayList<>(finalClasspathEltOrder.size());
            for (final ClasspathElement classpathElement : finalClasspathEltOrder) {
                if (!classpathElement.containsRejectedClasspathElementResourcePath && (acceptIsEmpty
                        || classpathElement.containsSpecificallyAcceptedClasspathElementResourcePath)) {
                    finalClasspathEltOrderFiltered.add(classpathElement);
                }
            }
        }

        if (performScan) {
            // Scan classpath / modules, producing a ScanResult.
            return performScan(finalClasspathEltOrderFiltered);
        } else {
            // Only getting classpath -- return a placeholder ScanResult to hold classpath elements
            if (topLevelLog != null) {
                topLevelLog.log("Only returning classpath elements (not performing a scan)");
            }
            return new ScanResult(scanSpec, finalClasspathEltOrderFiltered,
                    /* classNameToClassInfo = */ new HashMap<>(), /* packageNameToPackageInfo = */ new HashMap<>(),
                    /* moduleNameToModuleInfo = */ new HashMap<>(), /* fileToLastModified = */ null, vfs,
                    topLevelLog);
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Determine the unique ordered classpath elements, and run a scan looking for file or classfile matches if
     * necessary.
     *
     * @return the scan result, or null if a failure handler was provided and the scan failed (in which case the
     *         failure handler was called, and the result is ignored by the caller).
     * @throws InterruptedException
     *             if scanning was interrupted
     * @throws CancellationException
     *             if scanning was cancelled
     * @throws ExecutionException
     *             if a worker threw an uncaught exception
     */
    @Override
    public @Nullable ScanResult call() throws InterruptedException, CancellationException, ExecutionException {
        ScanResult scanResult = null;
        final var scanStart = System.nanoTime();
        final var removeTemporaryFilesAfterScan = scanSpec.removeTemporaryFilesAfterScan;
        try {
            // Perform the scan
            scanResult = openClasspathElementsThenScan();

            // Log total time after scan completes, and flush log
            if (topLevelLog != null) {
                topLevelLog.log("~",
                        String.format(Locale.US, "Total time: %.3f sec", (System.nanoTime() - scanStart) * 1.0e-9));
                topLevelLog.flush();
            }

            // Call the scan result processor, if one was provided. The scan result is closed however the processor
            // ends, including by throwing an Error rather than an Exception, which is what a failing assertion
            // inside a scan result processor throws -- nothing else would ever close it, since the scan result is
            // not passed to the failure handler, and the one returned by this method is discarded by the caller
            // that provided a scan result processor
            if (scanResultProcessor != null) {
                try {
                    scanResultProcessor.accept(scanResult);
                } catch (final Exception e) {
                    throw new ExecutionException(e);
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

            // Stop any running threads (should not be needed, threads should already be quiescent)
            interruptionChecker.interrupt();

            // A failed scan produces no ScanResult for the caller to close, so remove the temporary files and
            // close the resources, zipfiles and modules here, whatever the failure handler goes on to do
            vfs.close(topLevelLog);

            if (failureHandler == null) {
                // If there is no failure handler set, re-throw the exception
                throw e;
            } else {
                // Otherwise, call the failure handler
                try {
                    failureHandler.accept(e);
                } catch (final Exception f) {
                    // The failure handler failed
                    if (topLevelLog != null) {
                        topLevelLog.log("~", "The failure handler threw an exception:", f);
                        topLevelLog.flush();
                    }
                    // Group the two exceptions into one, using the suppressed exception mechanism to show the scan
                    // exception below the failure handler exception
                    final var failureHandlerException = new ExecutionException(
                            "Exception while calling failure handler", f);
                    failureHandlerException.addSuppressed(e);
                    // A scan is only given a failure handler by ClassGraph#scanAsync, which runs the scanner
                    // inside a Runnable that catches ExecutionException and passes it to the same handler.
                    // So throwing here offers the handler a second chance to report the failure, this time with the
                    // original scan exception attached as a suppressed exception. If it throws again, the exception
                    // leaves the Runnable and is reported by the executor.
                    throw failureHandlerException;
                }
            }
        }

        if (removeTemporaryFilesAfterScan && vfs.hasTempFiles()) {
            // Temporary files back memory-mapped slices of the extracted nested jarfiles, so they cannot be deleted
            // without closing those slices, which closes the Vfs. If no temp files were created (i.e. if there were
            // no nested jars), the Vfs is left open, so the returned ScanResult can still be used to read resources
            // and load classes (#916)
            vfs.close(topLevelLog);
        }
        return scanResult;
    }
}
