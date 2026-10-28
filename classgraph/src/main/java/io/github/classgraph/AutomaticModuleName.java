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

import java.lang.module.ModuleDescriptor;
import java.util.regex.Pattern;

import io.github.classgraph.base.internal.path.PathSyntax;

/**
 * Derives automatic module names from jarfile names when no module descriptor or {@code Automatic-Module-Name}
 * manifest entry is present.
 */
final class AutomaticModuleName {
    /** Matches a hyphen followed by a version number. */
    private static final Pattern DASH_VERSION = Pattern.compile("-(\\d+(\\.|$))");

    /** Matches characters that are not alphanumeric (replaced with dots). */
    private static final Pattern NON_ALPHANUM = Pattern.compile("[^A-Za-z0-9]");

    /** Matches two or more consecutive dots. */
    private static final Pattern REPEATING_DOTS = Pattern.compile("\\.{2,}");

    /** Not instantiable. */
    private AutomaticModuleName() {
        // Cannot be constructed
    }

    /**
     * Derive an automatic module name from a jar path, using the algorithm specified in
     * {@link java.lang.module.ModuleFinder#of(java.nio.file.Path...)}.
     *
     * @param jarPath
     *            The normalized jarfile path (with {@code '/'} directory separators and {@code "!/"} nested jar
     *            separators, without a trailing package root).
     * @return The derived automatic module name, or the empty string if a valid module name cannot be derived.
     */
    static String derive(final String jarPath) {
        // The jar is named by the last segment of its path, which is the name of the innermost nested jarfile for
        // a nested jar path, and the leafname of the path otherwise
        var moduleName = PathSyntax.lastSegment(jarPath);

        // Remove the ".jar" extension, but not a leading '.' of a name that is nothing but an extension
        final var lastDotIdx = moduleName.lastIndexOf('.');
        if (lastDotIdx > 0) {
            moduleName = moduleName.substring(0, lastDotIdx);
        }

        // Find first occurrence of "-[0-9]"
        final var matcher = DASH_VERSION.matcher(moduleName);
        if (matcher.find()) {
            moduleName = moduleName.substring(0, matcher.start());
        }

        // Replace non-alphanumeric characters with dots
        moduleName = NON_ALPHANUM.matcher(moduleName).replaceAll(".");

        // Collapse repeating dots into a single dot
        moduleName = REPEATING_DOTS.matcher(moduleName).replaceAll(".");

        // Drop the leading and trailing dot, if any (there is at most one of each, now that runs of dots have been
        // collapsed)
        if (moduleName.startsWith(".")) {
            moduleName = moduleName.substring(1);
        }
        if (moduleName.endsWith(".")) {
            moduleName = moduleName.substring(0, moduleName.length() - 1);
        }

        // The module system cannot name a jarfile whose derived name is not a legal module name, because a part of
        // it starts with a digit or is a Java keyword, as for "x-1a.jar" or "foo-int.jar"
        try {
            ModuleDescriptor.newAutomaticModule(moduleName);
        } catch (final IllegalArgumentException e) {
            return "";
        }
        return moduleName;
    }
}
