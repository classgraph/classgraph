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
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.classgraph.Scanner.ClasspathEntryWorkUnit;
import nonapi.io.github.classgraph.concurrency.WorkQueue;
import nonapi.io.github.classgraph.fastzipfilereader.FastZipEntry;
import nonapi.io.github.classgraph.fastzipfilereader.LogicalZipFile;
import nonapi.io.github.classgraph.fileslice.reader.ClassfileReader;
import nonapi.io.github.classgraph.scanspec.ScanSpec;
import nonapi.io.github.classgraph.scanspec.ScanSpec.ScanSpecPathMatch;
import nonapi.io.github.classgraph.utils.FileUtils;
import nonapi.io.github.classgraph.utils.JarUtils;
import nonapi.io.github.classgraph.utils.LogNode;
import org.jspecify.annotations.Nullable;

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
    @Nullable
    List<String> nestedClasspathRootPrefixes;

    /**
     * True if there was an exception when trying to open this classpath element (e.g. a corrupt ZipFile).
     */
    boolean skipClasspathElement;

    /** True if classpath element contains a specifically-accepted resource path. */
    boolean containsSpecificallyAcceptedClasspathElementResourcePath;

    /**
     * True if this classpath element is referenced directly from the classpath, as opposed to only being referenced
     * from the {@code Class-Path} manifest entry (or lib dir) of another classpath element.
     */
    private boolean isToplevel;

    /**
     * The index of the classpath element within the classpath (for toplevel classpath elements), or within the
     * parent classpath element (e.g. for classpath elements added via a Class-Path entry in the manifest). Set by
     * {@link #addReference(boolean, int, ClassLoader)}.
     */
    private int classpathElementIdxWithinParent = Integer.MAX_VALUE;

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

    /**
     * The classloader that this classpath element was obtained from, or null if unknown.
     */
    protected @Nullable ClassLoader classLoader;

    /** The package root within the jarfile or Path. */
    protected String packageRootPrefix;

    /**
     * The automatic package root prefixes (e.g. {@code "BOOT-INF/classes/"}) to look for within this classpath
     * element, as declared by the {@code ClassLoaderHandler} that found it. Child classpath elements inherit these,
     * since they come from the same classloader.
     */
    protected final String[] packageRootPrefixes;

    /**
     * The name of the module from the {@code module-info.class} module descriptor, if one is present in the root of
     * the classpath element.
     */
    @Nullable
    String moduleNameFromModuleDescriptor;

    /** The scan spec. */
    final ScanSpec scanSpec;

    /**
     * The ScanResult that the classpath element came from, or null until {@link #setScanResult(ScanResult)} is
     * called after the scan is complete.
     */
    protected @Nullable ScanResult scanResult;

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
     * Record a reference to this classpath element from a classpath entry work unit.
     *
     * <p>
     * The same classpath element may be referenced by more than one work unit -- e.g. a jar may be listed both in
     * the toplevel classpath and in the {@code Class-Path} manifest entry of another jar. Only one of those work
     * units creates the {@link ClasspathElement} singleton, and which one wins that race is nondeterministic, so
     * the classpath ordering key and the classloader have to be merged in from every work unit that references this
     * classpath element, not just from the one that happened to create it.
     *
     * <p>
     * The classloader follows the winning reference, since the same directory or jar can be reached through more
     * than one classloader (e.g. through a parent-last classloader and through its parent), and the classloader
     * that gives the classpath element its position in the classpath order is the one that should be reported for
     * the classes found within it -- otherwise which classloader is recorded depends on which work unit won the
     * race, and {@link ClassInfo#getClassLoader()} intermittently reports the wrong classloader.
     *
     * <p>
     * A toplevel reference always takes precedence over a reference from a parent classpath element, so that a jar
     * listed in the classpath is ordered by its position in the classpath, rather than by the position of a
     * manifest entry that also happens to reference it. Between two references of the same kind, the lowest index
     * wins, so that the earliest reference determines the position of the classpath element.
     *
     * @param isToplevelRef
     *            true if the work unit referenced this classpath element from the toplevel classpath, false if it
     *            referenced it from a parent classpath element
     * @param idx
     *            the index of the reference within the classpath, or within the parent classpath element
     * @param classLoader
     *            the classloader that the referencing work unit obtained the classpath entry from, or null if
     *            unknown
     */
    // #810
    synchronized void addReference(final boolean isToplevelRef, final int idx,
            final @Nullable ClassLoader classLoader) {
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
     * Sort toplevel classpath elements before classpath elements that are only referenced from a parent classpath
     * element, then sort in increasing order of classpathElementIdxWithinParent.
     */
    @Override
    public int compareTo(final ClasspathElement other) {
        if (this.isToplevel != other.isToplevel) {
            return this.isToplevel ? -1 : 1;
        }
        return Integer.compare(this.classpathElementIdxWithinParent, other.classpathElementIdxWithinParent);
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Get the ClassLoader the classpath element was obtained from.
     *
     * @return the classloader, or null if unknown
     */
    @Nullable
    ClassLoader getClassLoader() {
        return classLoader;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Check relativePath against classpathElementResourcePathAcceptReject.
     *
     * @param relativePath
     *            the relative path
     * @param log
     *            the log node, or null to skip logging
     * @return true if path should be scanned
     */
    protected boolean checkResourcePathAcceptReject(final String relativePath, final @Nullable LogNode log) {
        // Accept/reject classpath elements based on file resource paths
        if (!scanSpec.classpathElementResourcePathAcceptReject.acceptAndRejectAreEmpty()) {
            if (scanSpec.classpathElementResourcePathAcceptReject.isRejected(relativePath)) {
                if (log != null) {
                    log.log("Reached rejected classpath element resource path, stopping scanning: " + relativePath);
                }
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
     * Check whether a path is within a multi-release versioned section that should be ignored.
     *
     * <p>
     * A versioned path is only reached here if it was not already resolved by the multi-release machinery: for a
     * jarfile, {@link FastZipEntry#entryNameUnversioned} has any version prefix stripped, and the module system
     * strips version prefixes for modules, so a remaining version prefix means either a nested versioned section,
     * or a versioned section in a jar whose manifest is missing the {@code Multi-Release} key. Directories are not
     * multi-release at all -- the JVM loads the base version of a class from a directory even when a versioned copy
     * is present alongside it.
     *
     * @param relativePath
     *            the path of an entry, relative to the root of the classpath element.
     * @return true if the path is within a versioned section that should be ignored.
     */
    protected boolean isIgnoredVersionedPath(final String relativePath) {
        return !scanSpec.enableMultiReleaseVersions
                && relativePath.startsWith(LogicalZipFile.MULTI_RELEASE_PATH_PREFIX);
    }

    /**
     * Check whether a path is that of a classfile in the default (unnamed) package of a module.
     *
     * <p>
     * A module cannot contain classes in the default package, and {@code module-info.class} is the only classfile
     * allowed in the root of a module, so any other classfile found there cannot be loaded, and is ignored.
     *
     * @param isModule
     *            true if this classpath element declares a module name.
     * @param relativePath
     *            the path of an entry, relative to the package root.
     * @return true if the entry is a classfile in the default package of a module, and should be ignored.
     */
    protected static boolean isIgnoredDefaultPackageClassfile(final boolean isModule, final String relativePath) {
        return isModule && relativePath.indexOf('/') < 0 && relativePath.endsWith(".class")
                && !"module-info.class".equals(relativePath);
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
                        && scanSpec.classfileIsSpecificallyAccepted(relativePath));
    }

    /**
     * The accept/reject match status of the parent directory of the last relative path that was looked up.
     *
     * <p>
     * The entries of a classpath element are scanned in sorted order, so consecutive entries usually share a parent
     * directory, and caching the match status of the last parent directory means the match status only has to be
     * computed once per directory, rather than once per entry.
     */
    protected final class ParentDirMatchStatusCache {
        /** The parent directory of the last relative path that was looked up, or null if there was none. */
        private @Nullable String prevParentRelativePath;

        /** The match status of {@link #prevParentRelativePath}. */
        private ScanSpecPathMatch prevParentMatchStatus = ScanSpecPathMatch.NOT_WITHIN_ACCEPTED_PATH;

        /**
         * Get the accept/reject match status of the parent directory of a relative path.
         *
         * @param relativePath
         *            the path of an entry, relative to the package root.
         * @return the match status of the parent directory of the entry.
         */
        ScanSpecPathMatch getParentMatchStatus(final String relativePath) {
            final var lastSlashIdx = relativePath.lastIndexOf('/');
            final var parentRelativePath = lastSlashIdx < 0 ? "/" : relativePath.substring(0, lastSlashIdx + 1);
            if (!parentRelativePath.equals(prevParentRelativePath)) {
                prevParentRelativePath = parentRelativePath;
                prevParentMatchStatus = scanSpec.dirAcceptMatchStatus(parentRelativePath);
            }
            return prevParentMatchStatus;
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Check whether a candidate package root within a classpath element really is a package root, and not simply a
     * package that happens to have the same name as one of the automatic package root prefixes.
     *
     * <p>
     * {@code classes/} and {@code test-classes/} are both legal Java package names, so the layout of a classpath
     * element cannot by itself distinguish (for example) the Ant layout {@code <root>/classes/com/xyz/Foo.class}
     * from the Maven output directory {@code target/classes/}, which contains a package named {@code classes} at
     * {@code target/classes/classes/Foo.class}. The two cases can however be told apart by reading the name of the
     * class declared by any classfile beneath the candidate package root: if the candidate really is a package
     * root, then the class name matches the path of the classfile relative to the candidate root; if the candidate
     * is really a package, then the class name has the candidate's name as a package prefix, so it does not match.
     *
     * @param classfileReader
     *            a reader for a classfile found beneath the candidate package root.
     * @param classfileRelativePath
     *            the path of that classfile, relative to the candidate package root.
     * @return null if the candidate is a package root, or if the class name could not be read from the classfile
     *         (in which case the candidate is given the benefit of the doubt); otherwise the name of the class
     *         declared by the classfile, which disproves that the candidate is a package root.
     */
    // #929
    static @Nullable String getClassNameDisprovingPackageRoot(final ClassfileReader classfileReader,
            final String classfileRelativePath) {
        final var className = Classfile.readClassName(classfileReader);
        return className == null || (className + ".class").equals(classfileRelativePath) ? null
                : className.replace('/', '.');
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Get the canonical path of a directory, resolving symlinks (and, on Windows, junctions and 8.3 short names).
     *
     * <p>
     * {@link Path#toRealPath(java.nio.file.LinkOption...)} is used rather than {@link File#getCanonicalPath()},
     * since on Windows the latter resolves neither directory symlinks and junctions nor 8.3 short names (e.g.
     * {@code C:\Users\RUNNER~1} for {@code C:\Users\runneradmin}). {@link File#getCanonicalPath()} is used as a
     * fallback, since {@link Path#toRealPath(java.nio.file.LinkOption...)} requires the directory to exist.
     *
     * @param dir
     *            the directory.
     * @return the canonical path of the directory.
     * @throws IOException
     *             if the path could not be canonicalized.
     */
    private static String canonicalizeDirPath(final File dir) throws IOException {
        try {
            return dir.toPath().toRealPath().toString();
        } catch (final IOException | RuntimeException e) {
            // The directory does not exist, or the path is not valid for the default filesystem
            return dir.getCanonicalPath();
        }
    }

    /**
     * Get a key that identifies the file that a resource's {@link URI} refers to, for comparing two resources to
     * see if they are the same file.
     *
     * <p>
     * The same file can be reachable through more than one path, e.g. through a symlinked parent directory (on
     * macOS, the temp directory {@code /var/folders/...} is reached through the symlink {@code /var ->
     * /private/var}, so the same file has both a {@code /var/...} URI and a {@code /private/var/...} URI).
     * Therefore the parent directory of the file is canonicalized. (The file itself is not canonicalized, both to
     * halve the number of filesystem calls -- the canonical path of a directory is cached, whereas each file is
     * seen only once -- and because a symlink to a file elsewhere is a file in its own right.)
     *
     * @param uri
     *            the URI of a resource.
     * @param canonicalDirPathCache
     *            a cache of canonical directory paths.
     * @return a key that is equal for two URIs that refer to the same file. This is not a valid URI -- it is only
     *         useful for equality comparison. If the URI does not refer to a file (e.g. a {@code jrt:/} URI), or
     *         the file could not be canonicalized, the string representation of the URI is returned unchanged.
     */
    private static String getFileIdentityKey(final URI uri, final Map<String, String> canonicalDirPathCache) {
        final var uriStr = uri.toString();
        // Find the file part of the URI: "file:<path>", or "jar:file:<path>!/<entry>" (for a jar within a jar,
        // <path> is the path of the outermost jar, and everything from the first "!/" is part of the entry)
        final var filePartStartIdx = uriStr.startsWith("file:") ? 5 : uriStr.startsWith("jar:file:") ? 9 : -1;
        if (filePartStartIdx < 0) {
            // Not a file URI, so there is no path to canonicalize
            return uriStr;
        }
        final var nestedPathStartIdx = uriStr.indexOf("!/", filePartStartIdx);
        final var filePart = nestedPathStartIdx < 0 ? uriStr.substring(filePartStartIdx)
                : uriStr.substring(filePartStartIdx, nestedPathStartIdx);
        final var nestedPath = nestedPathStartIdx < 0 ? "" : uriStr.substring(nestedPathStartIdx);
        try {
            final var file = new File(URI.create("file:" + filePart));
            final var parentDir = file.getParentFile();
            if (parentDir == null) {
                return uriStr;
            }
            final var parentDirPath = parentDir.getPath();
            var canonicalParentDirPath = canonicalDirPathCache.get(parentDirPath);
            if (canonicalParentDirPath == null) {
                canonicalParentDirPath = canonicalizeDirPath(parentDir);
                canonicalDirPathCache.put(parentDirPath, canonicalParentDirPath);
            }
            return canonicalParentDirPath + File.separatorChar + file.getName() + nestedPath;
        } catch (final IOException | IllegalArgumentException | SecurityException e) {
            // The file could not be canonicalized -- fall back to comparing the URI itself
            return uriStr;
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Remove any resource that refers to the same file as a resource already found in this or an earlier classpath
     * element, i.e. a resource with the same {@link Resource#getURI()}.
     *
     * <p>
     * Two different classpath elements may legitimately contain different resources with the same relative path
     * (e.g. two jars may each contain their own {@code META-INF/services/} entries), and both should be returned by
     * {@link ScanResult#getAllResources()}. However, the same *file* can also be reached through more than one
     * classpath element -- e.g. Maven Surefire and IDEs splice the test output directory into a module using
     * {@code --patch-module}, while also placing that same directory on the classpath, so the module and the
     * classpath element both list the same file. Returning one file twice is never useful, so the second and
     * subsequent occurrences are removed here.
     *
     * @param classpathIdx
     *            the classpath index
     * @param collidingPaths
     *            the relative paths that occur in more than one place in the classpath / module path (only these
     *            can be duplicates, so only for these does the {@link URI} need to be computed)
     * @param fileIdentityKeysFound
     *            the file identity keys of the resources found so far (see {@link #getFileIdentityKey(URI, Map)})
     * @param canonicalDirPathCache
     *            a cache of canonical directory paths, shared between classpath elements
     * @param log
     *            the log node, or null to skip logging
     */
    // #704
    void maskDuplicateResources(final int classpathIdx, final Set<String> collidingPaths,
            final Set<String> fileIdentityKeysFound, final Map<String, String> canonicalDirPathCache,
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
                isMasked = uri != null
                        && !fileIdentityKeysFound.add(getFileIdentityKey(uri, canonicalDirPathCache));
                if (isMasked) {
                    if (maskedResources == null) {
                        // Compare by identity, since Resource#equals compares string representations, and the
                        // masked resource and the resource that masks it are in different classpath elements
                        maskedResources = Collections.newSetFromMap(new IdentityHashMap<>());
                    }
                    maskedResources.add(res);
                    if (log != null) {
                        log.log(String.format("%06d-1", classpathIdx),
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
     * Apply relative path masking within this classpath resource -- remove relative paths that were found in an
     * earlier classpath element.
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
        // xmlbeans-2.6.0.jar!org/apache/xmlbeans/xml/stream/Location.class
        final List<Resource> acceptedClassfileResourcesFiltered = new ArrayList<>(
                acceptedClassfileResources.size());
        var foundMasked = false;
        for (final Resource res : acceptedClassfileResources) {
            final var pathRelativeToPackageRoot = res.getPath();
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
                    log.log(String.format("%06d-1", classpathIdx), "Ignoring duplicate (masked) class "
                            + JarUtils.classfilePathToClassName(pathRelativeToPackageRoot) + " found at " + res);
                }
            } else {
                acceptedClassfileResourcesFiltered.add(res);
            }
        }
        if (foundMasked) {
            // Remove masked (duplicated) paths. N.B. this replaces the concurrent collection with a non-concurrent
            // collection, but this is the last time the collection is changed during a scan, and this method is run
            // from a single thread.
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
        final var isClassFile = FileUtils.isClassfile(path);
        var isAccepted = false;
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

        // Write to log if enabled, and as long as classfile scanning is not disabled, and this is not a rejected
        // classfile
        if (log != null && isAccepted) {
            final var type = isClassFile ? "classfile" : "resource";
            final var logStr = switch (parentMatchStatus) {
            case HAS_ACCEPTED_PATH_PREFIX -> "Found " + type + " within subpackage of accepted package: ";
            case AT_ACCEPTED_PATH -> "Found " + type + " within accepted package: ";
            case AT_ACCEPTED_CLASS_PACKAGE -> "Found specifically-accepted " + type + ": ";
            default -> "Found accepted " + type + ": ";
            };
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
    protected LogNode log(final int classpathElementIdx, final String msg, final LogNode log) {
        return log.log(String.format("%07d", classpathElementIdx), msg);
    }

    /**
     * Write entries to log in classpath / module path order.
     *
     * @param classpathElementIdx
     *            the index of this element in classpath / module path order
     * @param msg
     *            the log message
     * @param t
     *            the exception that was thrown
     * @param log
     *            the log node to write to
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
     *            the log node, or null to skip logging
     * @throws InterruptedException
     *             if the thread was interrupted while trying to open the classpath element.
     */
    abstract void open(final WorkQueue<ClasspathEntryWorkUnit> workQueue, final @Nullable LogNode log)
            throws InterruptedException;

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
     *            The relative path of the {@link Resource} to return. Path should have already be sanitized by
     *            calling {@link FileUtils#sanitizeEntryPath(String, boolean, boolean)}, or by providing a path that
     *            is already sanitized (i.e. doesn't start or end with "/", doesn't contain "/../" or "/./", etc.).
     * @return The {@link Resource} for the given relative path, or null if relativePath does not exist in this
     *         classpath element.
     */
    abstract @Nullable Resource getResource(final String relativePath);

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
    abstract @Nullable File getFile();

    /**
     * Get the name of this classpath element's module, or null if there is no module name.
     *
     * @return the module name
     */
    abstract @Nullable String getModuleName();
}
