package io.github.classgraph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** Tests for {@link BaseTypeSignature}, the type signature of the primitive types and void. */
public class BaseTypeSignatureTest {
    /** An annotation that can be written on a use of a type. */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.TYPE_USE)
    public @interface NonNegative {
    }

    /** A class with a primitive-typed field and method whose types carry a type annotation. */
    public static class AnnotatedPrimitives {
        /** A field whose type is annotated. */
        public @NonNegative int annotatedField;

        /**
         * A method whose return type is annotated.
         *
         * @return zero.
         */
        public @NonNegative long annotatedMethod() {
            return 0L;
        }
    }

    /** The type signature character of each base type, and the type it stands for. */
    private static final Map<Character, Class<?>> BASE_TYPES = Map.of('B', byte.class, 'C', char.class, 'D',
            double.class, 'F', float.class, 'I', int.class, 'J', long.class, 'S', short.class, 'Z', boolean.class,
            'V', void.class);

    /** Each base type knows its signature character, its name and the class of the type it stands for. */
    @Test
    public void everyBaseTypeIsNamedAndTyped() {
        BASE_TYPES.forEach((typeSignatureChar, type) -> {
            final var signature = new BaseTypeSignature(typeSignatureChar);
            assertThat(signature.getTypeSignatureChar()).isEqualTo(typeSignatureChar);
            assertThat(signature.getTypeName()).isEqualTo(type.getName());
            assertThat(signature.getType()).isEqualTo(type);
            assertThat(signature.toString()).isEqualTo(type.getName());
            // A base type has no ClassInfo, since primitive types are not scanned
            assertThat(signature.getClassName()).isEqualTo(type.getName());
            assertThat(signature.getClassInfo()).isNull();

            assertThat(BaseTypeSignature.typeCharToString(typeSignatureChar)).isEqualTo(type.getName());
            assertThat(BaseTypeSignature.getTypeChar(type.getName())).isEqualTo(typeSignatureChar);
            assertThat(BaseTypeSignature.getType(typeSignatureChar)).isEqualTo(type);
        });
    }

    /** A type signature character that is not a base type is rejected. */
    @Test
    public void anIllegalTypeCharacterIsRejected() {
        assertThatThrownBy(() -> new BaseTypeSignature('L')).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Illegal BaseTypeSignature type: 'L'");
    }

    /** The lookups report no match, rather than throwing, for anything that is not a base type. */
    @Test
    public void typesThatAreNotBaseTypesHaveNoMatch() {
        assertThat(BaseTypeSignature.typeCharToString('L')).isNull();
        assertThat(BaseTypeSignature.getType('L')).isNull();
        assertThat(BaseTypeSignature.getTypeChar("java.lang.String")).isEqualTo('\0');
        // "Integer" is not "int" -- only the primitive type names match
        assertThat(BaseTypeSignature.getTypeChar("Integer")).isEqualTo('\0');
    }

    /** Parsing reads exactly one base type character, and leaves anything else for the caller to parse. */
    @Test
    public void parsingReadsOneBaseTypeCharacter() throws TypeSignatureParseException {
        final var parser = new TypeSignatureParser("IJ");
        assertThat(BaseTypeSignature.parse(parser).getTypeName()).isEqualTo("int");
        assertThat(BaseTypeSignature.parse(parser).getTypeName()).isEqualTo("long");
        assertThat(parser.hasMore()).isFalse();
        // Parsing at the end of the string yields null rather than throwing
        assertThat(BaseTypeSignature.parse(parser)).isNull();

        final var classTypeParser = new TypeSignatureParser("Ljava/lang/String;");
        assertThat(BaseTypeSignature.parse(classTypeParser)).isNull();
        // A failed parse does not consume anything
        assertThat(classTypeParser.getPosition()).isZero();
    }

    /** Two base types are equal if they are the same type and carry the same type annotations. */
    @Test
    public void equalityIsByTypeAndTypeAnnotations() {
        final var intSignature = new BaseTypeSignature('I');
        assertThat(intSignature).isEqualTo(intSignature).isEqualTo(new BaseTypeSignature('I'))
                .isNotEqualTo(new BaseTypeSignature('J')).isNotEqualTo("int");
        assertThat(intSignature).hasSameHashCodeAs(new BaseTypeSignature('I'));

        assertThat(intSignature.equalsIgnoringTypeParams(new BaseTypeSignature('I'))).isTrue();
        assertThat(intSignature.equalsIgnoringTypeParams(new BaseTypeSignature('J'))).isFalse();
        assertThat(intSignature.equalsIgnoringTypeParams(null)).isFalse();

        try (var scanResult = new ClassGraph().enableClasspath().acceptClasses(AnnotatedPrimitives.class.getName())
                .enableClassInfo().enableFieldInfo().enableMethodInfo().enableAnnotationInfo()
                .enableStaticFinalFieldConstantInitializerValues().ignoreClassVisibility().ignoreFieldVisibility()
                .ignoreMethodVisibility().scan()) {
            final var annotatedInt = scanResult.getClassInfo(AnnotatedPrimitives.class.getName())
                    .getFieldInfo("annotatedField").getTypeSignatureOrTypeDescriptor();
            // The type annotation is part of the identity of the type, but not of the type ignoring type params
            assertThat(annotatedInt).isNotEqualTo(intSignature);
            assertThat(annotatedInt.equalsIgnoringTypeParams(intSignature)).isTrue();
        }
    }

    /** A type annotation on a primitive type is shown before the type name. */
    @Test
    public void typeAnnotationsAreShownBeforeTheTypeName() {
        try (var scanResult = new ClassGraph().enableClasspath().acceptClasses(AnnotatedPrimitives.class.getName())
                .enableClassInfo().enableFieldInfo().enableMethodInfo().enableAnnotationInfo()
                .enableStaticFinalFieldConstantInitializerValues().ignoreClassVisibility().ignoreFieldVisibility()
                .ignoreMethodVisibility().scan()) {
            final var classInfo = scanResult.getClassInfo(AnnotatedPrimitives.class.getName());
            final var annotation = "@" + NonNegative.class.getName();
            final var fieldType = classInfo.getFieldInfo("annotatedField").getTypeSignatureOrTypeDescriptor();
            assertThat(fieldType.toString()).isEqualTo(annotation + " int");
            assertThat(fieldType.toStringWithSimpleNames()).isEqualTo("@NonNegative int");
            assertThat(classInfo.getMethodInfo("annotatedMethod").get(0).getTypeSignatureOrTypeDescriptor()
                    .getResultType().toString()).isEqualTo(annotation + " long");

            // A base type is given the ScanResult like any other type signature, so that a type annotation on a
            // primitive type can reach its own annotation class. (#419 used to suppress this, back when a single
            // BaseTypeSignature instance was shared between all uses of a given primitive type; instances are no
            // longer shared, and a base type is reachable from the ScanResult anyway, so suppressing it only broke
            // type annotations.)
            assertThat(((BaseTypeSignature) fieldType).scanResult).isSameAs(scanResult);
        }
    }
}
