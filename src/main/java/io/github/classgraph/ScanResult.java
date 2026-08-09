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
import java.lang.annotation.Annotation;
import java.lang.ref.WeakReference;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import nonapi.io.github.classgraph.classpath.ClasspathFinder;
import nonapi.io.github.classgraph.fastzipfilereader.NestedJarHandler;
import nonapi.io.github.classgraph.reflection.ReflectionUtils;
import nonapi.io.github.classgraph.scanspec.AcceptReject;
import nonapi.io.github.classgraph.scanspec.ScanSpec;
import nonapi.io.github.classgraph.utils.Assert;
import nonapi.io.github.classgraph.utils.CollectionUtils;
import nonapi.io.github.classgraph.utils.FileUtils;
import nonapi.io.github.classgraph.utils.JarUtils;
import nonapi.io.github.classgraph.utils.LogNode;
import org.jspecify.annotations.Nullable;

/**
 * The result of a scan. You should assign a ScanResult in a try-with-resources
 * block, or manually close it when you have finished with the result of a scan.
 */
public final class ScanResult implements Closeable {
    /** The order of raw classpath elements. */
    private List<String> rawClasspathEltOrderStrs;

    /**
     * The order of classpath elements, after inner jars have been extracted to
     * temporary files, etc.
     */
    private @Nullable List<ClasspathElement> classpathOrder;

    /**
     * A list of all files that were found in accepted packages, or null if not yet
     * cached, or if this {@link ScanResult} has been closed.
     */
    private @Nullable ResourceList allAcceptedResourcesCached;

    /**
     * The number of times {@link #getResourcesWithPath(String)} has been called.
     */
    private final AtomicInteger getResourcesWithPathCallCount = new AtomicInteger();

    /**
     * The map from path (relative to package root) to a list of {@link Resource}
     * elements with the matching path.
     */
    private @Nullable Map<String, ResourceList> pathToAcceptedResourcesCached;

    /** The map from class name to {@link ClassInfo}. */
    Map<String, ClassInfo> classNameToClassInfo;

    /** The map from package name to {@link PackageInfo}. */
    private @Nullable Map<String, PackageInfo> packageNameToPackageInfo;

    /** The map from class name to {@link ClassInfo}. */
    private @Nullable Map<String, ModuleInfo> moduleNameToModuleInfo;

    /**
     * The file, directory and jarfile resources timestamped during a scan, along
     * with their timestamp at the time of the scan. For jarfiles, the timestamp
     * represents the timestamp of all files within the jar. May be null, if this
     * ScanResult object is the result of a call to
     * ClassGraph#getUniqueClasspathElementsAsync().
     */
    private @Nullable Map<File, Long> fileToLastModified;

    /** A custom ClassLoader that can load classes found during the scan. */
    private @Nullable ClassGraphClassLoader classGraphClassLoader;

    /** The {@link ClasspathFinder}. */
    private @Nullable ClasspathFinder classpathFinder;

    /** The nested jar handler instance. */
    private @Nullable NestedJarHandler nestedJarHandler;

    /** The scan spec. */
    ScanSpec scanSpec;

    /** If true, this ScanResult has already been closed. */
    private final AtomicBoolean closed = new AtomicBoolean(false);

    /**
     * The {@link ReflectionUtils} instance, or null if this {@link ScanResult} has
     * been closed.
     */
    @Nullable ReflectionUtils reflectionUtils;

    /**
     * Get the {@link ReflectionUtils} instance of a {@link ScanResult}, falling
     * back to a fresh instance if the {@link ScanResult} is null or has been
     * closed. {@link #close()} sets {@link #reflectionUtils} to null, so testing
     * only for a null {@link ScanResult} is not enough -- objects such as
     * {@link AnnotationInfo} keep working after the {@link ScanResult} they came
     * from is closed, and must not throw {@link NullPointerException}. The field is
     * read only once, so that a concurrent {@link #close()} cannot cause null to be
     * returned.
     *
     * @param scanResult the {@link ScanResult}, or null.
     * @return a non-null {@link ReflectionUtils}.
     */
    // #930
    static ReflectionUtils getReflectionUtils(final @Nullable ScanResult scanResult) {
        final var reflectionUtils = scanResult == null ? null : scanResult.reflectionUtils;
        return reflectionUtils == null ? new ReflectionUtils() : reflectionUtils;
    }

    /**
     * Get the classpath order, for use in code paths that are only reachable before
     * this {@link ScanResult} is closed.
     *
     * @return the classpath order
     * @throws NullPointerException if this {@link ScanResult} has been closed.
     */
    private List<ClasspathElement> classpathOrder() {
        return Objects.requireNonNull(classpathOrder);
    }

    /**
     * Get the map from package name to {@link PackageInfo}, for use in code paths
     * that are only reachable before this {@link ScanResult} is closed.
     *
     * @return the map from package name to {@link PackageInfo}
     * @throws NullPointerException if this {@link ScanResult} has been closed.
     */
    private Map<String, PackageInfo> packageNameToPackageInfo() {
        return Objects.requireNonNull(packageNameToPackageInfo);
    }

    /**
     * Get the map from module name to {@link ModuleInfo}, for use in code paths that
     * are only reachable before this {@link ScanResult} is closed.
     *
     * @return the map from module name to {@link ModuleInfo}
     * @throws NullPointerException if this {@link ScanResult} has been closed.
     */
    private Map<String, ModuleInfo> moduleNameToModuleInfo() {
        return Objects.requireNonNull(moduleNameToModuleInfo);
    }

    /**
     * Get the {@link ClasspathFinder}, for use in code paths that are only reachable
     * before this {@link ScanResult} is closed.
     *
     * @return the {@link ClasspathFinder}
     * @throws NullPointerException if this {@link ScanResult} has been closed.
     */
    ClasspathFinder classpathFinder() {
        return Objects.requireNonNull(classpathFinder);
    }

    /**
     * Get the {@link ClassGraphClassLoader}, for use in code paths that are only
     * reachable before this {@link ScanResult} is closed.
     *
     * @return the {@link ClassGraphClassLoader}
     * @throws NullPointerException if this {@link ScanResult} has been closed.
     */
    private ClassGraphClassLoader classGraphClassLoader() {
        return Objects.requireNonNull(classGraphClassLoader);
    }

    /** The toplevel log. */
    private final @Nullable LogNode topLevelLog;

    // -------------------------------------------------------------------------------------------------------------

    /** The {@link WeakReference} for this ScanResult. */
    private final WeakReference<ScanResult> weakReference;

    /**
     * The set of WeakReferences to non-closed ScanResult objects. Uses
     * WeakReferences so that garbage collection is not blocked.
     */
    // #233
    private static final Set<WeakReference<ScanResult>> nonClosedWeakReferences = Collections
            .newSetFromMap(new ConcurrentHashMap<>());

    /** If true, ScanResult#staticInit() has been run. */
    private static final AtomicBoolean initialized = new AtomicBoolean(false);

    // -------------------------------------------------------------------------------------------------------------
    // Shutdown hook init code

