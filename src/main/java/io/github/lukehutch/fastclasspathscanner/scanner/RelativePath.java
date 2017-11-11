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
 * Copyright (c) 2016 Luke Hutchison
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
package io.github.lukehutch.fastclasspathscanner.scanner;

import java.io.File;
import java.io.IOException;

import io.github.lukehutch.fastclasspathscanner.utils.ClasspathUtils;
import io.github.lukehutch.fastclasspathscanner.utils.FastPathResolver;
import io.github.lukehutch.fastclasspathscanner.utils.FileUtils;
import io.github.lukehutch.fastclasspathscanner.utils.JarUtils;
import io.github.lukehutch.fastclasspathscanner.utils.LogNode;
import io.github.lukehutch.fastclasspathscanner.utils.NestedJarHandler;

/**
 * A relative path. This is used for paths relative to the current directory (for classpath elements), and also for
 * relative paths within classpath elements (e.g. the files within a ZipFile).
 */
class RelativePath {
    /** The ClassLoader(s) used to load classes for this classpath element */
    private final ClassLoader[] classLoaders;

    /** Base path for path resolution. */
    private final String pathToResolveAgainst;

    /** The relative path. */
    private final String relativePath;

    /** If true, this is a jarfile. */
    private final boolean isJar;
    /**
     * If isJar is true, this gives the trailing zip-internal path, if the section of the path after the last '!' is
     * not a jarfile.
     */
    private String zipClasspathBaseDir = "";
    /** Handler for nested jars. */
    private final NestedJarHandler nestedJarHandler;

    /** The resolved path. */
    private String resolvedPathCached;
    /** True if the path has been resolved. */
    private boolean resolvedPathIsCached;

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

    // -------------------------------------------------------------------------------------------------------------

    /**
     * A relative path. This is used for paths relative to the current directory (for classpath elements), and also
     * for relative paths within classpath elements (e.g. the files within a ZipFile).
     */
    public RelativePath(final String pathToResolveAgainst, final String relativePath,
            final ClassLoader[] classLoaders, final NestedJarHandler nestedJarHandler) {
        this.classLoaders = classLoaders;
        this.pathToResolveAgainst = pathToResolveAgainst;
        this.nestedJarHandler = nestedJarHandler;

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

        this.isJar = this.relativePath.contains("!") || JarUtils.isJar(this.relativePath);
    }

    /** Hash based on canonical path. */
    @Override
    public int hashCode() {
        try {
            return getCanonicalPath().hashCode() + zipClasspathBaseDir.hashCode() * 57;
        } catch (final IOException e) {
            return 0;
        }
    }

    /** Return true based on equality of canonical paths. */
    @Override
    public boolean equals(final Object o) {
        if (o == null) {
            return false;
        }
        if (!(o instanceof RelativePath)) {
            return false;
        }
        final RelativePath other = (RelativePath) o;
        String thisCp;
        try {
            thisCp = getCanonicalPath();
            final String otherCp = other.getCanonicalPath();
            if (thisCp == null || otherCp == null) {
                return false;
            }
            if (!thisCp.equals(otherCp)) {
                return false;
            }
            return getZipClasspathBaseDir().equals(other.getZipClasspathBaseDir());
        } catch (final IOException e) {
            return false;
        }
    }

