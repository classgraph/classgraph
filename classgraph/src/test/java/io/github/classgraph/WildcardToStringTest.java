package io.github.classgraph;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * How a {@code ? extends Object} type argument is rendered as a string.
 */
class WildcardToStringTest {
    /** A type annotation. */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.TYPE_USE)
    public @interface A {
    }

    /** A class with wildcard fields. */
    public static class Fields {
        /** An upper bound of Object. */
        public List<? extends Object> extendsObject;

        /** An upper bound of Object, with a type annotation on the bound. */
        public List<? extends @A Object> extendsAnnotatedObject;
    }

    /** Get the type signature of a field. */
    private static TypeSignature fieldType(final ScanResult scanResult, final String fieldName) {
        return scanResult.getClassInfo(Fields.class.getName()).getFieldInfo(fieldName).getTypeSignature();
    }

    /** {@code ? extends Object} is rendered as {@code ?}, with or without simple names. */
    @Test
    void extendsObjectIsRenderedAsUnboundedWildcard() {
        try (var scanResult = new ClassGraph().enableClasspath().enableClassInfo().enableFieldInfo()
                .acceptClasses(Fields.class.getName()).scan()) {
            final var typeSignature = fieldType(scanResult, "extendsObject");
            assertThat(typeSignature.toString()).isEqualTo("java.util.List<?>");
            assertThat(typeSignature.toStringWithSimpleNames()).isEqualTo("List<?>");
        }
    }

    /** A type annotation on the {@code Object} bound is kept. */
    @Test
    void annotatedObjectBoundIsKept() {
        try (var scanResult = new ClassGraph().enableClasspath().enableClassInfo().enableFieldInfo()
                .enableAnnotationInfo().acceptClasses(Fields.class.getName(), A.class.getName()).scan()) {
            final var typeSignature = fieldType(scanResult, "extendsAnnotatedObject");
            assertThat(typeSignature.toString())
                    .isEqualTo("java.util.List<? extends @" + A.class.getName() + " java.lang.Object>");
            assertThat(typeSignature.toStringWithSimpleNames()).isEqualTo("List<? extends @A Object>");
        }
    }
}
