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
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.classgraph.ScanSpec.ScanSpecPathMatch;
import io.github.classgraph.utils.ClasspathOrModulePathEntry;
import io.github.classgraph.utils.FileUtils;
import io.github.classgraph.utils.JarUtils;
import io.github.classgraph.utils.LogNode;
import io.github.classgraph.utils.NestedJarHandler;
import io.github.classgraph.utils.WorkQueue;

/** A classpath element (a directory or jarfile on the classpath). */
// TODO: This can probably be merged with ClasspathOrModulePathEntry, since it is mostly a wrapper for that class. 
abstract class ClasspathElement {
    /** The path of the classpath element relative to the current directory. */
    final ClasspathOrModulePathEntry classpathEltPath;

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

    /**
     * The child classpath elements. These are the entries obtained from Class-Path entries in the manifest file, if
     * this classpath element is a jarfile.
     */
    List<ClasspathOrModulePathEntry> childClasspathElts;

    /** The list of all resources found within this classpath element that were whitelisted and not blacklisted. */
    protected List<Resource> resourceMatches;

    /** The list of all classfiles found within this classpath element that were whitelisted and not blacklisted. */
    protected List<Resource> whitelistedClassfileResources;

    /** The list of all classfiles found within this classpath element that were not specifically blacklisted. */
    protected List<Resource> nonBlacklistedClassfileResources;

    /** Map from class name to non-blacklisted resource. */
    protected Map<String, Resource> classNameToNonBlacklistedResource;

    /** The map from File to last modified timestamp, if scanFiles is true. */
    protected Map<File, Long> fileToLastModified;

    /** Flag to ensure classpath element is only scanned once. */
    protected AtomicBoolean scanned = new AtomicBoolean(false);

    /** The scan spec. */
    final ScanSpec scanSpec;

    /** A classpath element (a directory or jarfile on the classpath). */
    ClasspathElement(final ClasspathOrModulePathEntry classpathEltPath, final ScanSpec scanSpec) {
        this.classpathEltPath = classpathEltPath;
        this.scanSpec = scanSpec;
    }

    /** Return the classpath element's path. */
    @Override
    public String toString() {
        return classpathEltPath.toString();
    }

    /** Return the raw path for this classpath element, as found in the classpath. */
    String getRawPath() {
        return classpathEltPath.getRawPath();
    }

    /** Return the resolved path for this classpath element (i.e. the raw path resolved against the current dir). */
    String getResolvedPath() {
        return classpathEltPath.getResolvedPath();
    }

    /**
     * @return The classpath element's file (directory or jarfile), or null if this is a module. May trigger the
     *         extraction of nested jars.
     */
    File getClasspathElementFile(final LogNode log) {
        if (classpathEltPath.getModuleRef() != null) {
            return null;
        }
        try {
            return classpathEltPath.getFile(log);
        } catch (final IOException e) {
            // Shouldn't happen; files have already been screened for IOException during canonicalization
            throw new RuntimeException(e);
        }
    }

    /**
     * If non-empty, this path represents the package root within a jarfile, e.g. if the path is
     * "spring-project.jar!/BOOT-INF/classes", the package root is "BOOT-INF/classes". N.B. for non-modules, this
     * should only be called after {@link #getClasspathElementFile(LogNode)}, since that method determines the
     * package root (after extracting nested jars).
     */
    String getJarfilePackageRoot() {
        // Overridden in ClasspathElementZip
        return "";
    }

    /** Get the ClassLoader(s) to use when trying to load the class. */
    ClassLoader[] getClassLoaders() {
        return classpathEltPath.getClassLoaders();
    }

    /** Get the ModuleRef for the classpath element, if this is a module, otherwise returns null. */
    ModuleRef getClasspathElementModuleRef() {
        return classpathEltPath.getModuleRef();
    }

