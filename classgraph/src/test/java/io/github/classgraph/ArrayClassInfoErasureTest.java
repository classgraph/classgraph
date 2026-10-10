package io.github.classgraph;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * One {@link ArrayClassInfo} is shared by every array type with the same class name, so it has to describe the
 * erased array class, and the class name of an array of a type variable has to be the array of the type variable's
 * erasure. The {@link ArrayClassInfo} used to hold the type signature of whichever array type asked for it first,
 * and an array of a type variable used to be named after the class that the type variable is used in.
 */
public class ArrayClassInfoErasureTest {
    /** A {@code TYPE_USE} annotation, to annotate an array element type with. */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.TYPE_USE)
    public @interface TA {
    }

    /** An annotation with a class-valued parameter, to refer to an array class with a class literal. */
    @Retention(RetentionPolicy.RUNTIME)
    public @interface ClassRef {
        /** @return the class. */
        Class<?> value();
    }

    /**
     * Fields of array types that share an erased array class.
     *
     * @param <T>
     *            a type variable with a class bound
     * @param <U>
     *            a type variable with only an interface bound
     * @param <V>
     *            a type variable with no bound
     */
    @ClassRef(List[].class)
    public static class G<T extends Number, U extends Comparable<U>, V> {
        /** An array of a type variable with a class bound. */
        public T[] a;

        /** An array of the class that the type variables are declared in. */
        public G<?, ?, ?>[] b;

        /** An array of a parameterized type. */
        public List<String>[] c;

        /** An array of the same class with a different type argument. */
        public List<Integer>[] d;

        /** An array whose element type has a type annotation. */
        public @TA List<?>[] e;

        /** An array of a type variable with only an interface bound. */
        public U[] f;

        /** A two-dimensional array of an unbounded type variable. */
        public V[][] g;
    }

    /**
     * Scan the classes of this test.
     *
     * @return the scan result.
     */
    private static ScanResult scan() {
        return new ClassGraph().enableClasspath()
                .acceptPackagesNonRecursive(ArrayClassInfoErasureTest.class.getPackage().getName())
                .enableClassInfo().enableFieldInfo().enableAnnotationInfo().scan();
    }

    /**
     * Get the {@link ArrayClassInfo} of the type of a field of {@link G}.
     *
     * @param scanResult
     *            the scan result.
     * @param fieldName
     *            the name of the field.
     * @return the {@link ArrayClassInfo}.
     */
    private static ArrayClassInfo arrayClassInfo(final ScanResult scanResult, final String fieldName) {
        final var g = scanResult.getClassInfo(G.class.getName());
        assertThat(g).isNotNull();
        return ((ArrayTypeSignature) g.getFieldInfo(fieldName).getTypeSignatureOrTypeDescriptor())
                .getArrayClassInfo();
    }

    /**
     * Check that an {@link ArrayClassInfo} describes the erased class {@code java.util.List[]}.
     *
     * @param arrayClassInfo
     *            the {@link ArrayClassInfo}.
     */
    private static void assertErasedListArray(final ArrayClassInfo arrayClassInfo) {
        assertThat(arrayClassInfo.getName()).isEqualTo("java.util.List[]");
        assertThat(arrayClassInfo.getTypeSignatureString()).isEqualTo("[Ljava/util/List;");
        assertThat(arrayClassInfo.getArrayTypeSignature().toString()).isEqualTo("java.util.List[]");
        assertThat(arrayClassInfo.getElementTypeSignature().toString()).isEqualTo("java.util.List");
        assertThat(arrayClassInfo.getArrayTypeSignature().getTypeAnnotationInfo()).isEmpty();
        assertThat(arrayClassInfo.getElementTypeSignature().getTypeAnnotationInfo()).isEmpty();
    }

    /** The type signature of a shared {@link ArrayClassInfo} does not depend on which array type asked first. */
    @Test
    public void sharedArrayClassInfoHasTheErasedSignatureWhicheverArrayTypeAskedFirst() {
        for (final var order : List.of(List.of("d", "c", "e"), List.of("c", "d", "e"), List.of("e", "c", "d"))) {
            try (var scanResult = scan()) {
                final var first = arrayClassInfo(scanResult, order.get(0));
                assertErasedListArray(first);
                for (final var fieldName : order) {
                    assertThat(arrayClassInfo(scanResult, fieldName)).isSameAs(first);
                }
                // The same class reached through a class literal is the same object, with the same signature
                final var classRef = (AnnotationClassRef) scanResult.getClassInfo(G.class.getName())
                        .getAllAnnotationInfo().get(ClassRef.class.getName()).getParameterValues()
                        .getValue("value");
                assertThat(classRef.getClassInfo()).isSameAs(first);
                // The erased signature leads back to the shared object
                assertThat(first.getArrayTypeSignature().getArrayClassInfo()).isSameAs(first);
            }
        }
    }

    /** An array of a type variable is named after the erasure of the type variable. */
    @Test
    public void arrayOfATypeVariableIsNamedAfterItsErasure() {
        try (var scanResult = scan()) {
            final var a = arrayClassInfo(scanResult, "a");
            assertThat(a.getName()).isEqualTo("java.lang.Number[]");
            assertThat(a.getTypeSignatureString()).isEqualTo("[Ljava/lang/Number;");
            assertThat(a.getNumDimensions()).isEqualTo(1);

            assertThat(arrayClassInfo(scanResult, "f").getName()).isEqualTo("java.lang.Comparable[]");

            final var g = arrayClassInfo(scanResult, "g");
            assertThat(g.getName()).isEqualTo("java.lang.Object[][]");
            assertThat(g.getTypeSignatureString()).isEqualTo("[[Ljava/lang/Object;");
            assertThat(g.getNumDimensions()).isEqualTo(2);
        }
    }

    /** Looking up an array of a type variable does not change the {@link ArrayClassInfo} of the declaring class. */
    @Test
    public void arrayOfATypeVariableDoesNotTakeOverTheDeclaringClassArray() {
        try (var scanResult = scan()) {
            final var a = arrayClassInfo(scanResult, "a");
            final var b = arrayClassInfo(scanResult, "b");
            assertThat(b).isNotSameAs(a);
            assertThat(b.getName()).isEqualTo(G.class.getName() + "[]");
            assertThat(b.getTypeSignatureString()).isEqualTo("[L" + G.class.getName().replace('.', '/') + ";");
            assertThat(b.getElementClassInfo()).isSameAs(scanResult.getClassInfo(G.class.getName()));
            assertThat(a.getElementClassInfo()).isNull();
        }
    }
}
