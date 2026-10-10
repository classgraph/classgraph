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
package io.github.classgraph;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

import org.junit.jupiter.api.Test;

/**
 * A class inherits an annotation of its superclass only if the annotation directly present on the superclass is
 * {@link Inherited}, as in Java reflection. An {@link Inherited} meta-annotation of an annotation that is not
 * itself {@link Inherited} is not inherited. {@link ClassInfo#hasAnnotation(String)},
 * {@link ClassInfo#getAllAnnotations()}, {@link ClassInfo#getAllAnnotationInfo()} and
 * {@link ScanResult#getClassesWithAnnotation(String)} all follow this rule.
 */
public class InheritedMetaAnnotationAgreementTest {
    /** An inherited annotation, used only as a meta-annotation. */
    @Retention(RetentionPolicy.RUNTIME)
    @Inherited
    public @interface B {
    }

    /** An annotation that is not inherited, meta-annotated by {@link B}. */
    @Retention(RetentionPolicy.RUNTIME)
    @B
    public @interface A {
    }

    /** An inherited annotation, meta-annotated by {@link B}. */
    @Retention(RetentionPolicy.RUNTIME)
    @Inherited
    @B
    public @interface InheritedA {
    }

    /** A superclass annotated by {@link A}. */
    @A
    public static class Base {
    }

    /** A subclass of {@link Base}, which inherits neither {@link A} nor {@link B}. */
    public static class Sub extends Base {
    }

    /** A superclass annotated by {@link InheritedA}. */
    @InheritedA
    public static class InheritedBase {
    }

    /** A subclass of {@link InheritedBase}, which inherits {@link InheritedA} and {@link B}. */
    public static class InheritedSub extends InheritedBase {
    }

    /** The fixture classes. */
    private static final Class<?>[] FIXTURE_CLASSES = { B.class, A.class, InheritedA.class, Base.class, Sub.class,
            InheritedBase.class, InheritedSub.class };

    /**
     * Scan the fixture classes.
     *
     * @return the scan result.
     */
    private static ScanResult scan() {
        final var classNames = new String[FIXTURE_CLASSES.length];
        for (var i = 0; i < FIXTURE_CLASSES.length; i++) {
            classNames[i] = FIXTURE_CLASSES[i].getName();
        }
        return new ClassGraph().enableClasspath().acceptClasses(classNames).enableClassInfo().enableAnnotationInfo()
                .ignoreClassVisibility().scan();
    }

    /**
     * Assert that the class-based and the {@link AnnotationInfo}-based annotation queries of a class agree, apart
     * from the annotations in {@code java.lang.annotation}, which only {@link ClassInfo#getAllAnnotationInfo()}
     * lists.
     *
     * @param classInfo
     *            the class.
     */
    private static void assertAnnotationQueriesAgree(final ClassInfo classInfo) {
        final var annotationInfoNames = classInfo.getAllAnnotationInfo()
                .filter(annotationInfo -> !annotationInfo.getName().startsWith("java.lang.annotation.")).getNames();
        assertThat(classInfo.getAllAnnotations().getNames()).as(classInfo.getName())
                .containsExactlyInAnyOrderElementsOf(annotationInfoNames);
        for (final String annotationName : annotationInfoNames) {
            assertThat(classInfo.hasAnnotation(annotationName)).as(classInfo.getName() + " " + annotationName)
                    .isTrue();
        }
    }

    /**
     * A subclass does not inherit {@link B} from a superclass annotation that is not {@link Inherited}, and every
     * annotation query of every fixture class agrees.
     */
    @Test
    public void metaAnnotationOfNonInheritedAnnotationIsNotInherited() {
        try (var scanResult = scan()) {
            final var sub = scanResult.getClassInfo(Sub.class.getName());
            assertThat(sub.hasAnnotation(B.class)).isFalse();
            assertThat(sub.getAllAnnotations().getNames()).isEmpty();
            assertThat(sub.getAllAnnotationInfo(B.class)).isNull();
            assertThat(Sub.class.isAnnotationPresent(A.class)).isFalse();

            assertThat(scanResult.getClassesWithAnnotation(B.class).getNames()).containsExactly(A.class.getName(),
                    Base.class.getName(), InheritedA.class.getName(), InheritedBase.class.getName(),
                    InheritedSub.class.getName());

            for (final Class<?> fixtureClass : FIXTURE_CLASSES) {
                assertAnnotationQueriesAgree(scanResult.getClassInfo(fixtureClass.getName()));
            }
        }
    }

    /**
     * A subclass inherits {@link B} from a superclass annotation that is {@link Inherited} and meta-annotated by
     * {@link B}, and every class that {@link ScanResult#getClassesWithAnnotation(String)} returns for {@link B} has
     * an {@link AnnotationInfo} for it.
     */
    @Test
    public void metaAnnotationOfInheritedAnnotationIsInherited() {
        try (var scanResult = scan()) {
            final var inheritedSub = scanResult.getClassInfo(InheritedSub.class.getName());
            assertThat(inheritedSub.hasAnnotation(B.class)).isTrue();
            assertThat(inheritedSub.getAllAnnotations().getNames()).containsExactly(B.class.getName(),
                    InheritedA.class.getName());
            assertThat(InheritedSub.class.isAnnotationPresent(InheritedA.class)).isTrue();

            for (final ClassInfo classWithB : scanResult.getClassesWithAnnotation(B.class)) {
                assertThat(classWithB.getAllAnnotationInfo(B.class)).as(classWithB.getName()).isNotNull();
            }
        }
    }
}
