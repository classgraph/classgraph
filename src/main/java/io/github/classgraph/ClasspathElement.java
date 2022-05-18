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
import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.classgraph.Scanner.ClasspathEntryWorkUnit;
import nonapi.io.github.classgraph.concurrency.WorkQueue;
import nonapi.io.github.classgraph.scanspec.ScanSpec;
import nonapi.io.github.classgraph.scanspec.ScanSpec.ScanSpecPathMatch;
import nonapi.io.github.classgraph.utils.FileUtils;
import nonapi.io.github.classgraph.utils.JarUtils;
import nonapi.io.github.classgraph.utils.LogNode;

/** A classpath element (a directory or jarfile on the classpath). */
abstract class ClasspathElement implements Comparable<ClasspathElement> {
    /** The index of the classpath element within the classpath or module path. */
    int classpathElementIdx;

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

    /** True if classpath element contains a specifically-accepted resource path. */
    boolean containsSpecificallyAcceptedClasspathElementResourcePath;

    /**
     * The index of the classpath element within the parent classpath element (e.g. for classpath elements added via
     * a Class-Path entry in the manifest). Set to -1 initially in case the same ClasspathElement is present twice
     * in the classpath, as a child of different parent ClasspathElements.
     */
    final int classpathElementIdxWithinParent;

    /**
     * The child classpath elements, keyed by the order of the child classpath element within the Class-Path entry
     * of the manifest file the child classpath element was listed in (or the position of the file within the sorted
     * entries of a lib directory).
     */
    Collection<ClasspathElement> childClasspathElements = new ConcurrentLinkedQueue<>();

    /**
     * Resources found within this classpath element that were accepted and not rejected. (Only written by one
     * thread, so doesn't need to be a concurrent list.)
     */
    protected final List<Resource> acceptedResources = new ArrayList<>();

    /**
     * The list of all classfiles found within this classpath element that were accepted and not rejected. (Only
     * written by one thread, so doesn't need to be a concurrent list.)
     */
    protected List<Resource> acceptedClassfileResources = new ArrayList<>();

    /** The map from File to last modified timestamp, if scanFiles is true. */
    protected final Map<File, Long> fileToLastModified = new ConcurrentHashMap<>();

    /** Flag to ensure classpath element is only scanned once. */
    protected final AtomicBoolean scanned = new AtomicBoolean(false);

    /** The classloader that this classpath element was obtained from. */
    protected ClassLoader classLoader;

    /** The package root within the jarfile or Path. */
    protected String packageRootPrefix;

    /**
     * The name of the module from the {@code module-info.class} module descriptor, if one is present in the root of
     * the classpath element.
     */
    String moduleNameFromModuleDescriptor;

    /** The scan spec. */
    final ScanSpec scanSpec;

    // -------------------------------------------------------------------------------------------------------------

    /**
     * A classpath element.
     *
     * @param workUnit
     *            the work unit
     * @param scanSpec
     *            the scan spec
     */
    ClasspathElement(final ClasspathEntryWorkUnit workUnit, final ScanSpec scanSpec) {
        this.packageRootPrefix = workUnit.packageRootPrefix;
        this.classpathElementIdxWithinParent = workUnit.classpathElementIdxWithinParent;
        this.classLoader = workUnit.classLoader;
        this.scanSpec = scanSpec;
    }

    // -------------------------------------------------------------------------------------------------------------

