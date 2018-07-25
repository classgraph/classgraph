/*
 * This file is part of FastClasspathScanner.
 *
 * Author: Luke Hutchison
 *
 * Hosted at: https://github.com/lukehutch/fast-classpath-scanner
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
package io.github.lukehutch.fastclasspathscanner.utils;

import java.io.File;
import java.io.IOException;
import java.util.Map.Entry;
import java.util.Set;

import io.github.lukehutch.fastclasspathscanner.ModuleRef;
import io.github.lukehutch.fastclasspathscanner.ScanSpec;

/**
 * An entry in the classpath or module path. If the path is an http(s):// URL, the remote jar will be fetched and
 * cached if getFile() / isFile() etc. are called. If the path is a '!'-separated path to a nested jar, the
 * innermost jar will be extracted and cached on these calls.
 */
public class ClasspathOrModulePathEntry {
    /** The ClassLoader(s) used to load classes for this classpath element */
    public ClassLoader[] classLoaders;

    /** Base path for path resolution. */
    private final String pathToResolveAgainst;

    /** The relative path. */
    private final String relativePath;

    /**
     * For jarfiles, this gives the package root (e.g. "BOOT-INF/classes"), if the section of the path after the
     * last '!' is not a jarfile.
     */
    private String jarfilePackageRoot = "";

    /** Handler for nested jars. */
    private final NestedJarHandler nestedJarHandler;

    /** The resolved path. */
    private String resolvedPathCached;
    /** True if the path has been resolved. */
    private boolean resolvedPathIsCached;

    /** True if the resolved path is an http(s):// URL. */
    private boolean isHttpURL;
    /** True if isHttpURL has been set. */
    private boolean isHttpURLIsCached;

    /** True if the resolved path is a jrt:/ URL. */
    private boolean isJrtURL;
    /** True if isJrtURL has been set. */
    private boolean isJrtURLIsCached;

    /**
     * If this URL is the location of a module (whether a jrt:/ URL or a file:// URL), this is the ModuleRef for the
     * module.
     */
    private ModuleRef moduleRef;

    /** The canonical file for the relative path. */
    private File fileCached;
    /** True if the resolved path has been canonicalized. */
    private boolean fileIsCached;

    /** The path of the canonical file. */
    private String canonicalPathCached;
    /** True if the path of the canonical file has been read. */
    private boolean canonicalPathIsCached;

    /** True if getFile().isFile(). */
    private boolean isFileCached;
    /** True if isFileCached has been cached. */
    private boolean isFileIsCached;

    /** True if getFile().isDirectory(). */
    private boolean isDirectoryCached;
    /** True if isDirectoryCached has been cached. */
    private boolean isDirectoryIsCached;

    /** True if getFile().exists(). */
    private boolean existsCached;
    /** True if existsCached has been cached. */
    private boolean existsIsCached;

    private ScanSpec scanSpec;
    private final LogNode log;

    // -------------------------------------------------------------------------------------------------------------

    /**
     * A relative path. This is used for paths relative to the current directory (for classpath elements), and also
     * for relative paths within classpath elements (e.g. the files within a ZipFile).
     */
    public ClasspathOrModulePathEntry(final String pathToResolveAgainst, final String relativePath,
            final ClassLoader[] classLoaders, final NestedJarHandler nestedJarHandler, final ScanSpec scanSpec,
            final LogNode log) {
        this.classLoaders = classLoaders;
        this.pathToResolveAgainst = pathToResolveAgainst;
        this.nestedJarHandler = nestedJarHandler;
        this.scanSpec = scanSpec;
        this.log = log;

        // Fix Spring relative paths with empty zip resource sections
        if (relativePath.endsWith("!")) {
            this.relativePath = relativePath.substring(0, relativePath.length() - 1);
        } else if (relativePath.endsWith("!/")) {
            this.relativePath = relativePath.substring(0, relativePath.length() - 2);
        } else if (relativePath.endsWith("/!")) {
            this.relativePath = relativePath.substring(0, relativePath.length() - 2);
        } else if (relativePath.endsWith("/!/")) {
            this.relativePath = relativePath.substring(0, relativePath.length() - 3);
        } else {
            this.relativePath = relativePath;
        }
    }

    /** A relative path for a module (in JDK9+). */
    public ClasspathOrModulePathEntry(final ModuleRef moduleRef, final NestedJarHandler nestedJarHandler,
            final LogNode log) {
        if (moduleRef == null) {
            throw new IllegalArgumentException("moduleRef cannot be null");
        }
        this.moduleRef = moduleRef;
        final ClassLoader classLoader = moduleRef.getClassLoader();
        // System modules that use the bootstrap classloader may return null for their classloader
        this.classLoaders = classLoader == null ? null : new ClassLoader[] { classLoader };
        this.pathToResolveAgainst = "";
        this.nestedJarHandler = nestedJarHandler;
        this.relativePath = moduleRef.getLocationStr();
        this.log = log;
    }

    /** Hash based on canonical path. */
    @Override
    public int hashCode() {
        return relativePath.hashCode() + 31 * jarfilePackageRoot.hashCode();
    }

