package io.github.classgraph;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

import org.junit.jupiter.api.Test;

/**
 * When a list holds more than one item with the same name, {@link MappableInfoList#asMap()} maps the name to the
 * same item that {@link MappableInfoList#get(String)} returns.
 */
class AsMapDuplicateNameTest {
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
    void asMapAgreesWithGet() {
        try (var scanResult = new ClassGraph().enableClasspath().enableClassInfo().enableAnnotationInfo()
                .acceptClasses(Annotated.class.getName(), R.class.getName(), Rs.class.getName()).scan()) {
            final var annotationInfo = scanResult.getClassInfo(Annotated.class.getName()).getAllAnnotationInfo();
            assertThat(annotationInfo.getRepeatable(R.class)).hasSize(2);
            assertThat(annotationInfo.asMap().get(R.class.getName()))
                    .isSameAs(annotationInfo.get(R.class.getName()));
        }
    }
}
