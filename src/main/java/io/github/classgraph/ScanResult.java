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

import java.io.Closeable;
import java.io.File;
import java.lang.ref.WeakReference;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import nonapi.io.github.classgraph.concurrency.AutoCloseableExecutorService;
import nonapi.io.github.classgraph.fastzipfilereader.NestedJarHandler;
import nonapi.io.github.classgraph.json.JSONDeserializer;
import nonapi.io.github.classgraph.json.JSONSerializer;
import nonapi.io.github.classgraph.scanspec.ScanSpec;
import nonapi.io.github.classgraph.utils.CollectionUtils;
import nonapi.io.github.classgraph.utils.FileUtils;
import nonapi.io.github.classgraph.utils.JarUtils;
import nonapi.io.github.classgraph.utils.LogNode;

/**
 * The result of a scan. You should assign a ScanResult in a try-with-resources block, or manually close it when you
 * have finished with the result of a scan.
 */
public final class ScanResult implements Closeable, AutoCloseable {
    /** The order of raw classpath elements. */
    private List<String> rawClasspathEltOrderStrs;

    /** The order of classpath elements, after inner jars have been extracted to temporary files, etc. */
    private List<ClasspathElement> classpathOrder;

    /** A list of all files that were found in whitelisted packages. */
    private ResourceList allWhitelistedResourcesCached;

    /** The number of times {@link #getResourcesWithPath(String)} has been called. */
    private final AtomicInteger getResourcesWithPathCallCount = new AtomicInteger();

    /**
     * The map from path (relative to package root) to a list of {@link Resource} elements with the matching path.
     */
    private Map<String, ResourceList> pathToWhitelistedResourcesCached;

    /** The map from class name to {@link ClassInfo}. */
    Map<String, ClassInfo> classNameToClassInfo;

    /** The map from package name to {@link PackageInfo}. */
    private Map<String, PackageInfo> packageNameToPackageInfo;

    /** The map from class name to {@link ClassInfo}. */
    private Map<String, ModuleInfo> moduleNameToModuleInfo;

    /**
     * The file, directory and jarfile resources timestamped during a scan, along with their timestamp at the time
     * of the scan. For jarfiles, the timestamp represents the timestamp of all files within the jar. May be null,
     * if this ScanResult object is the result of a call to ClassGraph#getUniqueClasspathElementsAsync().
     */
    private Map<File, Long> fileToLastModified;

    /** If true, this {@link ScanResult} was produced by {@link ScanResult#fromJSON(String)}. */
    private boolean isObtainedFromDeserialization;

    /** A custom ClassLoader that can load classes found during the scan. */
    private ClassGraphClassLoader classGraphClassLoader;

    /**
     * The default order in which ClassLoaders are called to load classes, respecting parent-first/parent-last
     * delegation order.
     */
    private ClassLoader[] classLoaderOrderRespectingParentDelegation;

    /** The nested jar handler instance. */
    private NestedJarHandler nestedJarHandler;

    /** The scan spec. */
    ScanSpec scanSpec;

    /** If true, this ScanResult has already been closed. */
    private final AtomicBoolean closed = new AtomicBoolean(false);

    /** The toplevel log. */
    private final LogNode topLevelLog;

    // -------------------------------------------------------------------------------------------------------------

    /** The {@link WeakReference} for this ScanResult. */
    private final WeakReference<ScanResult> weakReference;

    /**
     * The set of WeakReferences to non-closed ScanResult objects. Uses WeakReferences so that garbage collection is
     * not blocked. (Bug #233)
     */
    private static Set<WeakReference<ScanResult>> nonClosedWeakReferences = Collections
            .newSetFromMap(new ConcurrentHashMap<WeakReference<ScanResult>, Boolean>());

    /** If true, ScanResult#staticInit() has been run. */
    private static final AtomicBoolean initialized = new AtomicBoolean(false);

    // -------------------------------------------------------------------------------------------------------------

    /** The current serialization format. */
    private static final String CURRENT_SERIALIZATION_FORMAT = "10";

    /** A class to hold a serialized ScanResult along with the ScanSpec that was used to scan. */
    private static class SerializationFormat {
        /** The serialization format. */
        public String format;

        /** The scan spec. */
        public ScanSpec scanSpec;

        /** The classpath, as a list of URL strings. */
        public List<String> classpath;

        /** The list of all {@link ClassInfo} objects. */
        public List<ClassInfo> classInfo;

        /** The list of all {@link PackageInfo} objects. */
        public List<PackageInfo> packageInfo;

        /** The list of all {@link ModuleInfo} objects. */
        public List<ModuleInfo> moduleInfo;

        /**
         * Constructor.
         */
        @SuppressWarnings("unused")
        public SerializationFormat() {
            // Empty
        }

        /**
         * Constructor.
         *
         * @param serializationFormatStr
         *            the serialization format string
         * @param scanSpec
         *            the scan spec
         * @param classInfo
         *            the list of all {@link ClassInfo} objects
         * @param packageInfo
         *            the list of all {@link PackageInfo} objects
         * @param moduleInfo
         *            the list of all {@link ModuleInfo} objects
         * @param classpath
         *            the classpath as a list of URL strings
         */
        public SerializationFormat(final String serializationFormatStr, final ScanSpec scanSpec,
                final List<ClassInfo> classInfo, final List<PackageInfo> packageInfo,
                final List<ModuleInfo> moduleInfo, final List<String> classpath) {
            this.format = serializationFormatStr;
            this.scanSpec = scanSpec;
            this.classpath = classpath;
            this.classInfo = classInfo;
            this.packageInfo = packageInfo;
            this.moduleInfo = moduleInfo;
        }
    }

    // -------------------------------------------------------------------------------------------------------------
    // Shutdown hook init code

    /**
     * Static initialization (warm up classloading), called when the ClassGraph class is initialized.
     */
    static void init() {
        if (!initialized.getAndSet(true)) {
            // Pre-load non-system classes necessary for calling scanResult.close(), so that classes that need
            // to be loaded to close resources are already loaded and cached. This was originally for use in
            // a shutdown hook (#331), which has now been removed, but it is probably still a good idea to
            // ensure that classes needed to unmap DirectByteBuffer instances are available at init.
            // We achieve this by mmap'ing a file and then closing it, since the only problematic classes are
            // the PriviledgedAction anonymous inner classes used by FileUtils::closeDirectByteBuffer.
            FileUtils.closeDirectByteBuffer(ByteBuffer.allocateDirect(32), /* log = */ null);
        }
    }

    // -------------------------------------------------------------------------------------------------------------
    // Constructor