    /**
     * Static initialization (warm up classloading), called when the ClassGraph
     * class is initialized.
     */
    static void init(final ReflectionUtils reflectionUtils) {
        if (!initialized.getAndSet(true)) {
            // Pre-load non-system classes necessary for calling scanResult.close(), so that
            // classes that need
            // to be loaded to close resources are already loaded and cached. This was
            // originally for use in
            // a shutdown hook (#331), which has now been removed, but it is probably still
            // a good idea to
            // ensure that classes needed to free/unmap DirectByteBuffer instances are
            // available at init.
            // We achieve this by allocating a small direct ByteBuffer and then freeing it.
            final var arena = FileUtils.openArena(reflectionUtils);
            if (arena != null) {
                // On JDK 22+, direct ByteBuffers are allocated and memory-mapped using the
                // java.lang.foreign.Arena API, and freed/unmapped by closing the arena that
                // created them, rather than by calling the terminally-deprecated method
                // Unsafe::invokeCleaner (#939) -- warm up the reflective arena code paths
                FileUtils.allocateDirectByteBufferUsingArena(arena, 32, reflectionUtils);
                FileUtils.closeArena(arena, reflectionUtils, /* log = */ null);
            } else {
                // On JDK less than 22, the only problematic classes are the PrivilegedAction
                // anonymous inner classes used by FileUtils::closeDirectByteBuffer
                FileUtils.closeDirectByteBuffer(ByteBuffer.allocateDirect(32), reflectionUtils, /* log = */ null);
            }
        }
    }

    // -------------------------------------------------------------------------------------------------------------
    // Constructor

