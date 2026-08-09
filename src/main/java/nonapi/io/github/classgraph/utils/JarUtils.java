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
import java.util.regex.Pattern;

import nonapi.io.github.classgraph.fastzipfilereader.NestedJarHandler;
import nonapi.io.github.classgraph.scanspec.ScanSpec;
import org.jspecify.annotations.Nullable;

/**
 * Jarfile utilities.
 */
public final class JarUtils {
    /**
     * Check if a path has a URL scheme at the beginning. Require at least 2 chars
     * in a URL scheme, so that Windows drive designations don't get treated as URL
     * schemes.
     */
    public static final Pattern URL_SCHEME_PATTERN = Pattern.compile("[a-zA-Z][a-zA-Z0-9+\\-.]+[:].*");

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

    /** The Constant DOUBLE_BACKSHLASH_WITH_COLON. */
    private static final Pattern DOUBLE_BACKSHLASH_WITH_COLON = Pattern.compile("\\\\:");

    /**
     * On everything but Windows, where the path separator is ':', need to treat the
     * colon in these substrings as non-separators, when at the beginning of the
     * string or following a ':'.
     */
    private static final String[] UNIX_NON_PATH_SEPARATORS = { //
            "jar:", "file:", "http://", "https://", //
            // Tomcat serves a non-exploded WAR file through its own "war:" URL protocol
            // (#925)
            "war:", //
            // Allow for escaping of ':' characters in paths, which probably goes beyond
            // what the spec would allow
            // for, but would make sense, since File.separatorChar will never be '\\' when
            // File.pathSeparatorChar is
            // ':'
            "\\:" //
    };

    /**
     * The position of the colon characters in the corresponding
     * UNIX_NON_PATH_SEPARATORS array entry.
     */
    private static final int[] UNIX_NON_PATH_SEPARATOR_COLON_POSITIONS;

