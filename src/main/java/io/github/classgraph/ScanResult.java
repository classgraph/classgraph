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

import static io.github.classgraph.PotentiallyUnmodifiableList.unmodifiable;

import java.io.Closeable;
import java.io.File;
import java.lang.annotation.Annotation;
import java.lang.ref.WeakReference;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
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

import nonapi.io.github.classgraph.fastzipfilereader.NestedJarHandler;
import nonapi.io.github.classgraph.reflection.ReflectionUtils;
import nonapi.io.github.classgraph.scanspec.AcceptReject;
import nonapi.io.github.classgraph.scanspec.ScanSpec;
import nonapi.io.github.classgraph.utils.Assert;
import nonapi.io.github.classgraph.utils.FileUtils;
import nonapi.io.github.classgraph.utils.JarUtils;
import nonapi.io.github.classgraph.utils.LogNode;
import org.jspecify.annotations.Nullable;

/**
 * The result of a scan. You should assign a ScanResult in a try-with-resources block, or manually close it when you
 * have finished with the result of a scan.
 */
public final class ScanResult implements Closeable {
    /**
     * The order of classpath elements, after inner jars have been extracted to temporary files, etc.
     */
    private @Nullable List<ClasspathElement> classpathOrder;

    /**
     * A list of all files that were found in accepted packages, or null if not yet cached, or if this
     * {@link ScanResult} has been closed.
     */
    private @Nullable ResourceList allAcceptedResourcesCached;

    /**
     * The number of times {@link #getResourcesWithPath(String)} has been called.
     */
    private final AtomicInteger getResourcesWithPathCallCount = new AtomicInteger();

    /**
     * The map from path (relative to package root) to a list of {@link Resource} elements with the matching path.
     */
    private @Nullable Map<String, ResourceList> pathToAcceptedResourcesCached;

    /** The map from class name to {@link ClassInfo}. */
    Map<String, ClassInfo> classNameToClassInfo;

    /** The map from package name to {@link PackageInfo}. */
    private @Nullable Map<String, PackageInfo> packageNameToPackageInfo;

    /** The map from class name to {@link ClassInfo}. */
    private @Nullable Map<String, ModuleInfo> moduleNameToModuleInfo;

    /**
     * The file, directory and jarfile resources timestamped during a scan, along with their timestamp at the time
     * of the scan. For jarfiles, the timestamp represents the timestamp of all files within the jar. May be null,
     * if this ScanResult object is the result of a call to ClassGraph#getUniqueClasspathElementsAsync().
     */
    private @Nullable Map<File, Long> fileToLastModified;

    /** The nested jar handler instance. */
    private @Nullable NestedJarHandler nestedJarHandler;

    /** The scan spec. */
    ScanSpec scanSpec;

    /** If true, this ScanResult has already been closed. */
    private final AtomicBoolean closed = new AtomicBoolean(false);

    /**
     * The {@link ReflectionUtils} instance, or null if this {@link ScanResult} has been closed.
     */
    @Nullable
    ReflectionUtils reflectionUtils;

    /** The message for the {@link IllegalStateException} thrown after closing. */
    private static final String CLOSED_MESSAGE = "Cannot use a ScanResult after it has been closed";

    /**
     * Get the classpath order, for use in code paths that are only reachable before this {@link ScanResult} is
     * closed.
     *
     * @return the classpath order
     * @throws IllegalStateException
     *             if this {@link ScanResult} has been closed.
     */
    private List<ClasspathElement> classpathOrder() {
        final var order = classpathOrder;
        if (order == null) {
            throw new IllegalStateException(CLOSED_MESSAGE);
        }
        return order;
    }

    /**
     * Get the map from package name to {@link PackageInfo}, for use in code paths that are only reachable before
     * this {@link ScanResult} is closed.
     *
     * @return the map from package name to {@link PackageInfo}
     * @throws IllegalStateException
     *             if this {@link ScanResult} has been closed.
     */
    private Map<String, PackageInfo> packageNameToPackageInfo() {
        final var map = packageNameToPackageInfo;
        if (map == null) {
            throw new IllegalStateException(CLOSED_MESSAGE);
        }
        return map;
    }

    /**
     * Get the map from module name to {@link ModuleInfo}, for use in code paths that are only reachable before this
     * {@link ScanResult} is closed.
     *
     * @return the map from module name to {@link ModuleInfo}
     * @throws IllegalStateException
     *             if this {@link ScanResult} has been closed.
     */
    private Map<String, ModuleInfo> moduleNameToModuleInfo() {
        final var map = moduleNameToModuleInfo;
        if (map == null) {
            throw new IllegalStateException(CLOSED_MESSAGE);
        }
        return map;
    }

    /** The toplevel log. */
    private final @Nullable LogNode topLevelLog;

    // -------------------------------------------------------------------------------------------------------------

    /** The {@link WeakReference} for this ScanResult. */
    private final WeakReference<ScanResult> weakReference;

    /**
     * The set of WeakReferences to non-closed ScanResult objects. Uses WeakReferences so that garbage collection is
     * not blocked.
     */
    // #233
    private static final Set<WeakReference<ScanResult>> nonClosedWeakReferences = Collections
            .newSetFromMap(new ConcurrentHashMap<>());

    // -------------------------------------------------------------------------------------------------------------
    // Constructor

