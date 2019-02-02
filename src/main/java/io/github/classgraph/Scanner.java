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
import java.io.FileNotFoundException;
import java.io.IOException;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;

import io.github.classgraph.ClassGraph.FailureHandler;
import io.github.classgraph.ClassGraph.ScanResultProcessor;
import nonapi.io.github.classgraph.ScanSpec;
import nonapi.io.github.classgraph.classpath.ClassLoaderAndModuleFinder;
import nonapi.io.github.classgraph.classpath.ClasspathFinder;
import nonapi.io.github.classgraph.concurrency.InterruptionChecker;
import nonapi.io.github.classgraph.concurrency.SingletonMap;
import nonapi.io.github.classgraph.concurrency.WorkQueue;
import nonapi.io.github.classgraph.concurrency.WorkQueue.WorkUnitProcessor;
import nonapi.io.github.classgraph.fastzipfilereader.NestedJarHandler;
import nonapi.io.github.classgraph.utils.FastPathResolver;
import nonapi.io.github.classgraph.utils.FileUtils;
import nonapi.io.github.classgraph.utils.JarUtils;
import nonapi.io.github.classgraph.utils.LogNode;

/** The classpath scanner. */
class Scanner implements Callable<ScanResult> {
    private final ScanSpec scanSpec;
    private final ExecutorService executorService;
    private final int numParallelTasks;
    private final InterruptionChecker interruptionChecker = new InterruptionChecker();
    private final ScanResultProcessor scanResultProcessor;
    private final FailureHandler failureHandler;
    private final LogNode topLevelLog;

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

