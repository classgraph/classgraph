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
            if (!(rtExists = rt.exists())) {
                rt = new File(parent, File.separatorChar == '/' ? "lib/rt.jar"
                        : String.format("lib%crt.jar", File.separatorChar));
                if (!(rtExists = rt.exists())) {
                    rt = new File(parent, "rt.jar");
                    rtExists = rt.exists();
                }
            }
            boolean isJREJar = false;
            if (rtExists && rt.isFile()) {
                // Found rt.jar -- check its manifest file to make sure it's the JRE's rt.jar and not something else 
                final FastManifestParser manifest = new FastManifestParser(rt, log);
                if (manifest.isSystemJar) {
                    // Found the JRE's rt.jar
                    try {
                        File rtCanonical = rt.getCanonicalFile();
                        knownRtJarPaths.add(rt.getPath());
                        String rtCanonicalParent = rtCanonical.getParent();
                        if (rtCanonicalParent != null) {
                            knownJREPaths.add(rtCanonicalParent);
                        }
                        isJREJar = true;
                    } catch (final IOException e) {
                        // On canonicalization exception, leave isJREJar set to false
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
