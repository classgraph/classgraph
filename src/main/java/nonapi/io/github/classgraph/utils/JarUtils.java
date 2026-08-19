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
 * Copyright (c) 2026 Luke Hutchison
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
     * On everything but Windows, where the path separator is ':', need to treat the colon in these substrings as
     * non-separators, when at the beginning of the string or following a ':'.
     */
    private static final String[] UNIX_NON_PATH_SEPARATORS = { //
            "jar:", "file:", "http://", "https://", //
            // Tomcat serves a non-exploded WAR file through its own "war:" URL protocol (#925)
            "war:", //
            // Spring Boot addresses entries within an executable jar through its own "nested:" URL protocol
            "nested:", //
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
            // The fast path for Windows (which uses ';' as a path separator), or for separator other than ':'.
            // N.B. the separator is searched for literally, rather than with String#split(), whose argument is a
            // regular expression -- a separator that is a regex metacharacter would otherwise split on the wrong
            // thing
            final List<String> partsFiltered = new ArrayList<>();
            for (int startIdx = 0; startIdx <= pathStr.length();) {
                int endIdx = pathStr.indexOf(separatorChar, startIdx);
                if (endIdx < 0) {
                    endIdx = pathStr.length();
                }
                final String partFiltered = pathStr.substring(startIdx, endIdx).trim();
                if (!partFiltered.isEmpty()) {
                    partsFiltered.add(partFiltered);
                }
                startIdx = endIdx + 1;
            }
            return partsFiltered.toArray(new String[0]);
        } else {
            // If the separator char is ':', don't split on URL protocol boundaries.
            // This will allow for HTTP(S) jars to be given in java.class.path.
            // (The JRE may not even support them, but we may as well do so.)
            final Set<Integer> splitPoints = new HashSet<>();
            for (int i = -1;;) {
                // A ':' escaped as "\:" is part of a path element, not a separator (this is the escaping
                // applied by appendPathElt, and undone by the DOUBLE_BACKSHLASH_WITH_COLON unescape below)
                boolean foundNonPathSeparator = i > 0 && pathStr.charAt(i - 1) == '\\';
                for (int j = 0; !foundNonPathSeparator && j < UNIX_NON_PATH_SEPARATORS.length; j++) {
                    // Skip ':' characters in the middle of non-path-separators such as "http://"
                    final int startIdx = i - UNIX_NON_PATH_SEPARATOR_COLON_POSITIONS[j];
                    if (pathStr.regionMatches(true, startIdx, UNIX_NON_PATH_SEPARATORS[j], 0,
                            UNIX_NON_PATH_SEPARATORS[j].length()) && startsAPathElement(pathStr, startIdx)) {
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
                                && !scheme.equals("file") && !scheme.equals("war")) {
                            final int schemeLen = scheme.length();
                            final int startIdx = i - schemeLen;
                            if (pathStr.regionMatches(true, startIdx, scheme, 0, schemeLen)
                                    && startsAPathElement(pathStr, startIdx)) {
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
                part = DOUBLE_BACKSHLASH_WITH_COLON.matcher(part).replaceAll(":");
                // Remove empty path components
                if (!part.isEmpty()) {
                    parts.add(part);
                }
            }
            return parts.toArray(new String[0]);
        }
    }

    /**
     * Test whether an index is at the start of a path element, i.e. whether everything between it and the previous
     * separator (or the start of the path) is whitespace. Whitespace is skipped because the path elements are
     * trimmed, so {@code "x.jar: http://domain/y.jar"} has to be read the same way as
     * {@code "x.jar:http://domain/y.jar"} -- otherwise the space would hide the URL scheme, and the path would be
     * split at the scheme's colon.
     *
     * @param pathStr
     *            The path being split.
     * @param startIdx
     *            The index to test.
     * @return true if a path element starts at the given index.
     */
    private static boolean startsAPathElement(final String pathStr, final int startIdx) {
        for (int i = startIdx - 1; i >= 0; i--) {
            final char c = pathStr.charAt(i);
            if (c == ':') {
                return true;
            }
            // Anything above ' ' is not trimmed off the path element, so it is part of the element
            if (c > ' ') {
                return false;
            }
        }
        return true;
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
        // (Use String.replace() rather than String.replaceAll(), so that both arguments are literal -- in a
        // replaceAll() replacement string, a single backslash escapes the char after it, so the intended
        // escape sequence would be emitted as a bare path separator.)
        final String path = File.separatorChar == '\\' ? pathElt.toString()
                : pathElt.toString().replace(File.pathSeparator, "\\" + File.pathSeparator);
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
     * Find the index of the outermost nested jar separator ('!') in a path, i.e. the '!' that separates the
     * outermost jarfile from a path nested within it, or -1 if the path contains no nested jar separator.
     *
     * <p>
     * A '!' is not necessarily a separator -- it is a legal character in a file or directory name on every
     * platform ClassGraph supports, and users do put it in their directory names (#903). The
     * {@link java.net.JarURLConnection} spec defines the separator as {@code "!/"}, and gives no way of escaping a
     * literal '!' other than percent-encoding it as {@code %21} within the inner URL, so the separator cannot be
     * identified by syntax alone: {@code /dir!/x.jar} is ambiguous between a jar {@code x.jar} in a directory named
     * {@code dir!}, and an entry {@code x.jar} within a jarfile named {@code dir}.
     *
     * <p>
     * That ambiguity is resolved here by testing the filesystem: the outermost '!' separator is the first '!' whose
     * preceding path names an existing regular file (which must be the outermost jarfile). Every subsequent '!' is
     * then a separator too, since only the last element of a '!'-delimited path may be a non-jar path. If no '!' is
     * preceded by an existing file, the path contains no separator, and any '!' in it is a literal filename
     * character.
     *
     * <p>
     * The filesystem cannot be consulted for non-{@code file:} URLs (e.g. {@code http:} jar URLs), so if no '!' is
     * preceded by an existing file and the path has a URL scheme, the old syntactic rule is retained, and the first
     * '!' is taken to be the separator. The filesystem is tested before the path is read as a URL, and not after,
     * because a relative path can itself begin with something shaped like a URL scheme: {@code ':'} is a legal
     * filename character everywhere but Windows, so a directory named {@code foo:bar} cannot be told by syntax from
     * a URL with the scheme {@code foo}. See {@link #hasURLScheme(String)}.
     *
     * @param path
     *            the path, with any {@code "jar:"} and {@code "file:"} scheme prefixes already stripped.
     * @return the index of the outermost nested jar separator, or -1 if there is none.
     */
    public static int indexOfNestedJarSeparator(final String path) {
        final int firstPlingIdx = path.indexOf('!');
        if (firstPlingIdx < 0) {
            return -1;
        }
        int plingIdx = firstPlingIdx;
        while (plingIdx >= 0) {
            // The outermost jarfile has to exist as a regular file for the classpath element to be scannable,
            // so if the path before the '!' names one, this '!' is the outermost separator
            // N.B. the path is not resolved via FastPathResolver here, since that calls
            // FileUtils#sanitizeEntryPath, which calls this method -- a relative path is resolved by
            // java.io.File against the current directory, which is the same base path anyway
            if (new File(path.substring(0, plingIdx)).isFile()) {
                return plingIdx;
            }
            plingIdx = path.indexOf('!', plingIdx + 1);
        }
        // No prefix of the path names a local file, so either the path has no separator, or its jarfile is remote
        // and could not be stat-ed however the path is spelled. Test the whole path to tell the two apart: if it
        // exists locally, it is a path whose every '!' is a literal filename character. Only if nothing local
        // answers to it is it read as a URL, and the syntactic rule used to find the separator.
        // (A path that names nothing at all cannot be told from a URL, but it fails to open either way.)
        return URL_SCHEME_PATTERN.matcher(path).matches() && !new File(path).exists() ? firstPlingIdx : -1;
    }

    /**
     * Find the index of the innermost nested jar separator ('!') in a path, or -1 if the path contains no nested
     * jar separator. See {@link #indexOfNestedJarSeparator(String)} for how separators are distinguished from
     * literal '!' characters in filenames (#903).
     *
     * @param path
     *            the path, with any {@code "jar:"} and {@code "file:"} scheme prefixes already stripped.
     * @return the index of the innermost nested jar separator, or -1 if there is none.
     */
    public static int lastIndexOfNestedJarSeparator(final String path) {
        // Every '!' after the outermost separator is also a separator, so if there is an outermost separator,
        // the last '!' in the path is the innermost separator
        return indexOfNestedJarSeparator(path) < 0 ? -1 : path.lastIndexOf('!');
    }

    /**
     * Determine whether a path is a URL, i.e. whether it begins with a URL scheme that names how to fetch it,
     * rather than naming something in the local filesystem.
     *
     * <p>
     * A URL scheme cannot be recognized by syntax alone. {@code ':'} is a legal filename character on every
     * platform ClassGraph supports except Windows, and a relative path is not required to begin with a {@code '/'},
     * so the relative path {@code foo:bar} is spelled exactly like a URL whose scheme is {@code foo}. (An absolute
     * path is unambiguous, since it begins with a separator, and so is a Windows path, since a drive designation is
     * a single character and a scheme is at least two.)
     *
     * <p>
     * The ambiguity is resolved the same way {@link #indexOfNestedJarSeparator(String)} resolves the one around
     * {@code '!'}: by testing the filesystem. A path whose outermost element exists locally names that file or
     * directory, and is not a URL. Only if nothing exists there is the path read as a URL, which is also the only
     * case in which the scheme could have been of any use.
     *
     * @param path
     *            the path, with any {@code "jar:"} and {@code "file:"} scheme prefixes already stripped.
     * @return true if the path is a URL rather than a path into the local filesystem.
     */
    public static boolean hasURLScheme(final String path) {
        if (!URL_SCHEME_PATTERN.matcher(path).matches()) {
            // Nothing shaped like a scheme at the front, so the filesystem does not need to be consulted at all
            return false;
        }
        // Only the outermost element of the path could carry a scheme, so that is the part to test for existence
        final int sepIdx = indexOfNestedJarSeparator(path);
        return !new File(sepIdx < 0 ? path : path.substring(0, sepIdx)).exists();
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Returns the leafname of a path, after first stripping off everything from the nested jar separator ('!')
     * onwards, if present, so that the leafname of a path within a jarfile is the name of the jarfile itself.
     *
     * @param path
     *            A file path.
     * @return The leafname of the path.
     */
    public static String leafName(final String path) {
        // Only a '!' that separates a jarfile from a path within it ends the leafname -- a '!' is an ordinary
        // filename character otherwise. Ending the leafname at the first '!' regardless meant that the leafname of
        // a jar in a directory whose name contains a '!' was the directory name rather than the jar name, so the
        // jar matched no accept or reject criterion and was silently skipped
        // #903
        final int nestedJarSepIdx = indexOfNestedJarSeparator(path);
        final int endIdx = nestedJarSepIdx >= 0 ? nestedJarSepIdx : path.length();
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
        if (!FileUtils.isClassfile(classfilePath)) {
            throw new IllegalArgumentException("Not the path of a classfile: " + classfilePath);
        }
        return classfilePath.substring(0, classfilePath.length() - FileUtils.CLASSFILE_EXTENSION_LENGTH)
                .replace('/', '.');
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
