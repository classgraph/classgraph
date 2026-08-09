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
package io.github.classgraph.issues.issue559;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

import org.junit.jupiter.api.Test;

import io.github.classgraph.AnnotationInfoList;
import io.github.classgraph.ClassGraph;
import io.github.classgraph.ClassInfo;
import io.github.classgraph.ScanResult;

/**
 * An annotation that can be reached in more than one way should still be listed only once.
 */
public class Issue559Test {
    /** An annotation that annotates itself, so that it is its own meta-annotation. */
    @Retention(RetentionPolicy.RUNTIME)
    @SelfAnnotating("on the annotation")
    public @interface SelfAnnotating {
        /** @return the value. */
        String value();
    }

    /** A repeatable annotation, to check that repeated annotations are still listed separately. */
    @Retention(RetentionPolicy.RUNTIME)
    @java.lang.annotation.Repeatable(RepeatableAnnotations.class)
    public @interface RepeatableAnnotation {
        /** @return the value. */
        String value();
    }

    /** The container for {@link RepeatableAnnotation}. */
    @Retention(RetentionPolicy.RUNTIME)
    public @interface RepeatableAnnotations {
        /** @return the repeated annotations. */
        RepeatableAnnotation[] value();
    }

    /** A class annotated twice with the same repeatable annotation value. */
    @RepeatableAnnotation("same")
    @RepeatableAnnotation("same")
    public static class RepeatedTwiceWithTheSameValue {
    }

    /**
     * Scan this test's package.
     *
     * @return the scan result
     */
    private static ScanResult scan() {
        return new ClassGraph().acceptPackages(Issue559Test.class.getPackage().getName()).enableAllInfo().scan();
    }

    /** An annotation that annotates itself is listed once, not twice. */
    @Test
    public void selfAnnotationIsNotListedTwice() {
        try (ScanResult scanResult = scan()) {
            final ClassInfo classInfo = scanResult.getClassInfo(SelfAnnotating.class.getName());
            assertThat(classInfo.getAnnotationInfoRepeatable(SelfAnnotating.class)).hasSize(1);
            // @Retention is a direct annotation on the annotation type, so it is listed too
            assertThat(classInfo.getAnnotationInfo().getNames())
                    .containsExactlyInAnyOrder(SelfAnnotating.class.getName(), Retention.class.getName());
            assertThat(classInfo.getAnnotationInfo(SelfAnnotating.class).getParameterValues().getValue("value"))
                    .isEqualTo("on the annotation");
        }
    }

    /**
     * Two occurrences of a repeatable annotation with identical parameter values are distinct annotations, and are
     * both still listed.
     */
    @Test
    public void repeatedAnnotationsWithTheSameValueAreBothListed() {
        try (ScanResult scanResult = scan()) {
            final ClassInfo classInfo = scanResult.getClassInfo(RepeatedTwiceWithTheSameValue.class.getName());
            final AnnotationInfoList repeated = classInfo.getAnnotationInfoRepeatable(RepeatableAnnotation.class);
            assertThat(repeated).hasSize(2);
            assertThat(repeated.get(0)).isNotSameAs(repeated.get(1));
        }
    }
}
