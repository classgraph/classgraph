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
package io.github.classgraph.utils;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Map.Entry;
import java.util.Set;

import io.github.classgraph.ModuleRef;
import io.github.classgraph.ScanSpec;

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
    private final String rawPath;

    /**
     * For jarfiles, the section of the path after the last '!' character (if the section after the last '!' is a
     * directory and not a jarfile), which is the package root (e.g. "BOOT-INF/classes").
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

    /** The ScanSpec. */
    private ScanSpec scanSpec;

    // -------------------------------------------------------------------------------------------------------------

    /**
     * A relative path. This is used for paths relative to the current directory (for classpath elements), and also
     * for relative paths within classpath elements (e.g. the files within a ZipFile).
     * 
     * @param pathToResolveAgainst
     *            The base path.
     * @param rawPath
     *            The raw path. May be relative to pathToResolveAgainst.
     * @param classLoaders
     *            The environment classloaders.
     * @param nestedJarHandler
     *            The {@link NestedJarHandler}.
     * @param scanSpec
     *            The {@link ScanSpec}.
     * @param log
     *            The log.
     */
    public ClasspathOrModulePathEntry(final String pathToResolveAgainst, final String rawPath,
            final ClassLoader[] classLoaders, final NestedJarHandler nestedJarHandler, final ScanSpec scanSpec,
            final LogNode log) {
        this.classLoaders = classLoaders;
        this.pathToResolveAgainst = pathToResolveAgainst;
        this.nestedJarHandler = nestedJarHandler;
        this.scanSpec = scanSpec;

        // Fix Spring relative paths with empty zip resource sections
        if (rawPath.endsWith("!")) {
            this.rawPath = rawPath.substring(0, rawPath.length() - 1);
        } else if (rawPath.endsWith("!/")) {
            this.rawPath = rawPath.substring(0, rawPath.length() - 2);
        } else if (rawPath.endsWith("/!")) {
            this.rawPath = rawPath.substring(0, rawPath.length() - 2);
        } else if (rawPath.endsWith("/!/")) {
            this.rawPath = rawPath.substring(0, rawPath.length() - 3);
        } else {
            this.rawPath = rawPath;
        }
    }

    /**
     * A relative path for a module (in JDK9+).
     * 
     * @param moduleRef
     *            The {@link ModuleRef}.
     * @param nestedJarHandler
     *            The {@link NestedJarHandler}.
     * @param log
     *            The log.
     */
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
        this.rawPath = moduleRef.getLocationStr();
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * @return The ClassLoader(s) that should be used to load classes for this classpath element.
     */
    public ClassLoader[] getClassLoaders() {
        return classLoaders;
    }

    /**
     * @return The raw path of this classpath element.
     */
    public String getRawPath() {
        return rawPath;
    }

    /**
     * @return The path of this classpath element, resolved against the parent path.
     */
    public String getResolvedPath() {
        if (!resolvedPathIsCached) {
            resolvedPathCached = FastPathResolver.resolve(pathToResolveAgainst, rawPath);
            resolvedPathIsCached = true;
        }
        return resolvedPathCached;
    }

    /** @return true if the path is an http(s):// URL. */
    public boolean isHttpURL() {
        if (!isHttpURLIsCached) {
            final String resolvedPath = getResolvedPath();
            isHttpURL = resolvedPath.regionMatches(/* ignoreCase = */ true, 0, "http://", 0, 7) || //
                    resolvedPath.regionMatches(/* ignoreCase = */ true, 0, "https://", 0, 8);
            isHttpURLIsCached = true;
        }
        return isHttpURL;
    }

    /**
     * @return true if the path is a jrt:/ URL.
     */
    public boolean isJrtURL() {
        if (!isJrtURLIsCached) {
            final String resolvedPath = getResolvedPath();
            isJrtURL = resolvedPath.regionMatches(/* ignoreCase = */ true, 0, "jrt:/", 0, 5);
            isJrtURLIsCached = true;
        }
        return isJrtURL;
    }

    /**
     * @return The {@link ModuleRef} for this module, if this {@link ClasspathOrModulePathEntry} corresponds to a
     *         module.
     */
    public ModuleRef getModuleRef() {
        return moduleRef;
    }

    /**
     * Try matching the file path one segment at a time, un-percent-escaping the path segments on the filesystem, in
     * case they contain a '%' character (issue 255).
     */
    private static File percentEncodingMatch(final String path) throws IOException {
        final String pathToSplit = !path.equals("/") && path.endsWith("/") ? path.substring(0, path.length() - 1)
                : path;
        final String[] pathSegments = pathToSplit.split("/");
        File dirOrFile = null;
        for (int i = 0; i < pathSegments.length; i++) {
            String pathSegment = pathSegments[i];
            if (i == 0 && pathSegment.isEmpty()) {
                // Handle root directory on Unix
                pathSegment = "/";
            } else if (i == 0 && pathSegment.length() == 2 && Character.isLetter(pathSegment.charAt(0))
                    && pathSegment.charAt(1) == ':') {
                // Handle root directory on Windows
                pathSegment += "/";
            }
            if (i == 0) {
                // Find first (or root) directory
                dirOrFile = new File(pathSegment);
            } else {
                // Find next subdirectory in path
                File nextSubDirOrFile = new File(dirOrFile, pathSegment);
                if (!nextSubDirOrFile.exists()) {
                    // Look through parent directory for entries containing '%'
                    for (final File file : dirOrFile.listFiles()) {
                        if (file.getName().indexOf('%') >= 0) {
                            final String nameNormalized = FastPathResolver.normalizePath(file.getName(),
                                    /* isHttpURL = */ false);
                            if (nameNormalized.equals(pathSegment)) {
                                // The directory on disk contained "%"-encoding that matched the path segment
                                nextSubDirOrFile = file;
                                break;
                            }
                        }
                    }
                }
                dirOrFile = nextSubDirOrFile;
            }
            if (!dirOrFile.exists()) {
                throw new FileNotFoundException("Not found: " + dirOrFile);
            }
            if (!FileUtils.canRead(dirOrFile)) {
                throw new IOException("Cannot read: " + dirOrFile);
            }
        }
        return dirOrFile;
    }

    /**
     * @param log
     *            The log.
     * @return The File object for the resolved path.
     * @throws IOException
     *             if the path cannot be canonicalized.
     */
    public File getFile(final LogNode log) throws IOException {
        if (!fileIsCached) {
            final String path = getResolvedPath();
            if (path == null) {
                throw new IOException(
                        "Path " + rawPath + " could not be resolved relative to " + pathToResolveAgainst);
            }

            if (isJrtURL) {
                // jrt:/ URLs don't correspond to file paths
                throw new IOException("Cannot use jrt:/ URL for non-module classpath entry " + rawPath);
            }

            final int lastPlingIdx = path.lastIndexOf('!');
            if (lastPlingIdx < 0) {
                // No "!" section -- just use the file directly
                fileCached = new File(path);
            } else {
                if (!scanSpec.performScan) {
                    // If only fetching classpath entries, don't extract nested jarfiles.
                    // N.B. this will cause remote jars (at http(s) URLs) to be skipped from the list of classpath
                    // entries when fetching only the classpath, since the FileUtils.canRead() test will fail below,
                    // but this is a pretty obscure usecase.
                    final int firstPlingIdx = path.indexOf('!');
                    final String basePath = path.substring(0, firstPlingIdx);
                    fileCached = new File(basePath);
                } else {
                    try {
                        // Fetch any remote jarfiles, recursively unzip any nested jarfiles, and remove ZipSFX header
                        // from jarfiles that don't start with "PK". In each case a temporary file will be created.
                        // Throws IOException if anything goes wrong.
                        final Entry<File, Set<String>> innermostJarAndRootRelativePaths = //
                                nestedJarHandler.getInnermostNestedJar(path, log);
                        if (innermostJarAndRootRelativePaths != null) {
                            final File innermostJar = innermostJarAndRootRelativePaths.getKey();
                            final Set<String> rootRelativePaths = innermostJarAndRootRelativePaths.getValue();
                            // Get section after last '!' (stripping any initial '/')
                            String packageRoot = path.substring(lastPlingIdx + 1);
                            while (packageRoot.startsWith("/")) {
                                packageRoot = packageRoot.substring(1);
                            }
                            // Check to see if last segment is listed in the set of root relative paths for
                            // the jar -- if so, then this is the classpath base for this jarfile. If not,
                            // then this is a suffix that contained nested jars.
                            if (!packageRoot.isEmpty() && rootRelativePaths.contains(packageRoot)) {
                                jarfilePackageRoot = packageRoot;
                            }
                            fileCached = innermostJar;
                        }
                    } catch (final IOException e) {
                        throw e;
                    } catch (final Exception e) {
                        // Unexpected exception
                        if (log != null) {
                            log.log("Exception while locating jarfile " + rawPath, e);
                        }
                        throw new IOException("Exception while locating jarfile " + rawPath + " : " + e);
                    }
                }
            }
            while (jarfilePackageRoot.startsWith("/")) {
                jarfilePackageRoot = jarfilePackageRoot.substring(1);
            }

            if (fileCached == null) {
                throw new IOException("Could not locate file " + rawPath
                        + (rawPath.equals(path) ? "" : " -- resolved to: " + path));
            }

            if (!FileUtils.canRead(fileCached)) {
                fileCached = percentEncodingMatch(path);
                if (fileCached == null || !FileUtils.canRead(fileCached)) {
                    throw new IOException("Could not locate file " + (fileCached == null ? rawPath : fileCached)
                            + (rawPath.equals(path) ? "" : " -- resolved to: " + path));
                }
            }

            isFileCached = fileCached.isFile();
            isFileIsCached = true;

            if (lastPlingIdx > 0 && !isFileCached) {
                throw new IOException("Expected a jarfile, but found a directory: " + path);
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
     * @return The package root within a jarfile, e.g. if the path is "spring-project.jar!/BOOT-INF/classes", the
     *         package root is "BOOT-INF/classes". Usually empty (""). N.B. this should only be called after
     *         {@link #getFile(LogNode)}, since that method sets this field.
     */
    public String getJarfilePackageRoot() {
        return jarfilePackageRoot;
    }

    /**
     * @param log
     *            The log.
     * @return The canonical path of the {@link File} object corresponding to the resolved path.
     * @throws IOException
     *             If there was an error in canonicalization.
     */
    public String getCanonicalPath(final LogNode log) throws IOException {
        if (!canonicalPathIsCached) {
            final File file = getFile(log);
            // Don't actually do full canonicalization, just use FastPathResolver.resolve,
            // since full path canonicalization can break the correspondence between directories
            // and packages, since soft links are resolved.
            canonicalPathCached = FastPathResolver.resolve(file.getPath());
            canonicalPathIsCached = true;
        }
        return canonicalPathCached;
    }

    /**
     * @param log
     *            The log.
     * @return true if this relative path corresponds with a file.
     * @throws IOException
     *             If the file can't be read.
     */
    public boolean isFile(final LogNode log) throws IOException {
        if (!isFileIsCached) {
            isFileCached = getFile(log).isFile();
            isFileIsCached = true;
        }
        return isFileCached;
    }

    /**
     * @param log
     *            The log.
     * @return True if this relative path corresponds with a directory.
     * @throws IOException
     *             If the file can't be read.
     */
    public boolean isDirectory(final LogNode log) throws IOException {
        if (!isDirectoryIsCached) {
            isDirectoryCached = getFile(log).isDirectory();
            isDirectoryIsCached = true;
        }
        return isDirectoryCached;
    }

    /**
     * @return true if resolved path has a .class extension, ignoring case.
     */
    public boolean isClassfile() {
        return FileUtils.isClassfile(getResolvedPath());
    }

    /** True if this relative path corresponds to a file or directory that exists. */
    private boolean exists(final LogNode log) throws IOException {
        if (!existsIsCached) {
            existsCached = FileUtils.canRead(getFile(log));
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
     * 
     * @param scanSpec
     *            The {@link ScanSpec}.
     * @param log
     *            The log.
     * @return true if this relative path is a valid classpath element.
     */
    public boolean isValidClasspathElement(final ScanSpec scanSpec, final LogNode log) {
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
                log.log("Ignoring invalid classpath element: " + path + " : " + e);
            }
            return false;
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /** Hash based on canonical path. */
    @Override
    public int hashCode() {
        return getResolvedPath().hashCode();
    }

    /** Return true based on equality of resolved paths. */
    @Override
    public boolean equals(final Object o) {
        if (o == null) {
            return false;
        }
        if (!(o instanceof ClasspathOrModulePathEntry)) {
            return false;
        }
        final ClasspathOrModulePathEntry other = (ClasspathOrModulePathEntry) o;
        return getResolvedPath().equals(other.getResolvedPath());
    }

    /** Return the path. */
    @Override
    public String toString() {
        if (isFileCached && fileCached != null
                && !FastPathResolver.resolve(fileCached.toString()).equals(getResolvedPath())) {
            return getResolvedPath() + " -> " + fileCached;
        }
        return getResolvedPath();
    }
}