    /**
     * The result of a scan. Make sure you call complete() after calling the constructor.
     *
     * @param scanSpec
     *            the scan spec
     * @param classpathOrder
     *            the classpath order
     * @param rawClasspathEltOrderStrs
     *            the raw classpath element order
     * @param classLoaderOrderRespectingParentDelegation
     *            the environment classloader order, respecting parent-first or parent-last delegation order
     * @param classNameToClassInfo
     *            a map from class name to class info
     * @param packageNameToPackageInfo
     *            a map from package name to package info
     * @param moduleNameToModuleInfo
     *            a map from module name to module info
     * @param fileToLastModified
     *            a map from file to last modified time
     * @param nestedJarHandler
     *            the nested jar handler
     * @param topLevelLog
     *            the toplevel log
     */
    ScanResult(final ScanSpec scanSpec, final List<ClasspathElement> classpathOrder,
            final List<String> rawClasspathEltOrderStrs,
            final ClassLoader[] classLoaderOrderRespectingParentDelegation,
            final Map<String, ClassInfo> classNameToClassInfo,
            final Map<String, PackageInfo> packageNameToPackageInfo,
            final Map<String, ModuleInfo> moduleNameToModuleInfo, final Map<File, Long> fileToLastModified,
            final NestedJarHandler nestedJarHandler, final LogNode topLevelLog) {
        this.scanSpec = scanSpec;
        this.rawClasspathEltOrderStrs = rawClasspathEltOrderStrs;
        this.classpathOrder = classpathOrder;
        this.classLoaderOrderRespectingParentDelegation = classLoaderOrderRespectingParentDelegation;
        this.fileToLastModified = fileToLastModified;
        this.classNameToClassInfo = classNameToClassInfo;
        this.packageNameToPackageInfo = packageNameToPackageInfo;
        this.moduleNameToModuleInfo = moduleNameToModuleInfo;
        this.nestedJarHandler = nestedJarHandler;
        this.topLevelLog = topLevelLog;

        if (classNameToClassInfo != null) {
            indexResourcesAndClassInfo();
        }

        if (classNameToClassInfo != null) {
            // Handle @Repeatable annotations
            final Set<String> allRepeatableAnnotationNames = new HashSet<>();
            for (final ClassInfo classInfo : classNameToClassInfo.values()) {
                if (classInfo.isAnnotation() && classInfo.annotationInfo != null) {
                    final AnnotationInfo repeatableMetaAnnotation = classInfo.annotationInfo
                            .get("java.lang.annotation.Repeatable");
                    if (repeatableMetaAnnotation != null) {
                        final AnnotationParameterValueList vals = repeatableMetaAnnotation.getParameterValues();
                        if (!vals.isEmpty()) {
                            final Object val = vals.getValue("value");
                            if (val instanceof AnnotationClassRef) {
                                final AnnotationClassRef classRef = (AnnotationClassRef) val;
                                final String repeatableAnnotationName = classRef.getName();
                                if (repeatableAnnotationName != null) {
                                    allRepeatableAnnotationNames.add(repeatableAnnotationName);
                                }
                            }
                        }
                    }
                }
            }
            if (!allRepeatableAnnotationNames.isEmpty()) {
                for (final ClassInfo classInfo : classNameToClassInfo.values()) {
                    classInfo.handleRepeatableAnnotations(allRepeatableAnnotationNames);
                }
            }
        }

        // Define a new ClassLoader that can load the classes found during the scan
        this.classGraphClassLoader = new ClassGraphClassLoader(this);

        // Provide the shutdown hook with a weak reference to this ScanResult
        this.weakReference = new WeakReference<>(this);
        nonClosedWeakReferences.add(this.weakReference);
    }

    /** Index {@link Resource} and {@link ClassInfo} objects. */
    private void indexResourcesAndClassInfo() {
        // Add backrefs from Info objects back to this ScanResult
        final Collection<ClassInfo> allClassInfo = classNameToClassInfo.values();
        for (final ClassInfo classInfo : allClassInfo) {
            classInfo.setScanResult(this);
        }

        // If inter-class dependencies are enabled, create placeholder ClassInfo objects for any referenced
        // classes that were not scanned
        if (scanSpec.enableInterClassDependencies) {
            for (final ClassInfo ci : new ArrayList<>(classNameToClassInfo.values())) {
                final Set<ClassInfo> refdClassesFiltered = new HashSet<>();
                for (final ClassInfo refdClassInfo : ci.findReferencedClassInfo()) {
                    // Don't add self-references, or references to Object
                    if (refdClassInfo != null && !ci.equals(refdClassInfo)
                            && !refdClassInfo.getName().equals("java.lang.Object")
                            // Only add class to result if it is whitelisted, or external classes are enabled
                            && (!refdClassInfo.isExternalClass() || scanSpec.enableExternalClasses)) {
                        refdClassInfo.setScanResult(this);
                        refdClassesFiltered.add(refdClassInfo);
                    }
                }
                ci.setReferencedClasses(new ClassInfoList(refdClassesFiltered, /* sortByName = */ true));
            }
        }
    }

    // -------------------------------------------------------------------------------------------------------------
    // Classpath / module path

    /**
     * Returns the list of File objects for unique classpath elements (directories or jarfiles), in classloader
     * resolution order.
     *
     * @return The unique classpath elements.
     */
    public List<File> getClasspathFiles() {
        if (closed.get()) {
            throw new IllegalArgumentException("Cannot use a ScanResult after it has been closed");
        }
        final List<File> classpathElementOrderFiles = new ArrayList<>();
        for (final ClasspathElement classpathElement : classpathOrder) {
            final File file = classpathElement.getFile();
            if (file != null) {
                classpathElementOrderFiles.add(file);
            }
        }
        return classpathElementOrderFiles;
    }

    /**
     * Returns all unique directories or zip/jarfiles on the classpath, in classloader resolution order, as a
     * classpath string, delineated with the standard path separator character.
     *
     * @return a the unique directories and jarfiles on the classpath, in classpath resolution order, as a path
     *         string.
     */
    public String getClasspath() {
        if (closed.get()) {
            throw new IllegalArgumentException("Cannot use a ScanResult after it has been closed");
        }
        return JarUtils.pathElementsToPathStr(getClasspathFiles());
    }

    /**
     * Returns an ordered list of unique classpath element and module URIs.
     *
     * @return The unique classpath element and module URIs.
     */
    public List<URI> getClasspathURIs() {
        if (closed.get()) {
            throw new IllegalArgumentException("Cannot use a ScanResult after it has been closed");
        }
        final List<URI> classpathElementOrderURIs = new ArrayList<>();
        for (final ClasspathElement classpathElement : classpathOrder) {
            try {
                final URI uri = classpathElement.getURI();
                if (uri != null) {
                    classpathElementOrderURIs.add(uri);
                }
            } catch (final IllegalArgumentException e) {
                // Skip null location URIs
            }
        }
        return classpathElementOrderURIs;
    }

    /**
     * Returns an ordered list of unique classpath element and module URLs. Will skip any system modules or modules
     * that are part of a jlink'd runtime image, since {@link URL} does not support the {@code jrt:} {@link URI}
     * scheme.
     *
     * @return The unique classpath element and module URLs.
     */
    public List<URL> getClasspathURLs() {
        if (closed.get()) {
            throw new IllegalArgumentException("Cannot use a ScanResult after it has been closed");
        }
        final List<URL> classpathElementOrderURLs = new ArrayList<>();
        for (final ClasspathElement classpathElement : classpathOrder) {
            try {
                final URI uri = classpathElement.getURI();
                if (uri != null) {
                    classpathElementOrderURLs.add(uri.toURL());
                }
            } catch (final IllegalArgumentException | MalformedURLException e) {
                // Skip "jrt:" URIs and malformed URLs
            }
        }
        return classpathElementOrderURLs;
    }

