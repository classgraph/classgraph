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
import java.util.Set;

public class JarUtils {
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
    public static boolean isJREJar(final File file, final int ancestralScanDepth, final Set<String> knownJREPaths,
            final Set<String> knownNonJREPaths, final Set<String> knownRtJarPaths, final LogNode log) {
        if (ancestralScanDepth == 0) {
            // Did not find JRE root
            return false;
        } else {
            final File parent = file.getParentFile();
            if (parent == null) {
                return false;
            }
            final String parentPathStr = parent.getPath();
            if (knownJREPaths.contains(parentPathStr)) {
                return true;
            }
            if (knownNonJREPaths.contains(parentPathStr)) {
                return false;
            }
            File rt = new File(parent, File.separatorChar == '/' ? "jre/lib/rt.jar"
                    : String.format("jre%clib%crt.jar", File.separatorChar, File.separatorChar));
            boolean rtExists;
            if (!(rtExists = ClasspathUtils.canRead(rt))) {
                rt = new File(parent, File.separatorChar == '/' ? "lib/rt.jar"
                        : String.format("lib%crt.jar", File.separatorChar));
                if (!(rtExists = ClasspathUtils.canRead(rt))) {
                    rt = new File(parent, "rt.jar");
                    rtExists = ClasspathUtils.canRead(rt);
                }
            }
            boolean isJREJar = false;
            if (rtExists && rt.isFile()) {
                // Found rt.jar -- check its manifest file to make sure it's the JRE's rt.jar and not something else 
                final FastManifestParser manifest = new FastManifestParser(rt, log);
                if (manifest.isSystemJar) {
                    // Found the JRE's rt.jar
                    try {
                        final File rtCanonical = rt.getCanonicalFile();
                        knownRtJarPaths.add(rt.getPath());
                        isJREJar = true;

                        // Add canonical parent path to known JRE paths, in case the path provided to isJREJar
                        // was non-canonical (this may help avoid some file operations in future).
                        final File rtCanonicalParent = rtCanonical.getParentFile();
                        if (rtCanonicalParent != null) {
                            knownJREPaths.add(rtCanonicalParent.getPath());
                            if (rtCanonicalParent.getName().equals("lib")) {
                                // rt.jar should be in "jre/lib". If it's in a directory named "lib",
                                // Add canonical grandparent path to known JRE paths too.
                                final File rtCanonicalGrandParent = rtCanonicalParent.getParentFile();
                                if (rtCanonicalGrandParent != null) {
                                    knownJREPaths.add(rtCanonicalGrandParent.getPath());
                                }
                            }
                        }
                    } catch (final IOException e) {
                        // If canonicalization exception is thrown, leave isJREJar set to false
                    }
                }
            }
            if (!isJREJar) {
                // Check if parent directory is JRE root
                isJREJar = isJREJar(parent, ancestralScanDepth - 1, knownJREPaths, knownNonJREPaths,
                        knownRtJarPaths, log);
            }
            if (!isJREJar) {
                knownNonJREPaths.add(parentPathStr);
            } else {
                knownJREPaths.add(parentPathStr);
            }
            return isJREJar;
        }
    }
}