    /**
     * The result of a scan. Make sure you call complete() after calling the constructor.
     *
     * @param scanSpec
     *            the scan spec
     * @param classpathOrder
     *            the classpath order
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
            final Map<String, ClassInfo> classNameToClassInfo,
            final Map<String, PackageInfo> packageNameToPackageInfo,
            final Map<String, ModuleInfo> moduleNameToModuleInfo,
            final @Nullable Map<File, Long> fileToLastModified, final NestedJarHandler nestedJarHandler,
            final @Nullable LogNode topLevelLog) {
        this.scanSpec = scanSpec;
        this.classpathOrder = classpathOrder;
        this.fileToLastModified = fileToLastModified;
        this.classNameToClassInfo = classNameToClassInfo;
        this.packageNameToPackageInfo = packageNameToPackageInfo;
        this.moduleNameToModuleInfo = moduleNameToModuleInfo;
        this.nestedJarHandler = nestedJarHandler;
        this.reflectionUtils = nestedJarHandler.scanResources.reflectionUtils;
        this.topLevelLog = topLevelLog;

        indexResourcesAndClassInfo(topLevelLog);

        // Handle @Repeatable annotations
        final Set<String> allRepeatableAnnotationNames = new HashSet<>();
        for (final ClassInfo classInfo : classNameToClassInfo.values()) {
            if (classInfo.isAnnotation() && classInfo.annotationInfo != null) {
                final var repeatableMetaAnnotation = classInfo.annotationInfo
                        .get("java.lang.annotation.Repeatable");
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

        // Provide the shutdown hook with a weak reference to this ScanResult
        this.weakReference = new WeakReference<>(this);
        nonClosedWeakReferences.add(this.weakReference);
    }

    /**
     * Index {@link Resource} and {@link ClassInfo} objects.
     *
     * @param log
     *            the log node, or null to skip logging
     */
    private void indexResourcesAndClassInfo(final @Nullable LogNode log) {
        // Add backrefs from Info objects back to this ScanResult
        final var allClassInfo = classNameToClassInfo.values();
        for (final ClassInfo classInfo : allClassInfo) {
            classInfo.setScanResult(this);
        }

        // If inter-class dependencies are enabled, create placeholder ClassInfo objects for any referenced classes
        // that were not scanned
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
     * @throws IllegalStateException
     *             if this {@link ScanResult} has been closed.
     */
    private void checkNotClosed() {
        if (closed.get()) {
            throw new IllegalStateException(CLOSED_MESSAGE);
        }
    }

    /**
     * Check that this {@link ScanResult} has not been closed, and that class info was enabled during the scan.
     *
     * @throws IllegalStateException
     *             if this {@link ScanResult} has been closed, or class info was not enabled.
     */
    private void checkClassInfoEnabled() {
        checkNotClosed();
        scanSpec.checkClassInfoEnabled();
    }

    /**
     * Check that this {@link ScanResult} has not been closed, and that class info and annotation info were enabled
     * during the scan.
     *
     * @throws IllegalStateException
     *             if this {@link ScanResult} has been closed, or class info or annotation info were not enabled.
     */
    private void checkAnnotationInfoEnabled() {
        checkNotClosed();
        if (!scanSpec.enableClassInfo || !scanSpec.enableAnnotationInfo) {
            throw new IllegalStateException(
                    "Please call ClassGraph#enableClassInfo() and #enableAnnotationInfo() before #scan()");
        }
    }

    /**
     * Check that this {@link ScanResult} has not been closed, and that class info, method info and annotation info
     * were enabled during the scan.
     *
     * @throws IllegalStateException
     *             if this {@link ScanResult} has been closed, or class info, method info or annotation info were
     *             not enabled.
     */
    private void checkMethodAnnotationInfoEnabled() {
        checkNotClosed();
        if (!scanSpec.enableClassInfo || !scanSpec.enableMethodInfo || !scanSpec.enableAnnotationInfo) {
            throw new IllegalStateException("Please call ClassGraph#enableClassInfo(), #enableMethodInfo(), "
                    + "and #enableAnnotationInfo() before #scan()");
        }
    }

    /**
     * Check that this {@link ScanResult} has not been closed, and that class info, field info and annotation info
     * were enabled during the scan.
     *
     * @throws IllegalStateException
     *             if this {@link ScanResult} has been closed, or class info, field info or annotation info were not
     *             enabled.
     */
    private void checkFieldAnnotationInfoEnabled() {
        checkNotClosed();
        if (!scanSpec.enableClassInfo || !scanSpec.enableFieldInfo || !scanSpec.enableAnnotationInfo) {
            throw new IllegalStateException("Please call ClassGraph#enableClassInfo(), #enableFieldInfo(), "
                    + "and #enableAnnotationInfo() before #scan()");
        }
    }

    // -------------------------------------------------------------------------------------------------------------
    // Classpath / module path

    /**
     * Returns the list of File objects for unique classpath elements (directories or jarfiles), in classloader
     * resolution order.
     *
     * @return The unique classpath elements.
     * @throws IllegalStateException
     *             if this {@link ScanResult} has been closed.
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
        return Collections.unmodifiableList(classpathElementOrderFiles);
    }

    /**
     * Returns all unique directories or zip/jarfiles on the classpath, in classloader resolution order, as a
     * classpath string, delineated with the standard path separator character.
     *
     * @return a the unique directories and jarfiles on the classpath, in classpath resolution order, as a path
     *         string.
     * @throws IllegalStateException
     *             if this {@link ScanResult} has been closed.
     */
    public String getClasspath() {
        checkNotClosed();
        return JarUtils.pathElementsToPathStr(getClasspathFiles());
    }

    /**
     * Returns an ordered list of unique classpath element and module URIs.
     *
     * @return The unique classpath element and module URIs.
     * @throws IllegalStateException
     *             if this {@link ScanResult} has been closed.
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
            } catch (final IllegalStateException e) {
                // Skip null location URIs
            }
        }
        return Collections.unmodifiableList(classpathElementOrderURIs);
    }

    /**
     * Returns an ordered list of unique classpath element and module URLs. Any URI that cannot be converted to a
     * {@link URL} is skipped.
     *
     * @return The unique classpath element and module URLs.
     * @throws IllegalStateException
     *             if this {@link ScanResult} has been closed.
     */
    public List<URL> getClasspathURLs() {
        checkNotClosed();
        final List<URL> classpathElementOrderURLs = new ArrayList<>();
        for (final URI uri : getClasspathURIs()) {
            try {
                classpathElementOrderURLs.add(uri.toURL());
            } catch (final IllegalArgumentException | MalformedURLException e) {
                // Skip malformed and relative URIs
            }
        }
        return Collections.unmodifiableList(classpathElementOrderURLs);
    }

    /**
     * Get {@link ModuleRef} references for all visible modules.
     *
     * @return {@link ModuleRef} references for all visible modules.
     * @throws IllegalStateException
     *             if this {@link ScanResult} has been closed.
     */
    public List<ModuleRef> getModules() {
        checkNotClosed();
        final List<ModuleRef> moduleRefs = new ArrayList<>();
        for (final ClasspathElement classpathElement : classpathOrder()) {
            if (classpathElement instanceof final ClasspathElementModule classpathElementModule) {
                moduleRefs.add(classpathElementModule.getModuleRef());
            }
        }
        return Collections.unmodifiableList(moduleRefs);
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
     * @throws IllegalStateException
     *             if this {@link ScanResult} has been closed.
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
     * @return A list of all resources (including classfiles and non-classfiles) found in accepted packages.
     * @throws IllegalStateException
     *             if this {@link ScanResult} has been closed.
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
                acceptedResourcesList.makeUnmodifiable();
                // Set atomically for thread safety
                allAcceptedResourcesCached = allAcceptedResources = acceptedResourcesList;
            }
            return allAcceptedResources;
        }
    }

    /**
     * Get a map from resource path to {@link Resource} for all resources (including classfiles and non-classfiles)
     * found in accepted packages.
     *
     * @return An unmodifiable map from resource path to {@link Resource} for all resources (including classfiles
     *         and non-classfiles) found in accepted packages.
     * @throws IllegalStateException
     *             if this {@link ScanResult} has been closed.
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
                for (final ResourceList resourceList : pathToAcceptedResourceListMap.values()) {
                    resourceList.makeUnmodifiable();
                }
                // Set atomically for thread safety
                pathToAcceptedResourcesCached = pathToAcceptedResources = pathToAcceptedResourceListMap;
            }
            return Collections.unmodifiableMap(pathToAcceptedResources);
        }
    }

    /**
     * Get the list of all resources found in accepted packages that have the given path, relative to the package
     * root of the classpath element. May match several resources, up to one per classpath element.
     *
     * @param resourcePath
     *            A complete resource path, relative to the classpath entry package root.
     * @return A list of all resources found in accepted packages that have the given path, relative to the package
     *         root of the classpath element. May match several resources, up to one per classpath element.
     * @throws IllegalStateException
     *             if this {@link ScanResult} has been closed.
     */
    public ResourceList getResourcesWithPath(final String resourcePath) {
        checkNotClosed();
        Assert.notNull(resourcePath, "resourcePath");
        final var path = FileUtils.sanitizeEntryPath(resourcePath, /* removeInitialSlash = */ true,
                /* removeFinalSlash = */ true);
        ResourceList matchingResources = null;
        if (getResourcesWithPathCallCount.incrementAndGet() > 3) {
            // If numerous calls are made, produce and cache a single HashMap for O(1) access time
            matchingResources = getAllResourcesAsMap().get(path);
        } else {
            // If just a few calls are made, directly search for resource with the requested path
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
        return matchingResources == null ? ResourceList.EMPTY_LIST : unmodifiable(matchingResources);
    }

    /**
     * Get the list of all resources found in any classpath element, <i>whether in accepted packages or not (as long
     * as the resource is not rejected)</i>, that have the given path, relative to the package root of the classpath
     * element. May match several resources, up to one per classpath element. Note that this may not return a
     * non-accepted resource, particularly when scanning directory classpath elements, because recursive scanning
     * terminates once there are no possible accepted resources below a given directory. However, resources in
     * ancestral directories of accepted directories can be found using this method.
     *
     * @param resourcePath
     *            A complete resource path, relative to the classpath entry package root.
     * @return A list of all resources found in any classpath element, <i>whether in accepted packages or not (as
     *         long as the resource is not rejected)</i>, that have the given path, relative to the package root of
     *         the classpath element. May match several resources, up to one per classpath element.
     * @throws IllegalStateException
     *             if this {@link ScanResult} has been closed.
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
        return unmodifiable(matchingResources);
    }

    /**
     * Get the list of all resources found in accepted packages that have the requested leafname.
     *
     * @param leafName
     *            A resource leaf filename.
     * @return A list of all resources found in accepted packages that have the requested leafname.
     * @throws IllegalStateException
     *             if this {@link ScanResult} has been closed.
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
            return unmodifiable(filteredResources);
        }
    }

    /**
     * Get the list of all resources found in accepted packages that have the requested filename extension.
     *
     * @param extension
     *            A filename extension, e.g. "xml" to match all resources ending in ".xml".
     * @return A list of all resources found in accepted packages that have the requested filename extension.
     * @throws IllegalStateException
     *             if this {@link ScanResult} has been closed.
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
            return unmodifiable(filteredResources);
        }
    }

    /**
     * Get the list of all resources found in accepted packages that have a path matching the requested regex
     * pattern. See also {@link #getResourcesMatchingWildcard(String)}.
     *
     * @param pattern
     *            A pattern to match {@link Resource} paths with.
     * @return A list of all resources found in accepted packages that have a path matching the requested pattern.
     * @throws IllegalStateException
     *             if this {@link ScanResult} has been closed.
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
            return unmodifiable(filteredResources);
        }
    }

    /**
     * Get the list of all resources found in accepted packages that have a path matching the requested wildcard
     * string.
     *
     * <p>
     * The wildcard string may contain:
     * <ul>
     * <li>{@code '*'}, to match zero or more of any character other than {@code '/'}</li>
     * <li>{@code "**"}, forming a complete path segment, to match zero or more whole path segments, e.g.
     * {@code "META-INF/**&#47;*.properties"} matches {@code META-INF/a.properties} and
     * {@code META-INF/services/a.properties}</li>
     * <li>{@code '?'}, to match exactly one character other than {@code '/'}</li>
     * </ul>
     *
     * <p>
     * Any other character is matched literally. This is the same glob syntax used by the accept/reject criteria of
     * {@link ClassGraph}. If you need more complex pattern matching, use a regex directly, via
     * {@link #getResourcesMatchingPattern(Pattern)}.
     *
     * @param wildcardString
     *            A wildcard (glob) pattern to match {@link Resource} paths with.
     * @return A list of all resources found in accepted packages that have a path matching the requested wildcard
     *         string.
     * @throws IllegalArgumentException
     *             if {@code "**"} is used without forming a complete path segment.
     * @throws IllegalStateException
     *             if this {@link ScanResult} has been closed.
     */
    public ResourceList getResourcesMatchingWildcard(final String wildcardString) {
        checkNotClosed();
        Assert.notNull(wildcardString, "wildcardString");
        return getResourcesMatchingPattern(
                AcceptReject.globToPattern(wildcardString, '/', /* prefixMatch = */ false));
    }

    // -------------------------------------------------------------------------------------------------------------
    // Modules

    /**
     * Get the {@link ModuleInfo} object for the named module, or null if no module of the requested name was found
     * during the scan.
     *
     * @param moduleName
     *            The module name, e.g. {@code "java.base"}.
     * @return The {@link ModuleInfo} object for the named module, or null if the module was not found.
     * @throws IllegalStateException
     *             if this {@link ScanResult} has been closed, or if {@link ClassGraph#enableClassInfo()} was not
     *             called before scanning.
     */
    public @Nullable ModuleInfo getModuleInfo(final String moduleName) {
        checkClassInfoEnabled();
        Assert.notNull(moduleName, "moduleName");
        return moduleNameToModuleInfo().get(moduleName);
    }

    /**
     * Get all modules found during the scan.
     *
     * @return A list of all modules found during the scan, or the empty list if none.
     * @throws IllegalStateException
     *             if this {@link ScanResult} has been closed, or if {@link ClassGraph#enableClassInfo()} was not
     *             called before scanning.
     */
    public ModuleInfoList getModuleInfo() {
        checkClassInfoEnabled();
        return unmodifiable(new ModuleInfoList(moduleNameToModuleInfo().values()));
    }

    // -------------------------------------------------------------------------------------------------------------
    // Packages

    /**
     * Get the {@link PackageInfo} object for the named package, or null if no package of the requested name was
     * found during the scan.
     *
     * @param packageName
     *            The package name, with {@code '.'} between package name segments, e.g. {@code "com.xyz"}. The root
     *            package is named {@code ""}.
     * @return The {@link PackageInfo} object for the named package, or null if the package was not found.
     * @throws IllegalStateException
     *             if this {@link ScanResult} has been closed, or if {@link ClassGraph#enableClassInfo()} was not
     *             called before scanning.
     */
    public @Nullable PackageInfo getPackageInfo(final String packageName) {
        checkClassInfoEnabled();
        Assert.notNull(packageName, "packageName");
        return packageNameToPackageInfo().get(packageName);
    }

    /**
     * Get all packages found during the scan.
     *
     * @return A list of all packages found during the scan, or the empty list if none.
     * @throws IllegalStateException
     *             if this {@link ScanResult} has been closed, or if {@link ClassGraph#enableClassInfo()} was not
     *             called before scanning.
     */
    public PackageInfoList getPackageInfo() {
        checkClassInfoEnabled();
        return unmodifiable(new PackageInfoList(packageNameToPackageInfo().values()));
    }

    // -------------------------------------------------------------------------------------------------------------
    // Class dependencies

    /**
     * Get a map from the {@link ClassInfo} object for each accepted class to a list of the classes referenced by
     * that class (i.e. returns a map from dependents to dependencies). Note that you need to call
     * {@link ClassGraph#enableInterClassDependencies()} before {@link ClassGraph#scan()} for this method to work.
     * You should also call {@link ClassGraph#enableExternalClasses()} before {@link ClassGraph#scan()} if you want
     * non-accepted classes to appear in the result. See also {@link #getReverseClassDependencyMap()}, which inverts
     * the map.
     *
     * @return A map from a {@link ClassInfo} object for each accepted class to a list of the classes referenced by
     *         that class (i.e. returns a map from dependents to dependencies). Each map value is the result of
     *         calling {@link ClassInfo#getClassDependencies()} on the corresponding key.
     * @throws IllegalStateException
     *             if this {@link ScanResult} has been closed, or if {@link ClassGraph#enableClassInfo()} was not
     *             called before scanning.
     */
    public Map<ClassInfo, ClassInfoList> getClassDependencyMap() {
        final Map<ClassInfo, ClassInfoList> map = new HashMap<>();
        for (final ClassInfo ci : getAllClasses()) {
            map.put(ci, ci.getClassDependencies());
        }
        return Collections.unmodifiableMap(map);
    }

    /**
     * Get the reverse class dependency map, i.e. a map from the {@link ClassInfo} object for each dependency class
     * (accepted or not) to a list of the accepted classes that referenced that class as a dependency (i.e. returns
     * a map from dependencies to dependents). Note that you need to call
     * {@link ClassGraph#enableInterClassDependencies()} before {@link ClassGraph#scan()} for this method to work.
     * You should also call {@link ClassGraph#enableExternalClasses()} before {@link ClassGraph#scan()} if you want
     * non-accepted classes to appear in the result. See also {@link #getClassDependencyMap}.
     *
     * @return A map from a {@link ClassInfo} object for each dependency class (accepted or not) to a list of the
     *         accepted classes that referenced that class as a dependency (i.e. returns a map from dependencies to
     *         dependents).
     * @throws IllegalStateException
     *             if this {@link ScanResult} has been closed, or if {@link ClassGraph#enableClassInfo()} was not
     *             called before scanning.
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
        return Collections.unmodifiableMap(revMapList);
    }

    // -------------------------------------------------------------------------------------------------------------
    // Classes

    /**
     * Get the {@link ClassInfo} object for the named class, or null if no class of the requested name was found in
     * an accepted/non-rejected package during the scan.
     *
     * @param className
     *            The fully-qualified name of the class, in the same form as {@link Class#getName()}: {@code '.'}
     *            between package name segments, and {@code '$'} between an outer class name and a nested class
     *            name, e.g. {@code "com.xyz.Outer$Inner"}.
     * @return The {@link ClassInfo} object for the named class, or null if the class was not found.
     * @throws IllegalStateException
     *             if this {@link ScanResult} has been closed, or if {@link ClassGraph#enableClassInfo()} was not
     *             called before scanning.
     */
    public @Nullable ClassInfo getClassInfo(final String className) {
        checkClassInfoEnabled();
        Assert.notNull(className, "className");
        return classNameToClassInfo.get(className);
    }

    /**
     * Get all classes, interfaces and annotations found during the scan.
     *
     * @return A list of all accepted classes found during the scan, or the empty list if none.
     * @throws IllegalStateException
     *             if this {@link ScanResult} has been closed, or if {@link ClassGraph#enableClassInfo()} was not
     *             called before scanning.
     */
    public ClassInfoList getAllClasses() {
        checkClassInfoEnabled();
        return ClassInfo.getAllClasses(classNameToClassInfo.values(), scanSpec);
    }

    /**
     * Get all {@link Enum} classes found during the scan.
     *
     * @return A list of all {@link Enum} classes found during the scan, or the empty list if none.
     * @throws IllegalStateException
     *             if this {@link ScanResult} has been closed, or if {@link ClassGraph#enableClassInfo()} was not
     *             called before scanning.
     */
    public ClassInfoList getAllEnums() {
        checkClassInfoEnabled();
        return ClassInfo.getAllEnums(classNameToClassInfo.values(), scanSpec);
    }

    /**
     * Get all {@code record} classes found during the scan (JDK 14+).
     *
     * @return A list of all {@code record} classes found during the scan, or the empty list if none.
     * @throws IllegalStateException
     *             if this {@link ScanResult} has been closed, or if {@link ClassGraph#enableClassInfo()} was not
     *             called before scanning.
     */
    public ClassInfoList getAllRecords() {
        checkClassInfoEnabled();
        return ClassInfo.getAllRecords(classNameToClassInfo.values(), scanSpec);
    }

    /**
     * Get a map from class name to {@link ClassInfo} object for all classes, interfaces and annotations found
     * during the scan.
     *
     * @return An unmodifiable map from class name to {@link ClassInfo} object for all classes, interfaces and
     *         annotations found during the scan.
     * @throws IllegalStateException
     *             if this {@link ScanResult} has been closed, or if {@link ClassGraph#enableClassInfo()} was not
     *             called before scanning.
     */
    public Map<String, ClassInfo> getAllClassesAsMap() {
        checkClassInfoEnabled();
        return Collections.unmodifiableMap(classNameToClassInfo);
    }

    /**
     * Get all standard (non-interface/non-annotation) classes found during the scan.
     *
     * @return A list of all accepted standard classes found during the scan, or the empty list if none.
     * @throws IllegalStateException
     *             if this {@link ScanResult} has been closed, or if {@link ClassGraph#enableClassInfo()} was not
     *             called before scanning.
     */
    public ClassInfoList getAllStandardClasses() {
        checkClassInfoEnabled();
        return ClassInfo.getAllStandardClasses(classNameToClassInfo.values(), scanSpec);
    }

    /**
     * Get all subclasses of the superclass, i.e. the classes that extend the superclass, and the classes that
     * extend those, transitively.
     *
     * @param superclass
     *            The superclass. Only the name of this class is used for the lookup, so the class itself does not
     *            need to have been on the scanned classpath.
     * @return A list of all subclasses of the superclass, or the empty list if none.
     * @throws IllegalStateException
     *             if this {@link ScanResult} has been closed, or if {@link ClassGraph#enableClassInfo()} was not
     *             called before scanning.
     */
    public ClassInfoList getAllSubclasses(final Class<?> superclass) {
        Assert.notNull(superclass, "superclass");
        return getAllSubclasses(superclass.getName());
    }

    /**
     * Get all subclasses of the named superclass, i.e. the classes that extend the superclass, and the classes that
     * extend those, transitively.
     *
     * @param superclassName
     *            The name of the superclass.
     * @return A list of all subclasses of the named superclass, or the empty list if none.
     * @throws IllegalStateException
     *             if this {@link ScanResult} has been closed, or if {@link ClassGraph#enableClassInfo()} was not
     *             called before scanning.
     */
    public ClassInfoList getAllSubclasses(final String superclassName) {
        checkClassInfoEnabled();
        Assert.notNull(superclassName, "superclassName");
        if ("java.lang.Object".equals(superclassName)) {
            // Every standard class is a subclass of Object by the rules of the language, whether or not its whole
            // superclass chain was scanned, and whether or not Object itself was scanned (interfaces don't extend
            // Object)
            return getAllStandardClasses().filter(classInfo -> !"java.lang.Object".equals(classInfo.getName()));
        } else {
            final var superclass = classNameToClassInfo.get(superclassName);
            return superclass == null ? ClassInfoList.EMPTY_LIST : superclass.getAllSubclasses();
        }
    }

    /**
     * Get the direct subclasses of the superclass, i.e. only the classes that name the superclass as their
     * superclass.
     *
     * @param superclass
     *            The superclass. Only the name of this class is used for the lookup, so the class itself does not
     *            need to have been on the scanned classpath.
     * @return A list of direct subclasses of the superclass, or the empty list if none.
     * @throws IllegalStateException
     *             if this {@link ScanResult} has been closed, or if {@link ClassGraph#enableClassInfo()} was not
     *             called before scanning.
     */
    public ClassInfoList getDirectSubclasses(final Class<?> superclass) {
        Assert.notNull(superclass, "superclass");
        return getDirectSubclasses(superclass.getName());
    }

    /**
     * Get the direct subclasses of the named superclass, i.e. only the classes that name the superclass as their
     * superclass.
     *
     * @param superclassName
     *            The name of the superclass.
     * @return A list of direct subclasses of the named superclass, or the empty list if none.
     * @throws IllegalStateException
     *             if this {@link ScanResult} has been closed, or if {@link ClassGraph#enableClassInfo()} was not
     *             called before scanning.
     */
    public ClassInfoList getDirectSubclasses(final String superclassName) {
        checkClassInfoEnabled();
        Assert.notNull(superclassName, "superclassName");
        final var superclass = classNameToClassInfo.get(superclassName);
        return superclass == null ? ClassInfoList.EMPTY_LIST : superclass.getDirectSubclasses();
    }

    /**
     * Get all superclasses of the named subclass, in ascending order in the class hierarchy, ending with
     * {@link Object} if the whole superclass chain was scanned.
     *
     * @param subclassName
     *            The name of the subclass.
     * @return A list of all superclasses of the named subclass, or the empty list if none.
     * @throws IllegalStateException
     *             if this {@link ScanResult} has been closed, or if {@link ClassGraph#enableClassInfo()} was not
     *             called before scanning.
     */
    public ClassInfoList getAllSuperclasses(final String subclassName) {
        checkClassInfoEnabled();
        Assert.notNull(subclassName, "subclassName");
        final var subclass = classNameToClassInfo.get(subclassName);
        return subclass == null ? ClassInfoList.EMPTY_LIST : subclass.getAllSuperclasses();
    }

    /**
     * Get all superclasses of the subclass, in ascending order in the class hierarchy, ending with {@link Object}
     * if the whole superclass chain was scanned.
     *
     * @param subclass
     *            The subclass. Only the name of this class is used for the lookup, so the class itself does not
     *            need to have been on the scanned classpath.
     * @return A list of all superclasses of the subclass, or the empty list if none.
     * @throws IllegalStateException
     *             if this {@link ScanResult} has been closed, or if {@link ClassGraph#enableClassInfo()} was not
     *             called before scanning.
     */
    public ClassInfoList getAllSuperclasses(final Class<?> subclass) {
        Assert.notNull(subclass, "subclass");
        return getAllSuperclasses(subclass.getName());
    }

    /**
     * Get classes that have a method with an annotation of the named type.
     *
     * @param methodAnnotation
     *            The method annotation. Only the name of this class is used for the lookup, so the class itself
     *            does not need to have been on the scanned classpath.
     * @return A list of classes with a method that has an annotation of the named type, or the empty list if none.
     * @throws IllegalArgumentException
     *             if {@code methodAnnotation} is not an annotation type.
     * @throws IllegalStateException
     *             if this {@link ScanResult} has been closed, or if {@link ClassGraph#enableClassInfo()},
     *             {@link ClassGraph#enableMethodInfo()} and {@link ClassGraph#enableAnnotationInfo()} were not all
     *             called before scanning.
     */
    public ClassInfoList getClassesWithMethodAnnotation(final Class<? extends Annotation> methodAnnotation) {
        Assert.notNull(methodAnnotation, "methodAnnotation");
        Assert.isAnnotation(methodAnnotation);
        return getClassesWithMethodAnnotation(methodAnnotation.getName());
    }

    /**
     * Get classes that have a method with an annotation of the named type.
     *
     * @param methodAnnotationName
     *            the name of the method annotation.
     * @return A list of classes with a method that has an annotation of the named type, or the empty list if none.
     * @throws IllegalStateException
     *             if this {@link ScanResult} has been closed, or if {@link ClassGraph#enableClassInfo()},
     *             {@link ClassGraph#enableMethodInfo()} and {@link ClassGraph#enableAnnotationInfo()} were not all
     *             called before scanning.
     */
    public ClassInfoList getClassesWithMethodAnnotation(final String methodAnnotationName) {
        checkMethodAnnotationInfoEnabled();
        Assert.notNull(methodAnnotationName, "methodAnnotationName");
        final var classInfo = classNameToClassInfo.get(methodAnnotationName);
        return classInfo == null ? ClassInfoList.EMPTY_LIST : classInfo.getClassesWithMethodAnnotation();
    }

    /**
     * Get classes that have a method with a parameter that is annotated with an annotation of the named type.
     *
     * @param methodParameterAnnotation
     *            The method parameter annotation. Only the name of this class is used for the lookup, so the class
     *            itself does not need to have been on the scanned classpath.
     * @return A list of classes that have a method with a parameter annotated with the named annotation type, or
     *         the empty list if none.
     * @throws IllegalArgumentException
     *             if {@code methodParameterAnnotation} is not an annotation type.
     * @throws IllegalStateException
     *             if this {@link ScanResult} has been closed, or if {@link ClassGraph#enableClassInfo()},
     *             {@link ClassGraph#enableMethodInfo()} and {@link ClassGraph#enableAnnotationInfo()} were not all
     *             called before scanning.
     */
    public ClassInfoList getClassesWithMethodParameterAnnotation(
            final Class<? extends Annotation> methodParameterAnnotation) {
        Assert.notNull(methodParameterAnnotation, "methodParameterAnnotation");
        Assert.isAnnotation(methodParameterAnnotation);
        return getClassesWithMethodParameterAnnotation(methodParameterAnnotation.getName());
    }

    /**
     * Get classes that have a method with a parameter that is annotated with an annotation of the named type.
     *
     * @param methodParameterAnnotationName
     *            the name of the method parameter annotation.
     * @return A list of classes that have a method with a parameter annotated with the named annotation type, or
     *         the empty list if none.
     * @throws IllegalStateException
     *             if this {@link ScanResult} has been closed, or if {@link ClassGraph#enableClassInfo()},
     *             {@link ClassGraph#enableMethodInfo()} and {@link ClassGraph#enableAnnotationInfo()} were not all
     *             called before scanning.
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
     * @param fieldAnnotation
     *            The field annotation. Only the name of this class is used for the lookup, so the class itself does
     *            not need to have been on the scanned classpath.
     * @return A list of classes that have a field with an annotation of the named type, or the empty list if none.
     * @throws IllegalArgumentException
     *             if {@code fieldAnnotation} is not an annotation type.
     * @throws IllegalStateException
     *             if this {@link ScanResult} has been closed, or if {@link ClassGraph#enableClassInfo()},
     *             {@link ClassGraph#enableFieldInfo()} and {@link ClassGraph#enableAnnotationInfo()} were not all
     *             called before scanning.
     */
    public ClassInfoList getClassesWithFieldAnnotation(final Class<? extends Annotation> fieldAnnotation) {
        Assert.notNull(fieldAnnotation, "fieldAnnotation");
        Assert.isAnnotation(fieldAnnotation);
        return getClassesWithFieldAnnotation(fieldAnnotation.getName());
    }

    /**
     * Get classes that have a field with an annotation of the named type.
     *
     * @param fieldAnnotationName
     *            the name of the field annotation.
     * @return A list of classes that have a field with an annotation of the named type, or the empty list if none.
     * @throws IllegalStateException
     *             if this {@link ScanResult} has been closed, or if {@link ClassGraph#enableClassInfo()},
     *             {@link ClassGraph#enableFieldInfo()} and {@link ClassGraph#enableAnnotationInfo()} were not all
     *             called before scanning.
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
     * Get all interface classes found during the scan (not including annotations, which are also technically
     * interfaces). See also {@link #getAllInterfacesAndAnnotations()}.
     *
     * @return A list of all accepted interfaces found during the scan, or the empty list if none.
     * @throws IllegalStateException
     *             if this {@link ScanResult} has been closed, or if {@link ClassGraph#enableClassInfo()} was not
     *             called before scanning.
     */
    public ClassInfoList getAllInterfaces() {
        checkClassInfoEnabled();
        return ClassInfo.getAllImplementedInterfaceClasses(classNameToClassInfo.values(), scanSpec);
    }

    /**
     * Get all superinterfaces of the named class or interface: all interfaces implemented by the named class or by
     * one of its superclasses, if the named class is a standard class, or all interfaces extended by the named
     * interface, directly or indirectly, if it is an interface.
     *
     * @param className
     *            The fully-qualified name of the class or interface, in the same form as {@link Class#getName()}.
     * @return A list of all superinterfaces of the named class or interface, or the empty list if none.
     * @throws IllegalStateException
     *             if this {@link ScanResult} has been closed, or if {@link ClassGraph#enableClassInfo()} was not
     *             called before scanning.
     */
    public ClassInfoList getAllSuperinterfaces(final String className) {
        checkClassInfoEnabled();
        Assert.notNull(className, "className");
        final var classInfo = classNameToClassInfo.get(className);
        return classInfo == null ? ClassInfoList.EMPTY_LIST : classInfo.getAllSuperinterfaces();
    }

    /**
     * Get all superinterfaces of the given class or interface: all interfaces implemented by the class or by one of
     * its superclasses, if the given class is a standard class, or all interfaces extended by the given interface,
     * directly or indirectly, if it is an interface.
     *
     * @param classRef
     *            The class.
     * @return A list of all superinterfaces of the given class or interface, or the empty list if none.
     * @throws IllegalStateException
     *             if this {@link ScanResult} has been closed, or if {@link ClassGraph#enableClassInfo()} was not
     *             called before scanning.
     */
    public ClassInfoList getAllSuperinterfaces(final Class<?> classRef) {
        Assert.notNull(classRef, "classRef");
        return getAllSuperinterfaces(classRef.getName());
    }

    /**
     * Get the direct superinterfaces of the named class or interface: the interfaces directly implemented by the
     * named class, if the named class is a standard class, or the interfaces directly extended by the named
     * interface, if it is an interface.
     *
     * @param className
     *            The fully-qualified name of the class or interface, in the same form as {@link Class#getName()}.
     * @return A list of the direct superinterfaces of the named class or interface, or the empty list if none.
     * @throws IllegalStateException
     *             if this {@link ScanResult} has been closed, or if {@link ClassGraph#enableClassInfo()} was not
     *             called before scanning.
     */
    public ClassInfoList getDirectSuperinterfaces(final String className) {
        checkClassInfoEnabled();
        Assert.notNull(className, "className");
        final var classInfo = classNameToClassInfo.get(className);
        return classInfo == null ? ClassInfoList.EMPTY_LIST : classInfo.getDirectSuperinterfaces();
    }

    /**
     * Get the direct superinterfaces of the given class or interface: the interfaces directly implemented by the
     * class, if the given class is a standard class, or the interfaces directly extended by the interface, if it is
     * an interface.
     *
     * @param classRef
     *            The class.
     * @return A list of the direct superinterfaces of the given class or interface, or the empty list if none.
     * @throws IllegalStateException
     *             if this {@link ScanResult} has been closed, or if {@link ClassGraph#enableClassInfo()} was not
     *             called before scanning.
     */
    public ClassInfoList getDirectSuperinterfaces(final Class<?> classRef) {
        Assert.notNull(classRef, "classRef");
        return getDirectSuperinterfaces(classRef.getName());
    }

    /**
     * Get all classes that implement (or have superclasses that implement) the interface (or one of its
     * subinterfaces).
     *
     * <p>
     * The returned list also contains the transitive subinterfaces of the interface. Call
     * {@link ClassInfoList#getInterfaces()} on the result for just the subinterfaces, or
     * {@link ClassInfoList#getStandardClasses()} for just the implementing classes.
     *
     * @param interfaceClass
     *            The interface. Only the name of this class is used for the lookup, so the class itself does not
     *            need to have been on the scanned classpath.
     * @return A list of all classes that implement the interface, and all transitive subinterfaces of the
     *         interface, or the empty list if none.
     * @throws IllegalArgumentException
     *             if {@code interfaceClass} is not an interface.
     * @throws IllegalStateException
     *             if this {@link ScanResult} has been closed, or if {@link ClassGraph#enableClassInfo()} was not
     *             called before scanning.
     */
    public ClassInfoList getAllClassesImplementing(final Class<?> interfaceClass) {
        Assert.notNull(interfaceClass, "interfaceClass");
        Assert.isInterface(interfaceClass);
        return getAllClassesImplementing(interfaceClass.getName());
    }

    /**
     * Get all classes that implement (or have superclasses that implement) the named interface (or one of its
     * subinterfaces).
     *
     * <p>
     * The returned list also contains the transitive subinterfaces of the interface. Call
     * {@link ClassInfoList#getInterfaces()} on the result for just the subinterfaces, or
     * {@link ClassInfoList#getStandardClasses()} for just the implementing classes.
     *
     * @param interfaceName
     *            The fully-qualified name of the interface, in the same form as {@link Class#getName()}.
     * @return A list of all classes that implement the named interface, and all transitive subinterfaces of the
     *         interface, or the empty list if none.
     * @throws IllegalStateException
     *             if this {@link ScanResult} has been closed, or if {@link ClassGraph#enableClassInfo()} was not
     *             called before scanning.
     */
    public ClassInfoList getAllClassesImplementing(final String interfaceName) {
        checkClassInfoEnabled();
        Assert.notNull(interfaceName, "interfaceName");
        final var classInfo = classNameToClassInfo.get(interfaceName);
        return classInfo == null ? ClassInfoList.EMPTY_LIST : classInfo.getAllClassesImplementing();
    }

    /**
     * Get the classes that directly declare that they implement the interface, and the interfaces that directly
     * extend the interface.
     *
     * @param interfaceClass
     *            The interface. Only the name of this class is used for the lookup, so the class itself does not
     *            need to have been on the scanned classpath.
     * @return A list of the classes that directly implement the interface, and the direct subinterfaces of the
     *         interface, or the empty list if none.
     * @throws IllegalArgumentException
     *             if {@code interfaceClass} is not an interface.
     * @throws IllegalStateException
     *             if this {@link ScanResult} has been closed, or if {@link ClassGraph#enableClassInfo()} was not
     *             called before scanning.
     */
    public ClassInfoList getDirectClassesImplementing(final Class<?> interfaceClass) {
        Assert.notNull(interfaceClass, "interfaceClass");
        Assert.isInterface(interfaceClass);
        return getDirectClassesImplementing(interfaceClass.getName());
    }

    /**
     * Get the classes that directly declare that they implement the named interface, and the interfaces that
     * directly extend the named interface.
     *
     * @param interfaceName
     *            The fully-qualified name of the interface, in the same form as {@link Class#getName()}.
     * @return A list of the classes that directly implement the named interface, and the direct subinterfaces of
     *         the interface, or the empty list if none.
     * @throws IllegalStateException
     *             if this {@link ScanResult} has been closed, or if {@link ClassGraph#enableClassInfo()} was not
     *             called before scanning.
     */
    public ClassInfoList getDirectClassesImplementing(final String interfaceName) {
        checkClassInfoEnabled();
        Assert.notNull(interfaceName, "interfaceName");
        final var classInfo = classNameToClassInfo.get(interfaceName);
        return classInfo == null ? ClassInfoList.EMPTY_LIST : classInfo.getDirectClassesImplementing();
    }

    /**
     * Get all transitive subinterfaces of the given interface, i.e. the interfaces that extend the interface, and
     * the interfaces that extend those.
     *
     * <p>
     * This is the interface-hierarchy equivalent of {@link #getAllSubclasses(Class)}, which only traverses the
     * superclass hierarchy.
     *
     * @param interfaceClass
     *            The interface. Only the name of this class is used for the lookup, so the class itself does not
     *            need to have been on the scanned classpath.
     * @return A list of all transitive subinterfaces of the interface, or the empty list if none.
     * @throws IllegalArgumentException
     *             if {@code interfaceClass} is not an interface.
     * @throws IllegalStateException
     *             if this {@link ScanResult} has been closed, or if {@link ClassGraph#enableClassInfo()} was not
     *             called before scanning.
     */
    public ClassInfoList getAllSubinterfaces(final Class<?> interfaceClass) {
        Assert.notNull(interfaceClass, "interfaceClass");
        Assert.isInterface(interfaceClass);
        return getAllSubinterfaces(interfaceClass.getName());
    }

    /**
     * Get all transitive subinterfaces of the named interface, i.e. the interfaces that extend the interface, and
     * the interfaces that extend those.
     *
     * <p>
     * This is the interface-hierarchy equivalent of {@link #getAllSubclasses(String)}, which only traverses the
     * superclass hierarchy.
     *
     * @param interfaceName
     *            The fully-qualified name of the interface, in the same form as {@link Class#getName()}.
     * @return A list of all transitive subinterfaces of the named interface, or the empty list if none.
     * @throws IllegalStateException
     *             if this {@link ScanResult} has been closed, or if {@link ClassGraph#enableClassInfo()} was not
     *             called before scanning.
     */
    public ClassInfoList getAllSubinterfaces(final String interfaceName) {
        checkClassInfoEnabled();
        Assert.notNull(interfaceName, "interfaceName");
        final var classInfo = classNameToClassInfo.get(interfaceName);
        return classInfo == null ? ClassInfoList.EMPTY_LIST : classInfo.getAllSubinterfaces();
    }

    /**
     * Get the direct subinterfaces of the given interface, i.e. only the interfaces that directly extend the
     * interface.
     *
     * <p>
     * This is the interface-hierarchy equivalent of {@link #getDirectSubclasses(Class)}, which only traverses the
     * superclass hierarchy.
     *
     * @param interfaceClass
     *            The interface. Only the name of this class is used for the lookup, so the class itself does not
     *            need to have been on the scanned classpath.
     * @return A list of the direct subinterfaces of the interface, or the empty list if none.
     * @throws IllegalArgumentException
     *             if {@code interfaceClass} is not an interface.
     * @throws IllegalStateException
     *             if this {@link ScanResult} has been closed, or if {@link ClassGraph#enableClassInfo()} was not
     *             called before scanning.
     */
    public ClassInfoList getDirectSubinterfaces(final Class<?> interfaceClass) {
        Assert.notNull(interfaceClass, "interfaceClass");
        Assert.isInterface(interfaceClass);
        return getDirectSubinterfaces(interfaceClass.getName());
    }

    /**
     * Get the direct subinterfaces of the named interface, i.e. only the interfaces that directly extend the
     * interface.
     *
     * <p>
     * This is the interface-hierarchy equivalent of {@link #getDirectSubclasses(String)}, which only traverses the
     * superclass hierarchy.
     *
     * @param interfaceName
     *            The fully-qualified name of the interface, in the same form as {@link Class#getName()}.
     * @return A list of the direct subinterfaces of the named interface, or the empty list if none.
     * @throws IllegalStateException
     *             if this {@link ScanResult} has been closed, or if {@link ClassGraph#enableClassInfo()} was not
     *             called before scanning.
     */
    public ClassInfoList getDirectSubinterfaces(final String interfaceName) {
        checkClassInfoEnabled();
        Assert.notNull(interfaceName, "interfaceName");
        final var classInfo = classNameToClassInfo.get(interfaceName);
        return classInfo == null ? ClassInfoList.EMPTY_LIST : classInfo.getDirectSubinterfaces();
    }

    // -------------------------------------------------------------------------------------------------------------
    // Annotations

    /**
     * Get all annotation classes found during the scan. See also {@link #getAllInterfacesAndAnnotations()}.
     *
     * @return A list of all annotation classes found during the scan, or the empty list if none.
     * @throws IllegalStateException
     *             if this {@link ScanResult} has been closed, or if {@link ClassGraph#enableClassInfo()} and
     *             {@link ClassGraph#enableAnnotationInfo()} were not all called before scanning.
     */
    public ClassInfoList getAllAnnotations() {
        checkAnnotationInfoEnabled();
        return ClassInfo.getAllAnnotationClasses(classNameToClassInfo.values(), scanSpec);
    }

    /**
     * Get all interface or annotation classes found during the scan. (Annotations are technically interfaces, and
     * they can be implemented.)
     *
     * @return A list of all accepted interfaces found during the scan, or the empty list if none.
     * @throws IllegalStateException
     *             if this {@link ScanResult} has been closed, or if {@link ClassGraph#enableClassInfo()} and
     *             {@link ClassGraph#enableAnnotationInfo()} were not all called before scanning.
     */
    public ClassInfoList getAllInterfacesAndAnnotations() {
        checkAnnotationInfoEnabled();
        return ClassInfo.getAllInterfacesOrAnnotationClasses(classNameToClassInfo.values(), scanSpec);
    }

    /**
     * Get classes with the class annotation or meta-annotation.
     *
     * @param annotation
     *            The class annotation or meta-annotation.
     * @return A list of all non-annotation classes that were found with the class annotation during the scan, or
     *         the empty list if none.
     * @throws IllegalArgumentException
     *             if {@code annotation} is not an annotation type.
     * @throws IllegalStateException
     *             if this {@link ScanResult} has been closed, or if {@link ClassGraph#enableClassInfo()} and
     *             {@link ClassGraph#enableAnnotationInfo()} were not both called before scanning.
     */
    public ClassInfoList getClassesWithAnnotation(final Class<? extends Annotation> annotation) {
        Assert.notNull(annotation, "annotation");
        Assert.isAnnotation(annotation);
        return getClassesWithAnnotation(annotation.getName());
    }

    /**
     * Get classes with all of the specified class annotations or meta-annotation.
     *
     * @param annotations
     *            The class annotations or meta-annotations.
     * @return A list of all non-annotation classes that were found with any of the class annotations during the
     *         scan, or the empty list if none.
     * @throws IllegalArgumentException
     *             if {@code cls} is not an annotation type.
     * @throws IllegalStateException
     *             if this {@link ScanResult} has been closed, or if {@link ClassGraph#enableClassInfo()} and
     *             {@link ClassGraph#enableAnnotationInfo()} were not both called before scanning.
     */
    @SuppressWarnings("unchecked")
    public ClassInfoList getClassesWithAllAnnotations(final Class<? extends Annotation>... annotations) {
        Assert.notNullElements(annotations, "annotations");
        final List<String> annotationNames = new ArrayList<>();
        for (final Class<?> cls : annotations) {
            Assert.isAnnotation(cls);
            annotationNames.add(cls.getName());
        }
        return getClassesWithAllAnnotations(annotationNames.toArray(String[]::new));
    }

    /**
     * Get classes with any of the specified class annotations or meta-annotation.
     *
     * @param annotations
     *            The class annotations or meta-annotations.
     * @return A list of all non-annotation classes that were found with any of the class annotations during the
     *         scan, or the empty list if none.
     * @throws IllegalArgumentException
     *             if {@code cls} is not an annotation type.
     * @throws IllegalStateException
     *             if this {@link ScanResult} has been closed, or if {@link ClassGraph#enableClassInfo()} and
     *             {@link ClassGraph#enableAnnotationInfo()} were not both called before scanning.
     */
    @SuppressWarnings("unchecked")
    public ClassInfoList getClassesWithAnyAnnotation(final Class<? extends Annotation>... annotations) {
        Assert.notNullElements(annotations, "annotations");
        final List<String> annotationNames = new ArrayList<>();
        for (final Class<?> cls : annotations) {
            Assert.isAnnotation(cls);
            annotationNames.add(cls.getName());
        }
        return getClassesWithAnyAnnotation(annotationNames.toArray(String[]::new));
    }

    /**
     * Get classes with the named class annotation or meta-annotation.
     *
     * @param annotationName
     *            The name of the class annotation or meta-annotation.
     * @return A list of all non-annotation classes that were found with the named class annotation during the scan,
     *         or the empty list if none.
     * @throws IllegalStateException
     *             if this {@link ScanResult} has been closed, or if {@link ClassGraph#enableClassInfo()} and
     *             {@link ClassGraph#enableAnnotationInfo()} were not all called before scanning.
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
     * @param annotationNames
     *            The name of the class annotations or meta-annotations.
     * @return A list of all non-annotation classes that were found with all of the named class annotations during
     *         the scan, or the empty list if none.
     * @throws IllegalStateException
     *             if this {@link ScanResult} has been closed, or if {@link ClassGraph#enableClassInfo()} and
     *             {@link ClassGraph#enableAnnotationInfo()} were not both called before scanning.
     */
    public ClassInfoList getClassesWithAllAnnotations(final String... annotationNames) {
        Assert.notNullElements(annotationNames, "annotationNames");
        ClassInfoList foundClassInfo = null;
        for (final String annotationName : annotationNames) {
            final var classInfoList = getClassesWithAnnotation(annotationName);
            if (classInfoList.isEmpty()) {
                // The intersection with an empty list is empty
                return ClassInfoList.EMPTY_LIST;
            }
            foundClassInfo = foundClassInfo == null ? classInfoList : foundClassInfo.intersect(classInfoList);
        }
        // The lists returned by #getClassesWithAnnotation(String) are sorted by name, and the intersection of
        // name-sorted lists is name-sorted, so the result does not need to be sorted again (and cannot be sorted
        // in place, since the lists returned by the public API are unmodifiable)
        return foundClassInfo == null ? ClassInfoList.EMPTY_LIST : foundClassInfo;
    }

    /**
     * Get classes with any of the named class annotations or meta-annotation.
     *
     * @param annotationNames
     *            The name of the class annotations or meta-annotations.
     * @return A list of all non-annotation classes that were found with any of the named class annotations during
     *         the scan, or the empty list if none.
     * @throws IllegalStateException
     *             if this {@link ScanResult} has been closed, or if {@link ClassGraph#enableClassInfo()} and
     *             {@link ClassGraph#enableAnnotationInfo()} were not both called before scanning.
     */
    public ClassInfoList getClassesWithAnyAnnotation(final String... annotationNames) {
        Assert.notNullElements(annotationNames, "annotationNames");
        ClassInfoList foundClassInfo = null;
        for (final String annotationName : annotationNames) {
            final var classInfoList = getClassesWithAnnotation(annotationName);
            if (classInfoList.isEmpty()) {
                // The union with an empty list is unchanged
                continue;
            }
            foundClassInfo = foundClassInfo == null ? classInfoList : foundClassInfo.union(classInfoList);
        }
        // The lists returned by #getClassesWithAnnotation(String) are sorted by name, and the union of name-sorted
        // lists is name-sorted, so the result does not need to be sorted again (and cannot be sorted in place,
        // since the lists returned by the public API are unmodifiable)
        return foundClassInfo == null ? ClassInfoList.EMPTY_LIST : foundClassInfo;
    }

    /**
     * Get the annotations and meta-annotations on the named class. This only returns the annotating classes; to
     * read annotation parameters, call {@link #getClassInfo(String)} to get the {@link ClassInfo} object for the
     * named class, then if the {@link ClassInfo} object is non-null, call {@link ClassInfo#getAllAnnotationInfo()}
     * to get detailed annotation info.
     *
     * @param className
     *            The name of the class.
     * @return A list of all annotations and meta-annotations on the named class, or the empty list if none.
     * @throws IllegalStateException
     *             if this {@link ScanResult} has been closed, or if {@link ClassGraph#enableClassInfo()} and
     *             {@link ClassGraph#enableAnnotationInfo()} were not all called before scanning.
     */
    public ClassInfoList getAllAnnotationsOnClass(final String className) {
        checkAnnotationInfoEnabled();
        Assert.notNull(className, "className");
        final var classInfo = classNameToClassInfo.get(className);
        return classInfo == null ? ClassInfoList.EMPTY_LIST : classInfo.getAllAnnotations();
    }

    /**
     * Get the annotations and meta-annotations on the class. This only returns the annotating classes; to read
     * annotation parameters, call {@link #getClassInfo(String)} to get the {@link ClassInfo} object for the class,
     * then if the {@link ClassInfo} object is non-null, call {@link ClassInfo#getAllAnnotationInfo()} to get
     * detailed annotation info.
     *
     * @param classRef
     *            The class.
     * @return A list of all annotations and meta-annotations on the class, or the empty list if none.
     * @throws IllegalStateException
     *             if this {@link ScanResult} has been closed, or if {@link ClassGraph#enableClassInfo()} and
     *             {@link ClassGraph#enableAnnotationInfo()} were not both called before scanning.
     */
    public ClassInfoList getAllAnnotationsOnClass(final Class<?> classRef) {
        Assert.notNull(classRef, "classRef");
        return getAllAnnotationsOnClass(classRef.getName());
    }

    /**
     * Get the annotations directly present on the named class, without expanding meta-annotations.
     *
     * @param className
     *            The name of the class.
     * @return A list of the annotations directly present on the named class, or the empty list if none.
     * @throws IllegalStateException
     *             if this {@link ScanResult} has been closed, or if {@link ClassGraph#enableClassInfo()} and
     *             {@link ClassGraph#enableAnnotationInfo()} were not all called before scanning.
     */
    public ClassInfoList getDirectAnnotationsOnClass(final String className) {
        checkAnnotationInfoEnabled();
        Assert.notNull(className, "className");
        final var classInfo = classNameToClassInfo.get(className);
        return classInfo == null ? ClassInfoList.EMPTY_LIST : classInfo.getDirectAnnotations();
    }

    /**
     * Get the annotations directly present on the class, without expanding meta-annotations.
     *
     * @param classRef
     *            The class.
     * @return A list of the annotations directly present on the class, or the empty list if none.
     * @throws IllegalStateException
     *             if this {@link ScanResult} has been closed, or if {@link ClassGraph#enableClassInfo()} and
     *             {@link ClassGraph#enableAnnotationInfo()} were not both called before scanning.
     */
    public ClassInfoList getDirectAnnotationsOnClass(final Class<?> classRef) {
        Assert.notNull(classRef, "classRef");
        return getDirectAnnotationsOnClass(classRef.getName());
    }

    // -------------------------------------------------------------------------------------------------------------
    // Classpath modification tests

    /**
     * Determine whether the classpath contents have been modified since the last scan. Checks the timestamps of
     * files and jarfiles encountered during the previous scan to see if they have changed. Does not perform a full
     * scan, so cannot detect the addition of directories that newly match accept criteria -- you need to perform a
     * full scan to detect those changes.
     *
     * @return true if the classpath contents have been modified since the last scan.
     * @throws IllegalStateException
     *             if this {@link ScanResult} has been closed.
     */
    public boolean isClasspathContentsModifiedSinceScan() {
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
     * Find the maximum last-modified timestamp of any accepted file/directory/jarfile encountered during the scan.
     * Checks the current timestamps, so this should increase between calls if something changes in accepted paths.
     * Assumes both file and system timestamps were generated from clocks whose time was accurate. Ignores
     * timestamps greater than the system time.
     *
     * <p>
     * This method cannot in general tell if classpath has changed (or modules have been added or removed) if it is
     * run twice during the same runtime session.
     *
     * @return the maximum last-modified time for accepted files/directories/jars encountered during the scan.
     * @throws IllegalStateException
     *             if this {@link ScanResult} has been closed.
     */
    public long getClasspathContentsLastModifiedMillis() {
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

    /**
     * Close this {@link ScanResult}, releasing the memory it holds, closing any open jarfiles, and deleting any
     * temporary files created by extracting jars from within jars. Temporary files that are still present when the
     * JVM exits are deleted then, via {@link java.io.File#deleteOnExit()}, but open jarfiles and memory-mapped
     * buffers are only released by this method -- so a program that scans repeatedly without closing its
     * {@link ScanResult}s will hold those resources until it exits.
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
                // Drop the reference to the cached list rather than clearing it, since the list (or a map
                // containing it) may have been returned to the caller, and returned collections are unmodifiable
                allAcceptedResourcesCached = null;
            }
            pathToAcceptedResourcesCached = null;
            // Don't clear classNameToClassInfo, since ClassInfo objects and the objects reachable from them keep
            // working after the ScanResult they came from is closed. Just rely on the garbage collector to collect
            // these once the ScanResult goes out of scope.
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
            // nestedJarHandler should be closed last, since it needs to have all MappedByteBuffer refs dropped
            // before it tries to delete any temporary files that were written to disk
            if (nestedJarHandler != null) {
                nestedJarHandler.close(topLevelLog);
                nestedJarHandler = null;
            }
            reflectionUtils = null;
            // Flush log on exit, in case additional log entries were generated after scan() completed
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
     * Close all {@link ScanResult} instances that have not yet been closed. Note that this will close all open
     * {@link ScanResult} instances for any class that uses the classloader that the {@link ScanResult} class is
     * cached in -- so if you call this method, you need to ensure that the lifecycle of the classloader matches the
     * lifecycle of your application, or that two concurrent applications don't share the same classloader,
     * otherwise one application might close another application's {@link ScanResult} instances while they are still
     * in use.
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
