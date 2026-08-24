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
package io.github.classgraph.test.classrefannotation;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

import org.junit.jupiter.api.Test;

import io.github.classgraph.AnnotationClassRef;
import io.github.classgraph.ClassGraph;

/**
 * AnnotationClassRefTest.
 */
public class AnnotationClassRefTest {
    /**
     * The Interface ClassRefAnnotation.
     */
    @Retention(RetentionPolicy.RUNTIME)
    public @interface ClassRefAnnotation {

        /**
         * Value.
         *
         * @return the class
         */
        Class<?> value();
    }

    /**
     * Method without annotation.
     */
    public void methodWithoutAnnotation() {
    }

    /**
     * Method with annotation.
     */
    @ClassRefAnnotation(Void.class)
    public void methodWithAnnotation() {
    }

    /**
     * Test class ref annotation.
     */
    @Test
    public void testClassRefAnnotation() {
        try (var scanResult = new ClassGraph().enableClasspath()
                .acceptPackages(AnnotationClassRefTest.class.getPackage().getName()).enableMethodInfo()
                .enableAnnotationInfo().scan()) {
            final var testClasses = scanResult.getClassesWithMethodAnnotation(ClassRefAnnotation.class);
            assertThat(testClasses.size()).isEqualTo(1);
            final var testClass = testClasses.get(0);
            final var method = testClass.getMethodInfo().getSingleMethod("methodWithAnnotation");
            assertThat(method).isNotNull();
            final var annotations = method.getAllAnnotationInfo();
            assertThat(annotations.size()).isEqualTo(1);
            final var annotation = annotations.get(0);
            final var paramVals = annotation.getParameterValues();
            assertThat(paramVals.size()).isEqualTo(1);
            final var paramVal = paramVals.get(0);
            final var val = paramVal.getValue();
            assertThat(val instanceof AnnotationClassRef).isTrue();
            final var classRefVal = (AnnotationClassRef) val;
            assertThat(classRefVal.getName()).isEqualTo(Void.class.getName());
        }
    }
}