    /**
     * The result of a scan. Make sure you call complete() after calling the
     * constructor.
     *
     * @param scanSpec                 the scan spec
     * @param classpathOrder           the classpath order
     * @param rawClasspathEltOrderStrs the raw classpath element order
     * @param classpathFinder          the {@link ClasspathFinder}
     * @param classNameToClassInfo     a map from class name to class info
     * @param packageNameToPackageInfo a map from package name to package info
     * @param moduleNameToModuleInfo   a map from module name to module info
     * @param fileToLastModified       a map from file to last modified time
     * @param nestedJarHandler         the nested jar handler
     * @param topLevelLog              the toplevel log
     */
    ScanResult(final ScanSpec scanSpec, final List<ClasspathElement> classpathOrder,
            final List<String> rawClasspathEltOrderStrs, final ClasspathFinder classpathFinder,
            final Map<String, ClassInfo> classNameToClassInfo, final Map<String, PackageInfo> packageNameToPackageInfo,
            final Map<String, ModuleInfo> moduleNameToModuleInfo, final @Nullable Map<File, Long> fileToLastModified,
            final NestedJarHandler nestedJarHandler, final @Nullable LogNode topLevelLog) {
        this.scanSpec = scanSpec;
        this.rawClasspathEltOrderStrs = rawClasspathEltOrderStrs;
        this.classpathOrder = classpathOrder;
        this.classpathFinder = classpathFinder;
        this.fileToLastModified = fileToLastModified;
        this.classNameToClassInfo = classNameToClassInfo;
        this.packageNameToPackageInfo = packageNameToPackageInfo;
        this.moduleNameToModuleInfo = moduleNameToModuleInfo;
        this.nestedJarHandler = nestedJarHandler;
        this.reflectionUtils = nestedJarHandler.reflectionUtils;
        this.topLevelLog = topLevelLog;

        indexResourcesAndClassInfo(topLevelLog);

        // Handle @Repeatable annotations
        final Set<String> allRepeatableAnnotationNames = new HashSet<>();
        for (final ClassInfo classInfo : classNameToClassInfo.values()) {
            if (classInfo.isAnnotation() && classInfo.annotationInfo != null) {
                final var repeatableMetaAnnotation = classInfo.annotationInfo.get("java.lang.annotation.Repeatable");
                if (repeatableMetaAnnotation != null) {
                    final var vals = repeatableMetaAnnotation.getParameterValues();
                    if (!vals.isEmpty()) {
                        final var val = vals.getValue("value");
                        if (val instanceof final AnnotationClassRef classRef) {
                            final var repeatableAnnotationName = classRef.getName();
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

        // Define a new ClassLoader that can load the classes found during the scan
        this.classGraphClassLoader = new ClassGraphClassLoader(this);

        // Provide the shutdown hook with a weak reference to this ScanResult
        this.weakReference = new WeakReference<>(this);
        nonClosedWeakReferences.add(this.weakReference);
    }

    /**
     * Index {@link Resource} and {@link ClassInfo} objects.
     *
     * @param log the log
     */
    private void indexResourcesAndClassInfo(final @Nullable LogNode log) {
        // Add backrefs from Info objects back to this ScanResult
        final var allClassInfo = classNameToClassInfo.values();
        for (final ClassInfo classInfo : allClassInfo) {
            classInfo.setScanResult(this);
        }

        // If inter-class dependencies are enabled, create placeholder ClassInfo objects
        // for any referenced
        // classes that were not scanned
        if (scanSpec.enableInterClassDependencies) {
            for (final ClassInfo ci : new ArrayList<>(classNameToClassInfo.values())) {
                final Set<ClassInfo> refdClassesFiltered = new HashSet<>();
                for (final ClassInfo refdClassInfo : ci.findReferencedClassInfo(log)) {
                    // Don't add self-references, or references to Object
                    if (refdClassInfo != null && !ci.equals(refdClassInfo)
                            && !"java.lang.Object".equals(refdClassInfo.getName())
                            // Only add class to result if it is accepted, or external classes are enabled
                            && (!refdClassInfo.isExternalClass() || scanSpec.enableExternalClasses)) {
                        refdClassInfo.setScanResult(this);
                        refdClassesFiltered.add(refdClassInfo);
                    }
                }
                ci.setReferencedClasses(new ClassInfoList(refdClassesFiltered, /* sortByName = */ true));
            }
        }

        if (scanSpec.enableClassInfo) {
            for (final PackageInfo pkgInfo : packageNameToPackageInfo().values()) {
                pkgInfo.setScanResult(this);
            }

            for (final ModuleInfo moduleInfo : moduleNameToModuleInfo().values()) {
                moduleInfo.setScanResult(this);
            }
        }
    }

    // -------------------------------------------------------------------------------------------------------------
    // Preconditions

    /**
     * Check that this {@link ScanResult} has not been closed.
     *
     * @throws IllegalArgumentException if this {@link ScanResult} has been closed.
     */
    private void checkNotClosed() {
        if (closed.get()) {
            throw new IllegalArgumentException("Cannot use a ScanResult after it has been closed");
        }
    }

    /**
     * Check that this {@link ScanResult} has not been closed, and that class info
     * was enabled during the scan.
     *
     * @throws IllegalArgumentException if this {@link ScanResult} has been closed,
     *                                  or class info was not enabled.
     */
    private void checkClassInfoEnabled() {
        checkNotClosed();
        if (!scanSpec.enableClassInfo) {
            throw new IllegalArgumentException("Please call ClassGraph#enableClassInfo() before #scan()");
        }
    }

    /**
     * Check that this {@link ScanResult} has not been closed, and that class info
     * and annotation info were enabled during the scan.
     *
     * @throws IllegalArgumentException if this {@link ScanResult} has been closed,
     *                                  or class info or annotation info were not
     *                                  enabled.
     */
    private void checkAnnotationInfoEnabled() {
        checkNotClosed();
        if (!scanSpec.enableClassInfo || !scanSpec.enableAnnotationInfo) {
            throw new IllegalArgumentException(
                    "Please call ClassGraph#enableClassInfo() and #enableAnnotationInfo() before #scan()");
        }
    }

    /**
     * Check that this {@link ScanResult} has not been closed, and that class info,
     * method info and annotation info were enabled during the scan.
     *
     * @throws IllegalArgumentException if this {@link ScanResult} has been closed,
     *                                  or class info, method info or annotation
     *                                  info were not enabled.
     */
    private void checkMethodAnnotationInfoEnabled() {
        checkNotClosed();
        if (!scanSpec.enableClassInfo || !scanSpec.enableMethodInfo || !scanSpec.enableAnnotationInfo) {
            throw new IllegalArgumentException("Please call ClassGraph#enableClassInfo(), #enableMethodInfo(), "
                    + "and #enableAnnotationInfo() before #scan()");
        }
    }

    /**
     * Check that this {@link ScanResult} has not been closed, and that class info,
     * field info and annotation info were enabled during the scan.
     *
     * @throws IllegalArgumentException if this {@link ScanResult} has been closed,
     *                                  or class info, field info or annotation info
     *                                  were not enabled.
     */
    private void checkFieldAnnotationInfoEnabled() {
        checkNotClosed();
        if (!scanSpec.enableClassInfo || !scanSpec.enableFieldInfo || !scanSpec.enableAnnotationInfo) {
            throw new IllegalArgumentException("Please call ClassGraph#enableClassInfo(), #enableFieldInfo(), "
                    + "and #enableAnnotationInfo() before #scan()");
        }
    }

    // -------------------------------------------------------------------------------------------------------------
    // Classpath / module path

    /**
     * Returns the list of File objects for unique classpath elements (directories
     * or jarfiles), in classloader resolution order.
     *
     * @return The unique classpath elements.
     */
    public List<File> getClasspathFiles() {
        checkNotClosed();
        final List<File> classpathElementOrderFiles = new ArrayList<>();
        for (final ClasspathElement classpathElement : classpathOrder()) {
            final var file = classpathElement.getFile();
            if (file != null) {
                classpathElementOrderFiles.add(file);
            }
        }
        return classpathElementOrderFiles;
    }

    /**
     * Returns all unique directories or zip/jarfiles on the classpath, in
     * classloader resolution order, as a classpath string, delineated with the
     * standard path separator character.
     *
     * @return a the unique directories and jarfiles on the classpath, in classpath
     *         resolution order, as a path string.
     */
    public String getClasspath() {
        checkNotClosed();
        return JarUtils.pathElementsToPathStr(getClasspathFiles());
    }

    /**
     * Returns an ordered list of unique classpath element and module URIs.
     *
     * @return The unique classpath element and module URIs.
     */
    public List<URI> getClasspathURIs() {
        checkNotClosed();
        final List<URI> classpathElementOrderURIs = new ArrayList<>();
        for (final ClasspathElement classpathElement : classpathOrder()) {
            try {
                for (final URI uri : classpathElement.getAllURIs()) {
                    if (uri != null) {
                        classpathElementOrderURIs.add(uri);
                    }
                }
            } catch (final IllegalArgumentException e) {
                // Skip null location URIs
            }
        }
        return classpathElementOrderURIs;
    }

    /**
     * Returns an ordered list of unique classpath element and module URLs. Will
     * skip any system modules or modules that are part of a jlink'd runtime image,
     * since {@link URL} does not support the {@code jrt:} {@link URI} scheme.
     *
     * @return The unique classpath element and module URLs.
     */
    public List<URL> getClasspathURLs() {
        checkNotClosed();
        final List<URL> classpathElementOrderURLs = new ArrayList<>();
        for (final URI uri : getClasspathURIs()) {
            try {
                classpathElementOrderURLs.add(uri.toURL());
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
        checkNotClosed();
        final List<ModuleRef> moduleRefs = new ArrayList<>();
        for (final ClasspathElement classpathElement : classpathOrder()) {
            if (classpathElement instanceof final ClasspathElementModule classpathElementModule) {
                moduleRefs.add(classpathElementModule.getModuleRef());
            }
        }
        return moduleRefs;
    }

    /**
     * Get the module path info provided on the commandline with
     * {@code --module-path}, {@code --add-modules}, {@code --patch-module},
     * {@code --add-exports}, {@code --add-opens}, and {@code --add-reads}, and also
     * the {@code Add-Exports} and {@code Add-Opens} entries from jarfile manifest
     * files encountered during scanning.
     *
     * <p>
     * Note that the returned {@link ModulePathInfo} object does not include
     * classpath entries from the traditional classpath or system modules. Use
     * {@link #getModules()} to get all visible modules, including anonymous,
     * automatic and system modules.
     *
     * @return The {@link ModulePathInfo}.
     */
    public ModulePathInfo getModulePathInfo() {
        checkNotClosed();
        scanSpec.modulePathInfo.getRuntimeInfo(Objects.requireNonNull(reflectionUtils));
        return scanSpec.modulePathInfo;
    }

    // -------------------------------------------------------------------------------------------------------------
    // Resources

    /**
     * Get the list of all resources.
     *
     * @return A list of all resources (including classfiles and non-classfiles)
     *         found in accepted packages.
     */
    public ResourceList getAllResources() {
        checkNotClosed();
        synchronized (this) {
            var allAcceptedResources = allAcceptedResourcesCached;
            if (allAcceptedResources == null) {
                // Index Resource objects by path
                final var acceptedResourcesList = new ResourceList();
                for (final ClasspathElement classpathElt : classpathOrder()) {
                    acceptedResourcesList.addAll(classpathElt.acceptedResources);
                }
                // Set atomically for thread safety
                allAcceptedResourcesCached = allAcceptedResources = acceptedResourcesList;
            }
            return allAcceptedResources;
        }
    }

    /**
     * Get a map from resource path to {@link Resource} for all resources (including
     * classfiles and non-classfiles) found in accepted packages.
     *
     * @return The map from resource path to {@link Resource} for all resources
     *         (including classfiles and non-classfiles) found in accepted packages.
     */
    public Map<String, ResourceList> getAllResourcesAsMap() {
        checkNotClosed();
        synchronized (this) {
            var pathToAcceptedResources = pathToAcceptedResourcesCached;
            if (pathToAcceptedResources == null) {
                final Map<String, ResourceList> pathToAcceptedResourceListMap = new HashMap<>();
                for (final Resource res : getAllResources()) {
                    pathToAcceptedResourceListMap.computeIfAbsent(res.getPath(), k -> new ResourceList()).add(res);
                }
                // Set atomically for thread safety
                pathToAcceptedResourcesCached = pathToAcceptedResources = pathToAcceptedResourceListMap;
            }
            return pathToAcceptedResources;
        }
    }

    /**
     * Get the list of all resources found in accepted packages that have the given
     * path, relative to the package root of the classpath element. May match
     * several resources, up to one per classpath element.
     *
     * @param resourcePath A complete resource path, relative to the classpath entry
     *                     package root.
     * @return A list of all resources found in accepted packages that have the
     *         given path, relative to the package root of the classpath element.
     *         May match several resources, up to one per classpath element.
     */
    public ResourceList getResourcesWithPath(final String resourcePath) {
        checkNotClosed();
        Assert.notNull(resourcePath, "resourcePath");
        final var path = FileUtils.sanitizeEntryPath(resourcePath, /* removeInitialSlash = */ true,
                /* removeFinalSlash = */ true);
        ResourceList matchingResources = null;
        if (getResourcesWithPathCallCount.incrementAndGet() > 3) {
            // If numerous calls are made, produce and cache a single HashMap for O(1)
            // access time
            matchingResources = getAllResourcesAsMap().get(path);
        } else {
            // If just a few calls are made, directly search for resource with the requested
            // path
            for (final ClasspathElement classpathElt : classpathOrder()) {
                for (final Resource res : classpathElt.acceptedResources) {
                    if (res.getPath().equals(path)) {
                        if (matchingResources == null) {
                            matchingResources = new ResourceList();
                        }
                        matchingResources.add(res);
                    }
                }
            }
        }
        return matchingResources == null ? ResourceList.EMPTY_LIST : matchingResources;
    }

    /**
     * Get the list of all resources found in any classpath element, <i>whether in
     * accepted packages or not (as long as the resource is not rejected)</i>, that
     * have the given path, relative to the package root of the classpath element.
     * May match several resources, up to one per classpath element. Note that this
     * may not return a non-accepted resource, particularly when scanning directory
     * classpath elements, because recursive scanning terminates once there are no
     * possible accepted resources below a given directory. However, resources in
     * ancestral directories of accepted directories can be found using this method.
     *
     * @param resourcePath A complete resource path, relative to the classpath entry
     *                     package root.
     * @return A list of all resources found in any classpath element, <i>whether in
     *         accepted packages or not (as long as the resource is not
     *         rejected)</i>, that have the given path, relative to the package root
     *         of the classpath element. May match several resources, up to one per
     *         classpath element.
     */
    public ResourceList getResourcesWithPathIgnoringAccept(final String resourcePath) {
        checkNotClosed();
        Assert.notNull(resourcePath, "resourcePath");
        final var path = FileUtils.sanitizeEntryPath(resourcePath, /* removeInitialSlash = */ true,
                /* removeFinalSlash = */ true);
        final var matchingResources = new ResourceList();
        for (final ClasspathElement classpathElt : classpathOrder()) {
            final var matchingResource = classpathElt.getResource(path);
            if (matchingResource != null) {
                matchingResources.add(matchingResource);
            }
        }
        return matchingResources;
    }

    /**
     * Get the list of all resources found in accepted packages that have the
     * requested leafname.
     *
     * @param leafName A resource leaf filename.
     * @return A list of all resources found in accepted packages that have the
     *         requested leafname.
     */
    public ResourceList getResourcesWithLeafName(final String leafName) {
        checkNotClosed();
        Assert.notNull(leafName, "leafName");
        final var allAcceptedResources = getAllResources();
        if (allAcceptedResources.isEmpty()) {
            return ResourceList.EMPTY_LIST;
        } else {
            final var filteredResources = new ResourceList();
            for (final Resource classpathResource : allAcceptedResources) {
                final var relativePath = classpathResource.getPath();
                final var lastSlashIdx = relativePath.lastIndexOf('/');
                if (relativePath.substring(lastSlashIdx + 1).equals(leafName)) {
                    filteredResources.add(classpathResource);
                }
            }
            return filteredResources;
        }
    }

    /**
     * Get the list of all resources found in accepted packages that have the
     * requested filename extension.
     *
     * @param extension A filename extension, e.g. "xml" to match all resources
     *                  ending in ".xml".
     * @return A list of all resources found in accepted packages that have the
     *         requested filename extension.
     */
    public ResourceList getResourcesWithExtension(final String extension) {
        checkNotClosed();
        Assert.notNull(extension, "extension");
        final var allAcceptedResources = getAllResources();
        if (allAcceptedResources.isEmpty()) {
            return ResourceList.EMPTY_LIST;
        } else {
            var bareExtension = extension;
            while (bareExtension.startsWith(".")) {
                bareExtension = bareExtension.substring(1);
            }
            final var filteredResources = new ResourceList();
            for (final Resource classpathResource : allAcceptedResources) {
                final var relativePath = classpathResource.getPath();
                final var lastSlashIdx = relativePath.lastIndexOf('/');
                final var lastDotIdx = relativePath.lastIndexOf('.');
                if (lastDotIdx > lastSlashIdx
                        && relativePath.substring(lastDotIdx + 1).equalsIgnoreCase(bareExtension)) {
                    filteredResources.add(classpathResource);
                }
            }
            return filteredResources;
        }
    }

    /**
     * Get the list of all resources found in accepted packages that have a path
     * matching the requested regex pattern. See also
     * {@link #getResourcesMatchingWildcard(String)}.
     *
     * @param pattern A pattern to match {@link Resource} paths with.
     * @return A list of all resources found in accepted packages that have a path
     *         matching the requested pattern.
     */
    public ResourceList getResourcesMatchingPattern(final Pattern pattern) {
        checkNotClosed();
        Assert.notNull(pattern, "pattern");
        final var allAcceptedResources = getAllResources();
        if (allAcceptedResources.isEmpty()) {
            return ResourceList.EMPTY_LIST;
        } else {
            final var filteredResources = new ResourceList();
            for (final Resource classpathResource : allAcceptedResources) {
                final var relativePath = classpathResource.getPath();
                if (pattern.matcher(relativePath).matches()) {
                    filteredResources.add(classpathResource);
                }
            }
            return filteredResources;
        }
    }

    /**
     * Get the list of all resources found in accepted packages that have a path
     * matching the requested wildcard string.
     *
     * <p>
     * The wildcard string may contain:
     * <ul>
     * <li>Single asterisks, to match zero or more of any character other than
     * '/'</li>
     * <li>Double asterisks, to match zero or more of any character</li>
     * <li>Question marks, to match one character</li>
     * <li>Any other regexp-style syntax, such as character sets (denoted by square
     * brackets) -- the remainder of the expression is passed through to the Java
     * regex parser, after escaping dot characters.</li>
     * </ul>
     *
     * <p>
     * The wildcard string is translated in a simplistic way into a regex. If you
     * need more complex pattern matching, use a regex directly, via
     * {@link #getResourcesMatchingPattern(Pattern)}.
     *
     * @param wildcardString A wildcard (glob) pattern to match {@link Resource}
     *                       paths with.
     * @return A list of all resources found in accepted packages that have a path
     *         matching the requested wildcard string.
     */
    public ResourceList getResourcesMatchingWildcard(final String wildcardString) {
        checkNotClosed();
        Assert.notNull(wildcardString, "wildcardString");
        return getResourcesMatchingPattern(AcceptReject.globToPattern(wildcardString, /* simpleGlob = */ false));
    }

    // -------------------------------------------------------------------------------------------------------------
    // Modules

    /**
     * Get the {@link ModuleInfo} object for the named module, or null if no module
     * of the requested name was found during the scan.
     *
     * @param moduleName The module name.
     * @return The {@link ModuleInfo} object for the named module, or null if the
     *         module was not found.
     */
    public @Nullable ModuleInfo getModuleInfo(final String moduleName) {
        checkClassInfoEnabled();
        Assert.notNull(moduleName, "moduleName");
        return moduleNameToModuleInfo().get(moduleName);
    }

    /**
     * Get all modules found during the scan.
     *
     * @return A list of all modules found during the scan, or the empty list if
     *         none.
     */
    public ModuleInfoList getModuleInfo() {
        checkClassInfoEnabled();
        return new ModuleInfoList(moduleNameToModuleInfo().values());
    }

    // -------------------------------------------------------------------------------------------------------------
    // Packages

    /**
     * Get the {@link PackageInfo} object for the named package, or null if no
     * package of the requested name was found during the scan.
     *
     * @param packageName The package name.
     * @return The {@link PackageInfo} object for the named package, or null if the
     *         package was not found.
     */
    public @Nullable PackageInfo getPackageInfo(final String packageName) {
        checkClassInfoEnabled();
        Assert.notNull(packageName, "packageName");
        return packageNameToPackageInfo().get(packageName);
    }

    /**
     * Get all packages found during the scan.
     *
     * @return A list of all packages found during the scan, or the empty list if
     *         none.
     */
    public PackageInfoList getPackageInfo() {
        checkClassInfoEnabled();
        return new PackageInfoList(packageNameToPackageInfo().values());
    }

    // -------------------------------------------------------------------------------------------------------------
    // Class dependencies

    /**
     * Get a map from the {@link ClassInfo} object for each accepted class to a list
     * of the classes referenced by that class (i.e. returns a map from dependents
     * to dependencies). Note that you need to call
     * {@link ClassGraph#enableInterClassDependencies()} before
     * {@link ClassGraph#scan()} for this method to work. You should also call
     * {@link ClassGraph#enableExternalClasses()} before {@link ClassGraph#scan()}
     * if you want non-accepted classes to appear in the result. See also
     * {@link #getReverseClassDependencyMap()}, which inverts the map.
     *
     * @return A map from a {@link ClassInfo} object for each accepted class to a
     *         list of the classes referenced by that class (i.e. returns a map from
     *         dependents to dependencies). Each map value is the result of calling
     *         {@link ClassInfo#getClassDependencies()} on the corresponding key.
     */
    public Map<ClassInfo, ClassInfoList> getClassDependencyMap() {
        final Map<ClassInfo, ClassInfoList> map = new HashMap<>();
        for (final ClassInfo ci : getAllClasses()) {
            map.put(ci, ci.getClassDependencies());
        }
        return map;
    }

    /**
     * Get the reverse class dependency map, i.e. a map from the {@link ClassInfo}
     * object for each dependency class (accepted or not) to a list of the accepted
     * classes that referenced that class as a dependency (i.e. returns a map from
     * dependencies to dependents). Note that you need to call
     * {@link ClassGraph#enableInterClassDependencies()} before
     * {@link ClassGraph#scan()} for this method to work. You should also call
     * {@link ClassGraph#enableExternalClasses()} before {@link ClassGraph#scan()}
     * if you want non-accepted classes to appear in the result. See also
     * {@link #getClassDependencyMap}.
     *
     * @return A map from a {@link ClassInfo} object for each dependency class
     *         (accepted or not) to a list of the accepted classes that referenced
     *         that class as a dependency (i.e. returns a map from dependencies to
     *         dependents).
     */
    public Map<ClassInfo, ClassInfoList> getReverseClassDependencyMap() {
        final Map<ClassInfo, Set<ClassInfo>> revMapSet = new HashMap<>();
        for (final ClassInfo ci : getAllClasses()) {
            for (final ClassInfo dep : ci.getClassDependencies()) {
                revMapSet.computeIfAbsent(dep, k -> new HashSet<>()).add(ci);
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
     * Get the {@link ClassInfo} object for the named class, or null if no class of
     * the requested name was found in an accepted/non-rejected package during the
     * scan.
     *
     * @param className The class name.
     * @return The {@link ClassInfo} object for the named class, or null if the
     *         class was not found.
     */
    public @Nullable ClassInfo getClassInfo(final String className) {
        checkClassInfoEnabled();
        Assert.notNull(className, "className");
        return classNameToClassInfo.get(className);
    }

    /**
     * Get all classes, interfaces and annotations found during the scan.
     *
     * @return A list of all accepted classes found during the scan, or the empty
     *         list if none.
     */
    public ClassInfoList getAllClasses() {
        checkClassInfoEnabled();
        return ClassInfo.getAllClasses(classNameToClassInfo.values(), scanSpec);
    }

    /**
     * Get all {@link Enum} classes found during the scan.
     *
     * @return A list of all {@link Enum} classes found during the scan, or the
     *         empty list if none.
     */
    public ClassInfoList getAllEnums() {
        checkClassInfoEnabled();
        return ClassInfo.getAllEnums(classNameToClassInfo.values(), scanSpec);
    }

    /**
     * Get all {@code record} classes found during the scan (JDK 14+).
     *
     * @return A list of all {@code record} classes found during the scan, or the
     *         empty list if none.
     */
    public ClassInfoList getAllRecords() {
        checkClassInfoEnabled();
        return ClassInfo.getAllRecords(classNameToClassInfo.values(), scanSpec);
    }

    /**
     * Get a map from class name to {@link ClassInfo} object for all classes,
     * interfaces and annotations found during the scan.
     *
     * @return The map from class name to {@link ClassInfo} object for all classes,
     *         interfaces and annotations found during the scan.
     */
    public Map<String, ClassInfo> getAllClassesAsMap() {
        checkClassInfoEnabled();
        return classNameToClassInfo;
    }

    /**
     * Get all standard (non-interface/non-annotation) classes found during the
     * scan.
     *
     * @return A list of all accepted standard classes found during the scan, or the
     *         empty list if none.
     */
    public ClassInfoList getAllStandardClasses() {
        checkClassInfoEnabled();
        return ClassInfo.getAllStandardClasses(classNameToClassInfo.values(), scanSpec);
    }

    /**
     * Get all subclasses of the superclass.
     *
     * @param superclass The superclass.
     * @return A list of subclasses of the superclass, or the empty list if none.
     */
    public ClassInfoList getSubclasses(final Class<?> superclass) {
        Assert.notNull(superclass, "superclass");
        return getSubclasses(superclass.getName());
    }

    /**
     * Get all subclasses of the named superclass.
     *
     * @param superclassName The name of the superclass.
     * @return A list of subclasses of the named superclass, or the empty list if
     *         none.
     */
    public ClassInfoList getSubclasses(final String superclassName) {
        checkClassInfoEnabled();
        Assert.notNull(superclassName, "superclassName");
        if ("java.lang.Object".equals(superclassName)) {
            // Return all standard classes (interfaces don't extend Object)
            return getAllStandardClasses();
        } else {
            final var superclass = classNameToClassInfo.get(superclassName);
            return superclass == null ? ClassInfoList.EMPTY_LIST : superclass.getSubclasses();
        }
    }

    /**
     * Get superclasses of the named subclass.
     *
     * @param subclassName The name of the subclass.
     * @return A list of superclasses of the named subclass, or the empty list if
     *         none.
     */
    public ClassInfoList getSuperclasses(final String subclassName) {
        checkClassInfoEnabled();
        Assert.notNull(subclassName, "subclassName");
        final var subclass = classNameToClassInfo.get(subclassName);
        return subclass == null ? ClassInfoList.EMPTY_LIST : subclass.getSuperclasses();
    }

    /**
     * Get superclasses of the subclass.
     *
     * @param subclass The subclass.
     * @return A list of superclasses of the named subclass, or the empty list if
     *         none.
     */
    public ClassInfoList getSuperclasses(final Class<?> subclass) {
        Assert.notNull(subclass, "subclass");
        return getSuperclasses(subclass.getName());
    }

    /**
     * Get classes that have a method with an annotation of the named type.
     *
     * @param methodAnnotation the method annotation.
     * @return A list of classes with a method that has an annotation of the named
     *         type, or the empty list if none.
     */
    public ClassInfoList getClassesWithMethodAnnotation(final Class<? extends Annotation> methodAnnotation) {
        Assert.notNull(methodAnnotation, "methodAnnotation");
        Assert.isAnnotation(methodAnnotation);
        return getClassesWithMethodAnnotation(methodAnnotation.getName());
    }

    /**
     * Get classes that have a method with an annotation of the named type.
     *
     * @param methodAnnotationName the name of the method annotation.
     * @return A list of classes with a method that has an annotation of the named
     *         type, or the empty list if none.
     */
    public ClassInfoList getClassesWithMethodAnnotation(final String methodAnnotationName) {
        checkMethodAnnotationInfoEnabled();
        Assert.notNull(methodAnnotationName, "methodAnnotationName");
        final var classInfo = classNameToClassInfo.get(methodAnnotationName);
        return classInfo == null ? ClassInfoList.EMPTY_LIST : classInfo.getClassesWithMethodAnnotation();
    }

    /**
     * Get classes that have a method with a parameter that is annotated with an
     * annotation of the named type.
     *
     * @param methodParameterAnnotation the method parameter annotation.
     * @return A list of classes that have a method with a parameter annotated with
     *         the named annotation type, or the empty list if none.
     */
    public ClassInfoList getClassesWithMethodParameterAnnotation(
            final Class<? extends Annotation> methodParameterAnnotation) {
        Assert.notNull(methodParameterAnnotation, "methodParameterAnnotation");
        Assert.isAnnotation(methodParameterAnnotation);
        return getClassesWithMethodParameterAnnotation(methodParameterAnnotation.getName());
    }

    /**
     * Get classes that have a method with a parameter that is annotated with an
     * annotation of the named type.
     *
     * @param methodParameterAnnotationName the name of the method parameter
     *                                      annotation.
     * @return A list of classes that have a method with a parameter annotated with
     *         the named annotation type, or the empty list if none.
     */
    public ClassInfoList getClassesWithMethodParameterAnnotation(final String methodParameterAnnotationName) {
        checkMethodAnnotationInfoEnabled();
        Assert.notNull(methodParameterAnnotationName, "methodParameterAnnotationName");
        final var classInfo = classNameToClassInfo.get(methodParameterAnnotationName);
        return classInfo == null ? ClassInfoList.EMPTY_LIST : classInfo.getClassesWithMethodParameterAnnotation();
    }

    /**
     * Get classes that have a field with an annotation of the named type.
     *
     * @param fieldAnnotation the field annotation.
     * @return A list of classes that have a field with an annotation of the named
     *         type, or the empty list if none.
     */
    public ClassInfoList getClassesWithFieldAnnotation(final Class<? extends Annotation> fieldAnnotation) {
        Assert.notNull(fieldAnnotation, "fieldAnnotation");
        Assert.isAnnotation(fieldAnnotation);
        return getClassesWithFieldAnnotation(fieldAnnotation.getName());
    }

    /**
     * Get classes that have a field with an annotation of the named type.
     *
     * @param fieldAnnotationName the name of the field annotation.
     * @return A list of classes that have a field with an annotation of the named
     *         type, or the empty list if none.
     */
    public ClassInfoList getClassesWithFieldAnnotation(final String fieldAnnotationName) {
        checkFieldAnnotationInfoEnabled();
        Assert.notNull(fieldAnnotationName, "fieldAnnotationName");
        final var classInfo = classNameToClassInfo.get(fieldAnnotationName);
        return classInfo == null ? ClassInfoList.EMPTY_LIST : classInfo.getClassesWithFieldAnnotation();
    }

    // -------------------------------------------------------------------------------------------------------------
    // Interfaces

    /**
     * Get all interface classes found during the scan (not including annotations,
     * which are also technically interfaces). See also
     * {@link #getAllInterfacesAndAnnotations()}.
     *
     * @return A list of all accepted interfaces found during the scan, or the empty
     *         list if none.
     */
    public ClassInfoList getAllInterfaces() {
        checkClassInfoEnabled();
        return ClassInfo.getAllImplementedInterfaceClasses(classNameToClassInfo.values(), scanSpec);
    }

    /**
     * Get all interfaces implemented by the named class or by one of its
     * superclasses, if the named class is a standard class, or the superinterfaces
     * extended by this interface, if it is an interface.
     *
     * @param className The class name.
     * @return A list of interfaces implemented by the named class (or
     *         superinterfaces extended by the named interface), or the empty list
     *         if none.
     */
    public ClassInfoList getInterfaces(final String className) {
        checkClassInfoEnabled();
        Assert.notNull(className, "className");
        final var classInfo = classNameToClassInfo.get(className);
        return classInfo == null ? ClassInfoList.EMPTY_LIST : classInfo.getInterfaces();
    }

    /**
     * Get all interfaces implemented by the class or by one of its superclasses, if
     * the given class is a standard class, or the superinterfaces extended by this
     * interface, if it is an interface.
     *
     * @param classRef The class.
     * @return A list of interfaces implemented by the given class (or
     *         superinterfaces extended by the given interface), or the empty list
     *         if none.
     */
    public ClassInfoList getInterfaces(final Class<?> classRef) {
        Assert.notNull(classRef, "classRef");
        return getInterfaces(classRef.getName());
    }

    /**
     * Get all classes that implement (or have superclasses that implement) the
     * interface (or one of its subinterfaces).
     *
     * <p>
     * The returned list also contains the transitive subinterfaces of the
     * interface. Call {@link ClassInfoList#getInterfaces()} on the result for just
     * the subinterfaces, or {@link ClassInfoList#getStandardClasses()} for just the
     * implementing classes.
     *
     * @param interfaceClass The interface class.
     * @return A list of all classes that implement the interface, and all
     *         transitive subinterfaces of the interface, or the empty list if none.
     */
    public ClassInfoList getClassesImplementing(final Class<?> interfaceClass) {
        Assert.notNull(interfaceClass, "interfaceClass");
        Assert.isInterface(interfaceClass);
        return getClassesImplementing(interfaceClass.getName());
    }

    /**
     * Get all classes that implement (or have superclasses that implement) the
     * named interface (or one of its subinterfaces).
     *
     * <p>
     * The returned list also contains the transitive subinterfaces of the
     * interface. Call {@link ClassInfoList#getInterfaces()} on the result for just
     * the subinterfaces, or {@link ClassInfoList#getStandardClasses()} for just the
     * implementing classes.
     *
     * @param interfaceName The interface name.
     * @return A list of all classes that implement the named interface, and all
     *         transitive subinterfaces of the interface, or the empty list if none.
     */
    public ClassInfoList getClassesImplementing(final String interfaceName) {
        checkClassInfoEnabled();
        Assert.notNull(interfaceName, "interfaceName");
        final var classInfo = classNameToClassInfo.get(interfaceName);
        return classInfo == null ? ClassInfoList.EMPTY_LIST : classInfo.getClassesImplementing();
    }

    /**
     * Get all transitive subinterfaces of the given interface, i.e. the interfaces
     * that extend the interface, and the interfaces that extend those.
     *
     * <p>
     * This is the interface-hierarchy equivalent of {@link #getSubclasses(Class)},
     * which only traverses the superclass hierarchy.
     *
     * @param interfaceClass The interface class.
     * @return A list of all transitive subinterfaces of the interface, or the empty
     *         list if none.
     */
    public ClassInfoList getSubinterfaces(final Class<?> interfaceClass) {
        Assert.notNull(interfaceClass, "interfaceClass");
        Assert.isInterface(interfaceClass);
        return getSubinterfaces(interfaceClass.getName());
    }

    /**
     * Get all transitive subinterfaces of the named interface, i.e. the interfaces
     * that extend the interface, and the interfaces that extend those.
     *
     * <p>
     * This is the interface-hierarchy equivalent of {@link #getSubclasses(String)},
     * which only traverses the superclass hierarchy.
     *
     * @param interfaceName The interface name.
     * @return A list of all transitive subinterfaces of the named interface, or the
     *         empty list if none.
     */
    public ClassInfoList getSubinterfaces(final String interfaceName) {
        checkClassInfoEnabled();
        Assert.notNull(interfaceName, "interfaceName");
        final var classInfo = classNameToClassInfo.get(interfaceName);
        return classInfo == null ? ClassInfoList.EMPTY_LIST : classInfo.getSubinterfaces();
    }

    // -------------------------------------------------------------------------------------------------------------
    // Annotations

    /**
     * Get all annotation classes found during the scan. See also
     * {@link #getAllInterfacesAndAnnotations()}.
     *
     * @return A list of all annotation classes found during the scan, or the empty
     *         list if none.
     */
    public ClassInfoList getAllAnnotations() {
        checkAnnotationInfoEnabled();
        return ClassInfo.getAllAnnotationClasses(classNameToClassInfo.values(), scanSpec);
    }

    /**
     * Get all interface or annotation classes found during the scan. (Annotations
     * are technically interfaces, and they can be implemented.)
     *
     * @return A list of all accepted interfaces found during the scan, or the empty
     *         list if none.
     */
    public ClassInfoList getAllInterfacesAndAnnotations() {
        checkAnnotationInfoEnabled();
        return ClassInfo.getAllInterfacesOrAnnotationClasses(classNameToClassInfo.values(), scanSpec);
    }

    /**
     * Get classes with the class annotation or meta-annotation.
     *
     * @param annotation The class annotation or meta-annotation.
     * @return A list of all non-annotation classes that were found with the class
     *         annotation during the scan, or the empty list if none.
     */
    public ClassInfoList getClassesWithAnnotation(final Class<? extends Annotation> annotation) {
        Assert.notNull(annotation, "annotation");
        Assert.isAnnotation(annotation);
        return getClassesWithAnnotation(annotation.getName());
    }

    /**
     * Get classes with all of the specified class annotations or meta-annotation.
     *
     * @param annotations The class annotations or meta-annotations.
     * @return A list of all non-annotation classes that were found with any of the
     *         class annotations during the scan, or the empty list if none.
     */
    @SuppressWarnings("unchecked")
    public ClassInfoList getClassesWithAllAnnotations(final Class<? extends Annotation>... annotations) {
        Assert.notNullElements(annotations, "annotations");
        final List<String> annotationNames = new ArrayList<>();
        for (final Class<?> cls : annotations) {
            Assert.isAnnotation(cls);
            annotationNames.add(cls.getName());
        }
        return getClassesWithAllAnnotations(annotationNames.toArray(new String[0]));
    }

    /**
     * Get classes with any of the specified class annotations or meta-annotation.
     *
     * @param annotations The class annotations or meta-annotations.
     * @return A list of all non-annotation classes that were found with any of the
     *         class annotations during the scan, or the empty list if none.
     */
    @SuppressWarnings("unchecked")
    public ClassInfoList getClassesWithAnyAnnotation(final Class<? extends Annotation>... annotations) {
        Assert.notNullElements(annotations, "annotations");
        final List<String> annotationNames = new ArrayList<>();
        for (final Class<?> cls : annotations) {
            Assert.isAnnotation(cls);
            annotationNames.add(cls.getName());
        }
        return getClassesWithAnyAnnotation(annotationNames.toArray(new String[0]));
    }

    /**
     * Get classes with the named class annotation or meta-annotation.
     *
     * @param annotationName The name of the class annotation or meta-annotation.
     * @return A list of all non-annotation classes that were found with the named
     *         class annotation during the scan, or the empty list if none.
     */
    public ClassInfoList getClassesWithAnnotation(final String annotationName) {
        checkAnnotationInfoEnabled();
        Assert.notNull(annotationName, "annotationName");
        final var classInfo = classNameToClassInfo.get(annotationName);
        return classInfo == null ? ClassInfoList.EMPTY_LIST : classInfo.getClassesWithAnnotation();
    }

    /**
     * Get classes with all of the named class annotations or meta-annotation.
     *
     * @param annotationNames The name of the class annotations or meta-annotations.
     * @return A list of all non-annotation classes that were found with all of the
     *         named class annotations during the scan, or the empty list if none.
     */
    public ClassInfoList getClassesWithAllAnnotations(final String... annotationNames) {
        Assert.notNullElements(annotationNames, "annotationNames");
        ClassInfoList foundClassInfo = null;
        for (final String annotationName : annotationNames) {
            final var classInfoList = getClassesWithAnnotation(annotationName);
            if (foundClassInfo == null) {
                foundClassInfo = classInfoList;
            } else {
                foundClassInfo = foundClassInfo.intersect(classInfoList);
            }
        }
        if (foundClassInfo == null) {
            return ClassInfoList.EMPTY_LIST;
        }
        CollectionUtils.sortIfNotEmpty(foundClassInfo);
        return foundClassInfo;
    }

    /**
     * Get classes with any of the named class annotations or meta-annotation.
     *
     * @param annotationNames The name of the class annotations or meta-annotations.
     * @return A list of all non-annotation classes that were found with any of the
     *         named class annotations during the scan, or the empty list if none.
     */
    public ClassInfoList getClassesWithAnyAnnotation(final String... annotationNames) {
        Assert.notNullElements(annotationNames, "annotationNames");
        ClassInfoList foundClassInfo = null;
        for (final String annotationName : annotationNames) {
            final var classInfoList = getClassesWithAnnotation(annotationName);
            if (foundClassInfo == null) {
                foundClassInfo = classInfoList;
            } else {
                foundClassInfo = foundClassInfo.union(classInfoList);
            }
        }
        if (foundClassInfo == null) {
            return ClassInfoList.EMPTY_LIST;
        }
        CollectionUtils.sortIfNotEmpty(foundClassInfo);
        return foundClassInfo;
    }

    /**
     * Get annotations on the named class. This only returns the annotating classes;
     * to read annotation parameters, call {@link #getClassInfo(String)} to get the
     * {@link ClassInfo} object for the named class, then if the {@link ClassInfo}
     * object is non-null, call {@link ClassInfo#getAnnotationInfo()} to get
     * detailed annotation info.
     *
     * @param className The name of the class.
     * @return A list of all annotation classes that were found with the named class
     *         annotation during the scan, or the empty list if none.
     */
    public ClassInfoList getAnnotationsOnClass(final String className) {
        checkAnnotationInfoEnabled();
        Assert.notNull(className, "className");
        final var classInfo = classNameToClassInfo.get(className);
        return classInfo == null ? ClassInfoList.EMPTY_LIST : classInfo.getAnnotations();
    }

    // -------------------------------------------------------------------------------------------------------------
    // Classpath modification tests

    /**
     * Determine whether the classpath contents have been modified since the last
     * scan. Checks the timestamps of files and jarfiles encountered during the
     * previous scan to see if they have changed. Does not perform a full scan, so
     * cannot detect the addition of directories that newly match accept criteria --
     * you need to perform a full scan to detect those changes.
     *
     * @return true if the classpath contents have been modified since the last
     *         scan.
     */
    public boolean classpathContentsModifiedSinceScan() {
        checkNotClosed();
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
     * Find the maximum last-modified timestamp of any accepted
     * file/directory/jarfile encountered during the scan. Checks the current
     * timestamps, so this should increase between calls if something changes in
     * accepted paths. Assumes both file and system timestamps were generated from
     * clocks whose time was accurate. Ignores timestamps greater than the system
     * time.
     *
     * <p>
     * This method cannot in general tell if classpath has changed (or modules have
     * been added or removed) if it is run twice during the same runtime session.
     *
     * @return the maximum last-modified time for accepted files/directories/jars
     *         encountered during the scan.
     */
    public long classpathContentsLastModifiedTime() {
        checkNotClosed();
        var maxLastModifiedTime = 0L;
        if (fileToLastModified != null) {
            final var currTime = System.currentTimeMillis();
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
     * Get the ClassLoader order, respecting parent-first/parent-last delegation
     * order.
     *
     * @return the class loader order.
     */
    ClassLoader @Nullable [] getClassLoaderOrderRespectingParentDelegation() {
        return classpathFinder().getClassLoaderOrderRespectingParentDelegation();
    }

    /**
     * Load a class given a class name. If ignoreExceptions is false, and the class
     * cannot be loaded (due to classloading error, or due to an exception being
     * thrown in the class initialization block), an IllegalArgumentException is
     * thrown; otherwise, the class will simply be skipped if an exception is
     * thrown.
     *
     * <p>
     * Enable verbose scanning to see details of any exceptions thrown during
     * classloading, even if ignoreExceptions is false.
     *
     * @param className                 the class to load.
     * @param returnNullIfClassNotFound If true, null is returned if there was an
     *                                  exception during classloading, otherwise
     *                                  IllegalArgumentException is thrown if a
     *                                  class could not be loaded.
     * @return a reference to the loaded class, or null if the class could not be
     *         loaded and ignoreExceptions is true.
     * @throws IllegalArgumentException if ignoreExceptions is false,
     *                                  IllegalArgumentException is thrown if there
     *                                  were problems loading or initializing the
     *                                  class. (Note that class initialization on
     *                                  load is disabled by default, you can enable
     *                                  it with
     *                                  {@code ClassGraph#initializeLoadedClasses(true)}
     *                                  .) Otherwise exceptions are suppressed, and
     *                                  null is returned if any of these problems
     *                                  occurs.
     */
    public @Nullable Class<?> loadClass(final String className, final boolean returnNullIfClassNotFound)
            throws IllegalArgumentException {
        checkNotClosed();
        Assert.notNull(className, "className");
        if (className.isEmpty()) {
            throw new IllegalArgumentException("className must not be empty");
        }
        try {
            return Class.forName(className, scanSpec.initializeLoadedClasses, classGraphClassLoader());
        } catch (final ClassNotFoundException | LinkageError e) {
            if (returnNullIfClassNotFound) {
                return null;
            } else {
                throw new IllegalArgumentException("Could not load class " + className + " : " + e, e);
            }
        }
    }

    /**
     * Load a class given a class name. If ignoreExceptions is false, and the class
     * cannot be loaded (due to classloading error, or due to an exception being
     * thrown in the class initialization block), an IllegalArgumentException is
     * thrown; otherwise, the class will simply be skipped if an exception is
     * thrown.
     *
     * <p>
     * Enable verbose scanning to see details of any exceptions thrown during
     * classloading, even if ignoreExceptions is false.
     *
     * @param <T>                       the superclass or interface type.
     * @param className                 the class to load.
     * @param superclassOrInterfaceType The class type to cast the result to.
     * @param returnNullIfClassNotFound If true, null is returned if there was an
     *                                  exception during classloading, otherwise
     *                                  IllegalArgumentException is thrown if a
     *                                  class could not be loaded.
     * @return a reference to the loaded class, or null if the class could not be
     *         loaded and ignoreExceptions is true.
     * @throws IllegalArgumentException if ignoreExceptions is false,
     *                                  IllegalArgumentException is thrown if there
     *                                  were problems loading the class,
     *                                  initializing the class, or casting it to the
     *                                  requested type. (Note that class
     *                                  initialization on load is disabled by
     *                                  default, you can enable it with
     *                                  {@code ClassGraph#initializeLoadedClasses(true)}
     *                                  .) Otherwise exceptions are suppressed, and
     *                                  null is returned if any of these problems
     *                                  occurs.
     */
    public <T> @Nullable Class<T> loadClass(final String className, final Class<T> superclassOrInterfaceType,
            final boolean returnNullIfClassNotFound) throws IllegalArgumentException {
        checkNotClosed();
        Assert.notNull(className, "className");
        if (className.isEmpty()) {
            throw new IllegalArgumentException("className must not be empty");
        }
        Assert.notNull(superclassOrInterfaceType, "superclassOrInterfaceType");
        final Class<?> loadedClass;
        try {
            loadedClass = Class.forName(className, scanSpec.initializeLoadedClasses, classGraphClassLoader());
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

    /**
     * Free any temporary files created by extracting jars or files from within
     * jars. Without calling this method, the temporary files created by extracting
     * the inner jars will be removed in a finalizer, called by the garbage
     * collector (or at JVM shutdown). If you don't want to experience long GC
     * pauses, make sure you call this close method when you have finished with the
     * {@link ScanResult}.
     */
    @Override
    public void close() {
        if (!closed.getAndSet(true)) {
            nonClosedWeakReferences.remove(weakReference);
            if (classpathOrder != null) {
                classpathOrder.clear();
                classpathOrder = null;
            }
            final var allAcceptedResources = allAcceptedResourcesCached;
            if (allAcceptedResources != null) {
                for (final Resource classpathResource : allAcceptedResources) {
                    classpathResource.close();
                }
                allAcceptedResources.clear();
                allAcceptedResourcesCached = null;
            }
            if (pathToAcceptedResourcesCached != null) {
                pathToAcceptedResourcesCached.clear();
                pathToAcceptedResourcesCached = null;
            }
            // Don't clear classNameToClassInfo, since it may be used by
            // ClassGraphClassLoader (#399).
            // Just rely on the garbage collector to collect these once the ScanResult goes
            // out of scope.
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
            // nestedJarHandler should be closed last, since it needs to have all
            // MappedByteBuffer refs
            // dropped before it tries to delete any temporary files that were written to
            // disk
            if (nestedJarHandler != null) {
                nestedJarHandler.close(topLevelLog);
                nestedJarHandler = null;
            }
            classGraphClassLoader = null;
            classpathFinder = null;
            reflectionUtils = null;
            // Flush log on exit, in case additional log entries were generated after scan()
            // completed
            if (topLevelLog != null) {
                topLevelLog.flush();
            }
        }
    }

    /**
     * Returns whether this ScanResult has been closed yet or not.
     *
     * @return {@code true} if this ScanResult has been closed
     */
    public boolean isClosed() {
        return closed.get();
    }

    /**
     * Close all {@link ScanResult} instances that have not yet been closed. Note
     * that this will close all open {@link ScanResult} instances for any class that
     * uses the classloader that the {@link ScanResult} class is cached in -- so if
     * you call this method, you need to ensure that the lifecycle of the
     * classloader matches the lifecycle of your application, or that two concurrent
     * applications don't share the same classloader, otherwise one application
     * might close another application's {@link ScanResult} instances while they are
     * still in use.
     */
    public static void closeAll() {
        for (final WeakReference<ScanResult> nonClosedWeakReference : new ArrayList<>(nonClosedWeakReferences)) {
            final var scanResult = nonClosedWeakReference.get();
            if (scanResult != null) {
                scanResult.close();
            }
        }
    }
}
