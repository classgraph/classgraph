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
 * Copyright (c) 2019 Luke Hutchison
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
package nonapi.io.github.classgraph.utils;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.github.classgraph.ClassGraphException;
import nonapi.io.github.classgraph.fastzipfilereader.NestedJarHandler;
import nonapi.io.github.classgraph.scanspec.ScanSpec;

/**
 * Jarfile utilities.
 */
public final class JarUtils {
    /**
     * Check if a path has a URL scheme at the beginning. Require at least 2 chars in a URL scheme, so that Windows
     * drive designations don't get treated as URL schemes.
     */
    public static final Pattern URL_SCHEME_PATTERN = Pattern.compile("[a-zA-Z][a-zA-Z0-9+-.]+[:].*");

    /** The Constant DASH_VERSION. */
    private static final Pattern DASH_VERSION = Pattern.compile("-(\\d+(\\.|$))");

    /** The Constant NON_ALPHANUM. */
    private static final Pattern NON_ALPHANUM = Pattern.compile("[^A-Za-z0-9]");

    /** The Constant REPEATING_DOTS. */
    private static final Pattern REPEATING_DOTS = Pattern.compile("(\\.)(\\1)+");

    /** The Constant LEADING_DOTS. */
    private static final Pattern LEADING_DOTS = Pattern.compile("^\\.");