    /**
     * Factory for creating a ClasspathElementDir singleton for directory classpath entries or a ClasspathElementZip
     * singleton for jarfile classpath entries.
     */
    static ClasspathElement newInstance(final ClasspathOrModulePathEntry classpathRelativePath,
            final ScanSpec scanSpec, final NestedJarHandler nestedJarHandler,
            final WorkQueue<ClasspathOrModulePathEntry> workQueue, final LogNode log) {
        boolean isModule = false;
        boolean isDir = false;
        String resolvedPath = null;
        try {
            resolvedPath = classpathRelativePath.getResolvedPath();
            isModule = classpathRelativePath.getModuleRef() != null;
            if (!isModule) {
                isDir = classpathRelativePath.isDirectory(log);
            }
        } catch (final IOException e) {
            if (log != null) {
                log.log("Exception while trying to canonicalize path " + classpathRelativePath.getResolvedPath(),
                        e);
            }
            return null;
        }
        LogNode subLog = null;
        if (log != null) {
            subLog = log.log(resolvedPath,
                    "Scanning " + (isModule ? "module " : isDir ? "directory " : "jarfile ")
                            + (isModule
                                    ? classpathRelativePath.getModuleRef()
                                            + (classpathRelativePath.getModuleRef().getLocationStr() == null ? ""
                                                    : " -> " + classpathRelativePath.getModuleRef()
                                                            .getLocationStr())
                                    : classpathRelativePath));
        }

        // Dispatch to appropriate constructor
        final ClasspathElement newInstance = isModule
                ? new ClasspathElementModule(classpathRelativePath, scanSpec, nestedJarHandler, subLog)
                : isDir ? new ClasspathElementDir(classpathRelativePath, scanSpec, subLog)
                        : new ClasspathElementZip(classpathRelativePath, scanSpec, nestedJarHandler, workQueue,
                                subLog);
        if (subLog != null) {
            subLog.addElapsedTime();
        }
        return newInstance;
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
                                        + " for classpath element " + res);
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

        // Mask all non-blacklisted classfile resources
        nonBlacklistedClassfileResources = ClasspathElement.maskClassfiles(scanSpec, classpathIdx,
                nonBlacklistedClassfileResources, nonBlacklistedClasspathRelativePathsFound,
                /* no need to log */ null);
    }

    // -------------------------------------------------------------------------------------------------------------

    /** Add a resource discovered during the scan. */
    protected boolean addResource(final Resource resource, final ScanSpecPathMatch parentMatchStatus,
            final LogNode log) {
        final String path = resource.getPath();
        final boolean isClassfile = scanSpec.enableClassInfo && FileUtils.isClassfile(path)
                && !scanSpec.classfilePathWhiteBlackList.isBlacklisted(path);

        // Record non-blacklisted classfile resources
        if (isClassfile) {
            nonBlacklistedClassfileResources.add(resource);
        }

        // Class can only be scanned if it's within a whitelisted path subtree, or if it is a classfile
        // that has been specifically-whitelisted
        final boolean isWhitelisted = parentMatchStatus == ScanSpecPathMatch.HAS_WHITELISTED_PATH_PREFIX
                || parentMatchStatus == ScanSpecPathMatch.AT_WHITELISTED_PATH
                || (parentMatchStatus == ScanSpecPathMatch.AT_WHITELISTED_CLASS_PACKAGE
                        && scanSpec.isSpecificallyWhitelistedClass(path));
        if (isWhitelisted) {
            if (log != null) {
                log.log(path, "Found whitelisted path: " + path);
            }

            // Record whitelisted classfile and non-classfile resources
            if (isClassfile) {
                whitelistedClassfileResources.add(resource);
            }
            resourceMatches.add(resource);

        } else {
            if (log != null) {
                log.log("Skipping non-whitelisted path: " + path);
            }
        }
        return isWhitelisted;
    }

    /**
     * Scan paths in the classpath element for whitelist/blacklist criteria, creating Resource objects for
     * whitelisted and non-blacklisted resources and classfiles.
     */
    abstract void scanPaths(final LogNode log);

    /**
     * Closes and empties the classpath element's resource recyclers (this closes and frees any open ZipFiles or
     * ModuleReaders, and is a no-op for directory classpath elements).
     */
    abstract void closeRecyclers();
}
