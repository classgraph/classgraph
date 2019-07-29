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
package io.github.classgraph.issues.issue151;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.junit.jupiter.api.Test;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.MethodInfo;
import io.github.classgraph.ScanResult;

/**
 * Issue151Test.
 */
public class Issue151Test {
    /**
     * Issue 151 test.
     */
    @Test
    public void issue151Test() {
        // Scans io.github.classgraph.issues.issue146.CompiledWithJDK8, which is in
        // src/test/resources
        final String pkg = Issue151Test.class.getPackage().getName();
        try (ScanResult scanResult = new ClassGraph().whitelistPackages(pkg) //
                .enableMethodInfo() //
                .enableAnnotationInfo() //
                .scan()) {
            final MethodInfo methodInfo = scanResult //
                    .getClassInfo(Issue151Test.class.getName()) //
                    .getMethodInfo("method") //
                    .get(0);
            assertThat(methodInfo.toString()) //
                    .isEqualTo("public void method(@" + ParamAnnotation0.class.getName() + " java.lang.String, @"
                            + ParamAnnotation1.class.getName() + " @" + ParamAnnotation2.class.getName()
                            + " java.lang.String)");
        }
    }

    /**
     * The Interface ParamAnnotation0.
     */
    @Retention(RetentionPolicy.CLASS)
    @Target(ElementType.PARAMETER)
    public @interface ParamAnnotation0 {
    }

    /**
     * The Interface ParamAnnotation1.
     */
    @Retention(RetentionPolicy.CLASS)
    @Target(ElementType.PARAMETER)
    public @interface ParamAnnotation1 {
    }

    /**
     * The Interface ParamAnnotation2.
     */
    @Retention(RetentionPolicy.CLASS)
    @Target(ElementType.PARAMETER)
    public @interface ParamAnnotation2 {
    }

    /**
     * Method.
     *
     * @param annotatedValue0
     *            the annotated value 0
     * @param annotatedValue1
     *            the annotated value 1
     */
    public void method(@ParamAnnotation0 final String annotatedValue0,
            @ParamAnnotation1 @ParamAnnotation2 final String annotatedValue1) {
    }
}
