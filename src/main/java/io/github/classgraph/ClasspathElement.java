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
import java.net.URI;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.classgraph.Scanner.RawClasspathElementWorkUnit;
import nonapi.io.github.classgraph.ScanSpec;
import nonapi.io.github.classgraph.ScanSpec.ScanSpecPathMatch;
import nonapi.io.github.classgraph.concurrency.WorkQueue;
import nonapi.io.github.classgraph.utils.FileUtils;
import nonapi.io.github.classgraph.utils.JarUtils;
import nonapi.io.github.classgraph.utils.LogNode;

/** A classpath element (a directory or jarfile on the classpath). */
abstract class ClasspathElement {
    /**
     * If non-null, contains a list of resolved paths for any classpath element roots nested inside this classpath
     * element. (Scanning should stop at a nested classpath element root, otherwise that subtree will be scanned
     * more than once.) N.B. contains only the nested part of the resolved path (the common prefix is removed). Also
     * includes a trailing '/', since only nested directory classpath elements need to be caught (nested jars do not
     * need to be caught, because we don't scan jars-within-jars unless the inner jar is explicitly listed on the
     * classpath).
     */
    List<String> nestedClasspathRootPrefixes;

    /**
     * True if there was an exception when trying to open this classpath element (e.g. a corrupt ZipFile).
     */
    boolean skipClasspathElement;

    /** True if classpath element contains a specifically-whitelisted resource path. */
    boolean containsSpecificallyWhitelistedClasspathElementResourcePath;

    /**
     * The child classpath elements, keyed by the order of the child classpath element within the Class-Path entry
     * of the manifest file the child classpath element was listed in (or the position of the file within the sorted
     * entries of a lib directory).
     */
    Queue<Entry<Integer, ClasspathElement>> childClasspathElementsIndexed = new ConcurrentLinkedQueue<>();

    /**
     * The child classpath elements, ordered by order within the parent classpath element.
     */
    List<ClasspathElement> childClasspathElementsOrdered;

    /** The list of all resources found within this classpath element that were whitelisted and not blacklisted. */
    protected List<Resource> whitelistedResources;

    /** The list of all classfiles found within this classpath element that were whitelisted and not blacklisted. */
    protected List<Resource> whitelistedClassfileResources;

    /** Map from class name to non-blacklisted resource. */
    protected Map<String, Resource> classNameToNonBlacklistedResource;

    /** The map from File to last modified timestamp, if scanFiles is true. */
    protected Map<File, Long> fileToLastModified;

    /** Flag to ensure classpath element is only scanned once. */
    protected final AtomicBoolean scanned = new AtomicBoolean(false);

    /** The classloader(s) handling this classpath element. */
    protected ClassLoader[] classLoaders;

    /** The module name, read from module-info.class if present. */
    String moduleName;

    /** The scan spec. */
    final ScanSpec scanSpec;

    // -------------------------------------------------------------------------------------------------------------

