package io.github.lukehutch.fastclasspathscanner.scanner;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;

import io.github.lukehutch.fastclasspathscanner.FastClasspathScanner;
import io.github.lukehutch.fastclasspathscanner.utils.FastManifestParser;
import io.github.lukehutch.fastclasspathscanner.utils.FastPathResolver;
import io.github.lukehutch.fastclasspathscanner.utils.LoggedThread.ThreadLog;

class ClasspathElement {
    public final String parentCanonicalPath;
    public final String pathToResolveAgainst;
    public final String relativePath;

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

    public ClasspathElement(final String parentCanonicalPath, final String pathToResolveAgainst,
            final String relativePath) {
        this.parentCanonicalPath = parentCanonicalPath;
        this.pathToResolveAgainst = pathToResolveAgainst;
        this.relativePath = relativePath;
    }

    /** Get the path of this classpath element, resolved against the parent path. */
    public String getResolvedPath() {
        if (!resolvedPathIsCached) {
            resolvedPathCached = FastPathResolver.resolve(pathToResolveAgainst, relativePath);
            resolvedPathIsCached = true;
        }
        return resolvedPathCached;
    }

    /** Get the File object for the resolved path. */
    public File getFile() {
        if (!fileIsCached) {
            final String path = getResolvedPath();
            if (path == null) {
                throw new RuntimeException(
                        "Path " + relativePath + " could not be resolved relative to " + pathToResolveAgainst);
            }
            fileCached = new File(path);
            fileIsCached = true;
        }
        return fileCached;
    }

    /**
     * Gets the canonical path of the File object corresponding to the resolved path, or null if the path could not
     * be canonicalized.
     */
    public String getCanonicalPath() {
        if (!canonicalPathIsCached) {
            final File file = getFile();
            try {
                canonicalPathCached = file.getCanonicalPath();
            } catch (final IOException e) {
                // Return null
            }
            canonicalPathIsCached = true;
        }
        return canonicalPathCached;
    }

    public boolean isFile() {
        if (!isFileIsCached) {
            isFileCached = getFile().isFile();
            isFileIsCached = true;
        }
        return isFileCached;
    }

    public boolean isDirectory() {
        if (!isDirectoryIsCached) {
            isDirectoryCached = getFile().isDirectory();
            isDirectoryIsCached = true;
        }
        return isDirectoryCached;
    }

    public boolean exists() {
        if (!existsIsCached) {
            existsCached = getFile().exists();
            existsIsCached = true;
        }
        return existsCached;
    }

    // -------------------------------------------------------------------------------------------------------------

    /** Returns true if the path ends with a JAR extension, matching case. */
    private static boolean isJarMatchCase(final String path) {
        return path.length() > 4 && path.charAt(path.length() - 4) == '.' // 
                && path.endsWith(".jar") || path.endsWith(".zip") || path.endsWith(".war") || path.endsWith(".car");
    }

    /** Returns true if the path ends with a JAR extension, ignoring case. */
    private static boolean isJar(final String path) {
        return isJarMatchCase(path) || isJarMatchCase(path.toLowerCase());
    }

    /**
     * Recursively search within ancestral directories of a jarfile to see if rt.jar is present, in order to
     * determine if the given jarfile is part of the JRE. This would typically be called with an initial
     * ancestralScandepth of 2, since JRE jarfiles can be in the lib or lib/ext directories of the JRE.
     */
    private static boolean isJREJar(final File file, final int ancestralScanDepth,
            final ConcurrentHashMap<String, String> knownJREPaths, final ThreadLog log) {
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
            File rt = new File(parent, "rt.jar");
            if (!rt.exists()) {
                rt = new File(new File(parent, "lib"), "rt.jar");
                if (!rt.exists()) {
                    rt = new File(new File(new File(parent, "jre"), "lib.jar"), "rt.jar");
                }
            }
            if (rt.exists()) {
                // Found rt.jar; check its manifest file to make sure it's the JRE's rt.jar and not something else 
                final FastManifestParser manifest = new FastManifestParser(rt, log);
                if (manifest.isSystemJar) {
                    // Found the JRE's rt.jar
                    knownJREPaths.put(parentPathStr, parentPathStr);
                    return true;
                }
            }
            return isJREJar(parent, ancestralScanDepth - 1, knownJREPaths, log);
        }
    }

    public boolean isValid(final ScanSpec scanSpec, final ConcurrentHashMap<String, String> knownJREPaths,
            final ThreadLog log) {
        // Get absolute URI and File for classpathElt
        final String path = getResolvedPath();
        if (path == null) {
            // Got an http: or https: URI as a classpath element
            if (FastClasspathScanner.verbose) {
                log.log("Skipping non-local classpath element: " + relativePath);
            }
            return false;
        }
        if (!exists()) {
            if (FastClasspathScanner.verbose) {
                log.log("Classpath element does not exist: " + getResolvedPath());
            }
            return false;
        }
        final boolean isFile = isFile();
        final boolean isDirectory = isDirectory();
        if (isFile != !isDirectory) {
            // Exactly one of isFile and isDirectory should be true
            if (FastClasspathScanner.verbose) {
                log.log("Ignoring invalid classpath element: " + getResolvedPath());
            }
            return false;
        }
        if (isFile) {
            // If a classpath entry is a file, it must be a jar
            if (!isJar(getResolvedPath())) {
                if (FastClasspathScanner.verbose) {
                    log.log("Ignoring non-jar file on classpath: " + getResolvedPath());
                }
                return false;
            }
            if (!scanSpec.scanJars) {
                if (FastClasspathScanner.verbose) {
                    log.log("Skipping jarfile, as jars are not being scanned: " + getResolvedPath());
                }
                return false;
            }
            if (scanSpec.blacklistSystemJars()
                    && isJREJar(getFile(), /* ancestralScanDepth = */2, knownJREPaths, log)) {
                // Don't scan system jars if they are blacklisted
                if (FastClasspathScanner.verbose) {
                    log.log("Skipping JRE jar: " + getResolvedPath());
                }
                return false;
            }
            if (!scanSpec.jarIsWhitelisted(getFile().getName())) {
                if (FastClasspathScanner.verbose) {
                    log.log("Skipping jarfile that did not match whitelist/blacklist criteria: "
                            + getResolvedPath());
                }
                return false;
            }
        } else {
            if (!scanSpec.scanDirs) {
                if (FastClasspathScanner.verbose) {
                    log.log("Skipping directory, as directories are not being scanned: " + getResolvedPath());
                }
                return false;
            }
        }
        return true;
    }
}