    /** Return true based on equality of canonical paths. */
    @Override
    public boolean equals(final Object o) {
        if (o == null) {
            return false;
        }
        if (!(o instanceof ClasspathOrModulePathEntry)) {
            return false;
        }
        final ClasspathOrModulePathEntry other = (ClasspathOrModulePathEntry) o;
        String thisCp;
        try {
            thisCp = getCanonicalPath(log);
            final String otherCp = other.getCanonicalPath(log);
            if (thisCp == null || otherCp == null) {
                return false;
            }
            if (!thisCp.equals(otherCp)) {
                return false;
            }
            return getJarfilePackageRoot().equals(other.getJarfilePackageRoot());
        } catch (final IOException e) {
            return false;
        }
    }

    /** Return the path. */
    @Override
    public String toString() {
        if (jarfilePackageRoot.isEmpty()) {
            return getResolvedPath();
        } else {
            try {
                return getFile(null) + "!" + jarfilePackageRoot;
            } catch (final Exception e) {
                return getResolvedPath();
            }
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /** Get the ClassLoader(s) that should be used to load classes for this classpath element. */
    public ClassLoader[] getClassLoaders() {
        return classLoaders;
    }

    /** Get the path of this classpath element, resolved against the parent path. */
    public String getResolvedPath() {
        if (!resolvedPathIsCached) {
            final String resolvedPath = FastPathResolver.resolve(pathToResolveAgainst, relativePath);
            if (resolvedPath.endsWith("!")) {
                resolvedPathCached = resolvedPath.substring(0, resolvedPath.length() - 1);
            } else if (resolvedPath.endsWith("!/")) {
                resolvedPathCached = resolvedPath.substring(0, resolvedPath.length() - 2);
            } else {
                resolvedPathCached = resolvedPath;
            }
            resolvedPathIsCached = true;
        }
        return resolvedPathCached;
    }

    /** Returns true if the path is an http(s):// URL. */
    public boolean isHttpURL() {
        if (!isHttpURLIsCached) {
            final String resolvedPath = getResolvedPath();
            isHttpURL = resolvedPath.regionMatches(/* ignoreCase = */ true, 0, "http://", 0, 7) || //
                    resolvedPath.regionMatches(/* ignoreCase = */ true, 0, "https://", 0, 8);
            isHttpURLIsCached = true;
        }
        return isHttpURL;
    }

    /** Returns true if the path is a jrt:/ URL. */
    public boolean isJrtURL() {
        if (!isJrtURLIsCached) {
            final String resolvedPath = getResolvedPath();
            isJrtURL = resolvedPath.regionMatches(/* ignoreCase = */ true, 0, "jrt:/", 0, 5);
            isJrtURLIsCached = true;
        }
        return isJrtURL;
    }

    /** Returns the ModuleRef for this module, if this RelativePath corresponds to a module (in JDK9+). */
    public ModuleRef getModuleRef() {
        return moduleRef;
    }

    /**
     * Get the File object for the resolved path.
     *
     * @throws IOException
     *             if the path cannot be canonicalized.
     */
    public File getFile(final LogNode log) throws IOException {
        if (!fileIsCached) {
            final String path = getResolvedPath();
            if (path == null) {
                throw new IOException(
                        "Path " + relativePath + " could not be resolved relative to " + pathToResolveAgainst);
            }

            if (isJrtURL) {
                // jrt:/ URLs don't correspond to file paths
                throw new IOException("Cannot use jrt:/ URL for non-module classpath entry " + relativePath);
            }

            final File pathFile = new File(path);
            if (pathFile.exists() && pathFile.isDirectory()) {
                fileCached = pathFile;
            } else {
                final int plingIdx = path.lastIndexOf('!');
                try {
                    // Fetch any remote jarfiles, recursively unzip any nested jarfiles, and remove ZipSFX header
                    // from jarfiles that don't start with "PK". In each case a temporary file will be created.
                    // Throws IOException if anything goes wrong.
                    final Entry<File, Set<String>> innermostJarAndRootRelativePaths = //
                            nestedJarHandler.getInnermostNestedJar(path, log);
                    if (innermostJarAndRootRelativePaths != null) {
                        final File innermostJar = innermostJarAndRootRelativePaths.getKey();
                        final Set<String> rootRelativePaths = innermostJarAndRootRelativePaths.getValue();
                        if (rootRelativePaths.isEmpty()) {
                            // There is no package root for this jar, so the jar itself is the classpath element
                            fileCached = innermostJar;
                        } else {
                            // Get section after last '!' (stripping any initial '/')
                            final String packageRoot = path.length() == plingIdx + 1 || plingIdx == -1 ? ""
                                    : path.charAt(plingIdx + 1) == '/' ? path.substring(plingIdx + 2)
                                            : path.substring(plingIdx + 1);
                            // Check to see if last segment is listed in the set of root relative paths for the jar
                            // -- if so, then this is the classpath base for this jarfile
                            if (rootRelativePaths.contains(packageRoot)) {
                                if (scanSpec.createClassLoaderForMatchingClasses) {
                                    // If a custom classloader will be created for all matching classes, need to
                                    // extract the package root from the zipfile to a temporary directory
                                    fileCached = nestedJarHandler.unzipToTempDir(innermostJar, packageRoot, log);
                                } else {
                                    // Otherwise, can scan the zipfile starting from the package root,
                                    // without extracting it from the zipfile to disk
                                    fileCached = innermostJar;
                                    jarfilePackageRoot = packageRoot;
                                }
                            } else {
                                if (log != null) {
                                    log.log("Zipfile suffix is not a valid package root: !" + packageRoot);
                                }
                                // Fall back to ignoring the package root 
                                fileCached = innermostJar;
                            }
                        }
                    }
                } catch (final Exception e) {
                    throw new IOException("Exception while locating jarfile " + relativePath, e);
                }
            }
            if (fileCached == null || !ClasspathUtils.canRead(fileCached)) {
                throw new IOException("Could not locate jarfile " + relativePath
                        + (relativePath.equals(path) ? "" : " -- resolved to: " + path));
            }

            try {
                fileCached = fileCached.getCanonicalFile();
            } catch (final IOException e) {
                throw new IOException("Could not canonicalize path " + path + " : " + e);
            } catch (final SecurityException e) {
                throw new IOException("Could not canonicalize path " + path + " : " + e);
            }
            fileIsCached = true;
        }
        return fileCached;
    }

    /**
     * If non-empty, this path represents the package root within a jarfile, e.g. if the path is
     * "spring-project.jar!/BOOT-INF/classes", the package root is "BOOT-INF/classes".
     */
    public String getJarfilePackageRoot() {
        return jarfilePackageRoot;
    }

    /** Gets the canonical path of the File object corresponding to the resolved path. */
    public String getCanonicalPath(final LogNode log) throws IOException {
        if (!canonicalPathIsCached) {
            final File file = getFile(log);
            canonicalPathCached = FastPathResolver.resolve(file.getPath());
            canonicalPathIsCached = true;
        }
        return canonicalPathCached;
    }

    /** True if this relative path corresponds with a file. */
    public boolean isFile(final LogNode log) throws IOException {
        if (!isFileIsCached) {
            isFileCached = getFile(log).isFile();
            isFileIsCached = true;
        }
        return isFileCached;
    }

    /** True if this relative path corresponds with a directory. */
    public boolean isDirectory(final LogNode log) throws IOException {
        if (!isDirectoryIsCached) {
            isDirectoryCached = getFile(log).isDirectory();
            isDirectoryIsCached = true;
        }
        return isDirectoryCached;
    }

    /** Returns true if resolved path has a .class extension, ignoring case. */
    public boolean isClassfile() {
        return FileUtils.isClassfile(getResolvedPath());
    }

    /** True if this relative path corresponds to a file or directory that exists. */
    private boolean exists(final LogNode log) throws IOException {
        if (!existsIsCached) {
            existsCached = ClasspathUtils.canRead(getFile(log));
            existsIsCached = true;
        }
        return existsCached;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * True if this relative path is a valid classpath element: that its path can be canonicalized, that it exists,
     * that it is a jarfile or directory, that it is not a blacklisted jar, that it should be scanned, etc.
     *
     * <p>
     * N.B. this has the side effect of fetching any http(s):// URLs, and/or unzipping any inner jarfiles, to
     * determine if these paths are valid. Any resulting temporary files will be cached.
     */
    public boolean isValidClasspathElement(final ScanSpec scanSpec, final LogNode log) throws InterruptedException {
        // Get absolute URI and File for classpathElt
        final String path = getResolvedPath();
        if (isJrtURL || moduleRef != null) {
            // Modules are always valid
            return true;
        }
        try {
            if (!exists(log)) {
                if (log != null) {
                    log.log("Classpath element does not exist: " + path);
                }
                return false;
            }
            // Call isFile(), which calls getFile(), which will fetch URLs and/or unzip nested jarfiles.
            final boolean isFile = isFile(log);
            final boolean isDirectory = isDirectory(log);
            if (isFile != !isDirectory) {
                // Exactly one of isFile and isDirectory should be true
                if (log != null) {
                    log.log("Ignoring invalid classpath element: " + path);
                }
                return false;
            }
            if (isFile) {
                final String canonicalPath = getCanonicalPath(log);
                if (scanSpec.blacklistSystemJarsOrModules && JarUtils.isJREJar(canonicalPath, scanSpec, log)) {
                    // Don't scan system jars if they are blacklisted
                    if (log != null) {
                        log.log("Ignoring JRE jar: " + path);
                    }
                    return false;
                }
                // If a classpath entry is a file, it must be a jar. Jarfiles may not have a jar/zip extension (see
                // Issue #166), so can't check extension. If the file is not a zipfile, opening it will fail during
                // scanning.
                return true;
            } else {
                // For directories, return true
                return true;
            }
        } catch (final IOException e) {
            if (log != null) {
                log.log("Could not canonicalize path " + path + " : " + e);
            }
            return false;
        }
    }
}
