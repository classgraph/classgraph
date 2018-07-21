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

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import io.github.lukehutch.fastclasspathscanner.ScanSpec;

public class JarUtils {
    /**
     * On everything but Windows, where the path separator is ':', need to treat the colon in these substrings as
     * non-separators, when at the beginning of the string or following a ':'.
     */
    private static final String[] UNIX_NON_PATH_SEPARATORS = { //
            "jar:", "file:", "http://", "https://", //
            // Allow for escaping of ':' characters in paths, which probably goes beyond what the spec would allow
            // for, but would make sense, since File.separatorChar will never be '\\' when File.pathSeparatorChar is
            // ':'
            "\\:" //
    };

    /**
     * The position of the colon characters in the corresponding UNIX_NON_PATH_SEPARATORS array entry.
     */
    private static final int[] UNIX_NON_PATH_SEPARATOR_COLON_POSITIONS;

    static {
        UNIX_NON_PATH_SEPARATOR_COLON_POSITIONS = new int[UNIX_NON_PATH_SEPARATORS.length];
        for (int i = 0; i < UNIX_NON_PATH_SEPARATORS.length; i++) {
            UNIX_NON_PATH_SEPARATOR_COLON_POSITIONS[i] = UNIX_NON_PATH_SEPARATORS[i].indexOf(':');
            if (UNIX_NON_PATH_SEPARATOR_COLON_POSITIONS[i] < 0) {
                throw new RuntimeException("Could not find ':' in \"" + UNIX_NON_PATH_SEPARATORS[i] + "\"");
            }
        }
    }

