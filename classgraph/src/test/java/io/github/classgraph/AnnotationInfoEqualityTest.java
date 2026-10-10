package io.github.classgraph;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.HashSet;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Two {@link AnnotationInfo} objects are equal when they have the same name and the same parameter values, counting
 * default values, as in {@link java.lang.annotation.Annotation#equals(Object)}.
 */
public class AnnotationInfoEqualityTest {
    /** An annotation whose elements all have default values. */
    @Retention(RetentionPolicy.RUNTIME)
    public @interface A {
        /**
         * An int element.
         *
         * @return the value.
         */
        int x() default 5;

        /**
         * An array element.
         *
         * @return the value.
         */
        String[] s() default { "a" };
    }

    /** An annotation with a nested annotation value. */
    @Retention(RetentionPolicy.RUNTIME)
    public @interface Outer {
        /**
         * A nested annotation element.
         *
         * @return the value.
         */
        A inner();
    }

    /** A meta-annotation that applies {@link A} with a value equal to its default. */
    @A(x = 5)
    @Retention(RetentionPolicy.RUNTIME)
    public @interface MetaDefault {
    }

    /** A meta-annotation that applies {@link A} with the same value as {@link DirectAndSameMeta}. */
    @A(x = 7)
    @Retention(RetentionPolicy.RUNTIME)
    public @interface MetaSeven {
    }

    /** No values given. */
    @A
    public static class NoValues {
    }

    /** The default value of x given. */
    @A(x = 5)
    public static class DefaultX {
    }

    /** The default values of both elements given. */
    @A(x = 5, s = { "a" })
    public static class AllDefaults {
    }

    /** A value that is not the default. */
    @A(x = 6)
    public static class OtherX {
    }

    /** A nested annotation with no values given. */
    @Outer(inner = @A)
    public static class OuterNoValues {
    }

    /** A nested annotation with the default value of x given. */
    @Outer(inner = @A(x = 5))
    public static class OuterDefaultX {
    }

    /** {@link A} directly present, and reached through a meta-annotation with an equal value. */
    @A
    @MetaDefault
    public static class DirectAndMetaDefault {
    }

    /** {@link A} directly present, and reached through a meta-annotation with the same declared value. */
    @A(x = 7)
    @MetaSeven
    public static class DirectAndSameMeta {
    }

    /**
     * Scan the test classes.
     *
     * @return the scan result.
     */
    private static ScanResult scan() {
        return new ClassGraph().enableClasspath().enableClassInfo().enableAnnotationInfo()
                .acceptClasses(A.class.getName(), Outer.class.getName(), MetaDefault.class.getName(),
                        MetaSeven.class.getName(), NoValues.class.getName(), DefaultX.class.getName(),
                        AllDefaults.class.getName(), OtherX.class.getName(), OuterNoValues.class.getName(),
                        OuterDefaultX.class.getName(), DirectAndMetaDefault.class.getName(),
                        DirectAndSameMeta.class.getName())
                .scan();
    }

    /**
     * Get the annotation of the given name that is directly present on a class.
     *
     * @param scanResult
     *            the scan result
     * @param annotatedClass
     *            the annotated class
     * @param annotationClass
     *            the annotation class
     * @return the annotation.
     */
    private static AnnotationInfo annotation(final ScanResult scanResult, final Class<?> annotatedClass,
            final Class<?> annotationClass) {
        final var classInfo = scanResult.getClassInfo(annotatedClass.getName());
        assertThat(classInfo).isNotNull();
        final var annotationInfo = classInfo.getAllAnnotationInfo().directOnly().get(annotationClass.getName());
        assertThat(annotationInfo).isNotNull();
        return annotationInfo;
    }

    /**
     * Annotations whose values are equal once defaults are counted are equal and have equal hash codes, and an
     * annotation with another value has another hash code.
     */
    @Test
    public void defaultValuesCountTowardEquality() {
        try (var scanResult = scan()) {
            final var noValues = annotation(scanResult, NoValues.class, A.class);
            final var defaultX = annotation(scanResult, DefaultX.class, A.class);
            final var allDefaults = annotation(scanResult, AllDefaults.class, A.class);
            final var otherX = annotation(scanResult, OtherX.class, A.class);

            // The JDK counts default values too
            assertThat(NoValues.class.getAnnotation(A.class)).isEqualTo(DefaultX.class.getAnnotation(A.class))
                    .isEqualTo(AllDefaults.class.getAnnotation(A.class));

            for (final AnnotationInfo a : List.of(noValues, defaultX, allDefaults)) {
                for (final AnnotationInfo b : List.of(noValues, defaultX, allDefaults)) {
                    assertThat(a).isEqualTo(b);
                    assertThat(a.compareTo(b)).isZero();
                    assertThat(a.hashCode()).isEqualTo(b.hashCode());
                }
                assertThat(a).isNotEqualTo(otherX);
                assertThat(otherX).isNotEqualTo(a);
                assertThat(a.compareTo(otherX)).isNegative();
                assertThat(otherX.compareTo(a)).isPositive();
                // The hash code depends on the values, not just on the name
                assertThat(a.hashCode()).isNotEqualTo(otherX.hashCode());
            }
            assertThat(new HashSet<>(List.of(noValues, defaultX, allDefaults, otherX))).hasSize(2);

            // Nested annotations count their default values too
            assertThat(annotation(scanResult, OuterNoValues.class, Outer.class))
                    .isEqualTo(annotation(scanResult, OuterDefaultX.class, Outer.class));
        }
    }

    /** Comparing annotations does not throw once the {@link ScanResult} has been closed. */
    @Test
    public void equalityWorksAfterTheScanResultIsClosed() {
        final AnnotationInfo outerNoValues;
        final AnnotationInfo outerDefaultX;
        try (var scanResult = scan()) {
            outerNoValues = annotation(scanResult, OuterNoValues.class, Outer.class);
            outerDefaultX = annotation(scanResult, OuterDefaultX.class, Outer.class);
        }
        assertThat(outerNoValues).isEqualTo(outerDefaultX);
        assertThat(outerNoValues.compareTo(outerDefaultX)).isZero();
        assertThat(outerNoValues.hashCode()).isEqualTo(outerDefaultX.hashCode());
    }

    /**
     * Filtering keeps a meta-annotation out of the direct annotations even when it equals a directly present
     * annotation.
     */
    @Test
    public void filterTellsDirectAnnotationsFromEqualMetaAnnotations() {
        try (var scanResult = scan()) {
            for (final Class<?> annotatedClass : List.of(DirectAndMetaDefault.class, DirectAndSameMeta.class)) {
                final var classInfo = scanResult.getClassInfo(annotatedClass.getName());
                assertThat(classInfo).isNotNull();
                final var reachable = classInfo.getAllAnnotationInfo();
                final var direct = reachable.directOnly().get(A.class.getName());
                assertThat(direct).isNotNull();

                final var filtered = reachable.filter(ai -> ai.getName().equals(A.class.getName()));
                assertThat(filtered).as(annotatedClass.getSimpleName()).hasSize(2);
                assertThat(filtered.directOnly()).as(annotatedClass.getSimpleName()).hasSize(1);
                assertThat(filtered.directOnly().get(0)).isSameAs(direct);
                assertThat(classInfo.getAllAnnotationInfoRepeatable(A.class).directOnly())
                        .as(annotatedClass.getSimpleName()).hasSize(1);
            }
        }
    }
}