    /**
     * Get {@link ModuleRef} references for all visible modules.
     *
     * @return {@link ModuleRef} references for all visible modules.
     */
    public List<ModuleRef> getModules() {
        if (closed.get()) {
            throw new IllegalArgumentException("Cannot use a ScanResult after it has been closed");
        }
        final List<ModuleRef> moduleRefs = new ArrayList<>();
        for (final ClasspathElement classpathElement : classpathOrder) {
            if (classpathElement instanceof ClasspathElementModule) {
                moduleRefs.add(((ClasspathElementModule) classpathElement).getModuleRef());
            }
        }
        return moduleRefs;
    }

    /**
     * Get the module path info provided on the commandline with {@code --module-path}, {@code --add-modules},
     * {@code --patch-module}, {@code --add-exports}, {@code --add-opens}, and {@code --add-reads}, and also the
     * {@code Add-Exports} and {@code Add-Opens} entries from jarfile manifest files encountered during scanning.
     * 
     * <p>
     * Note that the returned {@link ModulePathInfo} object does not include classpath entries from the traditional
     * classpath or system modules. Use {@link #getModules()} to get all visible modules, including anonymous,
     * automatic and system modules.
     * 
     * @return The {@link ModulePathInfo}.
     */
    public ModulePathInfo getModulePathInfo() {
        return scanSpec.modulePathInfo;
    }

    // -------------------------------------------------------------------------------------------------------------
    // Resources

    /**
     * Get the list of all resources.
     *
     * @return A list of all resources (including classfiles and non-classfiles) found in whitelisted packages.
     */
    public ResourceList getAllResources() {
        if (allWhitelistedResourcesCached == null) {
            // Index Resource objects by path
            final ResourceList whitelistedResourcesList = new ResourceList();
            for (final ClasspathElement classpathElt : classpathOrder) {
                whitelistedResourcesList.addAll(classpathElt.whitelistedResources);
            }
            // Set atomically for thread safety
            allWhitelistedResourcesCached = whitelistedResourcesList;
        }
        return allWhitelistedResourcesCached;
    }

    /**
     * Get a map from resource path to {@link Resource} for all resources (including classfiles and non-classfiles)
     * found in whitelisted packages.
     *
     * @return The map from resource path to {@link Resource} for all resources (including classfiles and
     *         non-classfiles) found in whitelisted packages.
     */
    public Map<String, ResourceList> getAllResourcesAsMap() {
        if (pathToWhitelistedResourcesCached == null) {
            final Map<String, ResourceList> pathToWhitelistedResourceListMap = new HashMap<>();
            for (final Resource res : getAllResources()) {
                ResourceList resList = pathToWhitelistedResourceListMap.get(res.getPath());
                if (resList == null) {
                    pathToWhitelistedResourceListMap.put(res.getPath(), resList = new ResourceList());
                }
                resList.add(res);
            }
            // Set atomically for thread safety
            pathToWhitelistedResourcesCached = pathToWhitelistedResourceListMap;
        }
        return pathToWhitelistedResourcesCached;
    }

    /**
     * Get the list of all resources found in whitelisted packages that have the given path, relative to the package
     * root of the classpath element. May match several resources, up to one per classpath element.
     *
     * @param resourcePath
     *            A complete resource path, relative to the classpath entry package root.
     * @return A list of all resources found in whitelisted packages that have the given path, relative to the
     *         package root of the classpath element. May match several resources, up to one per classpath element.
     */
    public ResourceList getResourcesWithPath(final String resourcePath) {
        if (closed.get()) {
            throw new IllegalArgumentException("Cannot use a ScanResult after it has been closed");
        }
        final String path = FileUtils.sanitizeEntryPath(resourcePath, /* removeInitialSlash = */ true);
        if (getResourcesWithPathCallCount.incrementAndGet() > 3) {
            // If numerous calls are made, produce and cache a single HashMap for O(1) access time
            return getAllResourcesAsMap().get(path);
        } else {
            // If just a few calls are made, directly search for resource with the requested path
            ResourceList matchingResources = null;
            for (final ClasspathElement classpathElt : classpathOrder) {
                for (final Resource res : classpathElt.whitelistedResources) {
                    if (res.getPath().equals(path)) {
                        if (matchingResources == null) {
                            matchingResources = new ResourceList();
                        }
                        matchingResources.add(res);
                    }
                }
            }
            return matchingResources == null ? ResourceList.EMPTY_LIST : matchingResources;
        }
    }

    /**
     * Get the list of all resources found in any classpath element, <i>whether in whitelisted packages or not (as
     * long as the resource is not blacklisted)</i>, that have the given path, relative to the package root of the
     * classpath element. May match several resources, up to one per classpath element.
     *
     * @param resourcePath
     *            A complete resource path, relative to the classpath entry package root.
     * @return A list of all resources found in any classpath element, <i>whether in whitelisted packages or not (as
     *         long as the resource is not blacklisted)</i>, that have the given path, relative to the package root
     *         of the classpath element. May match several resources, up to one per classpath element.
     */
    public ResourceList getResourcesWithPathIgnoringWhitelist(final String resourcePath) {
        if (closed.get()) {
            throw new IllegalArgumentException("Cannot use a ScanResult after it has been closed");
        }
        final String path = FileUtils.sanitizeEntryPath(resourcePath, /* removeInitialSlash = */ true);
        final ResourceList matchingResources = new ResourceList();
        for (final ClasspathElement classpathElt : classpathOrder) {
            final Resource matchingResource = classpathElt.getResource(path);
            if (matchingResource != null) {
                matchingResources.add(matchingResource);
            }
        }
        return matchingResources;
    }

    /**
     * Get the list of all resources found in whitelisted packages that have the requested leafname.
     *
     * @param leafName
     *            A resource leaf filename.
     * @return A list of all resources found in whitelisted packages that have the requested leafname.
     */
    public ResourceList getResourcesWithLeafName(final String leafName) {
        if (closed.get()) {
            throw new IllegalArgumentException("Cannot use a ScanResult after it has been closed");
        }
        final ResourceList allWhitelistedResources = getAllResources();
        if (allWhitelistedResources.isEmpty()) {
            return ResourceList.EMPTY_LIST;
        } else {
            final ResourceList filteredResources = new ResourceList();
            for (final Resource classpathResource : allWhitelistedResources) {
                final String relativePath = classpathResource.getPath();
                final int lastSlashIdx = relativePath.lastIndexOf('/');
                if (relativePath.substring(lastSlashIdx + 1).equals(leafName)) {
                    filteredResources.add(classpathResource);
                }
            }
            return filteredResources;
        }
    }

