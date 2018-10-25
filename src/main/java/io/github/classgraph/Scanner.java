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
import java.io.IOException;
import java.util.AbstractMap.SimpleEntry;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
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

    /** Used to enqueue classfiles for scanning. */
    private static class ClassfileScanWorkUnit {
        ClasspathElement classpathElement;
        Resource classfileResource;
        boolean isExternalClass;

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
        private final Map<String, Resource> classNameToNonBlacklistedResource;
        private final Set<String> scannedClassNames;
        private final ConcurrentLinkedQueue<ClassInfoUnlinked> classInfoUnlinkedQueue;
        private final LogNode log;
        private final InterruptionChecker interruptionChecker;

        public ClassfileScannerWorkUnitProcessor(final ScanSpec scanSpec,
                final Map<String, Resource> classNameToNonBlacklistedResource, final Set<String> scannedClassNames,
                final ConcurrentLinkedQueue<ClassInfoUnlinked> classInfoUnlinkedQueue, final LogNode log,
                final InterruptionChecker interruptionChecker) {
            this.scanSpec = scanSpec;
            this.classNameToNonBlacklistedResource = classNameToNonBlacklistedResource;
            this.scannedClassNames = scannedClassNames;
            this.classInfoUnlinkedQueue = classInfoUnlinkedQueue;
            this.log = log;
            this.interruptionChecker = interruptionChecker;
        }

        /** Extend scanning to a superclass, interface or annotation. */
        private List<ClassfileScanWorkUnit> extendScanningUpwards(final String className, final String relationship,
                final ClasspathElement classpathElement, final List<ClassfileScanWorkUnit> additionalWorkUnitsIn,
                final LogNode subLog) {
            List<ClassfileScanWorkUnit> additionalWorkUnits = additionalWorkUnitsIn;
            // Don't scan a class more than once 
            if (className != null && scannedClassNames.add(className)) {
                // See if the named class can be found as a non-blacklisted resource
                final Resource classResource = classNameToNonBlacklistedResource.get(className);
                if (classResource != null) {
                    // Class is a non-blacklisted external class -- enqueue for scanning
                    if (subLog != null) {
                        subLog.log("Scheduling external class for scanning: " + relationship + " " + className);
                    }
                    if (additionalWorkUnits == null) {
                        additionalWorkUnits = new ArrayList<>();
                    }
                    additionalWorkUnits.add(new ClassfileScanWorkUnit(classpathElement, classResource,
                            /* isExternalClass = */ true));
                } else {
                    if (subLog != null && !className.equals("java.lang.Object")) {
                        subLog.log("External " + relationship + " " + className
                                + " was not found in non-blacklisted packages -- "
                                + "cannot extend scanning to this superclass");
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
                        // Check superclass
                        List<ClassfileScanWorkUnit> additionalWorkUnits = null;
                        additionalWorkUnits = extendScanningUpwards(classInfoUnlinked.superclassName, "superclass",
                                workUnit.classpathElement, additionalWorkUnits, subLog);

                        // Check implemented interfaces
                        if (classInfoUnlinked.implementedInterfaces != null) {
                            for (final String className : classInfoUnlinked.implementedInterfaces) {
                                additionalWorkUnits = extendScanningUpwards(className, "interface",
                                        workUnit.classpathElement, additionalWorkUnits, subLog);
                            }
                        }

                        // Check class annotations
                        if (classInfoUnlinked.classAnnotations != null) {
                            for (final AnnotationInfo annotationInfo : classInfoUnlinked.classAnnotations) {
                                additionalWorkUnits = extendScanningUpwards(annotationInfo.getName(),
                                        "class annotation", workUnit.classpathElement, additionalWorkUnits, subLog);
                            }
                        }

                        // Check method annotations and method parameter annotations
                        if (classInfoUnlinked.methodInfoList != null) {
                            for (final MethodInfo methodInfo : classInfoUnlinked.methodInfoList) {
                                if (methodInfo.annotationInfo != null) {
                                    for (final AnnotationInfo methodAnnotationInfo : methodInfo.annotationInfo) {
                                        additionalWorkUnits = extendScanningUpwards(methodAnnotationInfo.getName(),
                                                "method annotation", workUnit.classpathElement, additionalWorkUnits,
                                                subLog);
                                    }
                                    if (methodInfo.parameterAnnotationInfo != null
                                            && methodInfo.parameterAnnotationInfo.length > 0) {
                                        for (final AnnotationInfo[] paramAnns : methodInfo.parameterAnnotationInfo) {
                                            if (paramAnns != null && paramAnns.length > 0) {
                                                for (final AnnotationInfo paramAnn : paramAnns) {
                                                    additionalWorkUnits = extendScanningUpwards(paramAnn.getName(),
                                                            "method parameter annotation",
                                                            workUnit.classpathElement, additionalWorkUnits, subLog);
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
                                                "field annotation", workUnit.classpathElement, additionalWorkUnits,
                                                subLog);
                                    }
                                }
                            }
                        }

                        // If any external classes were found, schedule them for scanning
                        if (additionalWorkUnits != null) {
                            workQueue.addWorkUnits(additionalWorkUnits);
                        }
                    }
                }
                if (subLog != null) {
                    subLog.addElapsedTime();
                }
            } catch (

            final IOException e) {
                if (subLog != null) {
                    subLog.log("IOException while attempting to read classfile " + workUnit.classfileResource
                            + " -- skipping", e);
                }
            } catch (final Throwable e) {
                if (subLog != null) {
                    subLog.log("Exception while parsing classfile " + workUnit.classfileResource, e);
                }
                // Re-throw
                throw e;
            }
            interruptionChecker.check();
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
                        public void processWorkUnit(final ClasspathOrModulePathEntry rawClasspathEltPath,
                                final WorkQueue<ClasspathOrModulePathEntry> workQueue) throws Exception {
                            try {
                                // Ensure the work queue is set -- will be done multiple times, but is idempotent
                                classpathElementMap.setWorkQueue(workQueue);

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
                        /* classNameToClassInfo = */ null, /* packageNameToPackageInfo = */ null,
                        /* moduleNameToModuleInfo = */ null, /* fileToLastModified = */ null, nestedJarHandler,
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
                            public void processWorkUnit(final ClasspathElement classpathElement,
                                    final WorkQueue<ClasspathElement> workQueueIgnored) throws Exception {
                                // Scan the paths within a directory or jar
                                classpathElement.scanPaths(pathScanLog);
                                if (preScanLog != null) {
                                    preScanLog.addElapsedTime();
                                }
                            }
                        }, interruptionChecker, pathScanLog);

                // Implement classpath masking -- if the same relative classfile path occurs multiple times in the
                // classpath, ignore (remove) the second and subsequent occurrences. Note that classpath masking is
                // performed whether or not a jar is whitelisted, and whether or not jar or dir scanning is enabled,
                // in order to ensure that class references passed into MatchProcessors are the same as those that
                // would be loaded by standard classloading. (See bug #100.)
                {
                    final LogNode maskLog = topLevelLog == null ? null : topLevelLog.log("Masking classfiles");
                    final HashSet<String> nonBlacklistedClasspathRelativePathsFound = new HashSet<>();
                    final HashSet<String> whitelistedClasspathRelativePathsFound = new HashSet<>();
                    for (int classpathIdx = 0; classpathIdx < classpathOrder.size(); classpathIdx++) {
                        classpathOrder.get(classpathIdx).maskClassfiles(classpathIdx,
                                whitelistedClasspathRelativePathsFound, nonBlacklistedClasspathRelativePathsFound,
                                maskLog);
                    }
                }

                // Merge the maps from file to timestamp across all classpath elements (there will be no overlap in
                // keyspace, since file masking was already performed)
                final Map<File, Long> fileToLastModified = new HashMap<>();
                for (final ClasspathElement classpathElement : classpathOrder) {
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
                    // Get whitelisted classfile order, and a map from class name to non-blacklisted classfile
                    final List<ClassfileScanWorkUnit> classfileScanWorkItems = new ArrayList<>();
                    final Map<String, Resource> classNameToNonBlacklistedResource = new HashMap<>();
                    final Set<String> scannedClassNames = Collections
                            .newSetFromMap(new ConcurrentHashMap<String, Boolean>());
                    for (final ClasspathElement classpathElement : classpathOrder) {
                        // Get classfile scan order across all classpath elements
                        for (final Resource resource : classpathElement.whitelistedClassfileResources) {
                            classfileScanWorkItems.add(new ClassfileScanWorkUnit(classpathElement, resource,
                                    /* isExternal = */ false));
                            // Pre-seed scanned class names with all whitelisted classes (since these will
                            // be scanned for sure)
                            scannedClassNames.add(JarUtils.classfilePathToClassName(resource.getPath()));
                        }
                        // Get mapping from class name to Resource object for non-blacklisted classes
                        // (these are used to scan superclasses, interfaces and annotations of whitelisted
                        // classes that are not themselves whitelisted)
                        for (final Resource resource : classpathElement.nonBlacklistedClassfileResources) {
                            classNameToNonBlacklistedResource
                                    .put(JarUtils.classfilePathToClassName(resource.getPath()), resource);
                        }
                    }

                    // Scan classfiles in parallel
                    final ConcurrentLinkedQueue<ClassInfoUnlinked> classInfoUnlinkedQueue = //
                            new ConcurrentLinkedQueue<>();
                    final LogNode classfileScanLog = topLevelLog == null ? null
                            : topLevelLog.log("Scanning classfiles");
                    WorkQueue.runWorkQueue(classfileScanWorkItems, executorService, numParallelTasks,
                            new ClassfileScannerWorkUnitProcessor(scanSpec, classNameToNonBlacklistedResource,
                                    scannedClassNames, classInfoUnlinkedQueue, classfileScanLog,
                                    interruptionChecker),
                            interruptionChecker, classfileScanLog);
                    if (classfileScanLog != null) {
                        classfileScanLog.addElapsedTime();
                    }

                    // Build the class graph: convert ClassInfoUnlinked to linked ClassInfo objects.
                    final LogNode classGraphLog = topLevelLog == null ? null
                            : topLevelLog.log("Building class graph");
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
                        classNameToClassInfo, packageNameToPackageInfo, moduleNameToModuleInfo, fileToLastModified,
                        nestedJarHandler, topLevelLog);

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
            this.nestedJarHandler.close(topLevelLog);
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
