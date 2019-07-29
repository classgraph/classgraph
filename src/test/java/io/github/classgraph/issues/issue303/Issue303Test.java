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
package io.github.classgraph.issues.issue303;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.ScanResult;

/**
 * Issue303Test.
 */
public class Issue303Test {
    /** The Constant PACKAGE_NAME. */
    private static final String PACKAGE_NAME = "io.github.classgraph";

    /**
     * Test package info classes.
     */
    @Test
    public void testPackageInfoClasses() {
        final List<String> allClassNamesRecursive;
        final List<String> allClassNamesNonRecursive;
        final List<String> packageClassNamesRecursive;
        final List<String> packageClassNamesNonRecursive0;
        final List<String> packageClassNamesNonRecursive1;
        final List<String> packageClassNamesNonRecursive2;
        try (ScanResult scanResult = new ClassGraph().whitelistPackages(PACKAGE_NAME).enableAllInfo().scan()) {
            packageClassNamesRecursive = scanResult.getPackageInfo(PACKAGE_NAME).getClassInfoRecursive().getNames();
            packageClassNamesNonRecursive0 = scanResult.getPackageInfo(PACKAGE_NAME).getClassInfo().getNames();
            allClassNamesRecursive = scanResult.getAllClasses().getNames();
        }
        try (ScanResult scanResult = new ClassGraph().whitelistPackagesNonRecursive(PACKAGE_NAME).enableAllInfo()
                .scan()) {
            packageClassNamesNonRecursive1 = scanResult.getPackageInfo(PACKAGE_NAME).getClassInfoRecursive()
                    .getNames();
            packageClassNamesNonRecursive2 = scanResult.getPackageInfo(PACKAGE_NAME).getClassInfo().getNames();
            allClassNamesNonRecursive = scanResult.getAllClasses().getNames();
        }
        assertThat(packageClassNamesRecursive.size()).isGreaterThan(packageClassNamesNonRecursive0.size());
        assertThat(packageClassNamesNonRecursive0).isEqualTo(packageClassNamesNonRecursive1);
        assertThat(packageClassNamesNonRecursive0).isEqualTo(packageClassNamesNonRecursive2);
        assertThat(packageClassNamesRecursive).isEqualTo(allClassNamesRecursive);
        assertThat(packageClassNamesNonRecursive0).isEqualTo(allClassNamesNonRecursive);
    }
}