    /**
     * Get the list of all resources found in whitelisted packages that have the requested filename extension.
     *
     * @param extension
     *            A filename extension, e.g. "xml" to match all resources ending in ".xml".
     * @return A list of all resources found in whitelisted packages that have the requested filename extension.
     */
    public ResourceList getResourcesWithExtension(final String extension) {
        if (closed.get()) {
            throw new IllegalArgumentException("Cannot use a ScanResult after it has been closed");
        }
        final ResourceList allWhitelistedResources = getAllResources();
        if (allWhitelistedResources.isEmpty()) {
            return ResourceList.EMPTY_LIST;
        } else {
            String bareExtension = extension;
            while (bareExtension.startsWith(".")) {
                bareExtension = bareExtension.substring(1);
            }
            final ResourceList filteredResources = new ResourceList();
            for (final Resource classpathResource : allWhitelistedResources) {
                final String relativePath = classpathResource.getPath();
                final int lastSlashIdx = relativePath.lastIndexOf('/');
                final int lastDotIdx = relativePath.lastIndexOf('.');
                if (lastDotIdx > lastSlashIdx
                        && relativePath.substring(lastDotIdx + 1).equalsIgnoreCase(bareExtension)) {
                    filteredResources.add(classpathResource);
                }
            }
            return filteredResources;
        }
    }

    /**
     * Get the list of all resources found in whitelisted packages that have a path matching the requested pattern.
     *
     * @param pattern
     *            A pattern to match {@link Resource} paths with.
     * @return A list of all resources found in whitelisted packages that have a path matching the requested
     *         pattern.
     */
    public ResourceList getResourcesMatchingPattern(final Pattern pattern) {
        if (closed.get()) {
            throw new IllegalArgumentException("Cannot use a ScanResult after it has been closed");
        }
        final ResourceList allWhitelistedResources = getAllResources();
        if (allWhitelistedResources.isEmpty()) {
            return ResourceList.EMPTY_LIST;
        } else {
            final ResourceList filteredResources = new ResourceList();
            for (final Resource classpathResource : allWhitelistedResources) {
                final String relativePath = classpathResource.getPath();
                if (pattern.matcher(relativePath).matches()) {
                    filteredResources.add(classpathResource);
                }
            }
            return filteredResources;
        }
    }

    // -------------------------------------------------------------------------------------------------------------
    // Modules

    /**
     * Get the {@link ModuleInfo} object for the named module, or null if no module of the requested name was found
     * during the scan.
     * 
     * @param moduleName
     *            The module name.
     * @return The {@link ModuleInfo} object for the named module, or null if the module was not found.
     */
    public ModuleInfo getModuleInfo(final String moduleName) {
        if (closed.get()) {
            throw new IllegalArgumentException("Cannot use a ScanResult after it has been closed");
        }
        if (!scanSpec.enableClassInfo) {
            throw new IllegalArgumentException("Please call ClassGraph#enableClassInfo() before #scan()");
        }
        return moduleNameToModuleInfo.get(moduleName);
    }

    /**
     * Get all modules found during the scan.
     *
     * @return A list of all modules found during the scan, or the empty list if none.
     */
    public ModuleInfoList getModuleInfo() {
        if (closed.get()) {
            throw new IllegalArgumentException("Cannot use a ScanResult after it has been closed");
        }
        if (!scanSpec.enableClassInfo) {
            throw new IllegalArgumentException("Please call ClassGraph#enableClassInfo() before #scan()");
        }
        return new ModuleInfoList(moduleNameToModuleInfo.values());
    }

    // -------------------------------------------------------------------------------------------------------------
    // Packages

    /**
     * Get the {@link PackageInfo} object for the named package, or null if no package of the requested name was
     * found during the scan.
     * 
     * @param packageName
     *            The package name.
     * @return The {@link PackageInfo} object for the named package, or null if the package was not found.
     */
    public PackageInfo getPackageInfo(final String packageName) {
        if (closed.get()) {
            throw new IllegalArgumentException("Cannot use a ScanResult after it has been closed");
        }
        if (!scanSpec.enableClassInfo) {
            throw new IllegalArgumentException("Please call ClassGraph#enableClassInfo() before #scan()");
        }
        return packageNameToPackageInfo.get(packageName);
    }

    /**
     * Get all packages found during the scan.
     *
     * @return A list of all packages found during the scan, or the empty list if none.
     */
    public PackageInfoList getPackageInfo() {
        if (closed.get()) {
            throw new IllegalArgumentException("Cannot use a ScanResult after it has been closed");
        }
        if (!scanSpec.enableClassInfo) {
            throw new IllegalArgumentException("Please call ClassGraph#enableClassInfo() before #scan()");
        }
        return new PackageInfoList(packageNameToPackageInfo.values());
    }

    // -------------------------------------------------------------------------------------------------------------
    // Class dependencies

    /**
     * Get a map from the {@link ClassInfo} object for each whitelisted class to a list of the classes referenced by
     * that class (i.e. returns a map from dependents to dependencies). Note that you need to call
     * {@link ClassGraph#enableInterClassDependencies()} before {@link ClassGraph#scan()} for this method to work.
     * You should also call {@link ClassGraph#enableExternalClasses()} before {@link ClassGraph#scan()} if you want
     * non-whitelisted classes to appear in the result. See also {@link #getReverseClassDependencyMap()}, which
     * inverts the map.
     *
     * @return A map from a {@link ClassInfo} object for each whitelisted class to a list of the classes referenced
     *         by that class (i.e. returns a map from dependents to dependencies). Each map value is the result of
     *         calling {@link ClassInfo#getClassDependencies()} on the corresponding key.
     */
    public Map<ClassInfo, ClassInfoList> getClassDependencyMap() {
        final Map<ClassInfo, ClassInfoList> map = new HashMap<>();
        for (final ClassInfo ci : getAllClasses()) {
            map.put(ci, ci.getClassDependencies());
        }
        return map;
    }

    /**
     * Get the reverse class dependency map, i.e. a map from the {@link ClassInfo} object for each dependency class
     * (whitelisted or not) to a list of the whitelisted classes that referenced that class as a dependency (i.e.
     * returns a map from dependencies to dependents). Note that you need to call
     * {@link ClassGraph#enableInterClassDependencies()} before {@link ClassGraph#scan()} for this method to work.
     * You should also call {@link ClassGraph#enableExternalClasses()} before {@link ClassGraph#scan()} if you want
     * non-whitelisted classes to appear in the result. See also {@link #getClassDependencyMap}.
     *
     * @return A map from a {@link ClassInfo} object for each dependency class (whitelisted or not) to a list of the
     *         whitelisted classes that referenced that class as a dependency (i.e. returns a map from dependencies
     *         to dependents).
     */
    public Map<ClassInfo, ClassInfoList> getReverseClassDependencyMap() {
        final Map<ClassInfo, Set<ClassInfo>> revMapSet = new HashMap<>();
        for (final ClassInfo ci : getAllClasses()) {
            for (final ClassInfo dep : ci.getClassDependencies()) {
                Set<ClassInfo> set = revMapSet.get(dep);
                if (set == null) {
                    revMapSet.put(dep, set = new HashSet<ClassInfo>());
                }
                set.add(ci);
            }
        }
        final Map<ClassInfo, ClassInfoList> revMapList = new HashMap<>();
        for (final Entry<ClassInfo, Set<ClassInfo>> ent : revMapSet.entrySet()) {
            revMapList.put(ent.getKey(), new ClassInfoList(ent.getValue(), /* sortByName = */ true));
        }
        return revMapList;
    }

    // -------------------------------------------------------------------------------------------------------------
    // Classes

