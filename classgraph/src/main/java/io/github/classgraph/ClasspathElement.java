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
import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

import org.jspecify.annotations.Nullable;

import io.github.classgraph.ScanSpec.ScanSpecPathMatch;
import io.github.classgraph.Scanner.ClasspathEntryWorkUnit;
import io.github.classgraph.base.LogNode;
import io.github.classgraph.base.internal.path.FileUtils;
import io.github.classgraph.base.internal.path.PathSyntax;
import io.github.classgraph.vfs.Vfs;
import io.github.classgraph.vfs.VfsSpec;

/** A classpath element (a directory or jarfile on the classpath). */
abstract sealed class ClasspathElement implements Comparable<ClasspathElement>
        permits ClasspathElementDir, ClasspathElementZip, ClasspathElementModule {
    /** The position of the classpath element in the final classpath order, set after opening. */
    int classpathElementIdx;

    /**
     * Resolved relative path prefixes (with trailing {@code '/'}) of nested directory classpath elements within
     * this classpath element, or null if none.
     */
    @Nullable
    List<String> nestedClasspathRootPrefixes;

    /**
     * True if there was an exception when trying to open this classpath element (e.g. a corrupt ZipFile).
     */
    boolean skipClasspathElement;

    /** True if classpath element contains a specifically-accepted resource path. */
    boolean containsSpecificallyAcceptedClasspathElementResourcePath;

    /** True if classpath element contains a rejected resource path, so the whole element must not be scanned. */
    boolean containsRejectedClasspathElementResourcePath;

    /**
     * True if this classpath element is referenced directly from the classpath rather than only from a manifest
     * entry or lib dir of another classpath element.
     */
    private boolean isToplevel;

    /**
     * The index of the earliest reference to this classpath element within the toplevel classpath (if
     * {@link #isToplevel} is true) or within its parent classpath element.
     */
    private int classpathElementIdxWithinParent = Integer.MAX_VALUE;

    /**
     * A reference from a parent classpath element to a child classpath element from its {@code Class-Path} manifest
     * entry or lib directory.
     *
     * @param idxWithinParent
     *            the index of the child within the parent's {@code Class-Path} manifest entry or lib directory
     * @param classpathElement
     *            the child classpath element
     */
    // #810
    record ChildClasspathElement(int idxWithinParent,
            ClasspathElement classpathElement) implements Comparable<ChildClasspathElement> {
        @Override
        public int compareTo(final ChildClasspathElement other) {
            // Each entry of a Class-Path manifest entry or lib directory has its own index, so two different child
            // classpath elements of the same parent can never tie
            return Integer.compare(this.idxWithinParent, other.idxWithinParent);
        }
    }

    /**
     * Child classpath elements referenced by this classpath element's {@code Class-Path} manifest entry or lib
     * directory.
     */
    Collection<ChildClasspathElement> childClasspathElements = new ConcurrentLinkedQueue<>();

    /** Accepted resources found within this classpath element. */
    protected final List<Resource> acceptedResources = new ArrayList<>();

    /** Accepted classfile resources found within this classpath element. */
    protected List<Resource> acceptedClassfileResources = new ArrayList<>();

    /** The map from File to last modified timestamp, if scanFiles is true. */
    protected final Map<File, Long> fileToLastModified = new ConcurrentHashMap<>();

    /** Flag to ensure classpath element is only scanned once. */
    protected final AtomicBoolean scanned = new AtomicBoolean(false);

    /**
     * The string representation of the classloader that this classpath element was obtained from, or null if
     * unknown.
     */
    protected @Nullable String classLoaderStr;

    /** The package root within the jarfile or Path. */
    protected String packageRootPrefix;

    /**
     * Automatic package root prefixes (e.g. {@code "BOOT-INF/classes/"}) to search for within this classpath
     * element.
     */
    protected final List<String> packageRootPrefixes;

    /**
     * Lib directory prefixes (e.g. {@code "BOOT-INF/lib/"}) to search for nested jarfiles within this classpath
     * element.
     */
    protected final List<String> libDirPrefixes;

    /**
     * The name of the module from the {@code module-info.class} module descriptor, if one is present in the root of
     * the classpath element.
     */
    @Nullable
    String moduleNameFromModuleDescriptor;

    /** The module name from the {@code Automatic-Module-Name} manifest attribute, if present. */
    @Nullable
    String moduleNameFromManifestFile;

    /** The scan spec. */
    final ScanSpec scanSpec;

    /** The virtual filesystem configuration for this classpath element. */
    final VfsSpec vfsSpec;

    /** The {@link ScanResult} that owns this classpath element, set after scanning completes. */
    protected @Nullable ScanResult scanResult;

    // -------------------------------------------------------------------------------------------------------------

    /**
     * A classpath element.
     *
     * @param classLoaderStr
     *            the string form of the classloader the classpath element was obtained from, or null if unknown
     * @param packageRootPrefix
     *            the package root prefix (e.g. {@code "BOOT-INF/classes/"}), or {@code ""} if the classpath element
     *            is not a package root
     * @param packageRootPrefixes
     *            the automatic package root prefixes to look for within this classpath element
     * @param libDirPrefixes
     *            the lib dirs whose jarfiles are to be added to the classpath, within this classpath element
     * @param vfs
     *            the virtual filesystem the classpath element is read through
     * @param scanSpec
     *            the scan spec
     */
    ClasspathElement(final @Nullable String classLoaderStr, final String packageRootPrefix,
            final List<String> packageRootPrefixes, final List<String> libDirPrefixes, final Vfs vfs,
            final ScanSpec scanSpec) {
        this.classLoaderStr = classLoaderStr;
        this.packageRootPrefix = packageRootPrefix;
        this.packageRootPrefixes = packageRootPrefixes;
        this.libDirPrefixes = libDirPrefixes;
        this.scanSpec = scanSpec;
        this.vfsSpec = vfs.getVfsSpec();
    }

    /**
     * Schedules child classpath elements (package roots, nested lib jars, and manifest {@code Class-Path} and
     * {@code Bundle-ClassPath} entries) for opening in order.
     */
    final class ChildClasspathElementScheduler {
        /** The work queue to add child classpath elements to. */
        private final WorkQueue<ClasspathEntryWorkUnit> workQueue;
        /** Child classpath entries that have already been scheduled. */
        private final Set<Object> alreadyScheduled = new HashSet<>();
        /** The order of the next child classpath element within this classpath element. */
        private int childClasspathEntryIdx;

        /**
         * Constructor.
         *
         * @param workQueue
         *            the work queue to add child classpath elements to
         * @param ownClasspathEntry
         *            the classpath entry of this classpath element, which is never scheduled as its own child
         */
        ChildClasspathElementScheduler(final WorkQueue<ClasspathEntryWorkUnit> workQueue,
                final Object ownClasspathEntry) {
            this.workQueue = workQueue;
            alreadyScheduled.add(ownClasspathEntry);
        }

        /**
         * Schedule a child classpath element for opening, unless it has already been scheduled.
         *
         * @param childClasspathEntry
         *            the classpath entry of the child classpath element (a {@link String} path or a
         *            {@link java.nio.file.Path})
         * @param childPackageRootPrefix
         *            the package root prefix of the child classpath element, or {@code ""} if it is not a package
         *            root
         */
        void schedule(final Object childClasspathEntry, final String childPackageRootPrefix) {
            if (alreadyScheduled.add(childClasspathEntry)) {
                workQueue.addWorkUnit(new ClasspathEntryWorkUnit(childClasspathEntry, getClassLoaderString(),
                        /* parentClasspathElement = */ ClasspathElement.this, childClasspathEntryIdx++,
                        childPackageRootPrefix, packageRootPrefixes, libDirPrefixes));
            }
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Used to set the {@link ScanResult} after the scan is complete.
     *
     * @param scanResult
     *            the {@link ScanResult}
     */
    void setScanResult(final ScanResult scanResult) {
        this.scanResult = scanResult;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Record a reference to this classpath element from a work unit, updating its ordering index and classloader if
     * this reference takes precedence (toplevel references beat child references, and lower indices win ties).
     *
     * @param isToplevelRef
     *            true if referenced directly from the toplevel classpath, false if referenced from a parent
     *            classpath element
     * @param idx
     *            the index of the reference within the classpath or parent classpath element
     * @param classLoaderStr
     *            the string representation of the classloader, or null if unknown
     */
    // #810
    synchronized void addReference(final boolean isToplevelRef, final int idx,
            final @Nullable String classLoaderStr) {
        if (isToplevelRef && !isToplevel) {
            // A toplevel reference always beats a reference from a parent classpath element
            isToplevel = true;
            classpathElementIdxWithinParent = idx;
            this.classLoaderStr = classLoaderStr;
        } else if (isToplevelRef == isToplevel && idx < classpathElementIdxWithinParent) {
            // Otherwise the earliest reference of the same kind wins
            classpathElementIdxWithinParent = idx;
            this.classLoaderStr = classLoaderStr;
        }
    }

    /** Compare toplevel classpath elements by their classpath index. */
    @Override
    public int compareTo(final ClasspathElement other) {
        return Integer.compare(this.classpathElementIdxWithinParent, other.classpathElementIdxWithinParent);
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Get the string form of the ClassLoader the classpath element was obtained from.
     *
     * @return the string form of the classloader, or null if unknown
     */
    @Nullable
    String getClassLoaderString() {
        return classLoaderStr;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Check relativePath against classpathElementResourcePathAcceptReject.
     *
     * @param relativePath
     *            the relative path
     * @param log
     *            the log node, or null to skip logging
     * @return true if scanning of this classpath element should continue
     */
    protected boolean checkResourcePathAcceptReject(final String relativePath, final @Nullable LogNode log) {
        // Accept/reject classpath elements based on file resource paths
        if (!scanSpec.classpathElementResourcePathAcceptReject.acceptAndRejectAreEmpty()) {
            if (scanSpec.classpathElementResourcePathAcceptReject.isRejected(relativePath)) {
                if (log != null) {
                    log.log("Reached rejected classpath element resource path, stopping scanning: " + relativePath);
                }
                containsRejectedClasspathElementResourcePath = true;
                return false;
            }
            if (scanSpec.classpathElementResourcePathAcceptReject.isSpecificallyAccepted(relativePath)) {
                if (log != null) {
                    log.log("Reached specifically accepted classpath element resource path: " + relativePath);
                }
                containsSpecificallyAcceptedClasspathElementResourcePath = true;
            }
        }
        return true;
    }

    /**
     * Check whether a relative path is within a multi-release versioned section that should be ignored.
     *
     * @param relativePath
     *            the path of an entry, relative to the root of the classpath element.
     * @return true if the path is within a versioned section that should be ignored.
     */
    protected boolean isIgnoredVersionedPath(final String relativePath) {
        return vfsSpec.isIgnoredVersionedPath(relativePath);
    }

    /**
     * Return the nested classpath root prefix that {@code relativePath} lies within, or null if none.
     *
     * @param relativePath
     *            the path of an entry, relative to the root of this classpath element.
     * @return the nested classpath root, with a trailing {@code '/'}, or null if the path is not within one.
     */
    @Nullable
    String nestedClasspathRootOf(final String relativePath) {
        if (nestedClasspathRootPrefixes != null) {
            // This is O(mn), which is inefficient, but the number of nested classpath roots should be small
            for (final String nestedClasspathRoot : nestedClasspathRootPrefixes) {
                if (relativePath.startsWith(nestedClasspathRoot)) {
                    return nestedClasspathRoot;
                }
            }
        }
        return null;
    }

    /**
     * Check whether {@code relativePath} is in an ignored multi-release versioned section or within a nested
     * classpath root.
     *
     * @param relativePath
     *            the path of an entry, relative to the root of this classpath element.
     * @return true if the scan skips the path.
     */
    boolean isSkippedPath(final String relativePath) {
        return isIgnoredVersionedPath(relativePath) || nestedClasspathRootOf(relativePath) != null;
    }

    /**
     * Check whether a resource is accepted, given the accept/reject match status of its parent directory.
     *
     * @param relativePath
     *            the path of the resource, relative to the package root.
     * @param parentMatchStatus
     *            the accept/reject match status of the parent directory of the resource.
     * @return true if the resource is accepted.
     */
    protected boolean isAcceptedResourcePath(final String relativePath, final ScanSpecPathMatch parentMatchStatus) {
        return parentMatchStatus == ScanSpecPathMatch.HAS_ACCEPTED_PATH_PREFIX
                || parentMatchStatus == ScanSpecPathMatch.AT_ACCEPTED_PATH
                // A directory that only contains specifically-accepted classes accepts only those classes
                || (parentMatchStatus == ScanSpecPathMatch.AT_ACCEPTED_CLASS_PACKAGE
                        && ClassNames.isClassfilePath(relativePath) && scanSpec.classfileIsSpecificallyAccepted(
                                ClassNames.withLowerCaseClassfileExtension(relativePath)));
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Get a canonical file identity key for the file referenced by a resource's {@link URI}, used to detect
     * duplicate resources across classpath elements.
     *
     * @param uri
     *            the URI of a resource.
     * @param canonicalPathCache
     *            a cache of canonical paths.
     * @return a key that is equal for two URIs that refer to the same file, or {@code uri.toString()} if the URI
     *         does not refer to a local file or could not be canonicalized.
     */
    private static String getFileIdentityKey(final URI uri, final Map<String, String> canonicalPathCache) {
        final var uriStr = uri.toString();
        // Find the file part of the URI: "file:<path>", or "jar:file:<path>!/<entry>" (for a jar within a jar,
        // <path> is the path of the outermost jar, and everything from the first "!/" is part of the entry). A
        // "file:" URI names nothing nested, so a "!/" in it, as at the end of a directory named "dir!", is part of
        // the path. A "jar:file:" URI that ClassGraph forms has any such "!/" in <path> percent-encoded as "%21/"
        final var isJarURI = uriStr.startsWith("jar:file:");
        final var filePartStartIdx = uriStr.startsWith("file:") ? 5 : isJarURI ? 9 : -1;
        if (filePartStartIdx < 0) {
            // Not a file URI, so there is no path to canonicalize
            return uriStr;
        }
        final var nestedPathStartIdx = isJarURI ? uriStr.indexOf("!/", filePartStartIdx) : -1;
        final var filePart = nestedPathStartIdx < 0 ? uriStr.substring(filePartStartIdx)
                : uriStr.substring(filePartStartIdx, nestedPathStartIdx);
        final var nestedPath = nestedPathStartIdx < 0 ? "" : uriStr.substring(nestedPathStartIdx);
        try {
            final var file = new File(URI.create("file:" + filePart));
            final var filePath = file.getPath();
            var canonicalPath = canonicalPathCache.get(filePath);
            if (canonicalPath == null) {
                canonicalPath = FileUtils.canonicalize(file).getPath();
                canonicalPathCache.put(filePath, canonicalPath);
            }
            return canonicalPath + nestedPath;
        } catch (final IOException | IllegalArgumentException | SecurityException e) {
            // The file could not be canonicalized -- fall back to comparing the URI itself
            return uriStr;
        }
    }

    /**
     * Get a canonical file identity key for the file or directory referenced by this classpath element.
     *
     * @param canonicalPathCache
     *            a cache of canonical paths.
     * @return a key that is equal for two classpath elements that refer to the same file or directory, or null if
     *         this classpath element has no location URI.
     */
    @Nullable
    String getFileIdentityKey(final Map<String, String> canonicalPathCache) {
        URI uri;
        try {
            uri = getURI();
        } catch (final IllegalStateException e) {
            // A module can have a null location
            return null;
        }
        return getFileIdentityKey(uri, canonicalPathCache);
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Remove any resource that refers to the same underlying file as a resource already found in this or an earlier
     * classpath element.
     *
     * @param classpathIdx
     *            the classpath index
     * @param collidingPaths
     *            the relative paths that occur in more than one place in the classpath / module path
     * @param fileIdentityKeysFound
     *            the file identity keys of the resources found so far
     * @param canonicalPathCache
     *            a cache of canonical paths, shared between classpath elements
     * @param log
     *            the log node, or null to skip logging
     */
    // #704
    void maskDuplicateResources(final int classpathIdx, final Set<String> collidingPaths,
            final Set<String> fileIdentityKeysFound, final Map<String, String> canonicalPathCache,
            final @Nullable LogNode log) {
        final List<Resource> acceptedResourcesFiltered = new ArrayList<>(acceptedResources.size());
        Set<Resource> maskedResources = null;
        for (final Resource res : acceptedResources) {
            var isMasked = false;
            if (collidingPaths.contains(res.getPath())) {
                URI uri;
                try {
                    uri = res.getURI();
                } catch (final RuntimeException e) {
                    // If the URI of a resource cannot be determined, it cannot be compared to any other resource's
                    // URI, so keep the resource rather than masking it
                    uri = null;
                }
                isMasked = uri != null && !fileIdentityKeysFound.add(getFileIdentityKey(uri, canonicalPathCache));
                if (isMasked) {
                    if (maskedResources == null) {
                        // Compare by identity, since Resource#equals compares string representations, and the
                        // masked resource and the resource that masks it are in different classpath elements
                        maskedResources = Collections.newSetFromMap(new IdentityHashMap<>());
                    }
                    maskedResources.add(res);
                    if (log != null) {
                        log.log(String.format(Locale.US, "%06d-1", classpathIdx),
                                "Ignoring duplicate (masked) resource " + res.getPath()
                                        + ", which is the same file as a resource found earlier in the "
                                        + "classpath: " + uri);
                    }
                }
            }
            if (!isMasked) {
                acceptedResourcesFiltered.add(res);
            }
        }
        if (maskedResources != null) {
            acceptedResources.clear();
            acceptedResources.addAll(acceptedResourcesFiltered);
            // A classfile resource is in both lists, so it has to be removed from both
            final List<Resource> acceptedClassfileResourcesFiltered = new ArrayList<>(
                    acceptedClassfileResources.size());
            for (final Resource res : acceptedClassfileResources) {
                if (!maskedResources.contains(res)) {
                    acceptedClassfileResourcesFiltered.add(res);
                }
            }
            acceptedClassfileResources = acceptedClassfileResourcesFiltered;
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Mask classfiles whose relative paths were already found in an earlier classpath element.
     *
     * @param classpathIdx
     *            the classpath index
     * @param classpathRelativePathsFound
     *            the classpath relative paths found
     * @param log
     *            the log node, or null to skip logging
     */
    void maskClassfiles(final int classpathIdx, final Set<String> classpathRelativePathsFound,
            final @Nullable LogNode log) {
        // Find relative paths that occur more than once in the classpath / module path. Usually duplicate relative
        // paths occur only between classpath / module path elements, not within, but actually there is no
        // restriction for paths within a zipfile to be unique, and in fact zipfiles in the wild do contain the same
        // classfiles multiple times with the same exact path, e.g.:
        // xmlbeans-2.6.0.jar!/org/apache/xmlbeans/xml/stream/Location.class
        final List<Resource> acceptedClassfileResourcesFiltered = new ArrayList<>(
                acceptedClassfileResources.size());
        var foundMasked = false;
        for (final Resource res : acceptedClassfileResources) {
            // Two classfiles that differ only in the case of their extension declare the same class, so they mask
            // each other in the same way as two classfiles at the same path
            final var pathRelativeToPackageRoot = ClassNames.withLowerCaseClassfileExtension(res.getPath());
            // Don't mask module-info.class or package-info.class, these are read for every module/package, and they
            // don't result in a ClassInfo object, so there will be no duplicate ClassInfo objects created, even if
            // they are encountered multiple times. Instead, any annotations on modules or packages are merged into
            // the appropriate ModuleInfo / PackageInfo object.
            if (!"module-info.class".equals(pathRelativeToPackageRoot)
                    && !"package-info.class".equals(pathRelativeToPackageRoot)
                    && !pathRelativeToPackageRoot.endsWith("/package-info.class")
                    // Check if pathRelativeToPackageRoot has been seen before
                    && !classpathRelativePathsFound.add(pathRelativeToPackageRoot)) {
                // This relative path has been encountered more than once;
                // mask the second and subsequent occurrences of the path
                foundMasked = true;
                if (log != null) {
                    log.log(String.format(Locale.US, "%06d-1", classpathIdx), "Ignoring duplicate (masked) class "
                            + ClassNames.classfilePathToClassName(pathRelativeToPackageRoot) + " found at " + res);
                }
            } else {
                acceptedClassfileResourcesFiltered.add(res);
            }
        }
        if (foundMasked) {
            // Remove masked (duplicated) paths
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
     *            the log node, or null to skip logging
     */
    protected void addAcceptedResource(final Resource resource, final ScanSpecPathMatch parentMatchStatus,
            final boolean isClassfileOnly, final @Nullable LogNode log) {
        final var path = resource.getPath();
        final var isClassFile = ClassNames.isClassfilePath(path);
        // A classfile is only scheduled for scanning if classfile scanning is enabled, its path is a class name in
        // internal form and is not beneath META-INF, and the classfile is not specifically rejected
        final var addedAsClassfile = isClassFile && scanSpec.enableClassInfo
                && ClassNames.isScannableClassfilePath(path)
                && !scanSpec.classfilePathAcceptReject.isRejected(ClassNames.withLowerCaseClassfileExtension(path));
        if (addedAsClassfile) {
            acceptedClassfileResources.add(resource);
        }

        // Add resource to list of accepted resources, whether for a classfile or non-classfile resource. A
        // classfile that is not scheduled for scanning is still listed as a resource, since it is still a file of
        // the classpath element.
        final var addedAsResource = !isClassfileOnly;
        if (addedAsResource) {
            acceptedResources.add(resource);
        }

        // Write to log if enabled, and as long as the resource was actually added to one of the two lists
        if (log != null && (addedAsClassfile || addedAsResource)) {
            // A classfile that was only added as a resource is logged as a resource, since it is not scanned
            final var type = addedAsClassfile ? "classfile" : "resource";
            final var logStr = switch (parentMatchStatus) {
            case HAS_ACCEPTED_PATH_PREFIX -> "Found " + type + " within subpackage of accepted package: ";
            case AT_ACCEPTED_PATH -> "Found " + type + " within accepted package: ";
            case AT_ACCEPTED_CLASS_PACKAGE -> "Found specifically-accepted " + type + ": ";
            default -> "Found accepted " + type + ": ";
            };
            // Precede log entry sort key with "0:file:" so that file entries come before dir entries for
            // ClasspathElementDir classpath elements
            final var pathRelativeToContainer = resource.getPathRelativeToContainer();
            resource.scanLog = log.log("0:" + path, logStr + path
                    + (path.equals(pathRelativeToContainer) ? "" : " ; full path: " + pathRelativeToContainer));
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Called by scanPaths() after scan completion.
     *
     * @param log
     *            the log node, or null to skip logging
     */
    protected void finishScanPaths(final @Nullable LogNode log) {
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
     *            the index of this element in classpath / module path order
     * @param msg
     *            the log message
     * @param log
     *            the log node to write to
     * @return the new {@link LogNode}
     */
    protected static LogNode log(final int classpathElementIdx, final String msg, final LogNode log) {
        return log.log(String.format(Locale.US, "%07d", classpathElementIdx), msg);
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Open this classpath element, read its manifest metadata, and schedule any child classpath elements (package
     * roots, manifest entries, and lib dir jars) on the work queue. Sets {@link #skipClasspathElement} if invalid.
     *
     * @param workQueue
     *            the work queue
     * @param log
     *            the log node, or null to skip logging
     */
    abstract void open(final WorkQueue<ClasspathEntryWorkUnit> workQueue, final @Nullable LogNode log);

    /**
     * Scan paths in the classpath element for accept/reject criteria, creating Resource objects for accepted and
     * non-rejected resources and classfiles.
     *
     * @param log
     *            the log node, or null to skip logging
     */
    abstract void scanPaths(final @Nullable LogNode log);

    /**
     * Get the {@link Resource} for a given relative path.
     *
     * @param relativePath
     *            The relative path of the {@link Resource} to return. Path should have already been sanitized by
     *            calling {@link PathSyntax#sanitizeRelativePath(String, boolean)}, or by providing a path that is
     *            already sanitized (i.e. doesn't start or end with "/", doesn't contain "/../" or "/./", etc.).
     * @return A new {@link Resource} for the given relative path, or null if relativePath does not exist in this
     *         classpath element, or is a path that the scan skips (see {@link #isSkippedPath(String)}).
     */
    // N.B. this deliberately ignores the accept and reject criteria, because it is how the class graph is extended
    // upwards through classes that the scan did not accept (Classfile#findClassfile, through getResourceDuringScan).
    // Callers that must not report a rejected resource filter the result with ScanSpec#resourcePathIsRejected(String).
    // It returns a new Resource on every call, rather than one of the acceptedResources, so that a caller can open
    // what it returns while the accepted Resource for the same path is open.
    abstract @Nullable Resource getResource(final String relativePath);

    /**
     * Get the {@link Resource} for a given relative path during a scan, caching directory existence checks where
     * applicable.
     *
     * @param relativePath
     *            The relative path of the {@link Resource} to return.
     * @return A new {@link Resource} for the given relative path, or null if relativePath does not exist in this
     *         classpath element, or is a path that the scan skips.
     */
    @Nullable
    Resource getResourceDuringScan(final String relativePath) {
        return getResource(relativePath);
    }

    /** Clear any state cached for {@link #getResourceDuringScan(String)} once classfile scanning has finished. */
    void classfileScanFinished() {
        // Nothing is remembered by default
    }

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
     * Get the file for this classpath element: for a jarfile nested within another jarfile, the outermost jarfile.
     *
     * @return the file for the classpath element, or null if there is none, as described for
     *         {@link Resource#getClasspathElementFile()}.
     */
    abstract @Nullable File getFile();

    /**
     * Get the name of this classpath element's module, or null if there is no module name.
     *
     * @return the module name
     */
    abstract @Nullable String getModuleName();

    /**
     * Get the module name that this classpath element declares, either by its {@code module-info.class} module
     * descriptor, or by the {@code Automatic-Module-Name} attribute of its manifest file.
     *
     * @return the declared module name, or null if this classpath element declares none.
     */
    protected @Nullable String getDeclaredModuleName() {
        if (moduleNameFromModuleDescriptor != null && !moduleNameFromModuleDescriptor.isEmpty()) {
            return moduleNameFromModuleDescriptor;
        }
        return moduleNameFromManifestFile == null || moduleNameFromManifestFile.isEmpty() ? null
                : moduleNameFromManifestFile;
    }
}
