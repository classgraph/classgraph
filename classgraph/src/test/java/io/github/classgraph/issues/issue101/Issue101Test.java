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
package io.github.classgraph.issues.issue101;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.ClassInfo;

public class Issue101Test {
    /**
     * Non inherited annotation.
     */
    @Test
    public void nonInheritedAnnotation() {
        try (var scanResult = new ClassGraph().enableClasspath()
                .acceptPackages(Issue101Test.class.getPackage().getName()).enableClassInfo().enableFieldInfo()
                .enableMethodInfo().enableAnnotationInfo().enableStaticFinalFieldConstantInitializerValues()
                .ignoreClassVisibility().ignoreFieldVisibility().ignoreMethodVisibility().scan()) {
            assertThat(scanResult.getClassesWithAnnotation(NonInheritedAnnotation.class).getNames())
                    .containsOnly(AnnotatedClass.class.getName());
        }
    }

    /**
     * Inherited meta annotation. {@link NonAnnotatedSubclass} does not inherit {@link InheritedMetaAnnotation},
     * since the annotation that carries it, {@link NonInheritedAnnotation}, is not inherited.
     */
    @Test
    public void inheritedMetaAnnotation() {
        try (var scanResult = new ClassGraph().enableClasspath()
                .acceptPackages(Issue101Test.class.getPackage().getName()).enableClassInfo().enableFieldInfo()
                .enableMethodInfo().enableAnnotationInfo().enableStaticFinalFieldConstantInitializerValues()
                .ignoreClassVisibility().ignoreFieldVisibility().ignoreMethodVisibility().scan()) {
            assertThat(scanResult.getClassesWithAnnotation(InheritedMetaAnnotation.class).getStandardClasses()
                    .getNames()).containsOnly(AnnotatedClass.class.getName());
            assertThat(NonAnnotatedSubclass.class.isAnnotationPresent(NonInheritedAnnotation.class)).isFalse();
        }
    }

    /**
     * For every class, {@link ClassInfo#getAllAnnotations()} and {@link ClassInfo#getAllAnnotationInfo()} list the
     * same annotations, apart from those in {@code java.lang.annotation}, which only
     * {@link ClassInfo#getAllAnnotationInfo()} lists, and {@link ClassInfo#hasAnnotation(String)} and
     * {@link ClassInfo#getClassesWithAnnotation()} agree with them.
     */
    @Test
    public void annotationQueriesAgree() {
        try (var scanResult = new ClassGraph().enableClasspath()
                .acceptPackages(Issue101Test.class.getPackage().getName()).enableClassInfo().enableAnnotationInfo()
                .ignoreClassVisibility().scan()) {
            final var allClasses = scanResult.getAllClasses();
            assertThat(allClasses).isNotEmpty();
            for (final ClassInfo classInfo : allClasses) {
                final var annotationInfoNames = classInfo.getAllAnnotationInfo()
                        .filter(annotationInfo -> !annotationInfo.getName().startsWith("java.lang.annotation."))
                        .getNames();
                assertThat(classInfo.getAllAnnotations().getNames()).as(classInfo.getName())
                        .containsExactlyInAnyOrderElementsOf(annotationInfoNames);
                for (final ClassInfo annotation : scanResult.getAllAnnotations()) {
                    final var hasAnnotationInfo = annotationInfoNames.contains(annotation.getName());
                    assertThat(classInfo.hasAnnotation(annotation.getName()))
                            .as(classInfo.getName() + " " + annotation.getName()).isEqualTo(hasAnnotationInfo);
                    assertThat(annotation.getClassesWithAnnotation().containsName(classInfo.getName()))
                            .as(classInfo.getName() + " " + annotation.getName()).isEqualTo(hasAnnotationInfo);
                }
            }
        }
    }

    /**
     * Inherited annotation.
     */
    @Test
    public void inheritedAnnotation() {
        try (var scanResult = new ClassGraph().enableClasspath()
                .acceptPackages(Issue101Test.class.getPackage().getName()).enableClassInfo().enableFieldInfo()
                .enableMethodInfo().enableAnnotationInfo().enableStaticFinalFieldConstantInitializerValues()
                .ignoreClassVisibility().ignoreFieldVisibility().ignoreMethodVisibility().scan()) {
            assertThat(scanResult.getClassesWithAnnotation(InheritedAnnotation.class).getNames()).containsOnly(
                    AnnotatedClass.class.getName(), NonAnnotatedSubclass.class.getName(),
                    AnnotatedInterface.class.getName());
        }
    }
}
