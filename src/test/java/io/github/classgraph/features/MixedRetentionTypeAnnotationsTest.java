package io.github.classgraph.features;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.classgraph.AnnotationInfo;
import io.github.classgraph.AnnotationInfoList;
import io.github.classgraph.ClassGraph;
import io.github.classgraph.ClassInfo;
import io.github.classgraph.ClassRefTypeSignature;
import io.github.classgraph.ScanResult;
import io.github.classgraph.TypeSignature;

/**
 * When a declaration mixes type-use annotations with {@code RUNTIME} retention and type-use annotations with
 * {@code CLASS} retention, javac splits the annotations across a {@code RuntimeVisibleTypeAnnotations} attribute
 * and a {@code RuntimeInvisibleTypeAnnotations} attribute on the same target. Both attributes have to be read, and
 * their annotations merged, rather than the second attribute replacing the first.
 */
public class MixedRetentionTypeAnnotationsTest {
    /** A type-use annotation with RUNTIME retention, so it goes into the runtime visible attribute. */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.TYPE_USE)
    private @interface V {
    }

    /** A type-use annotation with CLASS retention, so it goes into the runtime invisible attribute. */
    @Retention(RetentionPolicy.CLASS)
    @Target(ElementType.TYPE_USE)
    private @interface C {
    }

    /** A fixture with a field and a method that mix both retentions on the same type. */
    private static class Fixture {
        @SuppressWarnings("unused")
        public List<@V @C String> mixedField;

        public @V @C String mixedMethod() {
            return null;
        }
    }

    /**
     * A fixture mixing both retentions on its extends clause. The superclass is a top-level parameterized type, so
     * the class has a Signature attribute, and the superclass reference has no nested-class suffixes, which keeps
     * the placement of the extends-clause annotations independent of which classes were accepted into the scan.
     */
    private static class GenericExtendsFixture extends @V @C ThreadLocal<String> {
    }

    private static ScanResult scanFixture(final Class<?> cls) {
        return new ClassGraph().enableAllInfo().acceptClasses(cls.getName()).scan();
    }

    private static void assertHasBothRetentions(final AnnotationInfoList annotations) {
        assertThat(annotations).isNotNull();
        assertThat(annotations).extracting(AnnotationInfo::getName)
                .containsExactlyInAnyOrder(V.class.getName(), C.class.getName());
    }

    /** Annotations from both retention policies are kept on a field's type argument. */
    @Test
    public void fieldKeepsBothRetentions() {
        try (ScanResult scanResult = scanFixture(Fixture.class)) {
            final ClassInfo classInfo = scanResult.getClassInfo(Fixture.class.getName());
            final ClassRefTypeSignature fieldSig = (ClassRefTypeSignature) classInfo.getFieldInfo("mixedField")
                    .getTypeSignatureOrTypeDescriptor();
            final ClassRefTypeSignature typeArg = (ClassRefTypeSignature) fieldSig.getTypeArguments().get(0)
                    .getTypeSignature();
            assertHasBothRetentions(typeArg.getTypeAnnotationInfo());
        }
    }

    /** Annotations from both retention policies are kept on a method's return type. */
    @Test
    public void methodReturnTypeKeepsBothRetentions() {
        try (ScanResult scanResult = scanFixture(Fixture.class)) {
            final TypeSignature resultType = scanResult.getClassInfo(Fixture.class.getName())
                    .getMethodInfo("mixedMethod").get(0).getTypeSignatureOrTypeDescriptor().getResultType();
            assertHasBothRetentions(resultType.getTypeAnnotationInfo());
        }
    }

    /** Annotations from both retention policies are kept on an extends clause. */
    @Test
    public void extendsClauseKeepsBothRetentions() {
        try (ScanResult scanResult = scanFixture(GenericExtendsFixture.class)) {
            final ClassRefTypeSignature superclassSignature = scanResult
                    .getClassInfo(GenericExtendsFixture.class.getName()).getTypeSignatureOrTypeDescriptor()
                    .getSuperclassSignature();
            assertThat(superclassSignature).isNotNull();
            assertHasBothRetentions(superclassSignature.getTypeAnnotationInfo());
        }
    }
}