    /**
     * Get the {@link ClassInfo} object for the named class, or null if no class of the requested name was found in
     * a whitelisted/non-blacklisted package during the scan.
     * 
     * @param className
     *            The class name.
     * @return The {@link ClassInfo} object for the named class, or null if the class was not found.
     */
    public ClassInfo getClassInfo(final String className) {
        if (closed.get()) {
            throw new IllegalArgumentException("Cannot use a ScanResult after it has been closed");
        }
        if (!scanSpec.enableClassInfo) {
            throw new IllegalArgumentException("Please call ClassGraph#enableClassInfo() before #scan()");
        }
        return classNameToClassInfo.get(className);
    }

    /**
     * Get all classes, interfaces and annotations found during the scan.
     *
     * @return A list of all whitelisted classes found during the scan, or the empty list if none.
     */
    public ClassInfoList getAllClasses() {
        if (closed.get()) {
            throw new IllegalArgumentException("Cannot use a ScanResult after it has been closed");
        }
        if (!scanSpec.enableClassInfo) {
            throw new IllegalArgumentException("Please call ClassGraph#enableClassInfo() before #scan()");
        }
        return ClassInfo.getAllClasses(classNameToClassInfo.values(), scanSpec);
    }

    /**
     * Get all {@link Enum} classes found during the scan.
     *
     * @return A list of all {@link Enum} classes found during the scan, or the empty list if none.
     */
    public ClassInfoList getAllEnums() {
        if (closed.get()) {
            throw new IllegalArgumentException("Cannot use a ScanResult after it has been closed");
        }
        if (!scanSpec.enableClassInfo) {
            throw new IllegalArgumentException("Please call ClassGraph#enableClassInfo() before #scan()");
        }
        return ClassInfo.getAllEnums(classNameToClassInfo.values(), scanSpec);
    }

    /**
     * Get all {@code record} classes found during the scan (JDK 14+).
     *
     * @return A list of all {@code record} classes found during the scan, or the empty list if none.
     */
    public ClassInfoList getAllRecords() {
        if (closed.get()) {
            throw new IllegalArgumentException("Cannot use a ScanResult after it has been closed");
        }
        if (!scanSpec.enableClassInfo) {
            throw new IllegalArgumentException("Please call ClassGraph#enableClassInfo() before #scan()");
        }
        return ClassInfo.getAllRecords(classNameToClassInfo.values(), scanSpec);
    }

    /**
     * Get a map from class name to {@link ClassInfo} object for all classes, interfaces and annotations found
     * during the scan.
     *
     * @return The map from class name to {@link ClassInfo} object for all classes, interfaces and annotations found
     *         during the scan.
     */
    public Map<String, ClassInfo> getAllClassesAsMap() {
        if (closed.get()) {
            throw new IllegalArgumentException("Cannot use a ScanResult after it has been closed");
        }
        if (!scanSpec.enableClassInfo) {
            throw new IllegalArgumentException("Please call ClassGraph#enableClassInfo() before #scan()");
        }
        return classNameToClassInfo;
    }

    /**
     * Get all standard (non-interface/non-annotation) classes found during the scan.
     *
     * @return A list of all whitelisted standard classes found during the scan, or the empty list if none.
     */
    public ClassInfoList getAllStandardClasses() {
        if (closed.get()) {
            throw new IllegalArgumentException("Cannot use a ScanResult after it has been closed");
        }
        if (!scanSpec.enableClassInfo) {
            throw new IllegalArgumentException("Please call ClassGraph#enableClassInfo() before #scan()");
        }
        return ClassInfo.getAllStandardClasses(classNameToClassInfo.values(), scanSpec);
    }

    /**
     * Get all subclasses of the named superclass.
     *
     * @param superclassName
     *            The name of the superclass.
     * @return A list of subclasses of the named superclass, or the empty list if none.
     */
    public ClassInfoList getSubclasses(final String superclassName) {
        if (closed.get()) {
            throw new IllegalArgumentException("Cannot use a ScanResult after it has been closed");
        }
        if (!scanSpec.enableClassInfo) {
            throw new IllegalArgumentException("Please call ClassGraph#enableClassInfo() before #scan()");
        }
        if (superclassName.equals("java.lang.Object")) {
            // Return all standard classes (interfaces don't extend Object)
            return getAllStandardClasses();
        } else {
            final ClassInfo superclass = classNameToClassInfo.get(superclassName);
            return superclass == null ? ClassInfoList.EMPTY_LIST : superclass.getSubclasses();
        }
    }

    /**
     * Get superclasses of the named subclass.
     *
     * @param subclassName
     *            The name of the subclass.
     * @return A list of superclasses of the named subclass, or the empty list if none.
     */
    public ClassInfoList getSuperclasses(final String subclassName) {
        if (closed.get()) {
            throw new IllegalArgumentException("Cannot use a ScanResult after it has been closed");
        }
        if (!scanSpec.enableClassInfo) {
            throw new IllegalArgumentException("Please call ClassGraph#enableClassInfo() before #scan()");
        }
        final ClassInfo subclass = classNameToClassInfo.get(subclassName);
        return subclass == null ? ClassInfoList.EMPTY_LIST : subclass.getSuperclasses();
    }

    /**
     * Get classes that have a method with an annotation of the named type.
     *
     * @param methodAnnotationName
     *            the name of the method annotation.
     * @return A list of classes with a method that has an annotation of the named type, or the empty list if none.
     */
    public ClassInfoList getClassesWithMethodAnnotation(final String methodAnnotationName) {
        if (closed.get()) {
            throw new IllegalArgumentException("Cannot use a ScanResult after it has been closed");
        }
        if (!scanSpec.enableClassInfo || !scanSpec.enableMethodInfo || !scanSpec.enableAnnotationInfo) {
            throw new IllegalArgumentException("Please call ClassGraph#enableClassInfo(), #enableMethodInfo(), "
                    + "and #enableAnnotationInfo() before #scan()");
        }
        final ClassInfo classInfo = classNameToClassInfo.get(methodAnnotationName);
        return classInfo == null ? ClassInfoList.EMPTY_LIST : classInfo.getClassesWithMethodAnnotation();
    }

    /**
     * Get classes that have a method with a parameter that is annotated with an annotation of the named type.
     *
     * @param methodParameterAnnotationName
     *            the name of the method parameter annotation.
     * @return A list of classes that have a method with a parameter annotated with the named annotation type, or
     *         the empty list if none.
     */
    public ClassInfoList getClassesWithMethodParameterAnnotation(final String methodParameterAnnotationName) {
        if (closed.get()) {
            throw new IllegalArgumentException("Cannot use a ScanResult after it has been closed");
        }
        if (!scanSpec.enableClassInfo || !scanSpec.enableMethodInfo || !scanSpec.enableAnnotationInfo) {
            throw new IllegalArgumentException("Please call ClassGraph#enableClassInfo(), #enableMethodInfo(), "
                    + "and #enableAnnotationInfo() before #scan()");
        }
        final ClassInfo classInfo = classNameToClassInfo.get(methodParameterAnnotationName);
        return classInfo == null ? ClassInfoList.EMPTY_LIST : classInfo.getClassesWithMethodParameterAnnotation();
    }

