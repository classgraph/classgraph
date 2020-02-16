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
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Queue;
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
abstract class ClasspathElement {
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

    /** True if classpath element contains a specifically-whitelisted resource path. */
    boolean containsSpecificallyWhitelistedClasspathElementResourcePath;

    /**
     * The child classpath elements, keyed by the order of the child classpath element within the Class-Path entry
     * of the manifest file the child classpath element was listed in (or the position of the file within the sorted
     * entries of a lib directory).
     */
    final Queue<Entry<Integer, ClasspathElement>> childClasspathElementsIndexed = new ConcurrentLinkedQueue<>();

    /**
     * The child classpath elements, ordered by order within the parent classpath element.
     */
    List<ClasspathElement> childClasspathElementsOrdered;

    /**
     * Resources found within this classpath element that were whitelisted and not blacklisted. (Only written by one
     * thread, so doesn't need to be a concurrent list.)
     */
    protected final List<Resource> whitelistedResources = new ArrayList<>();

    /**
     * The list of all classfiles found within this classpath element that were whitelisted and not blacklisted.
     * (Only written by one thread, so doesn't need to be a concurrent list.)
     */
    protected List<Resource> whitelistedClassfileResources = new ArrayList<>();

    /** The map from File to last modified timestamp, if scanFiles is true. */
    protected final Map<File, Long> fileToLastModified = new ConcurrentHashMap<>();

    /** Flag to ensure classpath element is only scanned once. */
    protected final AtomicBoolean scanned = new AtomicBoolean(false);

    /** The classloader that this classpath element was obtained from. */
    protected ClassLoader classLoader;

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
     * @param classLoader
     *            the classloader
     * @param scanSpec
     *            the scan spec
     */
    ClasspathElement(final ClassLoader classLoader, final ScanSpec scanSpec) {
        this.classLoader = classLoader;
        this.scanSpec = scanSpec;
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
        return whitelistedClassfileResources == null ? 0 : whitelistedClassfileResources.size();
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Check relativePath against classpathElementResourcePathWhiteBlackList.
     *
     * @param relativePath
     *            the relative path
     * @param log
     *            the log
     */
    protected void checkResourcePathWhiteBlackList(final String relativePath, final LogNode log) {
        // Whitelist/blacklist classpath elements based on file resource paths
        if (!scanSpec.classpathElementResourcePathWhiteBlackList.whitelistAndBlacklistAreEmpty()) {
            if (scanSpec.classpathElementResourcePathWhiteBlackList.isBlacklisted(relativePath)) {
                if (log != null) {
                    log.log("Reached blacklisted classpath element resource path, stopping scanning: "
                            + relativePath);
                }
                skipClasspathElement = true;
                return;
            }
            if (scanSpec.classpathElementResourcePathWhiteBlackList.isSpecificallyWhitelisted(relativePath)) {
                if (log != null) {
                    log.log("Reached specifically whitelisted classpath element resource path: " + relativePath);
                }
                containsSpecificallyWhitelistedClasspathElementResourcePath = true;
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
        final List<Resource> whitelistedClassfileResourcesFiltered = new ArrayList<>(
                whitelistedClassfileResources.size());
        boolean foundMasked = false;
        for (final Resource res : whitelistedClassfileResources) {
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
                whitelistedClassfileResourcesFiltered.add(res);
            }
        }
        if (foundMasked) {
            // Remove masked (duplicated) paths. N.B. this replaces the concurrent collection with a non-concurrent
            // collection, but this is the last time the collection is changed during a scan, and this method is
            // run from a single thread.
            whitelistedClassfileResources = whitelistedClassfileResourcesFiltered;
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
    protected void addWhitelistedResource(final Resource resource, final ScanSpecPathMatch parentMatchStatus,
            final boolean isClassfileOnly, final LogNode log) {
        final String path = resource.getPath();
        final boolean isClassFile = FileUtils.isClassfile(path);
        boolean isWhitelisted = false;
        if (isClassFile) {
            // Check classfile scanning is enabled, and classfile is not specifically blacklisted
            if (scanSpec.enableClassInfo && !scanSpec.classfilePathWhiteBlackList.isBlacklisted(path)) {
                // ClassInfo is enabled, and found a whitelisted classfile
                whitelistedClassfileResources.add(resource);
                isWhitelisted = true;
            }
        } else {
            // Resources are always whitelisted if found in whitelisted directories
            isWhitelisted = true;
        }

        if (!isClassfileOnly) {
            // Add resource to list of whitelisted resources, whether for a classfile or non-classfile resource
            whitelistedResources.add(resource);
        }

        // Write to log if enabled, and as long as classfile scanning is not disabled, and this is not
        // a blacklisted classfile
        if (log != null && isWhitelisted) {
            final String type = isClassFile ? "classfile" : "resource";
            String logStr;
            switch (parentMatchStatus) {
            case HAS_WHITELISTED_PATH_PREFIX:
                logStr = "Found " + type + " within subpackage of whitelisted package: ";
                break;
            case AT_WHITELISTED_PATH:
                logStr = "Found " + type + " within whitelisted package: ";
                break;
            case AT_WHITELISTED_CLASS_PACKAGE:
                logStr = "Found specifically-whitelisted " + type + ": ";
                break;
            default:
                logStr = "Found whitelisted " + type + ": ";
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
            if (whitelistedResources.isEmpty() && whitelistedClassfileResources.isEmpty()) {
                log.log(scanSpec.enableClassInfo ? "No whitelisted classfiles or resources found"
                        : "Classfile scanning is disabled, and no whitelisted resources found");
            } else if (whitelistedResources.isEmpty()) {
                log.log("No whitelisted resources found");
            } else if (whitelistedClassfileResources.isEmpty()) {
                log.log(scanSpec.enableClassInfo ? "No whitelisted classfiles found"
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
     * Scan paths in the classpath element for whitelist/blacklist criteria, creating Resource objects for
     * whitelisted and non-blacklisted resources and classfiles.
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
