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
            jrePathsSet.add(path);
            try {
                String canonicalPath = dir.getCanonicalPath();
                if (!canonicalPath.endsWith(File.separator)) {
                    canonicalPath += File.separator;
                }
                if (!canonicalPath.equals(path)) {
                    jrePathsSet.add(canonicalPath);
                }
            } catch (IOException | SecurityException e) {
            }
        }
    }

    // Find JRE jar dirs
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
        }
        final String javaExtDirs = getProperty("java.ext.dirs");
        if (javaExtDirs != null) {
            for (final String javaExtDir : javaExtDirs.split(File.pathSeparator)) {
                if (!javaExtDir.isEmpty()) {
                    final File javaExtDirFile = new File(javaExtDir);
                    addJREPath(javaExtDirFile, jrePathsSet);
                }
            }
        }
        JRE_PATHS.addAll(jrePathsSet);
    }

    /** Get the path of rt.jar */
    public static String getRtJarPath() {
        return RT_JAR_PATH;
    }

    /** Log the Java version and the JRE paths that were found. */
    public static void logJavaInfo(final LogNode log) {
        if (log != null) {
            final LogNode javaLog = log.log("Java info");
            javaLog.log("java.version = " + getProperty("java.version"));
            javaLog.log("java.vendor = " + getProperty("java.vendor"));
            final LogNode jrePathsLog = javaLog.log("JRE paths" + (JRE_PATHS.isEmpty() ? ": none found" : ""));
            for (final String jrePath : JRE_PATHS) {
                jrePathsLog.log(jrePath);
            }
            javaLog.log("rt.jar path: " + (RT_JAR_PATH == null ? "unknown" : RT_JAR_PATH));
        }
    }

    /** Returns true if the path ends with a jarfile extension, ignoring case. */
    public static boolean isJar(final String path) {
        final int len = path.length();
        return path.regionMatches(true, len - 4, ".jar", 0, 4) //
                || path.regionMatches(true, len - 4, ".zip", 0, 4) //
                || path.regionMatches(true, len - 4, ".war", 0, 4) //
                || path.regionMatches(true, len - 4, ".car", 0, 4) //
                || path.regionMatches(true, len - 6, ".wsjar", 0, 6);
    }

    /** Returns the leafname of a path. */
    public static String leafName(final String path) {
        final int lastSlashIdx = File.separatorChar == '/' ? path.lastIndexOf('/')
                : Math.max(path.lastIndexOf('/'), path.lastIndexOf(File.separatorChar));
        // In case of temp files (for jars extracted from within jars), remove the temp filename prefix
        int sepIdx = path.indexOf(NestedJarHandler.TEMP_FILENAME_SEPARATOR);
        if (sepIdx >= 0) {
            sepIdx += NestedJarHandler.TEMP_FILENAME_SEPARATOR.length() - 1;
        }
        final int maxIdx = Math.max(lastSlashIdx, sepIdx);
        return maxIdx < 0 ? path : path.substring(maxIdx + 1);
    }

    /**
     * Recursively search within ancestral directories of a jarfile to see if rt.jar is present, in order to
     * determine if the given jarfile is part of the JRE. This would typically be called with an initial
     * ancestralScandepth of 2, since JRE jarfiles can be in the lib or lib/ext directories of the JRE.
     */
    public static boolean isJREJar(final File file, final LogNode log) {
        final String filePath = file.getPath();
        for (final String jrePathPrefix : JRE_PATHS) {
            if (filePath.startsWith(jrePathPrefix)) {
                return true;
            }
        }
        return false;
    }
}
