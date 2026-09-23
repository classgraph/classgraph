package io.github.classgraph.issues;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.ScanResult;
import io.github.classgraph.TypeSignature;

/**
 * How a {@code ? extends Object} type argument is rendered as a string.
 */
public class WildcardToStringTest {
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
    public void extendsObjectIsRenderedAsUnboundedWildcard() {
        try (ScanResult scanResult = new ClassGraph().enableFieldInfo().acceptClasses(Fields.class.getName())
                .scan()) {
            final TypeSignature typeSignature = fieldType(scanResult, "extendsObject");
            assertThat(typeSignature.toString()).isEqualTo("java.util.List<?>");
            assertThat(typeSignature.toStringWithSimpleNames()).isEqualTo("List<?>");
        }
    }

    /** A type annotation on the {@code Object} bound is kept. */
    @Test
    public void annotatedObjectBoundIsKept() {
        try (ScanResult scanResult = new ClassGraph().enableFieldInfo().enableAnnotationInfo()
                .acceptClasses(Fields.class.getName(), A.class.getName()).scan()) {
            final TypeSignature typeSignature = fieldType(scanResult, "extendsAnnotatedObject");
            assertThat(typeSignature.toString())
                    .isEqualTo("java.util.List<? extends @" + A.class.getName() + " java.lang.Object>");
            assertThat(typeSignature.toStringWithSimpleNames()).isEqualTo("List<? extends @A Object>");
        }
    }
}