    /**
     * Recursively perform a depth-first search of jar interdependencies, breaking cycles if necessary, to determine
     * the final classpath element order.
     */
    private static void findClasspathOrderRec(final ClasspathElement currClasspathElement,
            final HashSet<ClasspathElement> visitedClasspathElts, final ArrayList<ClasspathElement> order) {
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

    /** Comparator used to sort ClasspathElement values into increasing order of integer index key */
    private static final Comparator<Entry<Integer, ClasspathElement>> INDEXED_CLASSPATH_ELEMENT_COMPARATOR = //
            new Comparator<Map.Entry<Integer, ClasspathElement>>() {
                @Override
                public int compare(final Entry<Integer, ClasspathElement> o1,
                        final Entry<Integer, ClasspathElement> o2) {
                    return o1.getKey() - o2.getKey();
                }
            };

    /** Sort a collection of indexed ClasspathElements into increasing order of integer index key. */
    private static List<ClasspathElement> orderClasspathElements(
            final Collection<Entry<Integer, ClasspathElement>> classpathEltsIndexed) {
        final List<Entry<Integer, ClasspathElement>> classpathEltsIndexedOrdered = new ArrayList<>(
                classpathEltsIndexed);
        Collections.sort(classpathEltsIndexedOrdered, INDEXED_CLASSPATH_ELEMENT_COMPARATOR);
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
     */
    private List<ClasspathElement> findClasspathOrder(final Set<ClasspathElement> openedClasspathElementsSet,
            final Queue<Entry<Integer, ClasspathElement>> toplevelClasspathEltsIndexed) {
        final List<ClasspathElement> toplevelClasspathEltsOrdered = orderClasspathElements(
                toplevelClasspathEltsIndexed);
        for (final ClasspathElement classpathElt : openedClasspathElementsSet) {
            classpathElt.childClasspathElementsOrdered = orderClasspathElements(
                    classpathElt.childClasspathElementsIndexed);
        }
        final HashSet<ClasspathElement> visitedClasspathElts = new HashSet<>();
        final ArrayList<ClasspathElement> order = new ArrayList<>();
        for (final ClasspathElement toplevelClasspathElt : toplevelClasspathEltsOrdered) {
            findClasspathOrderRec(toplevelClasspathElt, visitedClasspathElts, order);
        }
        return order;
    }

    // -------------------------------------------------------------------------------------------------------------

    /** Used to enqueue classpath elements for opening. */
    static class RawClasspathElementWorkUnit {
        final String rawClasspathEltPath;
        final ClasspathElement parentClasspathElement;
        final int orderWithinParentClasspathElement;

        public RawClasspathElementWorkUnit(final String rawClasspathEltPath,
                final ClasspathElement parentClasspathElement, final int orderWithinParentClasspathElement) {
            this.rawClasspathEltPath = rawClasspathEltPath;
            this.parentClasspathElement = parentClasspathElement;
            this.orderWithinParentClasspathElement = orderWithinParentClasspathElement;
        }
    }

    /** Used to enqueue classfiles for scanning. */
    private static class ClassfileScanWorkUnit {
        final ClasspathElement classpathElement;
        final Resource classfileResource;
        final boolean isExternalClass;

        ClassfileScanWorkUnit(final ClasspathElement classpathElement, final Resource classfileResource,
                final boolean isExternalClass) {
            this.classpathElement = classpathElement;
            this.classfileResource = classfileResource;
            this.isExternalClass = isExternalClass;
        }
    }

    /** WorkUnitProcessor for scanning classfiles. */
    private static class ClassfileScannerWorkUnitProcessor implements WorkUnitProcessor<ClassfileScanWorkUnit> {
        private final ScanSpec scanSpec;
        private final List<ClasspathElement> classpathOrder;
        private final Set<String> scannedClassNames;
        private final ConcurrentLinkedQueue<ClassInfoUnlinked> classInfoUnlinkedQueue;
        private final LogNode log;
        private final InterruptionChecker interruptionChecker;

        public ClassfileScannerWorkUnitProcessor(final ScanSpec scanSpec,
                final List<ClasspathElement> classpathOrder, final Set<String> scannedClassNames,
                final ConcurrentLinkedQueue<ClassInfoUnlinked> classInfoUnlinkedQueue, final LogNode log,
                final InterruptionChecker interruptionChecker) {
            this.scanSpec = scanSpec;
            this.classpathOrder = classpathOrder;
            this.scannedClassNames = scannedClassNames;
            this.classInfoUnlinkedQueue = classInfoUnlinkedQueue;
            this.log = log;
            this.interruptionChecker = interruptionChecker;
        }

        /** Extend scanning to a superclass, interface or annotation. */
        private List<ClassfileScanWorkUnit> extendScanningUpwards(final String className, final String relationship,
                final ClasspathElement currClasspathElement,
                final List<ClassfileScanWorkUnit> additionalWorkUnitsIn, final LogNode subLog) {
            List<ClassfileScanWorkUnit> additionalWorkUnits = additionalWorkUnitsIn;
            // Don't scan a class more than once 
            if (className != null && scannedClassNames.add(className)) {
                // Search for the named class' classfile among classpath elements, in classpath order (this is O(N)
                // for each class, but there shouldn't be too many cases of extending scanning upwards)
                final String classfilePath = JarUtils.classNameToClassfilePath(className);
                // First check current classpath element, to avoid iterating through other classpath elements
                Resource classResource = currClasspathElement.getResource(classfilePath);
                ClasspathElement foundInClasspathElt = null;
                if (classResource != null) {
                    // Found the classfile in the current classpath element
                    foundInClasspathElt = currClasspathElement;
                } else {
                    // Didn't find the classfile in the current classpath element -- iterate through other elements
                    for (final ClasspathElement classpathElt : classpathOrder) {
                        if (classpathElt != currClasspathElement) {
                            classResource = classpathElt.getResource(classfilePath);
                            if (classResource != null) {
                                foundInClasspathElt = classpathElt;
                                break;
                            }
                        }
                    }
                }
                if (classResource != null) {
                    // Found class resource 
                    if (subLog != null) {
                        subLog.log("Scheduling external class for scanning: " + relationship + " " + className
                                + " -- found in classpath element " + foundInClasspathElt);
                    }
                    if (additionalWorkUnits == null) {
                        additionalWorkUnits = new ArrayList<>();
                    }
                    additionalWorkUnits.add(new ClassfileScanWorkUnit(foundInClasspathElt, classResource,
                            /* isExternalClass = */ true));
                } else {
                    if (subLog != null && !className.equals("java.lang.Object")) {
                        subLog.log("External " + relationship + " " + className + " was not found in "
                                + "non-blacklisted packages -- cannot extend scanning to this class");
                    }
                }
            }
            return additionalWorkUnits;
        }

        /** Check if scanning needs to be extended upwards to an external superclass, interface or annotation. */
        private List<ClassfileScanWorkUnit> extendScanningUpwards(final ClasspathElement classpathElement,
                final ClassInfoUnlinked classInfoUnlinked, final LogNode subLog) {
            // Check superclass
            List<ClassfileScanWorkUnit> additionalWorkUnits = null;
            additionalWorkUnits = extendScanningUpwards(classInfoUnlinked.superclassName, "superclass",
                    classpathElement, additionalWorkUnits, subLog);
            // Check implemented interfaces
            if (classInfoUnlinked.implementedInterfaces != null) {
                for (final String className : classInfoUnlinked.implementedInterfaces) {
                    additionalWorkUnits = extendScanningUpwards(className, "interface", classpathElement,
                            additionalWorkUnits, subLog);
                }
            }
            // Check class annotations
            if (classInfoUnlinked.classAnnotations != null) {
                for (final AnnotationInfo annotationInfo : classInfoUnlinked.classAnnotations) {
                    additionalWorkUnits = extendScanningUpwards(annotationInfo.getName(), "class annotation",
                            classpathElement, additionalWorkUnits, subLog);
                }
            }
            // Check method annotations and method parameter annotations
            if (classInfoUnlinked.methodInfoList != null) {
                for (final MethodInfo methodInfo : classInfoUnlinked.methodInfoList) {
                    if (methodInfo.annotationInfo != null) {
                        for (final AnnotationInfo methodAnnotationInfo : methodInfo.annotationInfo) {
                            additionalWorkUnits = extendScanningUpwards(methodAnnotationInfo.getName(),
                                    "method annotation", classpathElement, additionalWorkUnits, subLog);
                        }
                        if (methodInfo.parameterAnnotationInfo != null
                                && methodInfo.parameterAnnotationInfo.length > 0) {
                            for (final AnnotationInfo[] paramAnns : methodInfo.parameterAnnotationInfo) {
                                if (paramAnns != null && paramAnns.length > 0) {
                                    for (final AnnotationInfo paramAnn : paramAnns) {
                                        additionalWorkUnits = extendScanningUpwards(paramAnn.getName(),
                                                "method parameter annotation", classpathElement,
                                                additionalWorkUnits, subLog);
                                    }
                                }
                            }
                        }
                    }
                }
            }
            // Check field annotations
            if (classInfoUnlinked.fieldInfoList != null) {
                for (final FieldInfo fieldInfo : classInfoUnlinked.fieldInfoList) {
                    if (fieldInfo.annotationInfo != null) {
                        for (final AnnotationInfo fieldAnnotationInfo : fieldInfo.annotationInfo) {
                            additionalWorkUnits = extendScanningUpwards(fieldAnnotationInfo.getName(),
                                    "field annotation", classpathElement, additionalWorkUnits, subLog);
                        }
                    }
                }
            }
            return additionalWorkUnits;
        }

        @Override
        public void processWorkUnit(final ClassfileScanWorkUnit workUnit,
                final WorkQueue<ClassfileScanWorkUnit> workQueue) throws Exception {
            final LogNode subLog = log == null ? null
                    : log.log(workUnit.classfileResource.getPath(),
                            "Parsing classfile " + workUnit.classfileResource);
            try {
                // Parse classfile binary format, creating a ClassInfoUnlinked object
                final ClassInfoUnlinked classInfoUnlinked = new ClassfileBinaryParser()
                        .readClassInfoFromClassfileHeader(workUnit.classpathElement,
                                workUnit.classfileResource.getPath(), workUnit.classfileResource,
                                workUnit.isExternalClass, scanSpec, subLog);

                // If class was successfully read, output new ClassInfoUnlinked object
                if (classInfoUnlinked != null) {
                    classInfoUnlinkedQueue.add(classInfoUnlinked);
                    classInfoUnlinked.logTo(subLog);

                    // Check if any superclasses, interfaces or annotations are external (non-whitelisted) classes
                    if (scanSpec.extendScanningUpwardsToExternalClasses) {
                        final List<ClassfileScanWorkUnit> additionalWorkUnits = extendScanningUpwards(
                                workUnit.classpathElement, classInfoUnlinked, subLog);
                        // If any external classes were found, schedule them for scanning
                        if (additionalWorkUnits != null) {
                            workQueue.addWorkUnits(additionalWorkUnits);
                        }
                    }
                }
                if (subLog != null) {
                    subLog.addElapsedTime();
                }
            } catch (final IOException e) {
                if (subLog != null) {
                    subLog.log("IOException while attempting to read classfile " + workUnit.classfileResource
                            + " : " + e);
                }
            } catch (final IllegalArgumentException e) {
                if (subLog != null) {
                    subLog.log("Corrupt or unsupported classfile " + workUnit.classfileResource + " : " + e);
                }
            } catch (final Throwable e) {
                if (subLog != null) {
                    subLog.log("Exception while parsing classfile " + workUnit.classfileResource, e);
                }
            }
            interruptionChecker.check();
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /** Find classpath elements whose path is a prefix of another classpath element, and record the nesting. */
    private void findNestedClasspathElements(final List<SimpleEntry<String, ClasspathElement>> classpathElts,
            final LogNode log) {
        // Sort classpath elements into lexicographic order
        Collections.sort(classpathElts, new Comparator<SimpleEntry<String, ClasspathElement>>() {
            @Override
            public int compare(final SimpleEntry<String, ClasspathElement> o1,
                    final SimpleEntry<String, ClasspathElement> o2) {
                return o1.getKey().compareTo(o2.getKey());
            }
        });
        // Find any nesting of elements within other elements
        LogNode nestedClasspathRootNode = null;
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
                                if (nestedClasspathRootNode == null) {
                                    nestedClasspathRootNode = log.log("Found nested classpath elements");
                                }
                                nestedClasspathRootNode
                                        .log(basePath + " is a prefix of the nested element " + comparePath);
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

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Determine the unique ordered classpath elements, and run a scan looking for file or classfile matches if
     * necessary.
     */
    @Override
    public ScanResult call() throws InterruptedException, ExecutionException {
        final LogNode classpathFinderLog = topLevelLog == null ? null
                : topLevelLog.log("Finding classpath entries");
        final NestedJarHandler nestedJarHandler = new NestedJarHandler(scanSpec, classpathFinderLog);
        final Map<String, ClassLoader[]> classpathEltPathToClassLoaders = new ConcurrentHashMap<>();
        try {
            final long scanStart = System.nanoTime();

            // Get classpath finder
            final ClasspathFinder classpathFinder = new ClasspathFinder(scanSpec, classpathEltPathToClassLoaders,
                    nestedJarHandler, classpathFinderLog);
            final ClassLoaderAndModuleFinder classLoaderAndModuleFinder = classpathFinder
                    .getClassLoaderAndModuleFinder();
            final ClassLoader[] contextClassLoaders = classLoaderAndModuleFinder.getContextClassLoaders();

            final List<ClasspathElementModule> moduleClasspathEltOrder = new ArrayList<>();
            if (scanSpec.overrideClasspath == null && scanSpec.overrideClassLoaders == null
                    && scanSpec.scanModules) {
                // Add modules to start of classpath order, before traditional classpath
                final List<ModuleRef> systemModuleRefs = classLoaderAndModuleFinder.getSystemModuleRefs();
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
                            final ClasspathElementModule classpathElementModule = new ClasspathElementModule(
                                    systemModuleRef, contextClassLoaders, nestedJarHandler, scanSpec);
                            classpathElementModule.open(/* ignored */ null, classpathFinderLog);
                            moduleClasspathEltOrder.add(classpathElementModule);
                        } else {
                            if (classpathFinderLog != null) {
                                classpathFinderLog.log(
                                        "Skipping non-whitelisted or blacklisted system module: " + moduleName);
                            }
                        }
                    }
                }
                final List<ModuleRef> nonSystemModuleRefs = classLoaderAndModuleFinder.getNonSystemModuleRefs();
                if (nonSystemModuleRefs != null) {
                    for (final ModuleRef nonSystemModuleRef : nonSystemModuleRefs) {
                        final String moduleName = nonSystemModuleRef.getName();
                        if (scanSpec.moduleWhiteBlackList.isWhitelistedAndNotBlacklisted(moduleName)) {
                            final ClasspathElementModule classpathElementModule = new ClasspathElementModule(
                                    nonSystemModuleRef, contextClassLoaders, nestedJarHandler, scanSpec);
                            classpathElementModule.open(/* ignored */ null, classpathFinderLog);
                            moduleClasspathEltOrder.add(classpathElementModule);
                        } else {
                            if (classpathFinderLog != null) {
                                classpathFinderLog
                                        .log("Skipping non-whitelisted or blacklisted module: " + moduleName);
                            }
                        }
                    }
                }
            }

            // Get order of elements in traditional classpath
            final List<RawClasspathElementWorkUnit> rawClasspathElementWorkUnits = new ArrayList<>();
            for (final String rawClasspathEltPath : classpathFinder.getClasspathOrder().getOrder()) {
                rawClasspathElementWorkUnits.add(
                        new RawClasspathElementWorkUnit(rawClasspathEltPath, /* parentClasspathElement = */ null,
                                /* orderWithinParentClasspathElement = */ rawClasspathElementWorkUnits.size()));
            }

            // For each classpath element path, canonicalize path, and create a ClasspathElement singleton
            final SingletonMap<String, ClasspathElement> classpathElementSingletonMap = //
                    new SingletonMap<String, ClasspathElement>() {
                        @Override
                        public ClasspathElement newInstance(final String classpathEltPath, final LogNode log)
                                throws Exception {
                            final ClassLoader[] classLoaders = classpathEltPathToClassLoaders.get(classpathEltPath);
                            if (classpathEltPath.regionMatches(true, 0, "http://", 0, 7)
                                    || classpathEltPath.regionMatches(true, 0, "https://", 0, 8)) {
                                // For remote URLs, must be a jar
                                return new ClasspathElementZip(classpathEltPath, classLoaders, nestedJarHandler,
                                        scanSpec);
                            }
                            // Normalize path -- strip off any leading "jar:" / "file:", and normalize separators
                            final String pathNormalized = FastPathResolver.resolve(FileUtils.CURR_DIR_PATH,
                                    classpathEltPath);
                            // Strip "jar:" and/or "file:" prefix, if present
                            // Strip everything after first "!", to get path of base jarfile or dir
                            final int plingIdx = pathNormalized.indexOf("!");
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
                            boolean isJar = classpathEltPath.regionMatches(true, 0, "jar:", 0, 4) || plingIdx > 0;
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
                            final String baseFileCanonicalPathNormalized = FastPathResolver
                                    .resolve(FileUtils.CURR_DIR_PATH, fileCanonicalized.getPath());
                            final String canonicalPathNormalized = plingIdx < 0 ? baseFileCanonicalPathNormalized
                                    : baseFileCanonicalPathNormalized + pathNormalized.substring(plingIdx);
                            if (!canonicalPathNormalized.equals(pathNormalized)) {
                                // If canonicalized path is not the same as pre-canonicalized path, need to recurse
                                // to map non-canonicalized path to singleton for canonicalized path (this should
                                // only recurse once, since File::getCanonicalFile and FastPathResolver::resolve are
                                // idempotent)
                                return this.get(canonicalPathNormalized, log);
                            } else {
                                // Otherwise path is already canonical, and this is the first time this path has
                                // been seen -- instantiate a ClasspathElementZip or ClasspathElementDir singleton
                                // for the classpath element path
                                return isJar
                                        ? new ClasspathElementZip(canonicalPathNormalized, classLoaders,
                                                nestedJarHandler, scanSpec)
                                        : new ClasspathElementDir(fileCanonicalized, classLoaders, scanSpec);
                            }
                        }
                    };

            // In parallel, create a ClasspathElement singleton for each classpath element, then call isValid()
            // on each ClasspathElement object, which in the case of jarfiles will cause LogicalZipFile instances
            // to be created for each (possibly nested) jarfile, then will read the manifest file and zip entries.
            final LogNode preScanLog = classpathFinderLog == null ? null
                    : classpathFinderLog.log("Reading jarfile directories and manifest files");
            final Set<ClasspathElement> openedClasspathElementsSet = Collections
                    .newSetFromMap(new ConcurrentHashMap<ClasspathElement, Boolean>());
            final Queue<Entry<Integer, ClasspathElement>> toplevelClasspathEltOrder = new ConcurrentLinkedQueue<>();
            WorkQueue.runWorkQueue(rawClasspathElementWorkUnits, executorService, numParallelTasks,
                    new WorkUnitProcessor<RawClasspathElementWorkUnit>() {
                        @Override
                        public void processWorkUnit(final RawClasspathElementWorkUnit workUnit,
                                final WorkQueue<RawClasspathElementWorkUnit> workQueue) throws Exception {
                            try {
                                // Create a ClasspathElementZip or ClasspathElementDir for each entry in the
                                // traditional classpath
                                final ClasspathElement classpathElt = classpathElementSingletonMap
                                        .get(workUnit.rawClasspathEltPath, classpathFinderLog);

                                // Only run open() once per ClasspathElement (it is possible for there to be
                                // multiple classpath elements with different non-canonical paths that map to
                                // the same canonical path, i.e. to the same ClasspathElement)
                                if (openedClasspathElementsSet.add(classpathElt)) {
                                    // Check if the classpath element is valid (classpathElt.skipClasspathElement
                                    // will be set if not). In case of ClasspathElementZip, open or extract nested
                                    // jars as LogicalZipFile instances. Read manifest files for jarfiles to look
                                    // for Class-Path manifest entries. Adds extra classpath elements to the work
                                    // queue if they are found.
                                    classpathElt.open(workQueue, preScanLog);

                                    // Create a new tuple consisting of the order of the new classpath element
                                    // within its parent, and the new classpath element
                                    final SimpleEntry<Integer, ClasspathElement> classpathEltEntry = //
                                            new SimpleEntry<>(workUnit.orderWithinParentClasspathElement,
                                                    classpathElt);
                                    if (workUnit.parentClasspathElement != null) {
                                        // Link classpath element to its parent, if it is not a toplevel element
                                        workUnit.parentClasspathElement.childClasspathElementsIndexed
                                                .add(classpathEltEntry);
                                    } else {
                                        // Record toplevel elements
                                        toplevelClasspathEltOrder.add(classpathEltEntry);
                                    }
                                }
                            } catch (final IllegalArgumentException e) {
                                if (classpathFinderLog != null) {
                                    classpathFinderLog.log(
                                            "Skipping invalid classpath element " + workUnit.rawClasspathEltPath);
                                }
                            } catch (final IOException e) {
                                if (classpathFinderLog != null) {
                                    classpathFinderLog.log("Skipping invalid classpath element "
                                            + workUnit.rawClasspathEltPath + " : " + e);
                                }
                            } catch (final Exception e) {
                                if (classpathFinderLog != null) {
                                    classpathFinderLog.log("Uncaught exception while processing classpath element "
                                            + workUnit.rawClasspathEltPath + " : " + e);
                                }
                            }
                        }
                    }, interruptionChecker, preScanLog);

            // Determine total ordering of classpath elements, inserting jars referenced in manifest Class-Path
            // entries in-place into the ordering, if they haven't been listed earlier in the classpath already.
            final List<ClasspathElement> finalTraditionalClasspathEltOrder = findClasspathOrder(
                    openedClasspathElementsSet, toplevelClasspathEltOrder);

            // Find classpath elements that are path prefixes of other classpath elements
            {
                final List<SimpleEntry<String, ClasspathElement>> classpathEltDirs = new ArrayList<>();
                final List<SimpleEntry<String, ClasspathElement>> classpathEltZips = new ArrayList<>();
                for (final ClasspathElement classpathElt : finalTraditionalClasspathEltOrder) {
                    if (classpathElt instanceof ClasspathElementDir) {
                        classpathEltDirs.add(new SimpleEntry<>(
                                ((ClasspathElementDir) classpathElt).getDirFile().getPath(), classpathElt));
                    } else if (classpathElt instanceof ClasspathElementZip) {
                        classpathEltZips.add(new SimpleEntry<>(
                                ((ClasspathElementZip) classpathElt).getZipFilePath(), classpathElt));
                    }
                }
                // Find nested classpath elements (writes to ClasspathElement#nestedClasspathRootPrefixes)
                findNestedClasspathElements(classpathEltDirs, classpathFinderLog);
                findNestedClasspathElements(classpathEltZips, classpathFinderLog);
            }

            // Order modules before classpath elements from traditional classpath 
            final LogNode classpathOrderLog = classpathFinderLog == null ? null
                    : classpathFinderLog.log("Final classpath element order:");
            final int numElts = moduleClasspathEltOrder.size() + finalTraditionalClasspathEltOrder.size();
            final List<ClasspathElement> finalClasspathEltOrder = new ArrayList<>(numElts);
            final List<String> finalClasspathEltOrderStrs = new ArrayList<>(numElts);
            for (final ClasspathElementModule classpathElt : moduleClasspathEltOrder) {
                finalClasspathEltOrder.add(classpathElt);
                finalClasspathEltOrderStrs.add(classpathElt.toString());
                if (classpathOrderLog != null) {
                    final ModuleRef moduleRef = classpathElt.getModuleRef();
                    classpathOrderLog.log(moduleRef.toString());
                }
            }
            for (final ClasspathElement classpathElt : finalTraditionalClasspathEltOrder) {
                finalClasspathEltOrder.add(classpathElt);
                finalClasspathEltOrderStrs.add(classpathElt.toString());
                if (classpathOrderLog != null) {
                    classpathOrderLog.log(classpathElt.toString());
                }
            }

            // If only getting classpath, not performing a scan
            if (!scanSpec.performScan) {
                if (topLevelLog != null) {
                    topLevelLog.log("Only returning classpath elements (not performing a scan)");
                }
                // This is the result of a call to ClassGraph#getUniqueClasspathElements(), so just
                // create placeholder ScanResult to contain classpathElementFilesOrdered.
                final ScanResult scanResult = new ScanResult(scanSpec, finalClasspathEltOrder,
                        finalClasspathEltOrderStrs, contextClassLoaders, /* classNameToClassInfo = */ null,
                        /* packageNameToPackageInfo = */ null, /* moduleNameToModuleInfo = */ null,
                        /* fileToLastModified = */ null, nestedJarHandler, topLevelLog);

                if (topLevelLog != null) {
                    topLevelLog.log("Completed", System.nanoTime() - scanStart);
                }

                // Skip the actual scan
                return scanResult;
            }

            // Scan paths within each classpath element, comparing them against whitelist/blacklist criteria
            final LogNode pathScanLog = classpathFinderLog == null ? null
                    : classpathFinderLog.log("Scanning filenames within classpath elements");
            WorkQueue.runWorkQueue(finalClasspathEltOrder, executorService, numParallelTasks,
                    new WorkUnitProcessor<ClasspathElement>() {
                        @Override
                        public void processWorkUnit(final ClasspathElement classpathElement,
                                final WorkQueue<ClasspathElement> workQueueIgnored) {
                            // Scan the paths within a directory or jar
                            classpathElement.scanPaths(pathScanLog);
                            if (preScanLog != null) {
                                preScanLog.addElapsedTime();
                            }
                        }
                    }, interruptionChecker, pathScanLog);
            if (preScanLog != null) {
                preScanLog.addElapsedTime();
            }

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

            // Implement classpath masking -- if the same relative classfile path occurs multiple times in the
            // classpath, ignore (remove) the second and subsequent occurrences. Note that classpath masking is
            // performed whether or not a jar is whitelisted, and whether or not jar or dir scanning is enabled,
            // in order to ensure that class references passed into MatchProcessors are the same as those that
            // would be loaded by standard classloading. (See bug #100.)
            {
                final LogNode maskLog = topLevelLog == null ? null : topLevelLog.log("Masking classfiles");
                final HashSet<String> nonBlacklistedClasspathRelativePathsFound = new HashSet<>();
                final HashSet<String> whitelistedClasspathRelativePathsFound = new HashSet<>();
                for (int classpathIdx = 0; classpathIdx < finalClasspathEltOrderFiltered.size(); classpathIdx++) {
                    finalClasspathEltOrderFiltered.get(classpathIdx).maskClassfiles(classpathIdx,
                            whitelistedClasspathRelativePathsFound, nonBlacklistedClasspathRelativePathsFound,
                            maskLog);
                }
            }

            // Merge the maps from file to timestamp across all classpath elements (there will be no overlap in
            // keyspace, since file masking was already performed)
            final Map<File, Long> fileToLastModified = new HashMap<>();
            for (final ClasspathElement classpathElement : finalClasspathEltOrderFiltered) {
                fileToLastModified.putAll(classpathElement.fileToLastModified);
            }

            final Map<String, ClassInfo> classNameToClassInfo = new HashMap<>();
            final Map<String, PackageInfo> packageNameToPackageInfo = new HashMap<>();
            final Map<String, ModuleInfo> moduleNameToModuleInfo = new HashMap<>();
            if (!scanSpec.enableClassInfo) {
                if (topLevelLog != null) {
                    topLevelLog.log("Classfile scanning is disabled");
                }
            } else {
                // Get whitelisted classfile order
                final List<ClassfileScanWorkUnit> classfileScanWorkItems = new ArrayList<>();
                final Set<String> scannedClassNames = Collections
                        .newSetFromMap(new ConcurrentHashMap<String, Boolean>());
                for (final ClasspathElement classpathElement : finalClasspathEltOrderFiltered) {
                    // Get classfile scan order across all classpath elements
                    for (final Resource resource : classpathElement.whitelistedClassfileResources) {
                        classfileScanWorkItems.add(
                                new ClassfileScanWorkUnit(classpathElement, resource, /* isExternal = */ false));
                        // Pre-seed scanned class names with all whitelisted classes (since these will
                        // be scanned for sure)
                        scannedClassNames.add(JarUtils.classfilePathToClassName(resource.getPath()));
                    }
                }

                // Scan classfiles in parallel
                final ConcurrentLinkedQueue<ClassInfoUnlinked> classInfoUnlinkedQueue = //
                        new ConcurrentLinkedQueue<>();
                final LogNode classfileScanLog = topLevelLog == null ? null
                        : topLevelLog.log("Scanning classfiles");
                WorkQueue.runWorkQueue(classfileScanWorkItems, executorService, numParallelTasks,
                        new ClassfileScannerWorkUnitProcessor(scanSpec, finalClasspathEltOrderFiltered,
                                scannedClassNames, classInfoUnlinkedQueue, classfileScanLog, interruptionChecker),
                        interruptionChecker, classfileScanLog);
                if (classfileScanLog != null) {
                    classfileScanLog.addElapsedTime();
                }

                // Build the class graph: convert ClassInfoUnlinked to linked ClassInfo objects.
                final LogNode classGraphLog = topLevelLog == null ? null : topLevelLog.log("Building class graph");
                for (final ClassInfoUnlinked c : classInfoUnlinkedQueue) {
                    c.link(scanSpec, classNameToClassInfo, packageNameToPackageInfo, moduleNameToModuleInfo,
                            classGraphLog);
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
                //        classInfo.getReferencedClassNames(referencedClassNames);
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
            final ScanResult scanResult = new ScanResult(scanSpec, finalClasspathEltOrder,
                    finalClasspathEltOrderStrs, contextClassLoaders, classNameToClassInfo, packageNameToPackageInfo,
                    moduleNameToModuleInfo, fileToLastModified, nestedJarHandler, topLevelLog);

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
            nestedJarHandler.close(topLevelLog);
            if (topLevelLog != null) {
                topLevelLog.log("Exception while scanning", e);
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
            }
            if (topLevelLog != null) {
                topLevelLog.flush();
            }
        }
    }
}