    /**
     * Get classes that have a field with an annotation of the named type.
     *
     * @param fieldAnnotationName
     *            the name of the field annotation.
     * @return A list of classes that have a field with an annotation of the named type, or the empty list if none.
     */
    public ClassInfoList getClassesWithFieldAnnotation(final String fieldAnnotationName) {
        if (closed.get()) {
            throw new IllegalArgumentException("Cannot use a ScanResult after it has been closed");
        }
        if (!scanSpec.enableClassInfo || !scanSpec.enableFieldInfo || !scanSpec.enableAnnotationInfo) {
            throw new IllegalArgumentException("Please call ClassGraph#enableClassInfo(), #enableFieldInfo(), "
                    + "and #enableAnnotationInfo() before #scan()");
        }
        final ClassInfo classInfo = classNameToClassInfo.get(fieldAnnotationName);
        return classInfo == null ? ClassInfoList.EMPTY_LIST : classInfo.getClassesWithFieldAnnotation();
    }

    // -------------------------------------------------------------------------------------------------------------
    // Interfaces

    /**
     * Get all interface classes found during the scan (not including annotations, which are also technically
     * interfaces). See also {@link #getAllInterfacesAndAnnotations()}.
     *
     * @return A list of all whitelisted interfaces found during the scan, or the empty list if none.
     */
    public ClassInfoList getAllInterfaces() {
        if (closed.get()) {
            throw new IllegalArgumentException("Cannot use a ScanResult after it has been closed");
        }
        if (!scanSpec.enableClassInfo) {
            throw new IllegalArgumentException("Please call ClassGraph#enableClassInfo() before #scan()");
        }
        return ClassInfo.getAllImplementedInterfaceClasses(classNameToClassInfo.values(), scanSpec);
    }

    /**
     * Get all interfaces implemented by the named class or by one of its superclasses, if this is a standard class,
     * or the superinterfaces extended by this interface, if this is an interface.
     *
     * @param className
     *            The class name.
     * @return A list of interfaces implemented by the named class (or superinterfaces extended by the named
     *         interface), or the empty list if none.
     */
    public ClassInfoList getInterfaces(final String className) {
        if (closed.get()) {
            throw new IllegalArgumentException("Cannot use a ScanResult after it has been closed");
        }
        if (!scanSpec.enableClassInfo) {
            throw new IllegalArgumentException("Please call ClassGraph#enableClassInfo() before #scan()");
        }
        final ClassInfo classInfo = classNameToClassInfo.get(className);
        return classInfo == null ? ClassInfoList.EMPTY_LIST : classInfo.getInterfaces();
    }

    /**
     * Get all classes that implement (or have superclasses that implement) the named interface (or one of its
     * subinterfaces).
     *
     * @param interfaceName
     *            The interface name.
     * @return A list of all classes that implement the named interface, or the empty list if none.
     */
    public ClassInfoList getClassesImplementing(final String interfaceName) {
        if (closed.get()) {
            throw new IllegalArgumentException("Cannot use a ScanResult after it has been closed");
        }
        if (!scanSpec.enableClassInfo) {
            throw new IllegalArgumentException("Please call ClassGraph#enableClassInfo() before #scan()");
        }
        final ClassInfo classInfo = classNameToClassInfo.get(interfaceName);
        return classInfo == null ? ClassInfoList.EMPTY_LIST : classInfo.getClassesImplementing();
    }

    // -------------------------------------------------------------------------------------------------------------
    // Annotations

    /**
     * Get all annotation classes found during the scan. See also {@link #getAllInterfacesAndAnnotations()}.
     *
     * @return A list of all annotation classes found during the scan, or the empty list if none.
     */
    public ClassInfoList getAllAnnotations() {
        if (closed.get()) {
            throw new IllegalArgumentException("Cannot use a ScanResult after it has been closed");
        }
        if (!scanSpec.enableClassInfo || !scanSpec.enableAnnotationInfo) {
            throw new IllegalArgumentException(
                    "Please call ClassGraph#enableClassInfo() and #enableAnnotationInfo() before #scan()");
        }
        return ClassInfo.getAllAnnotationClasses(classNameToClassInfo.values(), scanSpec);
    }

    /**
     * Get all interface or annotation classes found during the scan. (Annotations are technically interfaces, and
     * they can be implemented.)
     *
     * @return A list of all whitelisted interfaces found during the scan, or the empty list if none.
     */
    public ClassInfoList getAllInterfacesAndAnnotations() {
        if (closed.get()) {
            throw new IllegalArgumentException("Cannot use a ScanResult after it has been closed");
        }
        if (!scanSpec.enableClassInfo || !scanSpec.enableAnnotationInfo) {
            throw new IllegalArgumentException(
                    "Please call ClassGraph#enableClassInfo() and #enableAnnotationInfo() before #scan()");
        }
        return ClassInfo.getAllInterfacesOrAnnotationClasses(classNameToClassInfo.values(), scanSpec);
    }

    /**
     * Get classes with the named class annotation or meta-annotation.
     *
     * @param annotationName
     *            The name of the class annotation or meta-annotation.
     * @return A list of all non-annotation classes that were found with the named class annotation during the scan,
     *         or the empty list if none.
     */
    public ClassInfoList getClassesWithAnnotation(final String annotationName) {
        if (closed.get()) {
            throw new IllegalArgumentException("Cannot use a ScanResult after it has been closed");
        }
        if (!scanSpec.enableClassInfo || !scanSpec.enableAnnotationInfo) {
            throw new IllegalArgumentException(
                    "Please call ClassGraph#enableClassInfo() and #enableAnnotationInfo() before #scan()");
        }
        final ClassInfo classInfo = classNameToClassInfo.get(annotationName);
        return classInfo == null ? ClassInfoList.EMPTY_LIST : classInfo.getClassesWithAnnotation();
    }

    /**
     * Get annotations on the named class. This only returns the annotating classes; to read annotation parameters,
     * call {@link #getClassInfo(String)} to get the {@link ClassInfo} object for the named class, then if the
     * {@link ClassInfo} object is non-null, call {@link ClassInfo#getAnnotationInfo()} to get detailed annotation
     * info.
     *
     * @param className
     *            The name of the class.
     * @return A list of all annotation classes that were found with the named class annotation during the scan, or
     *         the empty list if none.
     */
    public ClassInfoList getAnnotationsOnClass(final String className) {
        if (closed.get()) {
            throw new IllegalArgumentException("Cannot use a ScanResult after it has been closed");
        }
        if (!scanSpec.enableClassInfo || !scanSpec.enableAnnotationInfo) {
            throw new IllegalArgumentException(
                    "Please call ClassGraph#enableClassInfo() and #enableAnnotationInfo() before #scan()");
        }
        final ClassInfo classInfo = classNameToClassInfo.get(className);
        return classInfo == null ? ClassInfoList.EMPTY_LIST : classInfo.getAnnotations();
    }

    // -------------------------------------------------------------------------------------------------------------
    // Classpath modification tests

    /**
     * Determine whether the classpath contents have been modified since the last scan. Checks the timestamps of
     * files and jarfiles encountered during the previous scan to see if they have changed. Does not perform a full
     * scan, so cannot detect the addition of directories that newly match whitelist criteria -- you need to perform
     * a full scan to detect those changes.
     *
     * @return true if the classpath contents have been modified since the last scan.
     */
    public boolean classpathContentsModifiedSinceScan() {
        if (closed.get()) {
            throw new IllegalArgumentException("Cannot use a ScanResult after it has been closed");
        }
        if (fileToLastModified == null) {
            return true;
        } else {
            for (final Entry<File, Long> ent : fileToLastModified.entrySet()) {
                if (ent.getKey().lastModified() != ent.getValue()) {
                    return true;
                }
            }
            return false;
        }
    }

