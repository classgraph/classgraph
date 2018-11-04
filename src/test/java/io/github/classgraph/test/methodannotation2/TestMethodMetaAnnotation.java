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
 * Copyright (c) 2018 Luke Hutchison
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
package io.github.classgraph.test.methodannotation2;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.List;

import org.junit.Test;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.ScanResult;
import io.github.classgraph.test.external.ExternalAnnotation;

public class TestMethodMetaAnnotation {

    @Target(ElementType.TYPE)
    @Retention(RetentionPolicy.RUNTIME)
    public @interface MetaAnnotation {
    }

    @Target(ElementType.TYPE)
    @Retention(RetentionPolicy.RUNTIME)
    @MetaAnnotation
    public @interface ClassAnnotation {
    }

    @Target(ElementType.METHOD)
    @Retention(RetentionPolicy.RUNTIME)
    @MetaAnnotation
    public @interface MethodAnnotation {
    }

    @ClassAnnotation
    public static class MetaAnnotatedClass {
        public void annotatedMethod() {
        }
    }

    public static class ClassWithMetaAnnotatedMethod {
        @MethodAnnotation
        public void annotatedMethod() {
        }
    }

    @Test
    @ExternalAnnotation
    public void testMetaAnnotation() {
        try (ScanResult scanResult = new ClassGraph()
                .whitelistPackages(TestMethodMetaAnnotation.class.getPackage().getName()).enableAnnotationInfo()
                .scan()) {
            final List<String> testClasses = scanResult.getClassesWithAnnotation(MetaAnnotation.class.getName())
                    .getNames();
            assertThat(testClasses).containsExactlyInAnyOrder(MethodAnnotation.class.getName(),
                    ClassAnnotation.class.getName(), MetaAnnotatedClass.class.getName());
        }
    }

    @Test
    @ExternalAnnotation
    public void testMetaAnnotationStandardClassesOnly() {
        try (ScanResult scanResult = new ClassGraph()
                .whitelistPackages(TestMethodMetaAnnotation.class.getPackage().getName()).enableAnnotationInfo()
                .scan()) {
            final List<String> testClasses = scanResult.getClassesWithAnnotation(MetaAnnotation.class.getName())
                    .getStandardClasses().getNames();
            assertThat(testClasses).containsExactlyInAnyOrder(MetaAnnotatedClass.class.getName());
        }
    }

    @Test
    @ExternalAnnotation
    public void testMethodMetaAnnotation() {
        try (ScanResult scanResult = new ClassGraph()
                .whitelistPackages(TestMethodMetaAnnotation.class.getPackage().getName()).enableMethodInfo()
                .enableAnnotationInfo().scan()) {
            final List<String> testClasses = scanResult
                    .getClassesWithMethodAnnotation(MetaAnnotation.class.getName()).getNames();
            assertThat(testClasses).containsExactlyInAnyOrder(ClassWithMetaAnnotatedMethod.class.getName());
        }
    }
}
