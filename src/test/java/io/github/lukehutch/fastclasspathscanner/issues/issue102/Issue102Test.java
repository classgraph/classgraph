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
package io.github.lukehutch.fastclasspathscanner.issues.issue102;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import io.github.lukehutch.fastclasspathscanner.FastClasspathScanner;
import io.github.lukehutch.fastclasspathscanner.matchprocessor.ClassAnnotationMatchProcessor;
import io.github.lukehutch.fastclasspathscanner.matchprocessor.MethodAnnotationMatchProcessor;

@Deprecated
public class Issue102Test {
    @Test
    public void deprecatedClass() {
        final List<String> matchedClassNames = new ArrayList<>();
        new FastClasspathScanner(Issue102Test.class.getPackage().getName())
                .matchClassesWithAnnotation(Deprecated.class, new ClassAnnotationMatchProcessor() {
                    @Override
                    public void processMatch(final Class<?> matchingClass) {
                        matchedClassNames.add(matchingClass.getName());
                    }
                }).scan();
        assertThat(matchedClassNames).containsOnly(Issue102Test.class.getName());
    }

    @Test
    public void deprecatedMethod() {
        final List<String> matchedClassNames = new ArrayList<>();
        new FastClasspathScanner(Issue102Test.class.getPackage().getName())
                .matchClassesWithMethodAnnotation(Deprecated.class, new MethodAnnotationMatchProcessor() {
                    @Override
                    public void processMatch(final Class<?> matchingClass, final Method matchingMethod) {
                        matchedClassNames.add(matchingClass.getName());
                    }
                }).scan();
        assertThat(matchedClassNames).containsOnly(Issue102Test.class.getName());
    }

    @Deprecated
    public void myMethod() {
    }
}