    /**
     * Find the maximum last-modified timestamp of any whitelisted file/directory/jarfile encountered during the
     * scan. Checks the current timestamps, so this should increase between calls if something changes in
     * whitelisted paths. Assumes both file and system timestamps were generated from clocks whose time was
     * accurate. Ignores timestamps greater than the system time.
     * 
     * <p>
     * This method cannot in general tell if classpath has changed (or modules have been added or removed) if it is
     * run twice during the same runtime session.
     *
     * @return the maximum last-modified time for whitelisted files/directories/jars encountered during the scan.
     */
    public long classpathContentsLastModifiedTime() {
        if (closed.get()) {
            throw new IllegalArgumentException("Cannot use a ScanResult after it has been closed");
        }
        long maxLastModifiedTime = 0L;
        if (fileToLastModified != null) {
            final long currTime = System.currentTimeMillis();
            for (final long timestamp : fileToLastModified.values()) {
                if (timestamp > maxLastModifiedTime && timestamp < currTime) {
                    maxLastModifiedTime = timestamp;
                }
            }
        }
        return maxLastModifiedTime;
    }

    // -------------------------------------------------------------------------------------------------------------
    // Classloading

    /**
     * Get the ClassLoader order, respecting parent-first/parent-last delegation order.
     *
     * @return the class loader order.
     */
    ClassLoader[] getClassLoaderOrderRespectingParentDelegation() {
        return classLoaderOrderRespectingParentDelegation;
    }

    /**
     * Load a class given a class name. If ignoreExceptions is false, and the class cannot be loaded (due to
     * classloading error, or due to an exception being thrown in the class initialization block), an
     * IllegalArgumentException is thrown; otherwise, the class will simply be skipped if an exception is thrown.
     * 
     * <p>
     * Enable verbose scanning to see details of any exceptions thrown during classloading, even if ignoreExceptions
     * is false.
     *
     * @param className
     *            the class to load.
     * @param returnNullIfClassNotFound
     *            If true, null is returned if there was an exception during classloading, otherwise
     *            IllegalArgumentException is thrown if a class could not be loaded.
     * @return a reference to the loaded class, or null if the class could not be loaded and ignoreExceptions is
     *         true.
     * @throws IllegalArgumentException
     *             if ignoreExceptions is false, IllegalArgumentException is thrown if there were problems loading
     *             or initializing the class. (Note that class initialization on load is disabled by default, you
     *             can enable it with {@code ClassGraph#initializeLoadedClasses(true)} .) Otherwise exceptions are
     *             suppressed, and null is returned if any of these problems occurs.
     */
    public Class<?> loadClass(final String className, final boolean returnNullIfClassNotFound)
            throws IllegalArgumentException {
        if (closed.get()) {
            throw new IllegalArgumentException("Cannot use a ScanResult after it has been closed");
        }
        if (className == null || className.isEmpty()) {
            throw new NullPointerException("className cannot be null or empty");
        }
        try {
            return Class.forName(className, scanSpec.initializeLoadedClasses, classGraphClassLoader);
        } catch (final ClassNotFoundException | LinkageError e) {
            if (returnNullIfClassNotFound) {
                return null;
            } else {
                throw new IllegalArgumentException("Could not load class " + className + " : " + e);
            }
        }
    }

    /**
     * Load a class given a class name. If ignoreExceptions is false, and the class cannot be loaded (due to
     * classloading error, or due to an exception being thrown in the class initialization block), an
     * IllegalArgumentException is thrown; otherwise, the class will simply be skipped if an exception is thrown.
     * 
     * <p>
     * Enable verbose scanning to see details of any exceptions thrown during classloading, even if ignoreExceptions
     * is false.
     *
     * @param <T>
     *            the superclass or interface type.
     * @param className
     *            the class to load.
     * @param superclassOrInterfaceType
     *            The class type to cast the result to.
     * @param returnNullIfClassNotFound
     *            If true, null is returned if there was an exception during classloading, otherwise
     *            IllegalArgumentException is thrown if a class could not be loaded.
     * @return a reference to the loaded class, or null if the class could not be loaded and ignoreExceptions is
     *         true.
     * @throws IllegalArgumentException
     *             if ignoreExceptions is false, IllegalArgumentException is thrown if there were problems loading
     *             the class, initializing the class, or casting it to the requested type. (Note that class
     *             initialization on load is disabled by default, you can enable it with
     *             {@code ClassGraph#initializeLoadedClasses(true)} .) Otherwise exceptions are suppressed, and null
     *             is returned if any of these problems occurs.
     */
    public <T> Class<T> loadClass(final String className, final Class<T> superclassOrInterfaceType,
            final boolean returnNullIfClassNotFound) throws IllegalArgumentException {
        if (closed.get()) {
            throw new IllegalArgumentException("Cannot use a ScanResult after it has been closed");
        }
        if (className == null || className.isEmpty()) {
            throw new NullPointerException("className cannot be null or empty");
        }
        if (superclassOrInterfaceType == null) {
            throw new NullPointerException("superclassOrInterfaceType parameter cannot be null");
        }
        final Class<?> loadedClass;
        try {
            loadedClass = Class.forName(className, scanSpec.initializeLoadedClasses, classGraphClassLoader);
        } catch (final ClassNotFoundException | LinkageError e) {
            if (returnNullIfClassNotFound) {
                return null;
            } else {
                throw new IllegalArgumentException("Could not load class " + className + " : " + e);
            }
        }
        if (loadedClass != null && !superclassOrInterfaceType.isAssignableFrom(loadedClass)) {
            if (returnNullIfClassNotFound) {
                return null;
            } else {
                throw new IllegalArgumentException("Loaded class " + loadedClass.getName() + " cannot be cast to "
                        + superclassOrInterfaceType.getName());
            }
        }
        @SuppressWarnings("unchecked")
        final Class<T> castClass = (Class<T>) loadedClass;
        return castClass;

    }

    // -------------------------------------------------------------------------------------------------------------
    // Serialization / deserialization

