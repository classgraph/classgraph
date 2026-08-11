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
package io.github.classgraph.issues.issue559;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

import org.junit.jupiter.api.Test;

import io.github.classgraph.AnnotationInfo;
import io.github.classgraph.ClassGraph;
import io.github.classgraph.ScanResult;

/**
 * An annotation that is reachable in more than one way should be reported from the most direct of those ways.
 */
public class Issue559Test {
    /** An annotation that annotates itself, so that it is its own meta-annotation. */
    @Retention(RetentionPolicy.RUNTIME)
    @SelfAnnotating("on the annotation")
    public @interface SelfAnnotating {
        /** @return the value. */
        String value();
    }

    /** A class, method and field annotated with the self-annotating annotation. */
    @SelfAnnotating("on the class")
    public static class Annotated {
        /** An annotated field. */
        @SelfAnnotating("on the field")
        public int field;

        /** An annotated method. */
        @SelfAnnotating("on the method")
        public void method() {
        }
    }

    /** An {@link Inherited} annotation. */
    @Retention(RetentionPolicy.RUNTIME)
    @Inherited
    public @interface Marker {
        /** @return the value. */
        String value();
    }

    /** An annotation that is meta-annotated with {@link Marker}. */
    @Retention(RetentionPolicy.RUNTIME)
    @Marker("on the meta-annotation")
    public @interface Marked {
    }

    /** A superclass with an {@link Inherited} annotation. */
    @Marker("on the superclass")
    public static class Base {
    }

    /** A subclass that is annotated with a {@link Marker}-meta-annotated annotation. */
    @Marked
    public static class Sub extends Base {
    }

    /**
     * @param annotationInfo
     *            an annotation
     * @return the annotation's {@code value} parameter
     */
    private static String value(final AnnotationInfo annotationInfo) {
        assertThat(annotationInfo).isNotNull();
        return (String) annotationInfo.getParameterValues().getValue("value");
    }

    /**
     * @return a scan result for this test's package
     */
    private static ScanResult scan() {
        return new ClassGraph().acceptPackages(Issue559Test.class.getPackage().getName()).enableAllInfo().scan();
    }

    /** An annotation directly present on a class wins over its own meta-annotation. */
    @Test
    public void directClassAnnotationWinsOverMetaAnnotation() {
        try (var scanResult = scan()) {
            final var classInfo = scanResult.getClassInfo(Annotated.class.getName());
            assertThat(value(classInfo.getAllAnnotationInfo(SelfAnnotating.class))).isEqualTo("on the class");
            assertThat(classInfo.getAllAnnotationInfoRepeatable(SelfAnnotating.class).get(0))
                    .isSameAs(classInfo.getAllAnnotationInfo(SelfAnnotating.class));
        }
    }

    /** The same holds for the annotations of a method and of a field. */
    @Test
    public void directMemberAnnotationWinsOverMetaAnnotation() {
        try (var scanResult = scan()) {
            final var classInfo = scanResult.getClassInfo(Annotated.class.getName());
            assertThat(value(classInfo.getMethodInfo("method").get(0).getAllAnnotationInfo(SelfAnnotating.class)))
                    .isEqualTo("on the method");
            assertThat(value(classInfo.getFieldInfo("field").getAllAnnotationInfo(SelfAnnotating.class)))
                    .isEqualTo("on the field");
        }
    }

    /** An annotation inherited from a superclass wins over a meta-annotation. */
    @Test
    public void inheritedAnnotationWinsOverMetaAnnotation() {
        try (var scanResult = scan()) {
            final var classInfo = scanResult.getClassInfo(Sub.class.getName());
            assertThat(value(classInfo.getAllAnnotationInfo(Marker.class))).isEqualTo("on the superclass");
        }
    }

    /** An annotation that annotates itself is listed once, not twice. */
    @Test
    public void selfAnnotationIsNotListedTwice() {
        try (var scanResult = scan()) {
            final var classInfo = scanResult.getClassInfo(SelfAnnotating.class.getName());
            assertThat(classInfo.getAllAnnotationInfoRepeatable(SelfAnnotating.class)).hasSize(1);
            assertThat(value(classInfo.getAllAnnotationInfo(SelfAnnotating.class))).isEqualTo("on the annotation");
        }
    }
}
