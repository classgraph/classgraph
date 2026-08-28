package io.github.classgraph;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

import org.junit.jupiter.api.Test;

/**
 * {@link AnnotationInfoList#filter(AnnotationInfoList.AnnotationInfoFilter)} dropped the record of which annotations
 * were directly present on the annotated element, so calling {@link AnnotationInfoList#directOnly()} on a filtered
 * list returned the meta-annotations too.
 */
public class AnnotationInfoListFilterDirectOnlyTest {
    /** An annotation placed on another annotation, making it a meta-annotation. */
    @Retention(RetentionPolicy.RUNTIME)
    public @interface MetaAnnotation {
    }

    /** An annotation that is directly present on the annotated class, and is itself annotated. */
    @Retention(RetentionPolicy.RUNTIME)
    @MetaAnnotation
    public @interface DirectAnnotation {
    }

    /** The annotated class. */
    @DirectAnnotation
    public static class AnnotatedClass {
    }

    /** Filtering a list of annotations does not turn its meta-annotations into direct annotations. */
    @Test
    public void filteringKeepsTrackOfWhichAnnotationsAreDirect() {
        try (ScanResult scanResult = new ClassGraph()
                .acceptClasses(MetaAnnotation.class.getName(), DirectAnnotation.class.getName(),
                        AnnotatedClass.class.getName())
                .enableAnnotationInfo().scan()) {
            final AnnotationInfoList annotations = scanResult.getClassInfo(AnnotatedClass.class.getName())
                    .getAnnotationInfo();
            assertThat(annotations.getNames()).containsExactly(DirectAnnotation.class.getName(),
                    MetaAnnotation.class.getName());
            assertThat(annotations.directOnly().getNames()).containsExactly(DirectAnnotation.class.getName());

            // A filter that accepts everything leaves the direct annotations unchanged
            final AnnotationInfoList filtered = annotations.filter(annotationInfo -> true);
            assertThat(filtered.getNames()).containsExactly(DirectAnnotation.class.getName(),
                    MetaAnnotation.class.getName());
            assertThat(filtered.directOnly().getNames()).containsExactly(DirectAnnotation.class.getName());

            // A filter that keeps only the meta-annotation leaves no direct annotation behind
            final AnnotationInfoList metaOnly = annotations
                    .filter(annotationInfo -> annotationInfo.getName().equals(MetaAnnotation.class.getName()));
            assertThat(metaOnly.getNames()).containsExactly(MetaAnnotation.class.getName());
            assertThat(metaOnly.directOnly()).isEmpty();
        }
    }
}
