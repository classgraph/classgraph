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
    }

    /** The array class name drops type annotations and type arguments. */
    @Test
    public void arrayClassNameIsAClassName() {
        try (var scanResult = new ClassGraph()
                .acceptPackagesNonRecursive(ArrayClassNameTest.class.getPackage().getName()).enableAllInfo()
                .scan()) {
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
}
