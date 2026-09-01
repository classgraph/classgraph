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

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Locale;

import org.junit.jupiter.api.Test;

/**
 * A jar accept or reject criterion names a file, and two filenames differing only in case name the same file on a
 * filesystem that ignores case, so the criterion is matched ignoring case. Otherwise the same criterion would mean
 * something different depending on the filesystem the classpath happened to be stored on, and a mis-capitalized
 * criterion would silently match nothing.
 */
public class JarNameCaseTest {
    /** The leafname of a jar on the test classpath, containing classes in the package {@code issue100}. */
    private static final String JAR_LEAF_NAME = "issue100-has-field-a.zip";

    /** The package that the jar's classes are in. */
    private static final String JAR_PACKAGE = "issue100";

    /**
     * Scan the test jar, accepting and rejecting jars by the given criteria.
     *
     * @param acceptJar
     *            the jar leafname to accept, or null to accept all jars.
     * @param rejectJar
     *            the jar leafname to reject, or null to reject no jars.
     * @return the number of classes found.
     */
    private static int numClassesFound(final String acceptJar, final String rejectJar) {
        final var jarURL = JarNameCaseTest.class.getClassLoader().getResource(JAR_LEAF_NAME);
        assertThat(jarURL).isNotNull();
        final var classGraph = new ClassGraph().enableClassInfo().enableClasspathEntries(jarURL)
                .acceptPackages(JAR_PACKAGE);
        if (acceptJar != null) {
            classGraph.acceptJars(acceptJar);
        }
        if (rejectJar != null) {
            classGraph.rejectJars(rejectJar);
        }
        try (var scanResult = classGraph.scan()) {
            return scanResult.getAllClasses().size();
        }
    }

    /** Sanity check: with no jar criteria at all, the test jar is scanned and its classes are found. */
    @Test
    public void theJarIsScannedWithNoJarCriteria() {
        assertThat(numClassesFound(/* acceptJar = */ null, /* rejectJar = */ null)).isPositive();
    }

    /** A jar is accepted by a leafname spelled with a different case. */
    @Test
    public void aJarIsAcceptedByALeafnameSpelledWithADifferentCase() {
        assertThat(numClassesFound(JAR_LEAF_NAME.toUpperCase(Locale.ROOT), /* rejectJar = */ null)).isPositive();
    }

    /** A jar is accepted by a glob spelled with a different case. */
    @Test
    public void aJarIsAcceptedByAGlobSpelledWithADifferentCase() {
        assertThat(numClassesFound("ISSUE100-HAS-FIELD-?.*", /* rejectJar = */ null)).isPositive();
    }

    /** A jar is rejected by a leafname spelled with a different case. */
    @Test
    public void aJarIsRejectedByALeafnameSpelledWithADifferentCase() {
        assertThat(numClassesFound(/* acceptJar = */ null, JAR_LEAF_NAME.toUpperCase(Locale.ROOT))).isZero();
    }

    /** A jar whose leafname differs by more than case is still not accepted. */
    @Test
    public void aJarIsNotAcceptedByADifferentLeafname() {
        assertThat(numClassesFound("issue100-has-field-b.zip", /* rejectJar = */ null)).isZero();
    }
}