    /** A classpath element (a directory or jarfile on the classpath). */
    ClasspathElement(final ClassLoader[] classLoaders, final ScanSpec scanSpec) {
        this.classLoaders = classLoaders;
        this.scanSpec = scanSpec;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * If non-empty, this path represents the package root within a jarfile, e.g. if the path is
     * "spring-project.jar!/BOOT-INF/classes", the package root is "BOOT-INF/classes". N.B. for non-modules, this
     * should only be called after {@link #getClasspathElementFile(LogNode)}, since that method determines the
     * package root (after extracting nested jars).
     */
    String getPackageRoot() {
        // Overridden in ClasspathElementZip
        return "";
    }

    /** Get the ClassLoader(s) to use when trying to load the class. */
    ClassLoader[] getClassLoaders() {
        return classLoaders;
    }

    /** Get the number of classfile matches. */
    int getNumClassfileMatches() {
        return whitelistedClassfileResources == null ? 0 : whitelistedClassfileResources.size();
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Apply relative path masking within this classpath resource -- remove relative paths that were found in an
     * earlier classpath element.
     * 
     * @return the masked classfile resources.
     */
    static List<Resource> maskClassfiles(final ScanSpec scanSpec, final int classpathIdx,
            final List<Resource> classfileResources, final HashSet<String> classpathRelativePathsFound,
            final LogNode log) {
        if (!scanSpec.performScan) {
            // Should not happen
            throw new IllegalArgumentException("performScan is false");
        }
        // Find relative paths that occur more than once in the classpath / module path.
        // Usually duplicate relative paths occur only between classpath / module path elements, not within,
        // but actually there is no restriction for paths within a zipfile to be unique, and in fact
        // zipfiles in the wild do contain the same classfiles multiple times with the same exact path,
        // e.g.: xmlbeans-2.6.0.jar!org/apache/xmlbeans/xml/stream/Location.class
        final BitSet masked = new BitSet(classfileResources.size());
        boolean foundMasked = false;
        for (int i = 0; i < classfileResources.size(); i++) {
            final Resource res = classfileResources.get(i);
            final String pathRelativeToPackageRoot = res.getPath();
            // Don't mask module-info.class or package-info.class, these are read for every module/package
            if (!pathRelativeToPackageRoot.equals("module-info.class")
                    && !pathRelativeToPackageRoot.endsWith("/module-info.class")
                    && !pathRelativeToPackageRoot.equals("package-info.class")
                    && !pathRelativeToPackageRoot.endsWith("/package-info.class")) {
                if (!classpathRelativePathsFound.add(pathRelativeToPackageRoot)) {
                    // This relative path has been encountered more than once; mask the second and subsequent
                    // occurrences of the path
                    masked.set(i);
                    foundMasked = true;
                    if (log != null) {
                        log.log(String.format("%06d-1", classpathIdx),
                                "Ignoring duplicate (masked) class "
                                        + JarUtils.classfilePathToClassName(pathRelativeToPackageRoot)
                                        + " found at " + res);
                    }
                }
            }
        }
        if (!foundMasked) {
            return classfileResources;
        }
        // Remove masked (duplicated) paths
        final List<Resource> maskedClassfileResources = new ArrayList<>();
        for (int i = 0; i < classfileResources.size(); i++) {
            if (!masked.get(i)) {
                maskedClassfileResources.add(classfileResources.get(i));
            }
        }
        return maskedClassfileResources;
    }

    /** Implement masking for classfiles defined more than once on the classpath. */
    void maskClassfiles(final int classpathIdx, final HashSet<String> whitelistedClasspathRelativePathsFound,
            final HashSet<String> nonBlacklistedClasspathRelativePathsFound, final LogNode maskLog) {
        // Mask whitelisted classfile resources
        whitelistedClassfileResources = ClasspathElement.maskClassfiles(scanSpec, classpathIdx,
                whitelistedClassfileResources, whitelistedClasspathRelativePathsFound, maskLog);
    }

    // -------------------------------------------------------------------------------------------------------------

    /** Add a resource discovered during the scan. */
    protected void addWhitelistedResource(final Resource resource, final ScanSpecPathMatch parentMatchStatus,
            final LogNode log) {
        final String path = resource.getPath();
        final boolean isClassFile = FileUtils.isClassfile(path);
        if (isClassFile) {
            if (scanSpec.enableClassInfo && !scanSpec.classfilePathWhiteBlackList.isBlacklisted(path)) {
                // ClassInfo is enabled, and found a whitelisted classfile
                whitelistedClassfileResources.add(resource);
                if (log != null) {
                    log.log(path,
                            "Found whitelisted classfile: " + path
                                    + (path.equals(resource.getPathRelativeToClasspathElement()) ? ""
                                            : " ; full path: " + resource.getPathRelativeToClasspathElement()));
                }
            }
        } else {
            if (log != null) {
                log.log(path,
                        "Found whitelisted resource:  " + path
                                + (path.equals(resource.getPathRelativeToClasspathElement()) ? ""
                                        : " ; full path: " + resource.getPathRelativeToClasspathElement()));
            }
        }
        whitelistedResources.add(resource);
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Determine if this classpath element is valid. If it is not valid, sets skipClasspathElement. For
     * {@link ClasspathElementZip}, may also open or extract inner jars, and also causes jarfile manifests to be
     * read to look for Class-Path entries. If nested jars or Class-Path entries are found, they are added to the
     * work queue. This method is only run once per classpath element, from a single thread.
     */
    abstract void open(final WorkQueue<RawClasspathElementWorkUnit> workQueue, final LogNode log);

    /**
     * Scan paths in the classpath element for whitelist/blacklist criteria, creating Resource objects for
     * whitelisted and non-blacklisted resources and classfiles.
     */
    abstract void scanPaths(final LogNode log);

    /**
     * @param relativePath
     *            The relative path of the {@link Resource} to return. Path should have already be sanitized by
     *            calling {@link FileUtils#sanitizeEntryPath(String, boolean)}, or by providing a path that is
     *            already sanitized (i.e. doesn't start or end with "/", doesn't contain "/../" or "/./", etc.).
     * @return The {@link Resource} for the given relative path, or null if relativePath does not exist in this
     *         classpath element.
     */
    abstract Resource getResource(final String relativePath);

    /** Get the URL string for this classpath element. */
    abstract URI getURI();
}