    /**
     * Deserialize a ScanResult from previously-serialized JSON.
     * 
     * @param json
     *            The JSON string for the serialized {@link ScanResult}.
     * @return The deserialized {@link ScanResult}.
     */
    public static ScanResult fromJSON(final String json) {
        final Matcher matcher = Pattern.compile("\\{[\\n\\r ]*\"format\"[ ]?:[ ]?\"([^\"]+)\"").matcher(json);
        if (!matcher.find()) {
            throw new IllegalArgumentException("JSON is not in correct format");
        }
        if (!CURRENT_SERIALIZATION_FORMAT.equals(matcher.group(1))) {
            throw new IllegalArgumentException(
                    "JSON was serialized in a different format from the format used by the current version of "
                            + "ClassGraph -- please serialize and deserialize your ScanResult using "
                            + "the same version of ClassGraph");
        }

        // Deserialize the JSON
        final SerializationFormat deserialized = JSONDeserializer.deserializeObject(SerializationFormat.class,
                json);
        if (!deserialized.format.equals(CURRENT_SERIALIZATION_FORMAT)) {
            // Probably the deserialization failed before now anyway, if fields have changed, etc.
            throw new IllegalArgumentException("JSON was serialized by newer version of ClassGraph");
        }

        // Perform a new "scan" with performScan set to false, which resolves all the ClasspathElement objects
        // and scans classpath element paths (needed for classloading), but does not scan the actual classfiles
        final ClassGraph classGraph = new ClassGraph();
        classGraph.scanSpec = deserialized.scanSpec;
        if (classGraph.scanSpec.overrideClasspath == null) {
            // Use the same classpath as before, if classpath was not overridden
            classGraph.overrideClasspath(deserialized.classpath);
        }
        final ScanResult scanResult;
        try (AutoCloseableExecutorService executorService = new AutoCloseableExecutorService(
                ClassGraph.DEFAULT_NUM_WORKER_THREADS)) {
            scanResult = classGraph.getClasspathScanResult(executorService);
        }
        scanResult.rawClasspathEltOrderStrs = deserialized.classpath;

        // Set the fields related to ClassInfo in the new ScanResult, based on the deserialized JSON 
        scanResult.scanSpec = deserialized.scanSpec;
        scanResult.classNameToClassInfo = new HashMap<>();
        if (deserialized.classInfo != null) {
            for (final ClassInfo ci : deserialized.classInfo) {
                scanResult.classNameToClassInfo.put(ci.getName(), ci);
                ci.setScanResult(scanResult);
            }
        }
        scanResult.moduleNameToModuleInfo = new HashMap<>();
        if (deserialized.moduleInfo != null) {
            for (final ModuleInfo mi : deserialized.moduleInfo) {
                scanResult.moduleNameToModuleInfo.put(mi.getName(), mi);
            }
        }
        scanResult.packageNameToPackageInfo = new HashMap<>();
        if (deserialized.packageInfo != null) {
            for (final PackageInfo pi : deserialized.packageInfo) {
                scanResult.packageNameToPackageInfo.put(pi.getName(), pi);
            }
        }

        // Index Resource and ClassInfo objects 
        scanResult.indexResourcesAndClassInfo();

        scanResult.isObtainedFromDeserialization = true;
        return scanResult;
    }

    /**
     * Serialize a ScanResult to JSON.
     * 
     * @param indentWidth
     *            If greater than 0, JSON will be formatted (indented), otherwise it will be minified (un-indented).
     * @return This {@link ScanResult}, serialized as a JSON string.
     */
    public String toJSON(final int indentWidth) {
        if (closed.get()) {
            throw new IllegalArgumentException("Cannot use a ScanResult after it has been closed");
        }
        if (!scanSpec.enableClassInfo) {
            throw new IllegalArgumentException("Please call ClassGraph#enableClassInfo() before #scan()");
        }
        final List<ClassInfo> allClassInfo = new ArrayList<>(classNameToClassInfo.values());
        CollectionUtils.sortIfNotEmpty(allClassInfo);
        final List<PackageInfo> allPackageInfo = new ArrayList<>(packageNameToPackageInfo.values());
        CollectionUtils.sortIfNotEmpty(allPackageInfo);
        final List<ModuleInfo> allModuleInfo = new ArrayList<>(moduleNameToModuleInfo.values());
        CollectionUtils.sortIfNotEmpty(allModuleInfo);
        return JSONSerializer.serializeObject(new SerializationFormat(CURRENT_SERIALIZATION_FORMAT, scanSpec,
                allClassInfo, allPackageInfo, allModuleInfo, rawClasspathEltOrderStrs), indentWidth, false);
    }

    /**
     * Serialize a ScanResult to minified (un-indented) JSON.
     * 
     * @return This {@link ScanResult}, serialized as a JSON string.
     */
    public String toJSON() {
        return toJSON(0);
    }

    /**
     * Checks if this {@link ScanResult} was obtained from JSON by deserialization, by calling
     * {@link #fromJSON(String)}.
     *
     * @return True if this {@link ScanResult} was obtained from JSON by deserialization.
     */
    public boolean isObtainedFromDeserialization() {
        return isObtainedFromDeserialization;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Free any temporary files created by extracting jars or files from within jars. Without calling this method,
     * the temporary files created by extracting the inner jars will be removed in a finalizer, called by the
     * garbage collector (or at JVM shutdown). If you don't want to experience long GC pauses, make sure you call
     * this close method when you have finished with the {@link ScanResult}.
     */
    @Override
    public void close() {
        if (!closed.getAndSet(true)) {
            nonClosedWeakReferences.remove(weakReference);
            if (classpathOrder != null) {
                classpathOrder.clear();
                classpathOrder = null;
            }
            if (allWhitelistedResourcesCached != null) {
                for (final Resource classpathResource : allWhitelistedResourcesCached) {
                    classpathResource.close();
                }
                allWhitelistedResourcesCached.clear();
                allWhitelistedResourcesCached = null;
            }
            if (pathToWhitelistedResourcesCached != null) {
                pathToWhitelistedResourcesCached.clear();
                pathToWhitelistedResourcesCached = null;
            }
            classGraphClassLoader = null;
            if (classNameToClassInfo != null) {
                // Don't clear classNameToClassInfo, since it may be used by ClassGraphClassLoader (#399).
                // Just rely on the garbage collector to collect these once the ScanResult goes out of scope.
                //                classNameToClassInfo.clear();
                //                classNameToClassInfo = null;
            }
            if (packageNameToPackageInfo != null) {
                packageNameToPackageInfo.clear();
                packageNameToPackageInfo = null;
            }
            if (moduleNameToModuleInfo != null) {
                moduleNameToModuleInfo.clear();
                moduleNameToModuleInfo = null;
            }
            if (fileToLastModified != null) {
                fileToLastModified.clear();
                fileToLastModified = null;
            }
            // nestedJarHandler should be closed last, since it needs to have all MappedByteBuffer refs
            // dropped before it tries to delete any temporary files that were written to disk
            if (nestedJarHandler != null) {
                nestedJarHandler.close(topLevelLog);
                nestedJarHandler = null;
            }
            classGraphClassLoader = null;
            classLoaderOrderRespectingParentDelegation = null;
            // Flush log on exit, in case additional log entries were generated after scan() completed
            if (topLevelLog != null) {
                topLevelLog.flush();
            }
        }
    }

    /**
     * Close all {@link ScanResult} instances that have not yet been closed. Note that this will close all open
     * {@link ScanResult} instances for any class that uses the classloader that the {@link ScanResult} class is
     * cached in -- so if you call this method, you need to ensure that the lifecycle of the classloader matches the
     * lifecycle of your application, or that two concurrent applications don't share the same classloader,
     * otherwise one application might close another application's {@link ScanResult} instances while they are still
     * in use.
     */
    public static void closeAll() {
        for (final WeakReference<ScanResult> nonClosedWeakReference : new ArrayList<>(nonClosedWeakReferences)) {
            final ScanResult scanResult = nonClosedWeakReference.get();
            if (scanResult != null) {
                scanResult.close();
            }
        }
    }
}
