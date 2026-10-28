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

/**
 * Utility methods for converting and validating class names, package names, and classfile paths.
 */
final class ClassNames {
    /** The filename extension of a classfile. */
    private static final String CLASSFILE_EXTENSION = ".class";

    /** The number of characters in the filename extension of a classfile. */
    private static final int CLASSFILE_EXTENSION_LENGTH = CLASSFILE_EXTENSION.length();

    /** Not instantiable. */
    private ClassNames() {
        // Cannot be constructed
    }

    /**
     * Check whether a path ends with a {@code ".class"} extension (matched case-insensitively) and has a non-empty
     * class name before the extension.
     *
     * @param path
     *            A file path.
     * @return true if the path is the path of a classfile.
     */
    static boolean isClassfilePath(final String path) {
        final var len = path.length();
        if (len <= CLASSFILE_EXTENSION_LENGTH || !path.regionMatches(true, len - CLASSFILE_EXTENSION_LENGTH,
                CLASSFILE_EXTENSION, 0, CLASSFILE_EXTENSION_LENGTH)) {
            return false;
        }
        // A file named only ".class", or ending in "..class", has an empty or trailing-dot class name
        final var charBeforeExtension = path.charAt(len - CLASSFILE_EXTENSION_LENGTH - 1);
        return charBeforeExtension != '/' && charBeforeExtension != '.';
    }

    /**
     * Check whether {@code classfilePath} equals {@code classNamePath} followed by a {@code ".class"} extension.
     *
     * @param classfilePath
     *            the path of a classfile, with {@code '/'} as the separator.
     * @param classNamePath
     *            the name of the class declared by that classfile, with {@code '/'} as the separator.
     * @return true if the classfile is stored at the path implied by its class name.
     */
    static boolean classfilePathMatchesClassName(final String classfilePath, final String classNamePath) {
        return classfilePath.length() == classNamePath.length() + CLASSFILE_EXTENSION_LENGTH
                && classfilePath.startsWith(classNamePath) && isClassfilePath(classfilePath);
    }

    /**
     * Check whether a classfile path is not under {@code "META-INF/"} and its name without the {@code ".class"}
     * extension is a valid JVM internal class name (JVMS 4.2.1).
     *
     * @param classfilePath
     *            the path of a classfile, as accepted by {@link #isClassfilePath(String)}, with {@code '/'} as the
     *            separator.
     * @return true if the classfile is to be scanned.
     */
    static boolean isScannableClassfilePath(final String classfilePath) {
        // isClassfilePath already rejects a path whose file name is empty, i.e. one ending in "/.class"
        return !classfilePath.startsWith("META-INF/")
                && hasLegalSegments(classfilePath, classfilePath.length() - CLASSFILE_EXTENSION_LENGTH, '/');
    }

    /**
     * Check whether a dot-separated class name has valid non-empty segments containing no {@code ';'} or
     * {@code '['} characters (JVMS 4.2.1).
     *
     * @param className
     *            the class name, with {@code '.'} as the separator.
     * @return true if the class name is legal.
     */
    static boolean isLegalClassName(final String className) {
        return hasLegalSegments(className, className.length(), '.');
    }

    /**
     * Check whether {@code name.substring(0, end)} consists of non-empty {@code separator}-delimited segments
     * containing no {@code '.'}, {@code ';'}, or {@code '['} characters.
     *
     * @param name
     *            the string.
     * @param end
     *            the end of the part of the string to check.
     * @param separator
     *            the separator character.
     * @return true if the part of the string is a sequence of legal segments.
     */
    private static boolean hasLegalSegments(final String name, final int end, final char separator) {
        if (end == 0) {
            return false;
        }
        for (var i = 0; i < end; i++) {
            final var c = name.charAt(i);
            if (c == separator) {
                // A separator at the start or end, or right after another separator, ends an empty segment
                if (i == 0 || i == end - 1 || name.charAt(i - 1) == separator) {
                    return false;
                }
            } else if (c == '.' || c == ';' || c == '[') {
                return false;
            }
        }
        return true;
    }

    /**
     * Return {@code classfilePath} with its {@code ".class"} extension converted to lower case.
     *
     * @param classfilePath
     *            the path of a classfile, as accepted by {@link #isClassfilePath(String)}.
     * @return the same path, with its {@code ".class"} extension in lower case.
     */
    static String withLowerCaseClassfileExtension(final String classfilePath) {
        return classfilePath.endsWith(CLASSFILE_EXTENSION) ? classfilePath
                : classfilePath.substring(0, classfilePath.length() - CLASSFILE_EXTENSION_LENGTH)
                        + CLASSFILE_EXTENSION;
    }

    /**
     * Convert a classfile path to the corresponding class name.
     *
     * @param classfilePath
     *            the classfile path
     * @return the class name
     */
    static String classfilePathToClassName(final String classfilePath) {
        if (!isClassfilePath(classfilePath)) {
            throw new IllegalArgumentException("Not the path of a classfile: " + classfilePath);
        }
        return classfilePath.substring(0, classfilePath.length() - CLASSFILE_EXTENSION_LENGTH).replace('/', '.');
    }

    /**
     * Convert a class name to the corresponding classfile path.
     *
     * @param className
     *            the class name
     * @return the classfile path
     */
    static String classNameToClassfilePath(final String className) {
        return className.replace('.', '/') + CLASSFILE_EXTENSION;
    }

    /**
     * Convert a package name to the corresponding path.
     *
     * @param packageName
     *            the package name
     * @return the path
     */
    static String packageNameToPath(final String packageName) {
        return packageName.replace('.', '/');
    }
}
