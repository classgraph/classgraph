package io.github.classgraph.issues.issue914;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.annotation.Annotation;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.junit.jupiter.api.Test;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.ClassInfo;
import io.github.classgraph.ScanResult;

/**
 * Test {@link ClassInfo#getMethodInfoWithAnnotation(String)} and friends (issue #914).
 */
public class Issue914Test {

    /** A method annotation. Also targets {@code ANNOTATION_TYPE}, so that it can meta-annotate {@link MetaMarker}. */
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ ElementType.METHOD, ElementType.FIELD, ElementType.ANNOTATION_TYPE })
    public @interface Marker {
    }

    /** A meta-annotated annotation, to check that meta-annotations are matched too. */
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ ElementType.METHOD, ElementType.FIELD })
    @Marker
    public @interface MetaMarker {
    }

    /** An unrelated annotation, which should never be matched. */
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ ElementType.METHOD, ElementType.FIELD })
    public @interface Unrelated {
    }

    /** Superclass, so that declared vs. inherited methods and fields can be distinguished. */
    public static class Super {
        @Marker
        public int superField;

        @Unrelated
        public int unmarkedSuperField;

        @Marker
        public void superMarked() {
        }

        @Unrelated
        public void superUnmarked() {
        }
    }

    /** Subclass. */
    public static class Sub extends Super {
        @Marker
        public int subField;

        @MetaMarker
        public int metaMarkedField;

        public int unannotatedField;

        @Marker
        public void subMarked() {
        }

        @MetaMarker
        public void subMetaMarked() {
        }

        public void unannotated() {
        }
    }

    private static final String MARKER = Marker.class.getName();

    /**
     * Scan this test's classes.
     *
     * @return the scan result.
     */
    private static ScanResult scan() {
        return new ClassGraph().acceptPackages(Issue914Test.class.getPackage().getName()).enableMethodInfo()
                .enableFieldInfo().enableAnnotationInfo().scan();
    }

    /** Declared methods with the annotation should not include methods declared by the superclass. */
    @Test
    public void declaredMethodInfoWithAnnotation() {
        try (ScanResult scanResult = scan()) {
            final ClassInfo sub = scanResult.getClassInfo(Sub.class.getName());
            assertThat(sub.getDeclaredMethodInfoWithAnnotation(MARKER).getNames())
                    .containsExactlyInAnyOrder("subMarked", "subMetaMarked");
        }
    }

    /** Methods with the annotation should include methods declared by the superclass. */
    @Test
    public void methodInfoWithAnnotation() {
        try (ScanResult scanResult = scan()) {
            final ClassInfo sub = scanResult.getClassInfo(Sub.class.getName());
            assertThat(sub.getMethodInfoWithAnnotation(MARKER).getNames())
                    .containsExactlyInAnyOrder("subMarked", "subMetaMarked", "superMarked");
        }
    }

    /** The {@link Class}-typed overloads should behave identically to the {@link String}-typed overloads. */
    @Test
    public void classOverloadsMatchStringOverloads() {
        try (ScanResult scanResult = scan()) {
            final ClassInfo sub = scanResult.getClassInfo(Sub.class.getName());
            assertThat(sub.getMethodInfoWithAnnotation(Marker.class).getNames())
                    .isEqualTo(sub.getMethodInfoWithAnnotation(MARKER).getNames());
            assertThat(sub.getDeclaredMethodInfoWithAnnotation(Marker.class).getNames())
                    .isEqualTo(sub.getDeclaredMethodInfoWithAnnotation(MARKER).getNames());
            assertThat(sub.getFieldInfoWithAnnotation(Marker.class).getNames())
                    .isEqualTo(sub.getFieldInfoWithAnnotation(MARKER).getNames());
            assertThat(sub.getDeclaredFieldInfoWithAnnotation(Marker.class).getNames())
                    .isEqualTo(sub.getDeclaredFieldInfoWithAnnotation(MARKER).getNames());
        }
    }

    /** An annotation that no method has should produce the empty list, not null. */
    @Test
    public void unmatchedAnnotationYieldsEmptyList() {
        try (ScanResult scanResult = scan()) {
            final ClassInfo sub = scanResult.getClassInfo(Sub.class.getName());
            assertThat(sub.getMethodInfoWithAnnotation("com.xyz.NonExistentAnnotation")).isEmpty();
            assertThat(sub.getFieldInfoWithAnnotation("com.xyz.NonExistentAnnotation")).isEmpty();
        }
    }

    /** Constructors are excluded, matching {@link ClassInfo#getMethodInfo()}. */
    @Test
    public void constructorsAreExcluded() {
        try (ScanResult scanResult = scan()) {
            final ClassInfo sub = scanResult.getClassInfo(Sub.class.getName());
            assertThat(sub.getMethodInfoWithAnnotation(MARKER).getNames()).doesNotContain("<init>");
        }
    }

    /** Declared fields with the annotation should not include fields declared by the superclass. */
    @Test
    public void declaredFieldInfoWithAnnotation() {
        try (ScanResult scanResult = scan()) {
            final ClassInfo sub = scanResult.getClassInfo(Sub.class.getName());
            assertThat(sub.getDeclaredFieldInfoWithAnnotation(MARKER).getNames())
                    .containsExactlyInAnyOrder("subField", "metaMarkedField");
        }
    }

    /** Fields with the annotation should include fields declared by the superclass. */
    @Test
    public void fieldInfoWithAnnotation() {
        try (ScanResult scanResult = scan()) {
            final ClassInfo sub = scanResult.getClassInfo(Sub.class.getName());
            assertThat(sub.getFieldInfoWithAnnotation(MARKER).getNames())
                    .containsExactlyInAnyOrder("subField", "metaMarkedField", "superField");
        }
    }

    /** Passing a non-annotation {@link Class} should be rejected. */
    @Test
    public void nonAnnotationClassIsRejected() {
        try (ScanResult scanResult = scan()) {
            final ClassInfo sub = scanResult.getClassInfo(Sub.class.getName());
            @SuppressWarnings("unchecked")
            final Class<? extends Annotation> notAnAnnotation = (Class<? extends Annotation>) (Class<?>) String.class;
            assertThatThrownBy(() -> sub.getMethodInfoWithAnnotation(notAnAnnotation))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> sub.getFieldInfoWithAnnotation(notAnAnnotation))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    /** Without {@code enableAnnotationInfo()}, an {@link IllegalArgumentException} should be thrown. */
    @Test
    public void annotationInfoMustBeEnabled() {
        try (ScanResult scanResult = new ClassGraph()
                .acceptPackages(Issue914Test.class.getPackage().getName()).enableMethodInfo().enableFieldInfo()
                .scan()) {
            final ClassInfo sub = scanResult.getClassInfo(Sub.class.getName());
            assertThatThrownBy(() -> sub.getMethodInfoWithAnnotation(MARKER))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> sub.getFieldInfoWithAnnotation(MARKER))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
