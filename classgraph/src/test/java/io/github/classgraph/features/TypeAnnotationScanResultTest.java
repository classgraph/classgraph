package io.github.classgraph.features;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.classgraph.AnnotationInfo;
import io.github.classgraph.ClassGraph;
import io.github.classgraph.ClassRefTypeSignature;
import io.github.classgraph.MethodTypeSignature;

/**
 * A type annotation is added to its type signature when the signature is first requested, which is after the
 * signature has been given its {@link io.github.classgraph.ScanResult}, so the annotation has to be given the
 * {@code ScanResult} as it is added. Without that, the annotation cannot reach the annotation class it names, so
 * its default parameter values are silently dropped.
 */
public class TypeAnnotationScanResultTest {
    /** A type annotation with a default parameter value. */
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ ElementType.TYPE_USE })
    public @interface Level {
        /** @return the level. */
        int level() default 7;
    }

    /** A class carrying type annotations in each of the positions where they are added lazily. */
    public class Annotated {
        /** A field whose type carries a type annotation. */
        public @Level String field;

        /** A field with a primitive type that carries a type annotation. */
        public @Level int primitiveField;

        /** A field whose type carries a type annotation on a nested class suffix of a class reference. */
        public GenericOuter<String>.@Level Inner suffixField;

        /**
         * A method with an annotated explicit receiver parameter.
         *
         * @return the empty string.
         */
        public String method(@Level Annotated this) {
            return "";
        }
    }

    /** A class whose enclosed classes give a nested class reference with more than one suffix. */
    public static class Outer {
        /** A non-static inner class. */
        public class Inner {
        }
    }

    /**
     * A generic class with an inner class, so that a reference to the inner class has a suffix that can carry a
     * type annotation.
     *
     * @param <T>
     *            an unused type parameter.
     */
    public static class GenericOuter<T> {
        /** A non-static inner class. */
        public class Inner {
        }
    }

    /** A class with no type annotations anywhere. */
    public static class Unannotated {
        /** A field of a plain class type. */
        public String plain;

        /** A field of a parameterized type. */
        public List<String> parameterized;

        /** A field whose type is a nested class reference. */
        public Outer.Inner nested;

        /** A method with no explicit receiver parameter. */
        public void method() {
            // Does nothing
        }
    }

    /** The type annotation getters return empty lists, not null, when there are no type annotations. */
    @Test
    public void typeAnnotationGettersReturnEmptyListsWhenThereAreNone() {
        try (var scanResult = new ClassGraph().enableClasspath().acceptClasses(Unannotated.class.getName())
                .enableClassInfo().enableFieldInfo().enableMethodInfo().enableAnnotationInfo().scan()) {
            final var classInfo = scanResult.getClassInfo(Unannotated.class.getName());
            assertThat(classInfo).isNotNull();

            for (final var fieldName : List.of("plain", "parameterized", "nested")) {
                final var typeAnnotations = classInfo.getFieldInfo(fieldName).getTypeSignatureOrTypeDescriptor()
                        .getTypeAnnotationInfo();
                assertThat(typeAnnotations).as(fieldName).isNotNull().isEmpty();
            }

            final var nestedType = (ClassRefTypeSignature) classInfo.getFieldInfo("nested")
                    .getTypeSignatureOrTypeDescriptor();
            assertThat(nestedType.getSuffixes()).isNotEmpty();
            assertThat(nestedType.getSuffixTypeAnnotationInfo()).hasSameSizeAs(nestedType.getSuffixes())
                    .allSatisfy(suffixAnnotations -> assertThat(suffixAnnotations).isEmpty());

            final MethodTypeSignature methodType = classInfo.getMethodInfo("method").get(0)
                    .getTypeSignatureOrTypeDescriptor();
            assertThat(methodType.getReceiverTypeAnnotationInfo()).isNotNull().isEmpty();
        }
    }

    /**
     * Type annotations are only read when annotation info is enabled, so without it the type annotation getters
     * throw rather than return an empty list that looks like "no type annotations". Rendering a type signature,
     * which checks whether a supertype is annotated, must still work.
     */
    @Test
    public void typeAnnotationGettersThrowWithoutAnnotationInfo() {
        try (var scanResult = new ClassGraph().enableClasspath()
                .acceptPackages(TypeAnnotationScanResultTest.class.getPackage().getName()).enableClassInfo()
                .enableFieldInfo().enableMethodInfo().ignoreClassVisibility().scan()) {
            final var classInfo = scanResult.getClassInfo(Annotated.class.getName());
            assertThat(classInfo).isNotNull();

            final var fieldType = classInfo.getFieldInfo("field").getTypeSignatureOrTypeDescriptor();
            assertThatThrownBy(fieldType::getTypeAnnotationInfo).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("enableAnnotationInfo");

            final var suffixType = (ClassRefTypeSignature) classInfo.getFieldInfo("suffixField")
                    .getTypeSignatureOrTypeDescriptor();
            assertThatThrownBy(suffixType::getSuffixTypeAnnotationInfo).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("enableAnnotationInfo");
            // The type argument of GenericOuter<String> is on a suffix, since GenericOuter is itself a nested class
            final var typeArgument = suffixType.getSuffixTypeArguments().stream().flatMap(List::stream).findFirst()
                    .orElseThrow();
            assertThatThrownBy(typeArgument::getTypeAnnotationInfo).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("enableAnnotationInfo");

            final MethodTypeSignature methodType = classInfo.getMethodInfo("method").get(0)
                    .getTypeSignatureOrTypeDescriptor();
            assertThatThrownBy(methodType::getReceiverTypeAnnotationInfo).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("enableAnnotationInfo");

            final var genericClassInfo = scanResult.getClassInfo(GenericOuter.class.getName());
            assertThat(genericClassInfo).isNotNull();
            assertThat(genericClassInfo.getTypeSignature()).isNotNull();
            assertThat(genericClassInfo.getTypeSignature().toString()).endsWith("GenericOuter<T>");
        }
    }

    /** Every type annotation must be able to reach its own annotation class, and so its default values. */
    @Test
    public void typeAnnotationsCanReachTheirAnnotationClass() {
        try (var scanResult = new ClassGraph().enableClasspath()
                .acceptPackages(TypeAnnotationScanResultTest.class.getPackage().getName()).enableClassInfo()
                .enableFieldInfo().enableMethodInfo().enableAnnotationInfo()
                .enableStaticFinalFieldConstantInitializerValues().ignoreClassVisibility().ignoreFieldVisibility()
                .ignoreMethodVisibility().scan()) {
            final var classInfo = scanResult.getClassInfo(Annotated.class.getName());
            assertThat(classInfo).isNotNull();

            final var fieldType = classInfo.getFieldInfo("field").getTypeSignatureOrTypeDescriptor();
            final var fieldTypeAnnotations = fieldType.getTypeAnnotationInfo();
            assertThat(fieldTypeAnnotations).isNotNull();
            assertDefaultValueIsVisible(fieldTypeAnnotations.get(0));

            final var primitiveType = classInfo.getFieldInfo("primitiveField").getTypeSignatureOrTypeDescriptor();
            final var primitiveTypeAnnotations = primitiveType.getTypeAnnotationInfo();
            assertThat(primitiveTypeAnnotations).isNotNull();
            assertDefaultValueIsVisible(primitiveTypeAnnotations.get(0));

            final var suffixType = (ClassRefTypeSignature) classInfo.getFieldInfo("suffixField")
                    .getTypeSignatureOrTypeDescriptor();
            final var suffixTypeAnnotations = suffixType.getSuffixTypeAnnotationInfo();
            assertThat(suffixTypeAnnotations).isNotEmpty();
            assertDefaultValueIsVisible(suffixTypeAnnotations.get(suffixTypeAnnotations.size() - 1).get(0));

            final MethodTypeSignature methodType = classInfo.getMethodInfo("method").get(0)
                    .getTypeSignatureOrTypeDescriptor();
            final var receiverAnnotations = methodType.getReceiverTypeAnnotationInfo();
            assertThat(receiverAnnotations).isNotNull();
            assertDefaultValueIsVisible(receiverAnnotations.get(0));
        }
    }

    /** Assert that an annotation can reach its annotation class, and so can report its default parameter value. */
    private static void assertDefaultValueIsVisible(final AnnotationInfo annotationInfo) {
        assertThat(annotationInfo.getName()).isEqualTo(Level.class.getName());
        assertThat(annotationInfo.getClassInfo()).isNotNull();
        assertThat(annotationInfo.getParameterValues().getValue("level")).isEqualTo(7);
    }
}