    /** The Constant TRAILING_DOTS. */
    private static final Pattern TRAILING_DOTS = Pattern.compile("\\.$");

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
                throw ClassGraphException
                        .newClassGraphException("Could not find ':' in \"" + UNIX_NON_PATH_SEPARATORS[i] + "\"");
            }
        }
    }

    /**
     * Constructor.
     */
    private JarUtils() {
        // Cannot be constructed
    }

    /**
     * Split a path on File.pathSeparator (':' on Linux, ';' on Windows), but also allow for the use of URLs with
     * protocol specifiers, e.g. "http://domain/jar1.jar:http://domain/jar2.jar".
     *
     * @param pathStr
     *            The path to split.
     * @param scanSpec
     *            the scan spec
     * @return The path element substrings.
     */
    public static String[] smartPathSplit(final String pathStr, final ScanSpec scanSpec) {
        return smartPathSplit(pathStr, File.pathSeparatorChar, scanSpec);
    }

    /**
     * Split a path on the given separator char. If the separator char is ':', also allow for the use of URLs with
     * protocol specifiers, e.g. "http://domain/jar1.jar:http://domain/jar2.jar".
     *
     * @param pathStr
     *            The path to split.
     * @param separatorChar
     *            The separator char to use.
     * @param scanSpec
     *            the scan spec
     * @return The path element substrings.
     */
    public static String[] smartPathSplit(final String pathStr, final char separatorChar, final ScanSpec scanSpec) {
        if (pathStr == null || pathStr.isEmpty()) {
            return new String[0];
        }
        if (separatorChar != ':') {
            // The fast path for Windows (which uses ';' as a path separator), or for separator other than ':'
            final List<String> partsFiltered = new ArrayList<>();
            for (final String part : pathStr.split(String.valueOf(separatorChar))) {
                final String partFiltered = part.trim();
                if (!partFiltered.isEmpty()) {
                    partsFiltered.add(partFiltered);
                }
            }
            return partsFiltered.toArray(new String[0]);
        } else {
            // If the separator char is ':', don't split on URL protocol boundaries.
            // This will allow for HTTP(S) jars to be given in java.class.path.
            // (The JRE may not even support them, but we may as well do so.)
            final Set<Integer> splitPoints = new HashSet<>();
            for (int i = -1;;) {
                boolean foundNonPathSeparator = false;
                for (int j = 0; j < UNIX_NON_PATH_SEPARATORS.length; j++) {
                    // Skip ':' characters in the middle of non-path-separators such as "http://"
                    final int startIdx = i - UNIX_NON_PATH_SEPARATOR_COLON_POSITIONS[j];
                    if (pathStr.regionMatches(true, startIdx, UNIX_NON_PATH_SEPARATORS[j], 0,
                            UNIX_NON_PATH_SEPARATORS[j].length())
                            && (startIdx == 0 || pathStr.charAt(startIdx - 1) == ':')) {
                        // Don't treat the "jar:" in the middle of "x.jar:y.jar" as a URL scheme
                        foundNonPathSeparator = true;
                        break;
                    }
                }
                if (!foundNonPathSeparator && scanSpec != null && scanSpec.allowedURLSchemes != null
                        && !scanSpec.allowedURLSchemes.isEmpty()) {
                    // If custom URL schemes have been registered, allow those to be used as delimiters too
                    for (final String scheme : scanSpec.allowedURLSchemes) {
                        // Skip schemes already handled by the faster matching code above
                        if (!scheme.equals("http") && !scheme.equals("https") && !scheme.equals("jar")
                                && !scheme.equals("file")) {
                            final int schemeLen = scheme.length();
                            final int startIdx = i - schemeLen;
                            if (pathStr.regionMatches(true, startIdx, scheme, 0, schemeLen)
                                    && (startIdx == 0 || pathStr.charAt(startIdx - 1) == ':')) {
                                foundNonPathSeparator = true;
                                break;
                            }
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
            CollectionUtils.sortIfNotEmpty(splitPointsSorted);
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
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Append a path element to a buffer.
     *
     * @param pathElt
     *            the path element
     * @param buf
     *            the buf
     */
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
        final int bangIdx = path.indexOf('!');
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

    /**
     * Convert a classfile path to the corresponding class name.
     *
     * @param classfilePath
     *            the classfile path
     * @return the class name
     */
    public static String classfilePathToClassName(final String classfilePath) {
        if (!classfilePath.endsWith(".class")) {
            throw new IllegalArgumentException("Classfile path does not end with \".class\": " + classfilePath);
        }
        return classfilePath.substring(0, classfilePath.length() - 6).replace('/', '.');
    }

    /**
     * Convert a class name to the corresponding classfile path.
     *
     * @param className
     *            the class name
     * @return the classfile path
     */
    public static String classNameToClassfilePath(final String className) {
        return className.replace('.', '/') + ".class";
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Derive automatic module name from jar name, using <a href=
     * "https://docs.oracle.com/javase/9/docs/api/java/lang/module/ModuleFinder.html#of-java.nio.file.Path...-">this
     * algorithm</a>.
     * 
     * @param jarPath
     *            The jar path.
     * @return The automatic module name.
     */
    public static String derivedAutomaticModuleName(final String jarPath) {
        // If jar path does not end in a file extension (with ".jar" most likely), strip off everything after
        // the last '!', in order to remove package root
        int endIdx = jarPath.length();
        final int lastPlingIdx = jarPath.lastIndexOf('!');
        if (lastPlingIdx > 0
                // If there is no '.' after the last '/' (if any) after the last '!'
                && jarPath.lastIndexOf('.') <= Math.max(lastPlingIdx, jarPath.lastIndexOf('/'))) {
            // Then truncate at last '!'
            endIdx = lastPlingIdx;
        }
        // Find the second to last '!' (or -1, if none)
        final int secondToLastPlingIdx = endIdx == 0 ? -1 : jarPath.lastIndexOf("!", endIdx - 1);
        // Find last '/' between the second to last and the last '!'
        final int startIdx = Math.max(secondToLastPlingIdx, jarPath.lastIndexOf('/', endIdx - 1)) + 1;
        // Find last '.' after that '/'
        final int lastDotBeforeLastPlingIdx = jarPath.lastIndexOf('.', endIdx - 1);
        if (lastDotBeforeLastPlingIdx > startIdx) {
            // Strip off extension
            endIdx = lastDotBeforeLastPlingIdx;
        }

        // Remove .jar extension
        String moduleName = jarPath.substring(startIdx, endIdx);

        // Find first occurrence of "-[0-9]"
        final Matcher matcher = DASH_VERSION.matcher(moduleName);
        if (matcher.find()) {
            moduleName = moduleName.substring(0, matcher.start());
        }

        // Replace non-alphanumeric characters with dots
        moduleName = NON_ALPHANUM.matcher(moduleName).replaceAll(".");

        // Collapse repeating dots into a single dot
        moduleName = REPEATING_DOTS.matcher(moduleName).replaceAll(".");

        // Drop leading dots
        if (moduleName.length() > 0 && moduleName.charAt(0) == '.') {
            moduleName = LEADING_DOTS.matcher(moduleName).replaceAll("");
        }

        // Drop trailing dots
        final int len = moduleName.length();
        if (len > 0 && moduleName.charAt(len - 1) == '.') {
            moduleName = TRAILING_DOTS.matcher(moduleName).replaceAll("");
        }
        return moduleName;
    }
}