    /** Return the canonical path. */
    @Override
    public String toString() {
        try {
            return zipClasspathBaseDir.isEmpty() ? getCanonicalPath()
                    : getCanonicalPath() + "!" + zipClasspathBaseDir;
        } catch (final IOException e) {
            return getResolvedPath();
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /** Get the ClassLoader(s) that should be used to load classes for this classpath element */
    public ClassLoader[] getClassLoaders() {
        return classLoaders;
    }

    /** Get the path of this classpath element, resolved against the parent path. */
    public String getResolvedPath() {
        if (!resolvedPathIsCached) {
            resolvedPathCached = FastPathResolver.resolve(pathToResolveAgainst, relativePath);
            resolvedPathIsCached = true;
        }
        return resolvedPathCached;
    }

    /**
     * Get the File object for the resolved path.
     * 
     * @throws IOException
     *             if the path cannot be canonicalized.
     */
    public File getFile() throws IOException {
        if (!fileIsCached) {
            final String path = getResolvedPath();
            if (path == null) {
                throw new IOException(
                        "Path " + relativePath + " could not be resolved relative to " + pathToResolveAgainst);
            }

            // Check if this is a nested or remote jarfile
            final boolean isRemote = path.startsWith("http://") || path.startsWith("https://");
            final int plingIdx = path.indexOf('!');
            if (plingIdx > 0 || isRemote) {
                // Check that each segment of path is a jarfile, optionally excluding the last segment
                final String[] parts = path.split("!");
                for (int i = 0, ii = parts.length - 1; i < ii; i++) {
                    if (!JarUtils.isJar(parts[i])) {
                        throw new IOException("Path " + path + " uses nested jar syntax, "
                                + "but contains a segment that does not have a jar extension");
                    }
                }
                String nestedJarPath;
                if (parts.length > 1 && !JarUtils.isJar(parts[parts.length - 1])) {
                    // Last segment is not a jarfile, so it represents a classpath root within the jarfile
                    // corresponding to the second-to-last element
                    zipClasspathBaseDir = parts[parts.length - 1];
                    if (zipClasspathBaseDir.startsWith("/")) {
                        zipClasspathBaseDir = zipClasspathBaseDir.substring(1);
                    }
                    nestedJarPath = path.substring(0, path.lastIndexOf('!'));
                } else {
                    nestedJarPath = path;
                }
                // Recursively unzip the nested jarfiles (or fetch remote jarfiles) to temporary files,
                // then return the innermost (or downloaded) jarfile. Throws IOException if anything goes wrong.
                try {
                    fileCached = nestedJarHandler.getInnermostNestedJar(nestedJarPath);
                } catch (final Exception e) {
                    throw new IOException("Exception while getting jarfile " + relativePath, e);
                }
                if (fileCached == null || !ClasspathUtils.canRead(fileCached)) {
                    throw new IOException("Could not find jarfile " + relativePath);
                }

            } else {
                fileCached = new File(path);
            }
            try {
                fileCached = fileCached.getCanonicalFile();
            } catch (final IOException e) {
                throw new IOException("Could not canonicalize path " + path, e);
            } catch (final SecurityException e) {
                throw new IOException("Could not canonicalize path " + path, e);
            }
            fileIsCached = true;
        }
        return fileCached;
    }

    /**
     * If non-empty, this path represents a classpath root within a jarfile, e.g. if the path is
     * "spring-project.jar!/BOOT-INF/classes", the zipClasspathBaseDir is "BOOT-INF/classes".
     */
    public String getZipClasspathBaseDir() {
        return zipClasspathBaseDir;
    }

    /**
     * Gets the canonical path of the File object corresponding to the resolved path.
     */
    public String getCanonicalPath() throws IOException {
        if (!canonicalPathIsCached) {
            final File file = getFile();
            canonicalPathCached = file.getPath();
            canonicalPathIsCached = true;
        }
        return canonicalPathCached;
    }

    /** True if this relative path corresponds with a file. */
    public boolean isFile() throws IOException {
        if (!isFileIsCached) {
            isFileCached = getFile().isFile();
            isFileIsCached = true;
        }
        return isFileCached;
    }

    /** True if this relative path corresponds with a directory. */
    public boolean isDirectory() throws IOException {
        if (!isDirectoryIsCached) {
            isDirectoryCached = getFile().isDirectory();
            isDirectoryIsCached = true;
        }
        return isDirectoryCached;
    }

    /** True if this relative path corresponds with a jarfile. */
    public boolean isJar() {
        return isJar;
    }

    /** Returns true if resolved path has a .class extension, ignoring case. */
    public boolean isClassfile() {
        return FileUtils.isClassfile(getResolvedPath());
    }

    /** True if this relative path corresponds to a file or directory that exists. */
    private boolean exists() throws IOException {
        if (!existsIsCached) {
            existsCached = ClasspathUtils.canRead(getFile());
            existsIsCached = true;
        }
        return existsCached;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * True if this relative path is a valid classpath element: that its path can be canonicalized, that it exists,
     * that it is a jarfile or directory, that it is not a blacklisted jar, that it should be scanned, etc.
     */
    public boolean isValidClasspathElement(final ScanSpec scanSpec, final LogNode log) throws InterruptedException {
        // Get absolute URI and File for classpathElt
        final String path = getResolvedPath();
        if (path == null) {
            // Got an http: or https: URI as a classpath element
            if (log != null) {
                log.log("Ignoring non-local classpath element: " + relativePath);
            }
            return false;
        }
        try {
            if (!exists()) {
                if (log != null) {
                    log.log("Classpath element does not exist: " + path);
                }
                return false;
            }
            final boolean isFile = isFile();
            final boolean isDirectory = isDirectory();
            if (isFile != !isDirectory) {
                // Exactly one of isFile and isDirectory should be true
                if (log != null) {
                    log.log("Ignoring invalid classpath element: " + path);
                }
                return false;
            }
            if (isFile) {
                // If a classpath entry is a file, it must be a jar
                final String canonicalPath = getCanonicalPath();
                if (!JarUtils.isJar(canonicalPath)) {
                    if (log != null) {
                        log.log("Ignoring non-jar file on classpath: " + path);
                    }
                    return false;
                }
                if (scanSpec.blacklistSystemJars() && JarUtils.isJREJar(path, log)) {
                    // Don't scan system jars if they are blacklisted
                    if (log != null) {
                        log.log("Ignoring JRE jar: " + path);
                    }
                    return false;
                }
            }
        } catch (final IOException e) {
            if (log != null) {
                log.log("Could not canonicalize path: " + path, e);
            }
            return false;
        }
        return true;
    }
}