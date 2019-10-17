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
package io.github.classgraph.issues.issue101;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.ScanResult;

/**
 * Issue101Test.
 */
public class Issue101Test {
    /**
     * Non inherited annotation.
     */
    @Test
    public void nonInheritedAnnotation() {
        try (ScanResult scanResult = new ClassGraph().whitelistPackages(Issue101Test.class.getPackage().getName())
                .enableAllInfo().scan()) {
            assertThat(scanResult.getClassesWithAnnotation(NonInheritedAnnotation.class.getName()).getNames())
                    .containsOnly(AnnotatedClass.class.getName());
        }
    }

    /**
     * Inherited meta annotation.
     */
    @Test
    public void inheritedMetaAnnotation() {
        try (ScanResult scanResult = new ClassGraph().whitelistPackages(Issue101Test.class.getPackage().getName())
                .enableAllInfo().scan()) {
            assertThat(scanResult.getClassesWithAnnotation(InheritedMetaAnnotation.class.getName())
                    .getStandardClasses().getNames()).containsOnly(AnnotatedClass.class.getName(),
                            NonAnnotatedSubclass.class.getName());
        }
    }

    /**
     * Inherited annotation.
     */
    @Test
    public void inheritedAnnotation() {
        try (ScanResult scanResult = new ClassGraph().whitelistPackages(Issue101Test.class.getPackage().getName())
                .enableAllInfo().scan()) {
            assertThat(scanResult.getClassesWithAnnotation(InheritedAnnotation.class.getName()).getNames())
                    .containsOnly(AnnotatedClass.class.getName(), NonAnnotatedSubclass.class.getName(),
                            AnnotatedInterface.class.getName());
        }
    }
}
