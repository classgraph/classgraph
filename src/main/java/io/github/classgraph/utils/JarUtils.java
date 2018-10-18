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

import io.github.classgraph.ScanSpec;

/**
 * Jarfile utilities.
 */
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
     * 
     * @param pathStr
     *            The path to split.
     * @return The path element substrings.
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
            return parts.toArray(new String[0]);
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
            return partsFiltered.toArray(new String[0]);
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Create a custom URLClassLoader from a classpath path string.
     * 
     * @param classpathStr
     *            The classpath string.
     * @return A custom {@link URLClassLoader} that can load from the path string.
     */
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
        return new URLClassLoader(urls.toArray(new URL[0]));
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
     * @param pathElts
     *            The path elements.
     * @return The delimited path formed out of the path elements.
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
     * @param pathElts
     *            The path elements.
     * @return The delimited path formed out of the path elements, after calling each of their toString() methods.
     */
    public static String pathElementsToPathStr(final Iterable<?> pathElts) {
        final StringBuilder buf = new StringBuilder();
        for (final Object pathElt : pathElts) {
            appendPathElt(pathElt, buf);
        }
        return buf.toString();
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Returns the leafname of a path, after first stripping off everything after the first '!', if present.
     * 
     * @param path
     *            A file path.
     * @return The leafname of the path.
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

    private static String getProperty(final String propName) {
        try {
            return System.getProperty(propName);
        } catch (final SecurityException e) {
            return null;
        }
    }

    private static void addJRERoot(final File jreRoot, final Set<File> jreDirsSet, final List<File> rtJarFiles) {
        if (addJREPath(jreRoot, jreDirsSet)) {
            final File libFile = new File(jreRoot, "lib");
            if (FileUtils.canRead(libFile) && libFile.isDirectory()) {
                if (addJREPath(libFile, jreDirsSet)) {
                    final File rtJarFile = new File(libFile, "rt.jar");
                    if (FileUtils.canRead(rtJarFile)) {
                        if (!rtJarFiles.contains(rtJarFile)) {
                            rtJarFiles.add(rtJarFile);
                        }
                    }
                }
            }
        }
    }

    private static boolean addJREPath(final File dir, final Set<File> jreLibExtDirsSet) {
        if (FileUtils.canRead(dir) && dir.isDirectory()) {
            try {
                final File canonicalDir = dir.getCanonicalFile();
                jreLibExtDirsSet.add(canonicalDir);
                return true;
            } catch (IOException | SecurityException e) {
            }
        }
        return false;
    }

    // Find jars in JRE dirs ({java.home}, {java.home}/lib, {java.home}/lib/ext, etc.)
    static {
        final Set<File> jreDirsSet = new HashSet<>();
        final List<File> jreRtJarFiles = new ArrayList<>();
        final Set<File> javaLibExtDirsSet = new HashSet<>();
        final String javaHome = getProperty("java.home");
        if (javaHome != null && !javaHome.isEmpty()) {
            final File javaHomeFile = new File(javaHome);
            addJRERoot(javaHomeFile, jreDirsSet, jreRtJarFiles);
            // Try adding "{java.home}/.." as a JDK root when java.home is a JRE path
            if (javaHomeFile.getName().equals("jre")) {
                addJRERoot(javaHomeFile.getParentFile(), jreDirsSet, jreRtJarFiles);
            } else {
                // Try adding "{java.home}/jre" as a JRE root when java.home is not a JRE path
                addJRERoot(new File(javaHomeFile, "jre"), jreDirsSet, jreRtJarFiles);
            }
            addJREPath(new File(javaHomeFile, "lib"), javaLibExtDirsSet);
            addJREPath(new File(javaHomeFile, "lib/ext"), javaLibExtDirsSet);
            addJREPath(new File(javaHomeFile, "jre/lib"), javaLibExtDirsSet);
            addJREPath(new File(javaHomeFile, "jre/lib/ext"), javaLibExtDirsSet);
            addJREPath(new File(javaHomeFile, "packages"), javaLibExtDirsSet);
            addJREPath(new File(javaHomeFile, "packages/lib"), javaLibExtDirsSet);
            addJREPath(new File(javaHomeFile, "packages/lib/ext"), javaLibExtDirsSet);
        }
        final String javaExtDirs = getProperty("java.ext.dirs");
        if (javaExtDirs != null) {
            for (final String javaExtDir : smartPathSplit(javaExtDirs)) {
                if (!javaExtDir.isEmpty()) {
                    addJREPath(new File(javaExtDir), javaLibExtDirsSet);
                }
            }
        }
        jreDirsSet.addAll(javaLibExtDirsSet);

        // System extension paths -- see: https://docs.oracle.com/javase/tutorial/ext/basics/install.html

        // Mac OS X
        addJRERoot(new File("/System/Library/Java"), jreDirsSet, jreRtJarFiles);
        addJREPath(new File("/System/Library/Java/Libraries"), javaLibExtDirsSet);
        addJREPath(new File("/System/Library/Java/Extensions"), javaLibExtDirsSet);

        // Linux
        addJREPath(new File("/usr/java/packages"), javaLibExtDirsSet);
        addJREPath(new File("/usr/java/packages/lib"), javaLibExtDirsSet);
        addJREPath(new File("/usr/java/packages/lib/ext"), javaLibExtDirsSet);

        // Solaris
        addJREPath(new File("/usr/jdk/packages"), javaLibExtDirsSet);
        addJREPath(new File("/usr/jdk/packages/lib"), javaLibExtDirsSet);
        addJREPath(new File("/usr/jdk/packages/lib/ext"), javaLibExtDirsSet);

        // Windows
        try {
            final String systemRoot = File.separatorChar == '\\' ? System.getenv("SystemRoot") : null;
            if (systemRoot != null) {
                addJRERoot(new File(systemRoot, "Sun\\Java"), jreDirsSet, jreRtJarFiles);
                addJREPath(new File(systemRoot, "Sun\\Java\\lib"), javaLibExtDirsSet);
                addJREPath(new File(systemRoot, "Sun\\Java\\lib\\ext"), javaLibExtDirsSet);
                addJRERoot(new File(systemRoot, "Oracle\\Java"), jreDirsSet, jreRtJarFiles);
                addJREPath(new File(systemRoot, "Oracle\\Java\\lib"), javaLibExtDirsSet);
                addJREPath(new File(systemRoot, "Oracle\\Java\\lib\\ext"), javaLibExtDirsSet);
            }
        } catch (final Exception e) {
        }

        // Find "lib/" and "ext/" jars
        final List<String> libExtJarPaths = new ArrayList<>();
        for (final File jreLibExtDir : javaLibExtDirsSet) {
            for (final File file : jreLibExtDir.listFiles()) {
                if (file.getPath().endsWith(".jar") && !jreRtJarFiles.contains(file)) {
                    libExtJarPaths.add(FastPathResolver.resolve(file.getPath()));
                }
            }
        }
        Collections.sort(libExtJarPaths);
        JRE_LIB_OR_EXT_JARS.addAll(libExtJarPaths);

        // Only include the first rt.jar -- if there is a copy in both the JDK and JRE, no need to scan both
        final String rtJarPath = jreRtJarFiles.size() > 0 ? FastPathResolver.resolve(jreRtJarFiles.get(0).getPath())
                : null;

        // Put rt.jar first in list of JRE jar paths
        if (rtJarPath != null) {
            JRE_JARS.add(rtJarPath);
        }
        JRE_JARS.addAll(libExtJarPaths);
        JRE_JARS_SET.addAll(JRE_JARS);
    }

    /**
     * @return The paths of jars in all JRE/JDK system directories, with any rt.jar listed first.
     */
    public static List<String> getJreJarPaths() {
        return JRE_JARS;
    }

    /** @return The paths for any JRE/JDK "lib/" or "ext/" jars. */
    public static Set<String> getJreLibOrExtJars() {
        return JRE_LIB_OR_EXT_JARS;
    }

    /**
     * @param filePath
     *            A file path.
     * @param scanSpec
     *            The {@link ScanSpec}.
     * @param log
     *            The log.
     * @return Whether a given jarfile is in a JRE system directory (jre, jre/lib, jre/lib/ext, etc.).
     */
    public static boolean isJREJar(final String filePath, final ScanSpec scanSpec, final LogNode log) {
        if (JRE_LIB_OR_EXT_JARS.contains(filePath)
                && scanSpec.libOrExtJarWhiteBlackList.isSpecificallyWhitelistedAndNotBlacklisted(filePath)) {
            // This is a whitelisted lib/ or ext/ jar, so de-associate it from the JRE/JDK
            return false;
        }
        // Should only return true for rt.jar
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

    /**
     * Return true if the given class name, package name or module name has a system package or module prefix
     * 
     * @param packageOrModuleName
     *            The class, package or module name.
     * @return Whether this is a system class, package or module.
     */
    public static boolean isInSystemPackageOrModule(final String packageOrModuleName) {
        for (int i = 0; i < SYSTEM_PACKAGE_PREFIXES.length; i++) {
            if (packageOrModuleName.startsWith(SYSTEM_PACKAGE_PREFIXES[i])) {
                return true;
            }
        }
        return false;
    }

    // -------------------------------------------------------------------------------------------------------------

    /** Convert a classfile path to the corresponding class name. */
    public static String classfilePathToClassName(final String classfilePath) {
        if (!classfilePath.endsWith(".class")) {
            throw new IllegalArgumentException("Classfile path does not end with \".class\": " + classfilePath);
        }
        return classfilePath.substring(0, classfilePath.length() - 6).replace('/', '.');
    }

    /** Convert a class name to the corresponding classfile path. */
    public static String classNameToClassfilePath(final String className) {
        return className.replace('.', '/') + ".class";
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Count the number of bytes before the characters "PK" in a zipfile.
     * 
     * @param zipfile
     *            The zipfile.
     * @return The number of bytes before the characters "PK" in a zipfile. Returns -1 if PK is not found anywhere
     *         in the file.
     * @throws IOException
     *             If the file could not be read.
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

    /**
     * Strip the self-extracting archive header from the beginning of a zipfile.
     * 
     * @param srcZipfile
     *            The source zipfile.
     * @param sfxHeaderBytes
     *            The number of bytes of the header to strip.
     * @param destZipfile
     *            The destination to save to.
     * @throws IOException
     *             If the operation could not be completed.
     */
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

    /**
     * Log the Java version and the JRE paths that were found.
     * 
     * @param log
     *            The log.
     */
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
