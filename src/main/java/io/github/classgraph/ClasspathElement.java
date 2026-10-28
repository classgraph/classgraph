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
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.classgraph.Scanner.ClasspathEntryWorkUnit;
import nonapi.io.github.classgraph.concurrency.WorkQueue;
import nonapi.io.github.classgraph.fileslice.reader.ClassfileReader;
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
     * If non-null, contains a list of resolved paths (with common prefix removed and a trailing {@code '/'}) for
     * any directory classpath element roots nested inside this classpath element.
     */
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
     * True if this classpath element is referenced directly from the classpath, as opposed to only being
     * referenced from the {@code Class-Path} manifest entry (or lib dir) of another classpath element.
     */
    private boolean isToplevel;

    /**
     * The index of the winning reference to this classpath element: its index within the classpath if
     * {@link #isToplevel} is true, or the earliest index within a referencing parent classpath element.
     */
    private int classpathElementIdxWithinParent = Integer.MAX_VALUE;

    /**
     * A reference from a parent classpath element to a child classpath element named by its {@code Class-Path}
     * manifest entry or found in its lib directory.
     */
    static class ChildClasspathElement implements Comparable<ChildClasspathElement> {
        /**
         * The index of the child classpath element within the {@code Class-Path} manifest entry of the parent
         * classpath element (or within the sorted entries of the parent's lib directory).
         */
        private final int idxWithinParent;

        /** The child classpath element. */
        final ClasspathElement classpathElement;

        /**
         * Constructor.
         *
         * @param idxWithinParent
         *            the index of the child classpath element within the {@code Class-Path} manifest entry of the
         *            parent classpath element (or within the sorted entries of the parent's lib directory)
         * @param classpathElement
         *            the child classpath element
         */
        ChildClasspathElement(final int idxWithinParent, final ClasspathElement classpathElement) {
            this.idxWithinParent = idxWithinParent;
            this.classpathElement = classpathElement;
        }

        @Override
        public int compareTo(final ChildClasspathElement other) {
            // Each entry of a Class-Path manifest entry or lib directory has its own index, so two different child
            // classpath elements of the same parent can never tie
            return Integer.compare(this.idxWithinParent, other.idxWithinParent);
        }
    }

    /**
     * The child classpath elements: the classpath elements named by the {@code Class-Path} manifest entry of this
     * classpath element, or found in its lib directory, each paired with its index within that entry or directory.
     */
    Collection<ChildClasspathElement> childClasspathElements = new ConcurrentLinkedQueue<>();

    /**
     * Resources found within this classpath element that were accepted and not rejected.
     */
    protected final List<Resource> acceptedResources = new ArrayList<>();

    /**
     * The list of all classfiles found within this classpath element that were accepted and not rejected.
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
     * The automatic package root prefixes (e.g. {@code "BOOT-INF/classes/"}) to look for within this classpath
     * element, as declared by the {@code ClassLoaderHandler} that found it. Child classpath elements inherit
     * these, since they come from the same classloader.
     */
    protected final String[] packageRootPrefixes;

    /**
     * The name of the module from the {@code module-info.class} module descriptor, if one is present in the root of
     * the classpath element.
     */
    String moduleNameFromModuleDescriptor;

    /** The scan spec. */
    final ScanSpec scanSpec;

    /** The ScanResult that the classpath element came from. */
    protected ScanResult scanResult;

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
        this.packageRootPrefixes = workUnit.packageRootPrefixes;
        this.classLoader = workUnit.classLoader;
        this.scanSpec = scanSpec;
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
     * Record a reference to this classpath element from a classpath entry work unit. Toplevel classpath references
     * take precedence over references from a parent classpath element, and earlier references take precedence over
     * later ones.
     *
     * @param isToplevelRef
     *            true if the work unit referenced this classpath element from the toplevel classpath, false if it
     *            referenced it from a parent classpath element
     * @param idx
     *            the index of the reference within the classpath, or within the parent classpath element
     * @param classLoader
     *            the classloader that the referencing work unit obtained the classpath entry from
     */
    synchronized void addReference(final boolean isToplevelRef, final int idx, final ClassLoader classLoader) {
        if (isToplevelRef && !isToplevel) {
            // A toplevel reference always beats a reference from a parent classpath element
            isToplevel = true;
            classpathElementIdxWithinParent = idx;
            this.classLoader = classLoader;
        } else if (isToplevelRef == isToplevel && idx < classpathElementIdxWithinParent) {
            // Otherwise the earliest reference of the same kind wins
            classpathElementIdxWithinParent = idx;
            this.classLoader = classLoader;
        }
    }

    /**
     * Sort toplevel classpath elements into their order within the classpath.
     */
    @Override
    public int compareTo(final ClasspathElement other) {
        return Integer.compare(this.classpathElementIdxWithinParent, other.classpathElementIdxWithinParent);
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
     * @return true if scanning of this classpath element should continue
     */
    protected boolean checkResourcePathAcceptReject(final String relativePath, final LogNode log) {
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

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Check whether a candidate package root within a classpath element is a package root rather than a package
     * with the same name as an automatic package root prefix.
     *
     * @param classfileReader
     *            a reader for a classfile found beneath the candidate package root.
     * @param classfileRelativePath
     *            the path of that classfile, relative to the candidate package root.
     * @return null if the candidate is a package root or if the class name could not be read from the classfile;
     *         otherwise the name of the class declared by the classfile.
     */
    static String getClassNameDisprovingPackageRoot(final ClassfileReader classfileReader,
            final String classfileRelativePath) {
        final String className = Classfile.readClassName(classfileReader);
        return className == null || FileUtils.classfilePathMatchesClassName(classfileRelativePath, className) ? null
                : className.replace('/', '.');
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Get a canonicalized key identifying the file that a resource's {@link URI} refers to.
     *
     * @param uri
     *            the URI of a resource.
     * @param canonicalPathCache
     *            a cache of canonical paths.
     * @return a key that is equal for two URIs that refer to the same file, or the URI string if it does not refer
     *         to a file or could not be canonicalized.
     */
    private static String getFileIdentityKey(final URI uri, final Map<String, String> canonicalPathCache) {
        final String uriStr = uri.toString();
        // Find the file part of the URI: "file:<path>", or "jar:file:<path>!/<entry>" (for a jar within a jar,
        // <path> is the path of the outermost jar, and everything from the first "!/" is part of the entry)
        final int filePartStartIdx = uriStr.startsWith("file:") ? 5 : uriStr.startsWith("jar:file:") ? 9 : -1;
        if (filePartStartIdx < 0) {
            // Not a file URI, so there is no path to canonicalize
            return uriStr;
        }
        final int nestedPathStartIdx = uriStr.indexOf("!/", filePartStartIdx);
        final String filePart = nestedPathStartIdx < 0 ? uriStr.substring(filePartStartIdx)
                : uriStr.substring(filePartStartIdx, nestedPathStartIdx);
        final String nestedPath = nestedPathStartIdx < 0 ? "" : uriStr.substring(nestedPathStartIdx);
        try {
            final File file = new File(URI.create("file:" + filePart));
            final String filePath = file.getPath();
            String canonicalPath = canonicalPathCache.get(filePath);
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
     * Get a key that identifies the file or directory that this classpath element refers to.
     *
     * @param canonicalPathCache
     *            a cache of canonical paths.
     * @return a key that is equal for two classpath elements that refer to the same file or directory (see
     *         {@link #getFileIdentityKey(URI, Map)}), or null if this classpath element has no location URI.
     */
    String getFileIdentityKey(final Map<String, String> canonicalPathCache) {
        URI uri;
        try {
            uri = getURI();
        } catch (final IllegalArgumentException e) {
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
     *            the file identity keys of the resources found so far (see
     *            {@link #getFileIdentityKey(URI, Map)})
     * @param canonicalPathCache
     *            a cache of canonical paths, shared between classpath elements
     * @param log
     *            the log
     */
    void maskDuplicateResources(final int classpathIdx, final Set<String> collidingPaths,
            final Set<String> fileIdentityKeysFound, final Map<String, String> canonicalPathCache,
            final LogNode log) {
        final List<Resource> acceptedResourcesFiltered = new ArrayList<>(acceptedResources.size());
        Set<Resource> maskedResources = null;
        for (final Resource res : acceptedResources) {
            boolean isMasked = false;
            if (collidingPaths.contains(res.getPath())) {
                URI uri;
                try {
                    uri = res.getURI();
                } catch (final RuntimeException e) {
                    // If the URI of a resource cannot be determined, it cannot be compared to any other
                    // resource's URI, so keep the resource rather than masking it
                    uri = null;
                }
                isMasked = uri != null
                        && !fileIdentityKeysFound.add(getFileIdentityKey(uri, canonicalPathCache));
                if (isMasked) {
                    if (maskedResources == null) {
                        // Compare by identity, since Resource#equals compares string representations, and the
                        // masked resource and the resource that masks it are in different classpath elements
                        maskedResources = Collections
                                .newSetFromMap(new IdentityHashMap<Resource, Boolean>());
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
     * Apply relative path masking within this classpath resource by removing relative paths that were found in an
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
        // e.g.: xmlbeans-2.6.0.jar!/org/apache/xmlbeans/xml/stream/Location.class
        final List<Resource> acceptedClassfileResourcesFiltered = new ArrayList<>(
                acceptedClassfileResources.size());
        boolean foundMasked = false;
        for (final Resource res : acceptedClassfileResources) {
            // Two classfiles that differ only in the case of their extension declare the same class, so they mask
            // each other in the same way as two classfiles at the same path
            final String pathRelativeToPackageRoot = FileUtils.withLowerCaseClassfileExtension(res.getPath());
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
                    log.log(String.format(Locale.US, "%06d-1", classpathIdx), "Ignoring duplicate (masked) class "
                            + JarUtils.classfilePathToClassName(pathRelativeToPackageRoot) + " found at " + res);
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
     *            the log
     */
    protected void addAcceptedResource(final Resource resource, final ScanSpecPathMatch parentMatchStatus,
            final boolean isClassfileOnly, final LogNode log) {
        final String path = resource.getPath();
        final boolean isClassFile = FileUtils.isClassfile(path);
        // A classfile is only scheduled for scanning if classfile scanning is enabled, its path is a class name in
        // internal form, and the classfile is not specifically rejected
        final boolean addedAsClassfile = isClassFile && scanSpec.enableClassInfo
                && FileUtils.classfilePathIsClassName(path)
                && !scanSpec.classfilePathAcceptReject.isRejected(FileUtils.withLowerCaseClassfileExtension(path));
        if (addedAsClassfile) {
            acceptedClassfileResources.add(resource);
        }

        // Add resource to list of accepted resources, whether for a classfile or non-classfile resource. A
        // classfile that is not scheduled for scanning is still listed as a resource, since it is still a file of
        // the classpath element.
        final boolean addedAsResource = !isClassfileOnly;
        if (addedAsResource) {
            acceptedResources.add(resource);
        }

        // Write to log if enabled, and as long as the resource was actually added to one of the two lists
        if (log != null && (addedAsClassfile || addedAsResource)) {
            // A classfile that was only added as a resource is logged as a resource, since it is not scanned
            final String type = addedAsClassfile ? "classfile" : "resource";
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
        return log.log(String.format(Locale.US, "%07d", classpathElementIdx), msg);
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
        return log.log(String.format(Locale.US, "%07d", classpathElementIdx), msg, t);
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Open this classpath element, and determine if it is valid. If it is not valid, sets skipClasspathElement. For
     * {@link ClasspathElementZip}, may also open or extract inner jars, and reads the manifest for the module name
     * and for the Class-Path and Bundle-ClassPath entries. Adds the classpath elements that this one contains or
     * refers to (package roots, manifest entries, and the jarfiles in lib dirs) to the work queue. This method is
     * only run once per classpath element, from a single thread.
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
     *            The relative path of the {@link Resource} to return. Path should have already been sanitized by
     *            calling {@link FileUtils#sanitizeRelativePath(String, boolean)}, or by providing a path that is
     *            already sanitized (i.e. doesn't start or end with "/", doesn't contain "/../" or "/./", etc.).
     * @return The {@link Resource} for the given relative path, or null if relativePath does not exist in this
     *         classpath element.
     */
    abstract Resource getResource(final String relativePath);

    /**
     * Get the {@link Resource} for a given relative path while the scan is running.
     *
     * @param relativePath
     *            The relative path of the {@link Resource} to return.
     * @return The {@link Resource} for the given relative path, or null if relativePath does not exist in this
     *         classpath element.
     */
    Resource getResourceDuringScan(final String relativePath) {
        return getResource(relativePath);
    }

    /**
     * Called once every classfile has been scanned to release any temporary state cached during the scan.
     */
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
     * Get the separator between the URI of this classpath element and the path of a resource within it
     * ({@code "!/"} for a jarfile, or {@code "/"} for a directory or package root).
     *
     * @return the separator.
     */
    String getResourcePathSeparator() {
        return "!/";
    }

    /**
     * Get the URI for this classpath element, and the URIs for any automatic nested package prefixes (e.g.
     * "spring-boot.jar/BOOT-INF/classes") within this jarfile.
     *
     * @return the URI for the classpath element.
     */
    abstract List<URI> getAllURIs();

    /**
     * Get the file for this classpath element. For a jar nested within another jar, this is the outermost jar.
     *
     * @return the file for the classpath element, or null if there is none (see
     *         {@link Resource#getClasspathElementFile()}).
     */
    abstract File getFile();

    /**
     * Get the name of this classpath element's module, or null if there is no module name.
     *
     * @return the module name
     */
    abstract String getModuleName();
}
