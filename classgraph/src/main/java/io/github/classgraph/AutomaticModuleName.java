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
package io.github.classgraph;

import java.util.regex.Pattern;

/**
 * The name that the module system gives a jarfile that has no module descriptor and no
 * {@code Automatic-Module-Name} manifest entry, which it derives from the name of the jarfile.
 */
final class AutomaticModuleName {
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
     * Constructor.
     */
    private AutomaticModuleName() {
        // Cannot be constructed
    }

    /**
     * Derive automatic module name from jar name, using <a href=
     * "https://docs.oracle.com/javase/9/docs/api/java/lang/module/ModuleFinder.html#of-java.nio.file.Path...-">this
     * algorithm</a>.
     *
     * @param jarPath
     *            The jar path.
     * @return The automatic module name.
     */
    static String derive(final String jarPath) {
        // If jar path does not end in a file extension (with ".jar" most likely), strip off everything after the
        // last '!', in order to remove package root
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
