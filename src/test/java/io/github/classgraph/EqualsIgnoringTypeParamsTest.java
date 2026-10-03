package io.github.classgraph;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.Arrays;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * {@link TypeSignature#equalsIgnoringTypeParams(TypeSignature)} compared the type arguments of the nested classes
 * in a class reference such as {@code Outer<String>.Inner<Integer>}, and compared type annotations, which the base
 * type and array signatures did not.
 */
public class EqualsIgnoringTypeParamsTest {
    /** A type annotation. */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.TYPE_USE)
    public @interface Checked {
    }

    /**
     * A generic class with inner classes.
     *
     * @param <X>
     *            the type parameter.
     */
    public static class Outer<X> {
        /**
         * An inner class.
         *
         * @param <Y>
         *            the type parameter.
         */
        public class Inner<Y> {
        }

        /**
         * A second inner class.
         *
         * @param <Y>
         *            the type parameter.
         */
        public class OtherInner<Y> {
        }
    }

    /**
     * Fields of the types that are compared.
     *
     * @param <T>
     *            the type parameter.
     */
    public static class Fields<T> {
        /** A field whose type is not annotated. */
        public String plainString;

        /** A field of the same type as {@link #plainString}, with an annotation on the type. */
        public @Checked String checkedString;

        /** A field whose type is an inner class of a generic class. */
        public Outer<String>.Inner<Integer> inner;

        /** A field of the same type as {@link #inner}, with a different type argument for the inner class. */
        public Outer<String>.Inner<Long> innerWithOtherArgument;

        /** A field of the same type as {@link #inner}, with a different type argument for the outer class. */
        public Outer<Long>.Inner<Integer> innerOfOtherOuter;

        /** A field of the same type as {@link #inner}, with an annotation on the inner class. */
        public Outer<String>.@Checked Inner<Integer> checkedInner;

        /** A field whose type is a different inner class of the same generic class as {@link #inner}. */
        public Outer<String>.OtherInner<Integer> otherInner;

        /** A field whose type is the type variable. */
        public T plainTypeVariable;

        /** A field whose type is the type variable, with an annotation on the type. */
        public @Checked T checkedTypeVariable;
    }

    /** The scan of the test classes. */
    private static ScanResult scanResult;

    /** Scan the test classes. */
    @BeforeAll
    static void scan() {
        scanResult = new ClassGraph().acceptClasses(EqualsIgnoringTypeParamsTest.class.getName() + "$*")
                .enableClassInfo().enableFieldInfo().enableAnnotationInfo().scan();
    }

    /** Close the scan result. */
    @AfterAll
    static void closeScanResult() {
        scanResult.close();
    }

    /**
     * The type of a field of {@link Fields}.
     *
     * @param name
     *            the name of the field.
     * @return the type of the field.
     */
    private static TypeSignature fieldType(final String name) {
        return scanResult.getClassInfo(Fields.class.getName()).getFieldInfo(name)
                .getTypeSignatureOrTypeDescriptor();
    }

    /**
     * A class reference compared ignoring type parameters ignores the type arguments of every class in a nested
     * class reference, not only of the outermost class, and ignores type annotations.
     */
    @Test
    public void classReferencesAreComparedIgnoringTheirTypeParametersAndAnnotations() {
        final TypeSignature inner = fieldType("inner");
        for (final TypeSignature other : Arrays.asList(fieldType("innerWithOtherArgument"),
                fieldType("innerOfOtherOuter"), fieldType("checkedInner"))) {
            assertThat(inner).isNotEqualTo(other);
            assertThat(inner.equalsIgnoringTypeParams(other)).as(other.toString()).isTrue();
            assertThat(other.equalsIgnoringTypeParams(inner)).as(other.toString()).isTrue();
        }
        assertThat(inner.equalsIgnoringTypeParams(fieldType("otherInner"))).isFalse();

        final TypeSignature plainString = fieldType("plainString");
        final TypeSignature checkedString = fieldType("checkedString");
        assertThat(checkedString).isNotEqualTo(plainString);
        assertThat(checkedString.equalsIgnoringTypeParams(plainString)).isTrue();
        assertThat(plainString.equalsIgnoringTypeParams(checkedString)).isTrue();
    }

    /** A type variable compared ignoring type parameters ignores type annotations. */
    @Test
    public void typeVariablesAreComparedIgnoringTheirAnnotations() {
        final TypeSignature plain = fieldType("plainTypeVariable");
        final TypeSignature checked = fieldType("checkedTypeVariable");
        assertThat(checked).isInstanceOf(TypeVariableSignature.class).isNotEqualTo(plain);
        assertThat(checked.equalsIgnoringTypeParams(plain)).isTrue();
        assertThat(plain.equalsIgnoringTypeParams(checked)).isTrue();
    }
}
