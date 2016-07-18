package io.github.lukehutch.fastclasspathscanner.scanner;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;

import io.github.lukehutch.fastclasspathscanner.FastClasspathScanner;
import io.github.lukehutch.fastclasspathscanner.utils.FastManifestParser;
import io.github.lukehutch.fastclasspathscanner.utils.FastPathResolver;
import io.github.lukehutch.fastclasspathscanner.utils.LoggedThread.ThreadLog;

/** A class to sort classpath elements by classpath entry index, then parent path, then relative path. */
class OrderedClasspathElement implements Comparable<OrderedClasspathElement> {
    public final String orderKey;
    public final String parentPath;
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

    public OrderedClasspathElement(final String orderKey, final String parentPath, final String relativePath) {
        this.orderKey = orderKey;
        this.parentPath = parentPath;
        this.relativePath = relativePath;
    }

    public String getResolvedPath() {
        if (!resolvedPathIsCached) {
            resolvedPathCached = FastPathResolver.resolve(parentPath, relativePath);
            resolvedPathIsCached = true;
        }
        return resolvedPathCached;
    }

    public File getFile() {
        if (!fileIsCached) {
            final String path = getResolvedPath();
            if (path == null) {
                throw new RuntimeException(
                        "Path " + relativePath + " could not be resolved relative to " + parentPath);
            }
            fileCached = new File(path);
            fileIsCached = true;
        }
        return fileCached;
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

    public String getCanonicalPath() throws IOException {
        if (!canonicalPathIsCached) {
            final File file = getFile();
            canonicalPathCached = file.getCanonicalPath();
            canonicalPathIsCached = true;
        }
        return canonicalPathCached;
    }

    /** Sort in order of orderKey, then parentURI, then relativePath. */
    @Override
    public int compareTo(final OrderedClasspathElement o) {
        int diff = orderKey.compareTo(o.orderKey);
        if (diff == 0) {
            diff = parentPath.compareTo(o.parentPath);
        }
        if (diff == 0) {
            diff = relativePath.compareTo(o.relativePath);
        }
        return diff;
    }

    @Override
    public boolean equals(final Object o) {
        if (o == null || !(o instanceof OrderedClasspathElement)) {
            return false;
        }
        final OrderedClasspathElement other = (OrderedClasspathElement) o;
        return orderKey.equals(other.orderKey) && parentPath.equals(other.parentPath)
                && relativePath.equals(other.relativePath);
    }

    @Override
    public int hashCode() {
        return orderKey.hashCode() + parentPath.hashCode() * 7 + relativePath.hashCode() * 17;
    }

    @Override
    public String toString() {
        return orderKey + "!" + parentPath + "!" + relativePath;
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

    private static boolean isEarliestOccurrenceOfPath(final String canonicalPath,
            final OrderedClasspathElement orderedElement,
            final ConcurrentHashMap<String, OrderedClasspathElement> pathToEarliestOrderedElement) {
        OrderedClasspathElement olderOrderedElement = pathToEarliestOrderedElement.put(canonicalPath,
                orderedElement);
        if (olderOrderedElement == null) {
            // First occurrence of this path
            return true;
        }
        final int diff = olderOrderedElement.compareTo(orderedElement);
        if (diff == 0) {
            // Should not happen, because relative paths are unique within a given filesystem or jar
            return false;
        } else if (diff < 0) {
            // olderOrderKey comes before orderKey, so this relative path is masked by an earlier one.
            // Need to put older order key back in map, avoiding race condition
            for (;;) {
                final OrderedClasspathElement nextOlderOrderedElt = pathToEarliestOrderedElement.put(canonicalPath,
                        olderOrderedElement);
                if (nextOlderOrderedElt.compareTo(olderOrderedElement) <= 0) {
                    break;
                }
                olderOrderedElement = nextOlderOrderedElt;
            }
            return false;
        } else {
            // orderKey comes before olderOrderKey, so this relative path masks an earlier one.
            return true;
        }
    }

    public boolean isValid(final ConcurrentHashMap<String, OrderedClasspathElement> pathToEarliestOrderKey,
            final boolean blacklistSystemJars, final ConcurrentHashMap<String, String> knownJREPaths,
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
        // Check that this classpath element is the earliest instance of the same canonical path
        // on the classpath (i.e. only scan a classpath element once
        String canonicalPath;
        try {
            canonicalPath = getCanonicalPath();
        } catch (final IOException e) {
            if (FastClasspathScanner.verbose) {
                log.log("Could not canonicalize path: " + getResolvedPath());
            }
            return false;
        }
        if (!isEarliestOccurrenceOfPath(canonicalPath, this, pathToEarliestOrderKey)) {
            if (FastClasspathScanner.verbose) {
                log.log("Ignoring duplicate classpath element: " + getResolvedPath());
            }
            return false;
        }
        final boolean isFile = isFile();
        final boolean isDirectory = isDirectory();
        if (!isFile && !isDirectory) {
            if (FastClasspathScanner.verbose) {
                log.log("Ignoring invalid classpath element: " + getResolvedPath());
            }
            return false;
        }
        if (isFile && !isJar(getResolvedPath())) {
            if (FastClasspathScanner.verbose) {
                log.log("Ignoring non-jar file on classpath: " + getResolvedPath());
            }
            return false;
        }
        if (isFile && blacklistSystemJars && isJREJar(getFile(), /* ancestralScanDepth = */2, knownJREPaths, log)) {
            // Don't scan system jars if they are blacklisted
            if (FastClasspathScanner.verbose) {
                log.log("Skipping JRE jar: " + getResolvedPath());
            }
            return false;
        }
        return true;
    }
}