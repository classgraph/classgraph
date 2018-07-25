/*
 * This file is part of FastClasspathScanner.
 *
 * Author: Luke Hutchison
 *
 * Hosted at: https://github.com/lukehutch/fast-classpath-scanner
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
package io.github.lukehutch.fastclasspathscanner.test.classrefannotation;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.List;

import org.junit.Test;

import io.github.lukehutch.fastclasspathscanner.AnnotationClassRef;
import io.github.lukehutch.fastclasspathscanner.AnnotationInfo;
import io.github.lukehutch.fastclasspathscanner.AnnotationInfoList;
import io.github.lukehutch.fastclasspathscanner.AnnotationParameterValue;
import io.github.lukehutch.fastclasspathscanner.ClassInfo;
import io.github.lukehutch.fastclasspathscanner.ClassInfoList;
import io.github.lukehutch.fastclasspathscanner.FastClasspathScanner;
import io.github.lukehutch.fastclasspathscanner.MethodInfo;

public class AnnotationClassRefTest {

    @Retention(RetentionPolicy.RUNTIME)
    public @interface ClassRefAnnotation {
        Class<?> value();
    }

    public void methodWithoutAnnotation() {
    }

    @ClassRefAnnotation(Void.class)
    public void methodWithAnnotation() {
    }

    @Test
    public void testClassRefAnnotation() throws Exception {
        final ClassInfoList testClasses = new FastClasspathScanner()
                .whitelistPackages(AnnotationClassRefTest.class.getPackage().getName()).enableMethodInfo()
                .enableAnnotationInfo().scan().getClassesWithMethodAnnotation(ClassRefAnnotation.class.getName());
        assertThat(testClasses.size()).isEqualTo(1);
        final ClassInfo testClass = testClasses.get(0);
        final MethodInfo method = testClass.getMethodInfo().get("methodWithAnnotation");
        assertThat(method).isNotNull();
        final AnnotationInfoList annotations = method.getAnnotationInfo();
        assertThat(annotations.size()).isEqualTo(1);
        final AnnotationInfo annotation = annotations.get(0);
        final List<AnnotationParameterValue> paramVals = annotation.getParameterValues();
        assertThat(paramVals.size()).isEqualTo(1);
        final AnnotationParameterValue paramVal = paramVals.get(0);
        final Object val = paramVal.getValue();
        assertThat(val instanceof AnnotationClassRef).isTrue();
        final AnnotationClassRef classRefVal = (AnnotationClassRef) val;
        assertThat(classRefVal.loadClass()).isEqualTo(Void.class);
    }
}
