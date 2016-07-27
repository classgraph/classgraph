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
import java.util.concurrent.ConcurrentHashMap;

import io.github.lukehutch.fastclasspathscanner.utils.FastManifestParser;
import io.github.lukehutch.fastclasspathscanner.utils.FastPathResolver;
import io.github.lukehutch.fastclasspathscanner.utils.LogNode;

class ClasspathRelativePath {
    private final String pathToResolveAgainst;
    private final String relativePath;

    private String resolvedPathCached;
    private boolean resolvedPathIsCached;
    private File fileCached;
    private boolean fileIsCached;
    private String canonicalPathCached;
    private boolean canonicalPathIsCached;
    private boolean isFileCached;
    private boolean isFileIsCached;
    private boolean isDirectoryCached;
    private boolean isDirectoryIsCached;
    private boolean existsCached;
    private boolean existsIsCached;

    // -------------------------------------------------------------------------------------------------------------

    public ClasspathRelativePath(final String pathToResolveAgainst, final String relativePath) {
        this.pathToResolveAgainst = pathToResolveAgainst;
        this.relativePath = relativePath;
    }

    /** Hash based on canonical path. */
    @Override
    public int hashCode() {
        try {
            return getCanonicalPath().hashCode();
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
        if (!(o instanceof ClasspathRelativePath)) {
            return false;
        }
        final ClasspathRelativePath other = (ClasspathRelativePath) o;
        String thisCp;
        try {
            thisCp = getCanonicalPath();
            final String otherCp = other.getCanonicalPath();
            if (thisCp == null || otherCp == null) {
                return false;
            }
            return thisCp.equals(otherCp);
        } catch (final IOException e) {
            return false;
        }
    }

    @Override
    public String toString() {
        try {
            return getCanonicalPath();
        } catch (final IOException e) {
            return getResolvedPath();
        }
    }

    // -------------------------------------------------------------------------------------------------------------

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
            fileCached = new File(path).getCanonicalFile();
            fileIsCached = true;
        }
        return fileCached;
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

    public boolean isFile() throws IOException {
        if (!isFileIsCached) {
            isFileCached = getFile().isFile();
            isFileIsCached = true;
        }
        return isFileCached;
    }

    public boolean isDirectory() throws IOException {
        if (!isDirectoryIsCached) {
            isDirectoryCached = getFile().isDirectory();
            isDirectoryIsCached = true;
        }
        return isDirectoryCached;
    }

    /** Returns true if path has a .class extension, ignoring case. */
    public static boolean isClassfile(final String path) {
        final int len = path.length();
        return len > 6 && path.regionMatches(true, len - 6, ".class", 0, 6);
    }

    /** Returns true if resolved path has a .class extension, ignoring case. */
    public boolean isClassfile() {
        return isClassfile(getResolvedPath());
    }

    private boolean exists() throws IOException {
        if (!existsIsCached) {
            existsCached = getFile().exists();
            existsIsCached = true;
        }
        return existsCached;
    }

    // -------------------------------------------------------------------------------------------------------------

    /** Returns true if the path ends with a jarfile extension, ignoring case. */
    private static boolean isJar(final String path) {
        final int len = path.length();
        final int extIdx = len - 3;
        return len > 4 && path.charAt(len - 4) == '.' // 
                && (path.regionMatches(true, extIdx, "jar", 0, 3) //
                        || path.regionMatches(true, extIdx, "zip", 0, 3) //
                        || path.regionMatches(true, extIdx, "war", 0, 3) //
                        || path.regionMatches(true, extIdx, "car", 0, 3));
    }

    /**
     * Recursively search within ancestral directories of a jarfile to see if rt.jar is present, in order to
     * determine if the given jarfile is part of the JRE. This would typically be called with an initial
     * ancestralScandepth of 2, since JRE jarfiles can be in the lib or lib/ext directories of the JRE.
     */
    private static boolean isJREJar(final File file, final int ancestralScanDepth,
            final ConcurrentHashMap<String, String> knownJREPaths,
            final ConcurrentHashMap<String, String> knownNonJREPaths, final LogNode log) {
        if (ancestralScanDepth == 0) {
            return false;
        } else {
            final File parent = file.getParentFile();
            if (parent == null) {
                return false;
            }
            final String parentPathStr = parent.getPath();
            if (knownJREPaths.containsKey(parentPathStr)) {
                return true;
            }
            if (knownNonJREPaths.containsKey(parentPathStr)) {
                return false;
            }
            File rt = new File(parent, "jre/lib/rt.jar");
            if (!rt.exists()) {
                rt = new File(parent, "lib/rt.jar");
                if (!rt.exists()) {
                    rt = new File(parent, "rt.jar");
                }
            }
            boolean isJREJar = false;
            if (rt.exists()) {
                // Found rt.jar; check its manifest file to make sure it's the JRE's rt.jar and not something else 
                final FastManifestParser manifest = new FastManifestParser(rt, log);
                if (manifest.isSystemJar) {
                    // Found the JRE's rt.jar
                    isJREJar = true;
                }
            }
            if (!isJREJar) {
                isJREJar = isJREJar(parent, ancestralScanDepth - 1, knownJREPaths, knownNonJREPaths, log);
            }
            if (!isJREJar) {
                knownNonJREPaths.put(parentPathStr, parentPathStr);
            } else {
                knownJREPaths.put(parentPathStr, parentPathStr);
            }
            return isJREJar;
        }
    }

    public boolean isValid(final ScanSpec scanSpec, final ConcurrentHashMap<String, String> knownJREPaths,
            final ConcurrentHashMap<String, String> knownNonJREPaths,
            final ClasspathRelativePathToElementMap classpathElementMap, final LogNode log)
            throws InterruptedException {
        // Get absolute URI and File for classpathElt
        final String path = getResolvedPath();
        if (path == null) {
            // Got an http: or https: URI as a classpath element
            if (log != null) {
                log.log("Ignoring non-local classpath element: " + relativePath);
            }
            return false;
        }
        // Check if classpath element is already in map -- saves some of the work below, and in the singleton
        // creation after this method exits.
        if (classpathElementMap.get(this) != null) {
            if (log != null) {
                log.log("Ignoring duplicate classpath element: " + path);
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
                if (!isJar(getResolvedPath())) {
                    if (log != null) {
                        log.log("Ignoring non-jar file on classpath: " + path);
                    }
                    return false;
                }
                if (!scanSpec.scanJars) {
                    if (log != null) {
                        log.log("Ignoring jarfile, as jars are not being scanned: " + path);
                    }
                    return false;
                }
                if (scanSpec.blacklistSystemJars()
                        && isJREJar(getFile(), /* ancestralScanDepth = */2, knownJREPaths, knownNonJREPaths, log)) {
                    // Don't scan system jars if they are blacklisted
                    if (log != null) {
                        log.log("Ignoring JRE jar: " + path);
                    }
                    return false;
                }
                if (!scanSpec.jarIsWhitelisted(getFile().getName())) {
                    if (log != null) {
                        log.log("Ignoring jarfile that did not match whitelist/blacklist criteria: " + path);
                    }
                    return false;
                }
            } else {
                if (!scanSpec.scanDirs) {
                    if (log != null) {
                        log.log("Ignoring directory, as directories are not being scanned: " + path);
                    }
                    return false;
                }
            }
        } catch (final IOException e) {
            if (log != null) {
                log.log("Could not canonicalize path: " + path);
            }
            return false;
        }
        return true;
    }
}