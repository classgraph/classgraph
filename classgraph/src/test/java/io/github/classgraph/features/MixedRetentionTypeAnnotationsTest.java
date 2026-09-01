package io.github.classgraph.features;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.classgraph.AnnotationInfo;
import io.github.classgraph.ClassGraph;
import io.github.classgraph.ClassInfo;
import io.github.classgraph.ClassRefTypeSignature;
import io.github.classgraph.ScanResult;

/**
 * When a declaration mixes type-use annotations with {@code RUNTIME} retention and type-use annotations with
 * {@code CLASS} retention, javac splits the annotations across a {@code RuntimeVisibleTypeAnnotations} attribute
 * and a {@code RuntimeInvisibleTypeAnnotations} attribute on the same target. Both attributes have to be read, and
 * their annotations merged, rather than the second attribute replacing the first.
 */
class MixedRetentionTypeAnnotationsTest {
    /** A type-use annotation with {@code RUNTIME} retention, so it goes into the runtime visible attribute. */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.TYPE_USE)
    private @interface V {
    }

    /** A type-use annotation with {@code CLASS} retention, so it goes into the runtime invisible attribute. */
    @Retention(RetentionPolicy.CLASS)
    @Target(ElementType.TYPE_USE)
    private @interface C {
    }

    /** A fixture with a field and a method that mix both retentions on the same type. */
    private static class Fixture {
        /** A field mixing both retentions on its type argument. The field itself is never accessed. */
        @SuppressWarnings("unused")
        public List<@V @C String> mixedField;

        /**
         * A method mixing both retentions on its return type.
         *
         * @return nothing, never called.
         */
        public @V @C String mixedMethod() {
            return null;
        }
    }

    /**
     * A fixture mixing both retentions on its extends clause. The superclass is a top-level parameterized type, so
     * the class has a {@code Signature} attribute, and the superclass reference has no nested-class suffixes, which
     * keeps the placement of the extends-clause annotations independent of which classes were accepted into the
     * scan.
     */
    private static class GenericExtendsFixture extends @V @C ThreadLocal<String> {
    }

    /**
     * Scan a fixture class.
     *
     * @param cls
     *            the fixture class.
     * @return the scan result.
     */
    private static ScanResult scanFixture(final Class<?> cls) {
        return new ClassGraph().enableClasspath().enableClassInfo().enableFieldInfo().enableMethodInfo()
                .enableAnnotationInfo().enableStaticFinalFieldConstantInitializerValues().ignoreClassVisibility()
                .ignoreFieldVisibility().ignoreMethodVisibility().acceptClasses(cls.getName()).scan();
    }

    /**
     * Get the type annotation info attached to the sole type argument of a parameterized field type.
     *
     * @param classInfo
     *            the class info.
     * @param fieldName
     *            the field name.
     * @return the list of type annotations on the type argument.
     */
    private static Iterable<AnnotationInfo> typeArgAnnotations(final ClassInfo classInfo, final String fieldName) {
        final var fieldSig = (ClassRefTypeSignature) classInfo.getFieldInfo(fieldName)
                .getTypeSignatureOrTypeDescriptor();
        final var typeArg = (ClassRefTypeSignature) fieldSig.getTypeArguments().get(0).getTypeSignature();
        return typeArg.getTypeAnnotationInfo();
    }

    /** Annotations from both retention policies are kept on a field's type argument. */
    @Test
    void fieldKeepsBothRetentions() {
        try (ScanResult scanResult = scanFixture(Fixture.class)) {
            final var annotations = typeArgAnnotations(scanResult.getClassInfo(Fixture.class.getName()),
                    "mixedField");
            assertThat(annotations).isNotNull();
            assertThat(annotations).extracting(AnnotationInfo::getName).containsExactlyInAnyOrder(V.class.getName(),
                    C.class.getName());
        }
    }

    /** Annotations from both retention policies are kept on a method's return type. */
    @Test
    void methodReturnTypeKeepsBothRetentions() {
        try (ScanResult scanResult = scanFixture(Fixture.class)) {
            final var resultType = scanResult.getClassInfo(Fixture.class.getName()).getMethodInfo("mixedMethod")
                    .get(0).getTypeSignatureOrTypeDescriptor().getResultType();
            assertThat(resultType.getTypeAnnotationInfo()).isNotNull();
            assertThat(resultType.getTypeAnnotationInfo()).extracting(AnnotationInfo::getName)
                    .containsExactlyInAnyOrder(V.class.getName(), C.class.getName());
        }
    }

    /** Annotations from both retention policies are kept on an extends clause. */
    @Test
    void extendsClauseKeepsBothRetentions() {
        try (ScanResult scanResult = scanFixture(GenericExtendsFixture.class)) {
            final var superclassSignature = scanResult.getClassInfo(GenericExtendsFixture.class.getName())
                    .getTypeSignatureOrTypeDescriptor().getSuperclassSignature();
            assertThat(superclassSignature).isNotNull();
            assertThat(superclassSignature.getTypeAnnotationInfo()).isNotNull();
            assertThat(superclassSignature.getTypeAnnotationInfo()).extracting(AnnotationInfo::getName)
                    .containsExactlyInAnyOrder(V.class.getName(), C.class.getName());
        }
    }
}