    /**
     * Split a path on File.pathSeparator (':' on Linux, ';' on Windows), but also allow for the use of URLs with
     * protocol specifiers, e.g. "http://domain/jar1.jar:http://domain/jar2.jar". This is really not even handled by
     * the JRE, in all likelihood, but it's better to be robust.
     */
    public static String[] smartPathSplit(final String pathStr) {
        if (pathStr == null || pathStr.isEmpty()) {
            return new String[0];
        }
        // The fast path for Windows can skips this special handling (no need to handle these cases if the path
        // separator is ';')
        if (File.pathSeparatorChar == ':') {
            // For Linux, don't split on URL protocol boundaries. This will allow for HTTP(S) jars to be given in
            // java.class.path. (The JRE may not even support them, but we may as well do so.)
            final Set<Integer> splitPoints = new HashSet<>();
            for (int i = -1;;) {
                boolean foundNonPathSeparator = false;
                for (int j = 0; j < UNIX_NON_PATH_SEPARATORS.length; j++) {
                    // Skip ':' characters in the middle of non-path-separators such as "http://"
                    final int startIdx = i - UNIX_NON_PATH_SEPARATOR_COLON_POSITIONS[j];
                    if (pathStr.regionMatches(true, startIdx, UNIX_NON_PATH_SEPARATORS[j], 0,
                            UNIX_NON_PATH_SEPARATORS[j].length())) {
                        // Don't treat the "jar:" in the middle of "x.jar:y.jar" as a URL scheme
                        if (startIdx == 0 || pathStr.charAt(startIdx - 1) == ':') {
                            foundNonPathSeparator = true;
                            break;
                        }
                    }
                }
                if (!foundNonPathSeparator) {
                    // The ':' character is a valid path separator
                    splitPoints.add(i);
                }
                // Search for next ':' character
                i = pathStr.indexOf(':', i + 1);
                if (i < 0) {
                    // Add end of string marker once last ':' has been found
                    splitPoints.add(pathStr.length());
                    break;
                }
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
            // For Windows, there is no confusion between the path separator ';' and URL schemes Trim path
            // components, and strip out empty components
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

    // -------------------------------------------------------------------------------------------------------------

    /** Create a custom URLClassLoader from a classpath path string. */
    public static ClassLoader createURLClassLoaderFromPathString(final String classpathStr) {
        final List<URL> urls = new ArrayList<>();
        for (final String pathEltStr : smartPathSplit(classpathStr)) {
            try {
                final URL url = new URL(pathEltStr);
                urls.add(url);
            } catch (final Exception e) {
                // Skip bad URLs
            }
        }
        return new URLClassLoader(urls.toArray(new URL[urls.size()]));
    }

    // -------------------------------------------------------------------------------------------------------------

    /** Append a path element to a path string. */
    private static void appendPathElt(final Object pathElt, final StringBuilder buf) {
        if (buf.length() > 0) {
            buf.append(File.pathSeparatorChar);
        }
        // Escape any rogue path separators, as long as file separator is not '\\' (on Windows, if there are any
        // extra ';' characters in a path element, there's really nothing we can do to escape them, since they can't
        // be escaped as "\\;")
        final String path = File.separatorChar == '\\' ? pathElt.toString()
                : pathElt.toString().replaceAll(File.pathSeparator, "\\" + File.pathSeparator);
        buf.append(path);
    }

    /**
     * Get a set of path elements as a string, from an array of objects (e.g. of String, File or URL type, whose
     * toString() method will be called to get the path component), and return the path as a single string
     * delineated with the standard path separator character.
     *
     * @return the delimited path.
     */
    public static String pathElementsToPathStr(final Object... pathElts) {
        final StringBuilder buf = new StringBuilder();
        for (final Object pathElt : pathElts) {
            appendPathElt(pathElt, buf);
        }
        return buf.toString();
    }

    /**
     * Get a set of path elements as a string, from an array of objects (e.g. of String, File or URL type, whose
     * toString() method will be called to get the path component), and return the path as a single string
     * delineated with the standard path separator character.
     *
     * @return the delimited path.
     */
    public static String pathElementsToPathStr(final Iterable<?> pathElts) {
        final StringBuilder buf = new StringBuilder();
        for (final Object pathElt : pathElts) {
            appendPathElt(pathElt, buf);
        }
        return buf.toString();
    }

    // -------------------------------------------------------------------------------------------------------------

    // /** Returns true if the path ends with a jarfile extension, ignoring case. */ public static boolean
    // isJar(final String path) { final int len = path.length(); final boolean isJar = path.regionMatches(true, len
    // - 4, ".jar", 0, 4) // || path.regionMatches(true, len - 4, ".zip", 0, 4) // || path.regionMatches(true, len -
    // 4, ".war", 0, 4) // || path.regionMatches(true, len - 4, ".car", 0, 4) // || path.regionMatches(true, len -
    // 4, ".ear", 0, 4) // || path.regionMatches(true, len - 4, ".sar", 0, 4) // || path.regionMatches(true, len -
    // 4, ".har", 0, 4) // || path.regionMatches(true, len - 4, ".par", 0, 4) // || path.regionMatches(true, len -
    // 6, ".wsjar", 0, 6); if (!isJar) { // Support URLs of the form
    // "http://domain.com/path/to/jarfile.jar?version=2" final int urlParamIdx = path.indexOf('?'); if (urlParamIdx
    // > 0) { return isJar(path.substring(0, urlParamIdx)); } } return isJar; }

    /**
     * Returns the leafname of a path, after first stripping off everything after the first '!', if present.
     */
    public static String leafName(final String path) {
        final int bangIdx = path.indexOf("!");
        final int endIdx = bangIdx >= 0 ? bangIdx : path.length();
        int leafStartIdx = 1 + (File.separatorChar == '/' ? path.lastIndexOf('/', endIdx)
                : Math.max(path.lastIndexOf('/', endIdx), path.lastIndexOf(File.separatorChar, endIdx)));
        // In case of temp files (for jars extracted from within jars), remove the temp filename prefix -- see
        // NestedJarHandler.unzipToTempFile()
        int sepIdx = path.indexOf(NestedJarHandler.TEMP_FILENAME_LEAF_SEPARATOR);
        if (sepIdx >= 0) {
            sepIdx += NestedJarHandler.TEMP_FILENAME_LEAF_SEPARATOR.length();
        }
        leafStartIdx = Math.max(leafStartIdx, sepIdx);
        leafStartIdx = Math.min(leafStartIdx, endIdx);
        return path.substring(leafStartIdx, endIdx);
    }

    // -------------------------------------------------------------------------------------------------------------

    private static final List<String> JRE_JARS = new ArrayList<>();
    private static final Set<String> JRE_JARS_SET = new HashSet<>();
    private static final Set<String> JRE_LIB_OR_EXT_JARS = new HashSet<>();

    // Find jars in JRE dirs ({java.home}, {java.home}/lib, {java.home}/lib/ext, etc.)
    static {
        final Set<String> jrePathsSet = new HashSet<>();
        final List<String> jreRtJarPaths = new ArrayList<>();
        final String javaHome = getProperty("java.home");
        if (javaHome != null && !javaHome.isEmpty()) {
            final File javaHomeFile = new File(javaHome);
            addJRERoot(javaHomeFile, jrePathsSet, jreRtJarPaths);
            // Try adding "{java.home}/.." as a JDK root when java.home is a JRE path
            if (javaHomeFile.getName().equals("jre")) {
                addJRERoot(javaHomeFile.getParentFile(), jrePathsSet, jreRtJarPaths);
            } else {
                // Try adding "{java.home}/jre" as a JRE root when java.home is not a JRE path
                addJRERoot(new File(javaHomeFile, "jre"), jrePathsSet, jreRtJarPaths);
            }
            // Add "{java.home}/packages" -- apparently this is used on Solaris, so potentially elsewhere too:
            // https://docs.oracle.com/javase/tutorial/ext/basics/load.html
            addJRERoot(new File(javaHomeFile, "packages"), jrePathsSet, jreRtJarPaths);
        }
        final String javaExtDirs = getProperty("java.ext.dirs");
        final Set<String> javaExtDirsSet = new HashSet<>();
        if (javaExtDirs != null) {
            for (final String javaExtDir : smartPathSplit(javaExtDirs)) {
                if (!javaExtDir.isEmpty()) {
                    addJREPath(new File(javaExtDir), javaExtDirsSet);
                }
            }
        }
        jrePathsSet.addAll(javaExtDirsSet);

        // Add special-case paths for Mac OS X, this is not always picked up from java.home or java.ext.dirs
        addJRERoot(new File("/System/Library/Java"), jrePathsSet, jreRtJarPaths);
        addJRERoot(new File("/System/Library/Java/Libraries"), jrePathsSet, jreRtJarPaths);
        addJRERoot(new File("/System/Library/Java/Extensions"), jrePathsSet, jreRtJarPaths);

        // Add some other site-wide package installation directories (these are prefixes of the typical values for
        // java.ext.dirs, since that only covers the "ext/" dir in this location)
        addJRERoot(new File("/usr/java/packages"), jrePathsSet, jreRtJarPaths);
        addJRERoot(new File("/usr/jdk/packages"), jrePathsSet, jreRtJarPaths);
        try {
            final String systemRoot = File.separatorChar == '\\' ? System.getenv("SystemRoot") : null;
            if (systemRoot != null) {
                addJRERoot(new File(systemRoot, "Sun\\Java"), jrePathsSet, jreRtJarPaths);
                addJRERoot(new File(systemRoot, "Oracle\\Java"), jrePathsSet, jreRtJarPaths);
            }
        } catch (final Exception e) {
        }

        // Find "lib/" and "ext/" jars
        final Set<String> jreJarPaths = new HashSet<>();
        for (final String jrePath : jrePathsSet) {
            final File dir = new File(jrePath);
            if (ClasspathUtils.canRead(dir) && dir.isDirectory()) {
                final boolean isLib = jrePath.endsWith("/lib");
                final boolean isExt = jrePath.endsWith("/ext")
                        // java.ext.dirs dirs may not necessarily end in "/ext"
                        || javaExtDirsSet.contains(jrePath);
                for (final File file : dir.listFiles()) {
                    final String filePath = FastPathResolver.resolve("", file.getPath());
                    if (!filePath.isEmpty()) {
                        if (filePath.endsWith(".jar")) {
                            jreJarPaths.add(filePath);
                            if (isLib || isExt) {
                                JRE_LIB_OR_EXT_JARS.add(filePath);
                            }
                        }
                    }
                }
            }
        }

        // Put rt.jar first in list of JRE jar paths
        jreJarPaths.removeAll(jreRtJarPaths);
        final List<String> jreJarPathsSorted = new ArrayList<>(jreJarPaths);
        Collections.sort(jreJarPathsSorted);
        if (jreRtJarPaths.size() > 0) {
            // Only include the first rt.jar -- if there is a copy in both the JDK and JRE, no need to scan both
            JRE_JARS.add(jreRtJarPaths.get(0));
        }
        JRE_JARS.addAll(jreJarPathsSorted);
        JRE_JARS_SET.addAll(JRE_JARS);
    }

    private static String getProperty(final String propName) {
        try {
            return System.getProperty(propName);
        } catch (final SecurityException e) {
            return null;
        }
    }

    private static void addJRERoot(final File jreRoot, final Set<String> jrePathsSet,
            final List<String> rtJarPaths) {
        if (addJREPath(jreRoot, jrePathsSet)) {
            final File libFile = new File(jreRoot, "lib");
            if (addJREPath(libFile, jrePathsSet)) {
                final File extFile = new File(libFile, "ext");
                addJREPath(extFile, jrePathsSet);
                final File rtJarFile = new File(libFile, "rt.jar");
                if (ClasspathUtils.canRead(rtJarFile)) {
                    final String rtJarPath = rtJarFile.getPath();
                    if (!rtJarPaths.contains(rtJarPath)) {
                        rtJarPaths.add(rtJarPath);
                    }
                }
            }
        }
    }

    private static boolean addJREPath(final File dir, final Set<String> jrePathsSet) {
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
            return true;
        }
        return false;
    }

    /** Get the paths of jars in all JRE/JDK system directories, with any rt.jar listed first. */
    public static List<String> getJreJarPaths() {
        return JRE_JARS;
    }

    /** Get the paths for any JRE/JDK "lib/" or "ext/" jars. */
    public static Set<String> getJreLibOrExtJars() {
        return JRE_LIB_OR_EXT_JARS;
    }

    /**
     * Determine whether a given jarfile is in a JRE system directory (jre, jre/lib, jre/lib/ext, etc.).
     */
    public static boolean isJREJar(final String filePath, final ScanSpec scanSpec, final LogNode log) {
        if (JRE_LIB_OR_EXT_JARS.contains(filePath)
                && scanSpec.libOrExtJarWhiteBlackList.isSpecificallyWhitelistedAndNotBlacklisted(filePath)) {
            // This is a whitelisted lib/ or ext/ jar, so de-associate it from the JRE/JDK
            return false;
        }
        return JRE_JARS_SET.contains(filePath);
    }

    /** Prefixes of system (JRE) packages. */
    public static final String[] SYSTEM_PACKAGE_PREFIXES = { //
            "java.", "javax.", "javafx.", "jdk.", "oracle.", "sun." };

    /** Prefixes of system (JRE) packages, turned into path form (with slashes instead of dots). */
    public static final String[] SYSTEM_PACKAGE_PATH_PREFIXES = new String[SYSTEM_PACKAGE_PREFIXES.length];
    static {
        for (int i = 0; i < SYSTEM_PACKAGE_PREFIXES.length; i++) {
            SYSTEM_PACKAGE_PATH_PREFIXES[i] = SYSTEM_PACKAGE_PREFIXES[i].replace('.', '/');
        }
    }

    /** Return true if the given class name, package name or module name has a system package or module prefix */
    public static boolean isInSystemPackageOrModule(final String packageOrModuleName) {
        for (int i = 0; i < SYSTEM_PACKAGE_PREFIXES.length; i++) {
            if (packageOrModuleName.startsWith(SYSTEM_PACKAGE_PREFIXES[i])) {
                return true;
            }
        }
        return false;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Count the number of bytes before the characters "PK" in a zipfile. Returns -1 if PK is not found anywhere in
     * the file.
     */
    public static long countBytesBeforePKMarker(final File zipfile) throws IOException {
        try (BufferedReader reader = new BufferedReader(new FileReader(zipfile))) {
            boolean readP = false;
            long fileIdx = 0;
            for (int c; (c = reader.read()) != -1; fileIdx++) {
                if (!readP) {
                    if (c == 'P') {
                        readP = true;
                    }
                } else {
                    if (c == 'K') {
                        // Found PK marker
                        return fileIdx - 1;
                    } else {
                        readP = false;
                    }
                }
            }
            return -1;
        }
    }

    /** Strip the self-extracting archive header from the beginning of a zipfile. */
    public static void stripSFXHeader(final File srcZipfile, final long sfxHeaderBytes, final File destZipfile)
            throws IOException {
        try (FileInputStream inputStream = new FileInputStream(srcZipfile);
                FileChannel inputChannel = inputStream.getChannel();
                FileOutputStream outputStream = new FileOutputStream(destZipfile);
                FileChannel outputChannel = outputStream.getChannel()) {
            inputChannel.position(sfxHeaderBytes);
            outputChannel.transferFrom(inputChannel, 0, inputChannel.size());
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /** Log the Java version and the JRE paths that were found. */
    public static void logJavaInfo(final LogNode log) {
        if (log != null) {
            log.log("Operating system: " + getProperty("os.name") + " " + getProperty("os.version") + " "
                    + getProperty("os.arch"));
            log.log("Java version: " + getProperty("java.version") + " / " + getProperty("java.runtime.version")
                    + " (" + getProperty("java.vendor") + ")");
            log.log("JRE jars:").log(JRE_JARS);
        }
    }
}