    static {
        UNIX_NON_PATH_SEPARATOR_COLON_POSITIONS = new int[UNIX_NON_PATH_SEPARATORS.length];
        for (var i = 0; i < UNIX_NON_PATH_SEPARATORS.length; i++) {
            UNIX_NON_PATH_SEPARATOR_COLON_POSITIONS[i] = UNIX_NON_PATH_SEPARATORS[i].indexOf(':');
            if (UNIX_NON_PATH_SEPARATOR_COLON_POSITIONS[i] < 0) {
                throw new RuntimeException("Could not find ':' in \"" + UNIX_NON_PATH_SEPARATORS[i] + "\"");
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
     * Split a path on File.pathSeparator (':' on Linux, ';' on Windows), but also
     * allow for the use of URLs with protocol specifiers, e.g.
     * "http://domain/jar1.jar:http://domain/jar2.jar".
     *
     * @param pathStr  The path to split, or null.
     * @param scanSpec the scan spec, or null
     * @return The path element substrings.
     */
    public static String[] smartPathSplit(final @Nullable String pathStr, final @Nullable ScanSpec scanSpec) {
        return smartPathSplit(pathStr, File.pathSeparatorChar, scanSpec);
    }

    /**
     * Split a path on the given separator char. If the separator char is ':', also
     * allow for the use of URLs with protocol specifiers, e.g.
     * "http://domain/jar1.jar:http://domain/jar2.jar".
     *
     * @param pathStr       The path to split, or null.
     * @param separatorChar The separator char to use.
     * @param scanSpec      the scan spec, or null
     * @return The path element substrings.
     */
    public static String[] smartPathSplit(final @Nullable String pathStr, final char separatorChar,
            final @Nullable ScanSpec scanSpec) {
        if (pathStr == null || pathStr.isEmpty()) {
            return new String[0];
        }
        if (separatorChar != ':') {
            // The fast path for Windows (which uses ';' as a path separator), or for
            // separator other than ':'
            final List<String> partsFiltered = new ArrayList<>();
            for (final String part : pathStr.split(String.valueOf(separatorChar))) {
                final var partFiltered = part.trim();
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
            for (var i = -1;;) {
                // A ':' escaped as "\:" is part of a path element, not a separator (this is the
                // escaping
                // applied by appendPathElt, and undone by the DOUBLE_BACKSHLASH_WITH_COLON
                // unescape below)
                var foundNonPathSeparator = i > 0 && pathStr.charAt(i - 1) == '\\';
                for (var j = 0; !foundNonPathSeparator && j < UNIX_NON_PATH_SEPARATORS.length; j++) {
                    // Skip ':' characters in the middle of non-path-separators such as "http://"
                    final var startIdx = i - UNIX_NON_PATH_SEPARATOR_COLON_POSITIONS[j];
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
                    // If custom URL schemes have been registered, allow those to be used as
                    // delimiters too
                    for (final String scheme : scanSpec.allowedURLSchemes) {
                        // Skip schemes already handled by the faster matching code above
                        if (!"http".equals(scheme) && !"https".equals(scheme) && !"jar".equals(scheme)
                                && !"file".equals(scheme) && !"war".equals(scheme)) {
                            final var schemeLen = scheme.length();
                            final var startIdx = i - schemeLen;
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
            for (var i = 1; i < splitPointsSorted.size(); i++) {
                final int idx0 = splitPointsSorted.get(i - 1);
                final int idx1 = splitPointsSorted.get(i);
                // Trim, and unescape "\\:"
                var part = pathStr.substring(idx0 + 1, idx1).trim();
                part = DOUBLE_BACKSHLASH_WITH_COLON.matcher(part).replaceAll(":");
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
     * @param pathElt the path element
     * @param buf     the buf
     */
    private static void appendPathElt(final Object pathElt, final StringBuilder buf) {
        if (!buf.isEmpty()) {
            buf.append(File.pathSeparatorChar);
        }
        // Escape any rogue path separators, as long as file separator is not '\\' (on
        // Windows, if there are any
        // extra ';' characters in a path element, there's really nothing we can do to
        // escape them, since they can't
        // be escaped as "\\;")
        // (Use String.replace() rather than String.replaceAll(), so that both arguments
        // are literal -- in a
        // replaceAll() replacement string, a single backslash escapes the char after
        // it, so the intended
        // escape sequence would be emitted as a bare path separator.)
        final var path = File.separatorChar == '\\' ? pathElt.toString()
                : pathElt.toString().replace(File.pathSeparator, "\\" + File.pathSeparator);
        buf.append(path);
    }

    /**
     * Get a set of path elements as a string, from an array of objects (e.g. of
     * String, File or URL type, whose toString() method will be called to get the
     * path component), and return the path as a single string delineated with the
     * standard path separator character.
     * 
     * @param pathElts The path elements.
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
     * Get a set of path elements as a string, from an array of objects (e.g. of
     * String, File or URL type, whose toString() method will be called to get the
     * path component), and return the path as a single string delineated with the
     * standard path separator character.
     * 
     * @param pathElts The path elements.
     * @return The delimited path formed out of the path elements, after calling
     *         each of their toString() methods.
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
     * Find the index of the outermost nested jar separator ('!') in a path, i.e.
     * the '!' that separates the outermost jarfile from a path nested within it, or
     * -1 if the path contains no nested jar separator.
     *
     * <p>
     * A '!' is not necessarily a separator -- it is a legal character in a file or
     * directory name on every platform ClassGraph supports, and users do put it in
     * their directory names (#903). The {@link java.net.JarURLConnection} spec
     * defines the separator as {@code "!/"}, and gives no way of escaping a literal
     * '!' other than percent-encoding it as {@code %21} within the inner URL, so
     * the separator cannot be identified by syntax alone: {@code /dir!/x.jar} is
     * ambiguous between a jar {@code x.jar} in a directory named {@code dir!}, and
     * an entry {@code x.jar} within a jarfile named {@code dir}.
     *
     * <p>
     * That ambiguity is resolved here by testing the filesystem: the outermost '!'
     * separator is the first '!' whose preceding path names an existing regular
     * file (which must be the outermost jarfile). Every subsequent '!' is then a
     * separator too, since only the last element of a '!'-delimited path may be a
     * non-jar path. If no '!' is preceded by an existing file, the path contains no
     * separator, and any '!' in it is a literal filename character.
     *
     * <p>
     * The filesystem cannot be consulted for non-{@code file:} URLs (e.g.
     * {@code http:} jar URLs), so for those the old syntactic rule is retained, and
     * the first '!' is taken to be the separator.
     *
     * @param path the path, with any {@code "jar:"} and {@code "file:"} scheme
     *             prefixes already stripped.
     * @return the index of the outermost nested jar separator, or -1 if there is
     *         none.
     */
    public static int indexOfNestedJarSeparator(final String path) {
        var plingIdx = path.indexOf('!');
        if (plingIdx < 0) {
            return -1;
        }
        if (URL_SCHEME_PATTERN.matcher(path).matches()) {
            // Cannot stat a remote URL -- fall back to the syntactic rule
            return plingIdx;
        }
        while (plingIdx >= 0) {
            // The outermost jarfile has to exist as a regular file for the classpath
            // element to be scannable,
            // so if the path before the '!' names one, this '!' is the outermost separator
            // N.B. the path is not resolved via FastPathResolver here, since that calls
            // FileUtils#sanitizeEntryPath, which calls this method -- a relative path is
            // resolved by
            // java.io.File against the current directory, which is the same base path
            // anyway
            if (new File(path.substring(0, plingIdx)).isFile()) {
                return plingIdx;
            }
            plingIdx = path.indexOf('!', plingIdx + 1);
        }
        return -1;
    }

    /**
     * Find the index of the innermost nested jar separator ('!') in a path, or -1
     * if the path contains no nested jar separator. See
     * {@link #indexOfNestedJarSeparator(String)} for how separators are
     * distinguished from literal '!' characters in filenames (#903).
     *
     * @param path the path, with any {@code "jar:"} and {@code "file:"} scheme
     *             prefixes already stripped.
     * @return the index of the innermost nested jar separator, or -1 if there is
     *         none.
     */
    public static int lastIndexOfNestedJarSeparator(final String path) {
        // Every '!' after the outermost separator is also a separator, so if there is
        // an outermost separator,
        // the last '!' in the path is the innermost separator
        return indexOfNestedJarSeparator(path) < 0 ? -1 : path.lastIndexOf('!');
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Returns the leafname of a path, after first stripping off everything after
     * the first '!', if present.
     * 
     * @param path A file path.
     * @return The leafname of the path.
     */
    public static String leafName(final String path) {
        final var bangIdx = path.indexOf('!');
        final var endIdx = bangIdx >= 0 ? bangIdx : path.length();
        var leafStartIdx = 1 + (File.separatorChar == '/' ? path.lastIndexOf('/', endIdx)
                : Math.max(path.lastIndexOf('/', endIdx), path.lastIndexOf(File.separatorChar, endIdx)));
        // In case of temp files (for jars extracted from within jars), remove the temp
        // filename prefix -- see
        // NestedJarHandler.unzipToTempFile()
        var sepIdx = path.indexOf(NestedJarHandler.TEMP_FILENAME_LEAF_SEPARATOR);
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
     * @param classfilePath the classfile path
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
     * @param className the class name
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
     * @param jarPath The jar path.
     * @return The automatic module name.
     */
    public static String derivedAutomaticModuleName(final String jarPath) {
        // If jar path does not end in a file extension (with ".jar" most likely), strip
        // off everything after
        // the last '!', in order to remove package root
        var endIdx = jarPath.length();
        final var lastPlingIdx = jarPath.lastIndexOf('!');
        if (lastPlingIdx > 0
                // If there is no '.' after the last '/' (if any) after the last '!'
                && jarPath.lastIndexOf('.') <= Math.max(lastPlingIdx, jarPath.lastIndexOf('/'))) {
            // Then truncate at last '!'
            endIdx = lastPlingIdx;
        }
        // Find the second to last '!' (or -1, if none)
        final var secondToLastPlingIdx = endIdx == 0 ? -1 : jarPath.lastIndexOf("!", endIdx - 1);
        // Find last '/' between the second to last and the last '!'
        final var startIdx = Math.max(secondToLastPlingIdx, jarPath.lastIndexOf('/', endIdx - 1)) + 1;
        // Find last '.' after that '/'
        final var lastDotBeforeLastPlingIdx = jarPath.lastIndexOf('.', endIdx - 1);
        if (lastDotBeforeLastPlingIdx > startIdx) {
            // Strip off extension
            endIdx = lastDotBeforeLastPlingIdx;
        }

        // Remove .jar extension
        var moduleName = jarPath.substring(startIdx, endIdx);

        // Find first occurrence of "-[0-9]"
        final var matcher = DASH_VERSION.matcher(moduleName);
        if (matcher.find()) {
            moduleName = moduleName.substring(0, matcher.start());
        }

        // Replace non-alphanumeric characters with dots
        moduleName = NON_ALPHANUM.matcher(moduleName).replaceAll(".");

        // Collapse repeating dots into a single dot
        moduleName = REPEATING_DOTS.matcher(moduleName).replaceAll(".");

        // Drop leading dots
        if (!moduleName.isEmpty() && moduleName.charAt(0) == '.') {
            moduleName = LEADING_DOTS.matcher(moduleName).replaceAll("");
        }

        // Drop trailing dots
        final var len = moduleName.length();
        if (len > 0 && moduleName.charAt(len - 1) == '.') {
            moduleName = TRAILING_DOTS.matcher(moduleName).replaceAll("");
        }
        return moduleName;
    }
}
