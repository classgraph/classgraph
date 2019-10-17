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
package io.github.classgraph.test.methodannotation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.ClassInfo;
import io.github.classgraph.ClassInfoList;
import io.github.classgraph.MethodInfo;
import io.github.classgraph.ScanResult;
import io.github.classgraph.test.external.ExternalAnnotation;

/**
 * MethodAnnotationTest.
 */
public class MethodAnnotationTest {
    /**
     * Get the names of classes with method annotation.
     */
    @Test
    public void testGetNamesOfClassesWithMethodAnnotation() {
        try (ScanResult scanResult = new ClassGraph()
                .whitelistPackages(MethodAnnotationTest.class.getPackage().getName()).enableClassInfo()
                .enableMethodInfo().enableAnnotationInfo().scan()) {
            final List<String> testClasses = scanResult
                    .getClassesWithMethodAnnotation(ExternalAnnotation.class.getName()).getNames();
            assertThat(testClasses).isEmpty();
        }
    }

    /**
     * Get the names of classes with method annotation ignoring visibility.
     */
    @Test
    public void testGetNamesOfClassesWithMethodAnnotationIgnoringVisibility() {
        try (ScanResult scanResult = new ClassGraph()
                .whitelistPackages(MethodAnnotationTest.class.getPackage().getName()).enableClassInfo()
                .enableMethodInfo().enableAnnotationInfo().ignoreMethodVisibility().scan()) {
            final ClassInfoList classesWithMethodAnnotation = scanResult
                    .getClassesWithMethodAnnotation(ExternalAnnotation.class.getName());
            final List<String> testClasses = classesWithMethodAnnotation.getNames();
            assertThat(testClasses).containsOnly(MethodAnnotationTest.class.getName());
            boolean found = false;
            for (final ClassInfo ci : classesWithMethodAnnotation) {
                for (final MethodInfo mi : ci.getMethodInfo()) {
                    if (mi.getAnnotationInfo().containsName(ExternalAnnotation.class.getName())) {
                        assertThat(mi.getName()).isEqualTo("privateMethodWithAnnotation");
                        found = true;
                    }
                }
            }
            assertThat(found).isEqualTo(true);
        }
    }

    /**
     * Method without annotation.
     */
    public void methodWithoutAnnotation() {
    }

    /**
     * Private method with annotation.
     */
    @ExternalAnnotation
    private void privateMethodWithAnnotation() {
    }
}
