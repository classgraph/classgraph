package io.github.classgraph;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

import org.junit.jupiter.api.Test;

/**
 * {@link AnnotationClassRef} is the value of a {@code Class<?>}-valued annotation parameter. The referenced type
 * may be a class or interface, a primitive type, or an array type.
 */
public class AnnotationClassRefTest {
    /** An annotation with {@code Class<?>}-valued parameters. */
    @Retention(RetentionPolicy.RUNTIME)
    public @interface ClassRefs {
        /**
         * A reference to a class.
         *
         * @return the class.
         */
        Class<?> classRef();

        /**
         * A reference to a primitive type.
         *
         * @return the primitive type.
         */
        Class<?> primitiveRef();

        /**
         * A reference to an array type.
         *
         * @return the array type.
         */
        Class<?> arrayRef();
    }

    /** A class annotated with class references. */
    @ClassRefs(classRef = String.class, primitiveRef = int.class, arrayRef = String[].class)
    public static class Annotated {
    }

    /**
     * Scan the annotated fixture class.
     *
     * @return the scan result.
     */
    private static ScanResult scanFixture() {
        return new ClassGraph().enableClassInfo().enableClasspath().enableAnnotationInfo()
                .acceptClasses(Annotated.class.getName()).scan();
    }

    /**
     * Get the value of a {@code Class<?>}-valued parameter of the {@link ClassRefs} annotation on
     * {@link Annotated}.
     *
     * @param scanResult
     *            the scan result.
     * @param parameterName
     *            the annotation parameter name.
     * @return the class reference.
     */
    private static AnnotationClassRef classRef(final ScanResult scanResult, final String parameterName) {
        final var classInfo = scanResult.getClassInfo(Annotated.class.getName());
        assertThat(classInfo).isNotNull();
        final var annotationInfo = classInfo.getAllAnnotationInfo(ClassRefs.class);
        assertThat(annotationInfo).isNotNull();
        final var value = annotationInfo.getParameterValues().getValue(parameterName);
        assertThat(value).isInstanceOf(AnnotationClassRef.class);
        return (AnnotationClassRef) value;
    }

    /** A reference to a class knows the class name, and can find its {@link ClassInfo} if it was scanned. */
    @Test
    public void aReferenceToAClassKnowsItsName() {
        try (var scanResult = scanFixture()) {
            final var classRef = classRef(scanResult, "classRef");
            assertThat(classRef.getName()).isEqualTo("java.lang.String");
            assertThat(classRef).hasToString("java.lang.String.class");
            // A referenced class gets a ClassInfo object even though it was not itself scanned
            final var referencedClassInfo = classRef.getClassInfo();
            assertThat(referencedClassInfo).isNotNull();
            assertThat(referencedClassInfo.getName()).isEqualTo("java.lang.String");
            assertThat(referencedClassInfo.isExternalClass()).isTrue();
        }
    }

    /** A reference to a primitive type reports the primitive type name. */
    @Test
    public void aReferenceToAPrimitiveTypeKnowsItsName() {
        try (var scanResult = scanFixture()) {
            final var primitiveRef = classRef(scanResult, "primitiveRef");
            assertThat(primitiveRef.getName()).isEqualTo("int");
            assertThat(primitiveRef).hasToString("int.class");
        }
    }

    /** A reference to an array type reports the array type name. */
    @Test
    public void aReferenceToAnArrayTypeKnowsItsName() {
        try (var scanResult = scanFixture()) {
            final var arrayRef = classRef(scanResult, "arrayRef");
            assertThat(arrayRef.getName()).isEqualTo("java.lang.String[]");
            assertThat(arrayRef).hasToString("java.lang.String[].class");
        }
    }

    /** Two class references are equal if they refer to the same type. */
    @Test
    public void classRefsAreComparedByTheReferencedType() {
        try (var scanResult = scanFixture(); var scanResult2 = scanFixture()) {
            final var classRef = classRef(scanResult, "classRef");
            final var sameClassRef = classRef(scanResult2, "classRef");
            assertThat(sameClassRef).isNotSameAs(classRef).isEqualTo(classRef).hasSameHashCodeAs(classRef);

            assertThat(classRef).isEqualTo(classRef).isNotEqualTo(classRef(scanResult, "primitiveRef"))
                    .isNotEqualTo(classRef(scanResult, "arrayRef")).isNotEqualTo(null)
                    .isNotEqualTo(classRef.toString());
        }
    }
}
