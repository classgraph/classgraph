package io.github.classgraph.issues;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

import org.junit.jupiter.api.Test;

import io.github.classgraph.AnnotationInfoList;
import io.github.classgraph.ClassGraph;
import io.github.classgraph.ScanResult;

/**
 * When a list holds more than one item with the same name, {@code asMap()} maps the name to the same item that
 * {@code get(String)} returns.
 */
public class AsMapDuplicateNameTest {
    /** A repeatable annotation. */
    @Retention(RetentionPolicy.RUNTIME)
    @Repeatable(Rs.class)
    public @interface R {
        /** The value. */
        int value();
    }

    /** The container of {@link R}. */
    @Retention(RetentionPolicy.RUNTIME)
    public @interface Rs {
        /** The contained annotations. */
        R[] value();
    }

    /** A class with a repeated annotation. */
    @R(1)
    @R(2)
    public static class Annotated {
    }

    /** {@code asMap().get(name)} and {@code get(name)} return the same annotation. */
    @Test
    public void asMapAgreesWithGet() {
        try (ScanResult scanResult = new ClassGraph().enableAnnotationInfo()
                .acceptClasses(Annotated.class.getName(), R.class.getName(), Rs.class.getName()).scan()) {
            final AnnotationInfoList annotationInfo = scanResult.getClassInfo(Annotated.class.getName())
                    .getAnnotationInfo();
            assertThat(annotationInfo.getRepeatable(R.class)).hasSize(2);
            assertThat(annotationInfo.asMap().get(R.class.getName()))
                    .isSameAs(annotationInfo.get(R.class.getName()));
        }
    }
}