    /** Sort in increasing order of classpathElementIdxWithinParent. */
    @Override
    public int compareTo(final ClasspathElement other) {
        return this.classpathElementIdxWithinParent - other.classpathElementIdxWithinParent;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Get the ClassLoader the classpath element was obtained from.
     *
     * @return the classloader
     */
    ClassLoader getClassLoader() {
        return classLoader;
    }

    /**
     * Get the number of classfile matches.
     *
     * @return the num classfile matches
     */
    int getNumClassfileMatches() {
        return acceptedClassfileResources == null ? 0 : acceptedClassfileResources.size();
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Check relativePath against classpathElementResourcePathAcceptReject.
     *
     * @param relativePath
     *            the relative path
     * @param log
     *            the log
     */
    protected void checkResourcePathAcceptReject(final String relativePath, final LogNode log) {
        // Accept/reject classpath elements based on file resource paths
        if (!scanSpec.classpathElementResourcePathAcceptReject.acceptAndRejectAreEmpty()) {
            if (scanSpec.classpathElementResourcePathAcceptReject.isRejected(relativePath)) {
                if (log != null) {
                    log.log("Reached rejected classpath element resource path, stopping scanning: " + relativePath);
                }
                skipClasspathElement = true;
                return;
            }
            if (scanSpec.classpathElementResourcePathAcceptReject.isSpecificallyAccepted(relativePath)) {
                if (log != null) {
                    log.log("Reached specifically accepted classpath element resource path: " + relativePath);
                }
                containsSpecificallyAcceptedClasspathElementResourcePath = true;
            }
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Apply relative path masking within this classpath resource -- remove relative paths that were found in an
     * earlier classpath element.
     *
     * @param classpathIdx
     *            the classpath index
     * @param classpathRelativePathsFound
     *            the classpath relative paths found
     * @param log
     *            the log
     */
    void maskClassfiles(final int classpathIdx, final Set<String> classpathRelativePathsFound, final LogNode log) {
        // Find relative paths that occur more than once in the classpath / module path.
        // Usually duplicate relative paths occur only between classpath / module path elements, not within,
        // but actually there is no restriction for paths within a zipfile to be unique, and in fact
        // zipfiles in the wild do contain the same classfiles multiple times with the same exact path,
        // e.g.: xmlbeans-2.6.0.jar!org/apache/xmlbeans/xml/stream/Location.class
        final List<Resource> acceptedClassfileResourcesFiltered = new ArrayList<>(
                acceptedClassfileResources.size());
        boolean foundMasked = false;
        for (final Resource res : acceptedClassfileResources) {
            final String pathRelativeToPackageRoot = res.getPath();
            // Don't mask module-info.class or package-info.class, these are read for every module/package,
            // and they don't result in a ClassInfo object, so there will be no duplicate ClassInfo objects
            // created, even if they are encountered multiple times. Instead, any annotations on modules or
            // packages are merged into the appropriate ModuleInfo / PackageInfo object.
            if (!pathRelativeToPackageRoot.equals("module-info.class")
                    && !pathRelativeToPackageRoot.equals("package-info.class")
                    && !pathRelativeToPackageRoot.endsWith("/package-info.class")
                    // Check if pathRelativeToPackageRoot has been seen before
                    && !classpathRelativePathsFound.add(pathRelativeToPackageRoot)) {
                // This relative path has been encountered more than once;
                // mask the second and subsequent occurrences of the path
                foundMasked = true;
                if (log != null) {
                    log.log(String.format("%06d-1", classpathIdx), "Ignoring duplicate (masked) class "
                            + JarUtils.classfilePathToClassName(pathRelativeToPackageRoot) + " found at " + res);
                }
            } else {
                acceptedClassfileResourcesFiltered.add(res);
            }
        }
        if (foundMasked) {
            // Remove masked (duplicated) paths. N.B. this replaces the concurrent collection with a non-concurrent
            // collection, but this is the last time the collection is changed during a scan, and this method is
            // run from a single thread.
            acceptedClassfileResources = acceptedClassfileResourcesFiltered;
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Add a resource discovered during the scan.
     *
     * @param resource
     *            the resource
     * @param parentMatchStatus
     *            the parent match status
     * @param isClassfileOnly
     *            if true, only add the resource to the list of classfile resources, not to the list of
     *            non-classfile resources
     * @param log
     *            the log
     */
    protected void addAcceptedResource(final Resource resource, final ScanSpecPathMatch parentMatchStatus,
            final boolean isClassfileOnly, final LogNode log) {
        final String path = resource.getPath();
        final boolean isClassFile = FileUtils.isClassfile(path);
        boolean isAccepted = false;
        if (isClassFile) {
            // Check classfile scanning is enabled, and classfile is not specifically rejected
            if (scanSpec.enableClassInfo && !scanSpec.classfilePathAcceptReject.isRejected(path)) {
                // ClassInfo is enabled, and found an accepted classfile
                acceptedClassfileResources.add(resource);
                isAccepted = true;
            }
        } else {
            // Resources are always accepted if found in accepted directories
            isAccepted = true;
        }

        if (!isClassfileOnly) {
            // Add resource to list of accepted resources, whether for a classfile or non-classfile resource
            acceptedResources.add(resource);
        }

        // Write to log if enabled, and as long as classfile scanning is not disabled, and this is not
        // a rejected classfile
        if (log != null && isAccepted) {
            final String type = isClassFile ? "classfile" : "resource";
            String logStr;
            switch (parentMatchStatus) {
            case HAS_ACCEPTED_PATH_PREFIX:
                logStr = "Found " + type + " within subpackage of accepted package: ";
                break;
            case AT_ACCEPTED_PATH:
                logStr = "Found " + type + " within accepted package: ";
                break;
            case AT_ACCEPTED_CLASS_PACKAGE:
                logStr = "Found specifically-accepted " + type + ": ";
                break;
            default:
                logStr = "Found accepted " + type + ": ";
                break;
            }
            // Precede log entry sort key with "0:file:" so that file entries come before dir entries for
            // ClasspathElementDir classpath elements
            resource.scanLog = log.log("0:" + path,
                    logStr + path + (path.equals(resource.getPathRelativeToClasspathElement()) ? ""
                            : " ; full path: " + resource.getPathRelativeToClasspathElement()));
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Called by scanPaths() after scan completion.
     *
     * @param log
     *            the log
     */
    protected void finishScanPaths(final LogNode log) {
        if (log != null) {
            if (acceptedResources.isEmpty() && acceptedClassfileResources.isEmpty()) {
                log.log(scanSpec.enableClassInfo ? "No accepted classfiles or resources found"
                        : "Classfile scanning is disabled, and no accepted resources found");
            } else if (acceptedResources.isEmpty()) {
                log.log("No accepted resources found");
            } else if (acceptedClassfileResources.isEmpty()) {
                log.log(scanSpec.enableClassInfo ? "No accepted classfiles found"
                        : "Classfile scanning is disabled");
            }
        }
        if (log != null) {
            log.addElapsedTime();
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Write entries to log in classpath / module path order.
     *
     * @param classpathElementIdx
     *            the classpath element idx
     * @param msg
     *            the log message
     * @param log
     *            the log
     * @return the new {@link LogNode}
     */
    protected LogNode log(final int classpathElementIdx, final String msg, final LogNode log) {
        return log.log(String.format("%07d", classpathElementIdx), msg);
    }

    /**
     * Write entries to log in classpath / module path order.
     *
     * @param classpathElementIdx
     *            the classpath element idx
     * @param msg
     *            the log message
     * @param t
     *            The exception that was thrown
     * @param log
     *            the log
     * @return the new {@link LogNode}
     */
    protected LogNode log(final int classpathElementIdx, final String msg, final Throwable t, final LogNode log) {
        return log.log(String.format("%07d", classpathElementIdx), msg, t);
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Determine if this classpath element is valid. If it is not valid, sets skipClasspathElement. For
     * {@link ClasspathElementZip}, may also open or extract inner jars, and also causes jarfile manifests to be
     * read to look for Class-Path entries. If nested jars or Class-Path entries are found, they are added to the
     * work queue. This method is only run once per classpath element, from a single thread.
     *
     * @param workQueue
     *            the work queue
     * @param log
     *            the log
     * @throws InterruptedException
     *             if the thread was interrupted while trying to open the classpath element.
     */
    abstract void open(final WorkQueue<ClasspathEntryWorkUnit> workQueue, final LogNode log)
            throws InterruptedException;

    /**
     * Scan paths in the classpath element for accept/reject criteria, creating Resource objects for accepted and
     * non-rejected resources and classfiles.
     *
     * @param log
     *            the log
     */
    abstract void scanPaths(final LogNode log);

    /**
     * Get the {@link Resource} for a given relative path.
     *
     * @param relativePath
     *            The relative path of the {@link Resource} to return. Path should have already be sanitized by
     *            calling {@link FileUtils#sanitizeEntryPath(String, boolean)}, or by providing a path that is
     *            already sanitized (i.e. doesn't start or end with "/", doesn't contain "/../" or "/./", etc.).
     * @return The {@link Resource} for the given relative path, or null if relativePath does not exist in this
     *         classpath element.
     */
    abstract Resource getResource(final String relativePath);

    /**
     * Get the URI for this classpath element.
     *
     * @return the URI for the classpath element.
     */
    abstract URI getURI();

    /**
     * Get the URI for this classpath element, and the URIs for any automatic nested package prefixes (e.g.
     * "spring-boot.jar/BOOT-INF/classes") within this jarfile.
     *
     * @return the URI for the classpath element.
     */
    abstract List<URI> getAllURIs();

    /**
     * Get the file for this classpath element, or null if this is a module with a "jrt:" URI.
     *
     * @return the file for the classpath element.
     */
    abstract File getFile();

    /**
     * Get the name of this classpath element's module, or null if there is no module name.
     *
     * @return the module name
     */
    abstract String getModuleName();
}
