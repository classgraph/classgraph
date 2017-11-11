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
package io.github.lukehutch.fastclasspathscanner.utils;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class JarUtils {
    private static final List<String> JRE_PATHS = new ArrayList<>();
    private static String RT_JAR_PATH = null;

    private static String getProperty(final String propName) {
        try {
            return System.getProperty(propName);
        } catch (final SecurityException e) {
            return null;
        }
    }

    private static void addJREPath(final File dir, final Set<String> jrePathsSet) {
        if (ClasspathUtils.canRead(dir) && dir.isDirectory()) {
            String path = dir.getPath();
            if (!path.endsWith(File.separator)) {
                path += File.separator;
            }
            final String jrePath = FastPathResolver.resolve("", path);
            if (!jrePath.isEmpty()) {
                jrePathsSet.add(jrePath);
            }
            try {
                String canonicalPath = dir.getCanonicalPath();
                if (!canonicalPath.endsWith(File.separator)) {
                    canonicalPath += File.separator;
                }
                final String jreCanonicalPath = FastPathResolver.resolve("", canonicalPath);
                if (!jreCanonicalPath.equals(jrePath) && !jreCanonicalPath.isEmpty()) {
                    jrePathsSet.add(jreCanonicalPath);
                }
            } catch (IOException | SecurityException e) {
            }
        }
    }

    // Find JRE jar dirs.
    // TODO: Update for JDK9.
    static {
        final Set<String> jrePathsSet = new HashSet<>();
        final String javaHome = getProperty("java.home");
        if (javaHome != null && !javaHome.isEmpty()) {
            final File javaHomeFile = new File(javaHome);
            addJREPath(javaHomeFile, jrePathsSet);
            final File libFile = new File(javaHomeFile, "lib");
            addJREPath(libFile, jrePathsSet);
            final File extFile = new File(libFile, "ext");
            addJREPath(extFile, jrePathsSet);
            final File rtJarFile = new File(libFile, "rt.jar");
            if (ClasspathUtils.canRead(rtJarFile)) {
                RT_JAR_PATH = rtJarFile.getPath();
            }
            if (javaHomeFile.getName().equals("jre")) {
                // Handle jre/../lib/tools.jar
                final File parent = javaHomeFile.getParentFile();
                if (parent != null) {
                    final File parentLibFile = new File(parent, "lib");
                    addJREPath(parentLibFile, jrePathsSet);
                }
            }
        }
        final String javaExtDirs = getProperty("java.ext.dirs");
        if (javaExtDirs != null) {
            for (final String javaExtDir : smartPathSplit(javaExtDirs)) {
                if (!javaExtDir.isEmpty()) {
                    final File javaExtDirFile = new File(javaExtDir);
                    addJREPath(javaExtDirFile, jrePathsSet);
                }
            }
        }

        // Add special-case path for Mac OS X, this is not always picked up
        // from java.home or java.ext.dirs
        addJREPath(new File("/System/Library/Java"), jrePathsSet);

        JRE_PATHS.addAll(jrePathsSet);
        Collections.sort(JRE_PATHS);
    }

    /**
     * Split a path on File.pathSeparator (':' on Linux, ';' on Windows), but also allow for the use of URLs with
     * protocol specifiers, e.g. "http://domain/jar1.jar:http://domain/jar2.jar". This is really not even handled by
     * the JRE, in all likelihood, but it's better to be robust.
     */
    public static String[] smartPathSplit(final String pathStr) {
        // Fast path for Windows
        if (File.pathSeparatorChar == ':') {
            // For Linux, don't split on URL protocol boundaries. This will allow for HTTP(S) jars to be
            // given in java.class.path. (The JRE may not even support them, but we may as well do so.)
            final Set<Integer> splitPoints = new HashSet<>();
            splitPoints.add(-1);
            splitPoints.add(pathStr.length());
            for (int i = pathStr.indexOf(File.pathSeparator); i >= 0; i = pathStr.indexOf(File.pathSeparator,
                    i + 1)) {
                splitPoints.add(i);
            }
            final String pathStrLower = pathStr.toLowerCase();
            for (int i = pathStrLower.indexOf("http://"); i >= 0; i = pathStrLower.indexOf("http://", i + 7)) {
                splitPoints.remove(i + 4);
            }
            for (int i = pathStrLower.indexOf("https://"); i >= 0; i = pathStrLower.indexOf("https://", i + 8)) {
                splitPoints.remove(i + 5);
            }
            // We can't really do this for "jar:" or "file:", because we would get spurious matches like
            // "/path/to/jar1.jar:/path/to/jar2.jar". However, we should probably handle escaping ("\\:")
            // as long as the file separator is not "\\".
            for (int i = pathStrLower.indexOf("\\:"); i >= 0; i = pathStrLower.indexOf("\\:", i + 2)) {
                splitPoints.remove(i + 1);
            }
            final List<Integer> splitPointsSorted = new ArrayList<>(splitPoints);
            Collections.sort(splitPointsSorted);
            final List<String> parts = new ArrayList<>();
            for (int i = 1; i < splitPointsSorted.size(); i++) {
                final int idx0 = splitPointsSorted.get(i - 1);
                final int idx1 = splitPointsSorted.get(i);
                // Trim, and unescape "\\:" 
                String part = pathStr.substring(idx0 + 1, idx1).trim();
                part = part.replaceAll("\\\\:", ":");
                // Remove empty path components
                if (!part.isEmpty()) {
                    parts.add(part);
                }
            }
            return parts.toArray(new String[parts.size()]);
        } else {
            // Trim path components, and strip out empty components
            final List<String> partsFiltered = new ArrayList<>();
            for (final String part : pathStr.split(File.pathSeparator)) {
                final String partFiltered = part.trim();
                if (!partFiltered.isEmpty()) {
                    partsFiltered.add(partFiltered);
                }
            }
            return partsFiltered.toArray(new String[partsFiltered.size()]);
        }
    }

    /** Get the path of rt.jar */
    public static String getRtJarPath() {
        return RT_JAR_PATH;
    }

    /** Log the Java version and the JRE paths that were found. */
    public static void logJavaInfo(final LogNode log) {
        if (log != null) {
            log.log("Operating system: " + getProperty("os.name") + " " + getProperty("os.version") + " "
                    + getProperty("os.arch"));
            log.log("Java version: " + getProperty("java.version") + " (" + getProperty("java.vendor") + ")");
            final LogNode javaLog = log.log("JRE paths:");
            for (final String jrePath : JRE_PATHS) {
                javaLog.log(jrePath);
            }
            if (RT_JAR_PATH != null) {
                javaLog.log(RT_JAR_PATH);
            }
        }
    }

    /** Determine whether a given jarfile is in a JRE system directory (jre, jre/lib, jre/lib/ext, etc.). */
    public static boolean isJREJar(final String filePath, final LogNode log) {
        for (final String jrePathPrefix : JRE_PATHS) {
            if (filePath.startsWith(jrePathPrefix)) {
                return true;
            }
        }
        return false;
    }

    /** Returns true if the path ends with a jarfile extension, ignoring case. */
    public static boolean isJar(final String path) {
        final int len = path.length();
        final boolean isJar = path.regionMatches(true, len - 4, ".jar", 0, 4) //
                || path.regionMatches(true, len - 4, ".zip", 0, 4) //
                || path.regionMatches(true, len - 4, ".war", 0, 4) //
                || path.regionMatches(true, len - 4, ".car", 0, 4) //
                || path.regionMatches(true, len - 4, ".ear", 0, 4) //
                || path.regionMatches(true, len - 4, ".sar", 0, 4) //
                || path.regionMatches(true, len - 4, ".har", 0, 4) //
                || path.regionMatches(true, len - 4, ".par", 0, 4) //
                || path.regionMatches(true, len - 6, ".wsjar", 0, 6);
        if (!isJar) {
            // Support URLs of the form "http://domain.com/path/to/jarfile.jar?version=2"
            final int urlParamIdx = path.indexOf('?');
            if (urlParamIdx > 0) {
                return isJar(path.substring(0, urlParamIdx));
            }
        }
        return isJar;
    }

    /** Returns the leafname of a path. */
    public static String leafName(final String path) {
        final int bangIdx = path.indexOf("!");
        final int endIdx = bangIdx >= 0 ? bangIdx : path.length();
        int leafStartIdx = 1 + (File.separatorChar == '/' ? path.lastIndexOf('/', endIdx)
                : Math.max(path.lastIndexOf('/', endIdx), path.lastIndexOf(File.separatorChar, endIdx)));
        // In case of temp files (for jars extracted from within jars), remove the temp filename prefix
        // -- see NestedJarHandler.unzipToTempFile()
        int sepIdx = path.indexOf(NestedJarHandler.TEMP_FILENAME_LEAF_SEPARATOR);
        if (sepIdx >= 0) {
            sepIdx += NestedJarHandler.TEMP_FILENAME_LEAF_SEPARATOR.length();
        }
        leafStartIdx = Math.max(leafStartIdx, sepIdx);
        leafStartIdx = Math.min(leafStartIdx, endIdx);
        return path.substring(leafStartIdx, endIdx);
    }
}
