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
 * Copyright (c) 2016 Luke Hutchison
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
package io.github.lukehutch.fastclasspathscanner.test.methodannotation2;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.List;

import org.junit.Test;

import io.github.lukehutch.fastclasspathscanner.FastClasspathScanner;
import io.github.lukehutch.fastclasspathscanner.test.external.ExternalAnnotation;

public class TestMethodMetaAnnotation {

    @Target(ElementType.TYPE)
    @Retention(RetentionPolicy.RUNTIME)
    public static @interface MetaAnnotation {
    }

    @Target(ElementType.TYPE)
    @Retention(RetentionPolicy.RUNTIME)
    @MetaAnnotation
    public static @interface ClassAnnotation {
    }

    @Target(ElementType.METHOD)
    @Retention(RetentionPolicy.RUNTIME)
    @MetaAnnotation
    public static @interface MethodAnnotation {
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
    public void testClassMetaAnnotation() {
        final List<String> testClasses = new FastClasspathScanner(
                TestMethodMetaAnnotation.class.getPackage().getName()).scan()
                        .getNamesOfClassesWithAnnotation(MetaAnnotation.class.getName());
        assertThat(testClasses).containsOnly(MetaAnnotatedClass.class.getName());
    }

    @Test
    @ExternalAnnotation
    public void testMethodMetaAnnotation() throws Exception {
        final List<String> testClasses = new FastClasspathScanner(
                TestMethodMetaAnnotation.class.getPackage().getName()).enableMethodAnnotationIndexing().scan()
                        .getNamesOfClassesWithMethodAnnotation(MetaAnnotation.class.getName());
        assertThat(testClasses).containsOnly(ClassWithMetaAnnotatedMethod.class.getName());
    }
}
