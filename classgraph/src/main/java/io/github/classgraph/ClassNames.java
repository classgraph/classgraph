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
 * The mapping between the name of a class or package and the path of the classfile or directory that holds it, and
 * the tests on the {@code ".class"} extension that the mapping is built on.
 */
final class ClassNames {
    /** The filename extension of a classfile. */
    private static final String CLASSFILE_EXTENSION = ".class";

    /** The number of characters in the filename extension of a classfile. */
    private static final int CLASSFILE_EXTENSION_LENGTH = CLASSFILE_EXTENSION.length();

    /**
     * Constructor.
     */
    private ClassNames() {
        // Cannot be constructed
    }

    /**
     * Check if a path is the path of a classfile, i.e. whether it ends with a {@code ".class"} extension.
     *
     * <p>
     * The extension is matched ignoring case, since a classfile that has been through a filesystem or archiving
     * tool that upper-cases filenames still declares the same class, and can still be read.
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
     * Check if a classfile is stored at the path implied by the name of the class it declares, i.e. whether the
     * path of the classfile is the name of the class in path form, plus a {@code ".class"} extension.
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
     * Check whether the directories that a classfile is stored in can be the packages that declare the class.
     *
     * <p>
     * The path of a classfile has to be the name of the class it declares, in path form, so every directory of that
     * path has to be a valid package name segment: a Java identifier. A classfile stored beneath a directory that
     * is not one cannot be loaded from where it is stored, whatever it holds, so it is not scanned as a classfile.
     * It is still listed as a resource, since it is still a file of the classpath element.
     *
     * <p>
     * This is what keeps the versioned copies of a multi-release jarfile from being scanned when
     * {@code disableMultiReleaseVersions()} reports each of them under the {@code "META-INF/versions/<N>/"} path it
     * is stored under: neither {@code "META-INF"} (a hyphen is not a Java identifier character) nor {@code "<N>"}
     * (a Java identifier cannot start with a digit) can be a package name segment, which is exactly why the JVM
     * itself ignores those paths when it is not resolving multi-release versions.
     *
     * @param classfilePath
     *            the path of a classfile, with {@code '/'} as the separator.
     * @return true if every directory of the path can be a package name segment.
     */
    static boolean classfilePathHasValidPackage(final String classfilePath) {
        final var lastSlashIdx = classfilePath.lastIndexOf('/');
        var segStart = 0;
        while (segStart < lastSlashIdx) {
            final var segEnd = classfilePath.indexOf('/', segStart);
            if (segEnd == segStart) {
                // An empty directory name is not a package name segment
                return false;
            }
            for (var i = segStart; i < segEnd;) {
                final var codePoint = classfilePath.codePointAt(i);
                if (!(i == segStart ? Character.isJavaIdentifierStart(codePoint)
                        : Character.isJavaIdentifierPart(codePoint))) {
                    return false;
                }
                i += Character.charCount(codePoint);
            }
            segStart = segEnd + 1;
        }
        return true;
    }

    /**
     * Lower-case the {@code ".class"} extension of the path of a classfile.
     *
     * <p>
     * Accept and reject criteria for classfiles are built from class names, so they name a classfile with a
     * lower-case extension, and the extension of a path has to be lower-cased before it is matched against them.
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
