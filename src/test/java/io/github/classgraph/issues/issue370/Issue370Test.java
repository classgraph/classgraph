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
package io.github.classgraph.issues.issue370;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import io.github.classgraph.AnnotationInfo;
import io.github.classgraph.ClassGraph;
import io.github.classgraph.ClassInfo;
import io.github.classgraph.MethodInfo;
import io.github.classgraph.ScanResult;
import io.github.classgraph.issues.issue370.annotations.ApiOperation;
import io.github.classgraph.issues.issue370.impl.ClassWithAnnotation;

/**
 * Unit Test.
 */
public class Issue370Test {
    /**
     * Unit test.
     */
    @Test
    public void issue370Test() {
        try (ScanResult scanResult = new ClassGraph().enableAllInfo()
                .whitelistPackages(ClassWithAnnotation.class.getPackage().getName()).scan()) {
            final ClassInfo clazzInfo = scanResult.getClassInfo(ClassWithAnnotation.class.getName());
            assertThat(clazzInfo).isNotNull();
            for (final MethodInfo methodInfo : clazzInfo.getMethodInfo().filter(MethodInfo::isPublic)) {
                final AnnotationInfo annotationInfo = methodInfo.getAnnotationInfo(ApiOperation.class.getName());
                final String value = annotationInfo.getParameterValues().get("notes").getValue().toString();
                assertThat(value).isEqualTo("${snippetclassifications.findById}");
            }
        }
    }
}
