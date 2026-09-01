package io.github.classgraph;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * {@link ArrayTypeSignature#getClassName()} used to be implemented as {@code toString()}, so the name of the array
 * class included any type annotations and type arguments rendered by {@code toString()}, e.g.
 * {@code "java.lang.String @Ann []"} or {@code "java.util.List<java.lang.String>[]"}. Neither is a class name, and
 * the name is used as the cache key for {@link ArrayClassInfo}.
 */
public class ArrayClassNameTest {
    /** A {@code TYPE_USE} annotation, to annotate an array type with. */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.TYPE_USE)
    public @interface Ann {
    }

    /**
     * Fields whose array types carry a type annotation and a type argument respectively.
     */
    public static class Holder {
        /** An array type with a type annotation on the array dimension. */
        public String @Ann [] annotatedArray;

        /** An array type whose element type has a type argument. */
        public List<String>[] genericArray;

        /** An array type with a primitive element type and more than one dimension. */
        public int[][] primitiveArray;
    }

    /**
     * The array type signature of a field of {@link Holder}.
     *
     * @param scanResult
     *            the scan result.
     * @param fieldName
     *            the name of the field.
     * @return the field's array type signature.
     */
    private static ArrayTypeSignature arrayType(final ScanResult scanResult, final String fieldName) {
        final var holder = scanResult.getClassInfo(Holder.class.getName());
        assertThat(holder).isNotNull();
        return (ArrayTypeSignature) holder.getFieldInfo(fieldName).getTypeSignatureOrTypeDescriptor();
    }

    /** The array class name drops type annotations and type arguments. */
    @Test
    public void arrayClassNameIsAClassName() {
        try (var scanResult = new ClassGraph().enableClasspath()
                .acceptPackagesNonRecursive(ArrayClassNameTest.class.getPackage().getName()).enableClassInfo()
                .enableFieldInfo().enableMethodInfo().enableAnnotationInfo()
                .enableStaticFinalFieldConstantInitializerValues().ignoreClassVisibility().ignoreFieldVisibility()
                .ignoreMethodVisibility().scan()) {
            final var holder = scanResult.getClassInfo(Holder.class.getName());
            assertThat(holder).isNotNull();

            final var annotated = (ArrayTypeSignature) holder.getFieldInfo("annotatedArray")
                    .getTypeSignatureOrTypeDescriptor();
            // toString() still renders the type annotation
            assertThat(annotated.toString()).contains("@" + Ann.class.getName());
            assertThat(annotated.getArrayClassInfo().getName()).isEqualTo(String[].class.getCanonicalName());

            final var generic = (ArrayTypeSignature) holder.getFieldInfo("genericArray")
                    .getTypeSignatureOrTypeDescriptor();
            // toString() still renders the type argument
            assertThat(generic.toString()).isEqualTo("java.util.List<java.lang.String>[]");
            assertThat(generic.getArrayClassInfo().getName()).isEqualTo(List[].class.getCanonicalName());
        }
    }

    /**
     * An array class describes itself in the JVM's own notation, which is what {@link Class#getName()} would give
     * for the same array class, rather than in the source form its name uses.
     */
    @Test
    public void anArrayClassReportsItsTypeSignatureInJvmNotation() {
        try (var scanResult = new ClassGraph().enableClasspath()
                .acceptPackagesNonRecursive(ArrayClassNameTest.class.getPackage().getName()).enableClassInfo()
                .enableFieldInfo().enableMethodInfo().enableAnnotationInfo()
                .enableStaticFinalFieldConstantInitializerValues().ignoreClassVisibility().ignoreFieldVisibility()
                .ignoreMethodVisibility().scan()) {
            final var primitive = arrayType(scanResult, "primitiveArray").getArrayClassInfo();
            assertThat(primitive.getTypeSignatureString()).isEqualTo(int[][].class.getName());
            assertThat(primitive.getName()).isEqualTo(int[][].class.getCanonicalName());
            assertThat(primitive.getNumDimensions()).isEqualTo(2);
            // A primitive element type has no classfile, so there is no ClassInfo for it
            assertThat(primitive.getElementClassInfo()).isNull();

            // The type signature of a generic array keeps the type argument that its class name drops
            final var generic = arrayType(scanResult, "genericArray").getArrayClassInfo();
            assertThat(generic.getTypeSignatureString()).isEqualTo("[Ljava/util/List<Ljava/lang/String;>;");

            // An array class is an array of its element type, and is not itself described by a class signature
            assertThat(primitive.isArrayClass()).isTrue();
            assertThat(primitive.getTypeSignature()).isNull();
            assertThat(primitive.getElementTypeSignature().toString()).isEqualTo("int");
        }
    }
}
