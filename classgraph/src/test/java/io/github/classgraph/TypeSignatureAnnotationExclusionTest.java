package io.github.classgraph;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * Tests for {@link TypeSignature#toString(boolean, java.util.Collection)}, which renders a type without the type
 * annotations that a field, method or parameter declaration already shows.
 */
public class TypeSignatureAnnotationExclusionTest {
    /**
     * An annotation that applies both to a declaration and to the type in it, so that javac records it on the
     * member and on the member's type.
     */
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER, ElementType.TYPE_USE })
    public @interface NN {
    }

    /** A static nested class, used as a member type. */
    public static class Nested {
    }

    /** A class whose members carry {@link NN} on the declaration and on its type. */
    public static class Annotated {
        /** A field of class type. */
        public @NN String name;

        /** A field of array type: the annotation applies to the element type. */
        public @NN String[] names;

        /** A field whose type argument, not the field, is annotated. */
        public List<@NN String> tags;

        /** A field of primitive type. */
        public @NN int count;

        /** A field whose type is a static nested class. */
        public @NN Nested nested;

        /** A field whose generic type is a static nested interface of another class. */
        public @NN Map.Entry<String, Integer> entry;

        /**
         * A method whose result and parameter carry the annotation.
         *
         * @param key
         *            the key.
         * @return the key.
         */
        public @NN String find(@NN final String key) {
            return key;
        }
    }

    /**
     * Scan the fixture class.
     *
     * @return the scan result.
     */
    private static ScanResult scan() {
        return new ClassGraph().enableClasspath().acceptClasses(Annotated.class.getName()).enableClassInfo()
                .enableFieldInfo().enableMethodInfo().enableAnnotationInfo().scan();
    }

    /** The type annotation that duplicates a field's declaration annotation is left out, wherever it sits. */
    @Test
    public void fieldAnnotationsAreLeftOutOfTheFieldType() {
        try (var scanResult = scan()) {
            final var classInfo = scanResult.getClassInfo(Annotated.class.getName());
            for (final var expected : List.of(List.of("name", "String", "java.lang.String"),
                    List.of("names", "String[]", "java.lang.String[]"), List.of("count", "int", "int"),
                    List.of("nested", "Nested", Nested.class.getName()))) {
                final var fieldInfo = classInfo.getFieldInfo(expected.get(0));
                final var typeSig = fieldInfo.getTypeSignatureOrTypeDescriptor();
                assertThat(typeSig.toString(true, fieldInfo.getAllAnnotationInfo().directOnly()))
                        .as(expected.get(0)).isEqualTo(expected.get(1));
                assertThat(typeSig.toString(false, fieldInfo.getAllAnnotationInfo().directOnly()))
                        .as(expected.get(0)).isEqualTo(expected.get(2));
                // The type alone still carries the annotation
                assertThat(typeSig.toString(true, List.of())).as(expected.get(0)).startsWith("@NN ");
                // The field renders its annotation once, then its modifiers and the type rendered without it
                assertThat(fieldInfo.toStringWithSimpleNames()).as(expected.get(0))
                        .isEqualTo("@NN public "
                                + typeSig.toString(true, fieldInfo.getAllAnnotationInfo().directOnly()) + " "
                                + expected.get(0));
            }
        }
    }

    /**
     * For a static nested type, the compiler records the annotation on the nested type, since its enclosing class
     * cannot be annotated, so that is where the duplicate is left out -- by the field itself too.
     */
    @Test
    public void fieldAnnotationsAreLeftOutOfAStaticNestedFieldType() {
        try (var scanResult = scan()) {
            final var fieldInfo = scanResult.getClassInfo(Annotated.class.getName()).getFieldInfo("entry");
            assertThat(fieldInfo.toStringWithSimpleNames()).isEqualTo("@NN public Entry<String, Integer> entry");
            assertThat(fieldInfo.toString()).isEqualTo("@" + NN.class.getName()
                    + " public java.util.Map$Entry<java.lang.String, java.lang.Integer> entry");
            final var typeSig = fieldInfo.getTypeSignatureOrTypeDescriptor();
            assertThat(typeSig.toString(true, List.of())).isEqualTo("@NN Entry<String, Integer>");
        }
    }

    /** An annotation inside a type argument is part of the type, not a duplicate of a declaration annotation. */
    @Test
    public void annotationsInTypeArgumentsAreKept() {
        try (var scanResult = scan()) {
            final var fieldInfo = scanResult.getClassInfo(Annotated.class.getName()).getFieldInfo("tags");
            assertThat(fieldInfo.getAllAnnotationInfo().directOnly()).isEmpty();
            final var typeSig = fieldInfo.getTypeSignatureOrTypeDescriptor();
            assertThat(typeSig.toString(true, List.of())).isEqualTo("List<@NN String>");
            // Excluding the annotation does not reach into the type argument
            final var nn = scanResult.getClassInfo(Annotated.class.getName()).getFieldInfo("name")
                    .getAllAnnotationInfo().directOnly();
            assertThat(typeSig.toString(true, nn)).isEqualTo("List<@NN String>");
        }
    }

    /** A method's result type and its parameter types leave out the method's and parameters' annotations. */
    @Test
    public void methodAndParameterAnnotationsAreLeftOutOfTheirTypes() {
        try (var scanResult = scan()) {
            final var methodInfo = scanResult.getClassInfo(Annotated.class.getName()).getMethodInfo("find").get(0);
            final var resultType = methodInfo.getTypeSignatureOrTypeDescriptor().getResultType();
            assertThat(resultType.toString(true, List.of())).isEqualTo("@NN String");
            assertThat(resultType.toString(true, methodInfo.getAllAnnotationInfo().directOnly()))
                    .isEqualTo("String");

            final var paramInfo = methodInfo.getParameterInfo().get(0);
            final var paramType = paramInfo.getTypeSignatureOrTypeDescriptor();
            assertThat(paramType.toString(true, List.of())).isEqualTo("@NN String");
            assertThat(paramType.toString(true, paramInfo.getAllAnnotationInfo().directOnly())).isEqualTo("String");
            // The same rendering that the method and the parameter use themselves
            assertThat(methodInfo.toStringWithSimpleNames())
                    .isEqualTo("@NN public String find(@NN final String key)");
            assertThat(paramInfo.toStringWithSimpleNames()).isEqualTo("@NN final String key");
        }
    }
}
