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
 * A type signature parsed from a classfile and rendered back into a type signature string has to give back the
 * string that javac wrote. javac separates a nested class from its enclosing class with {@code '$'} until an
 * enclosing class has type arguments, and with {@code '.'} after that, and after a {@code '.'} the whole simple
 * name of the nested class follows, even if it contains a {@code '$'}.
 */
public class TypeSignatureStringRoundTripTest {
    /** A type annotation. */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.TYPE_USE)
    public @interface TA {
    }

    /** A non-generic class with nested classes. */
    public static class A {
        /** An inner class. */
        public class B {
            /** An inner class of an inner class. */
            public class C {
            }
        }

        /** A static nested class. */
        public static class S {
            /** An inner class of a static nested class. */
            public class D {
            }
        }

        /** An inner class whose name contains '$'. */
        public class B$X {
        }
    }

    /**
     * A generic class with nested classes.
     *
     * @param <X>
     *            a type parameter
     */
    public static class G<X> {
        /** An inner class. */
        public class I {
            /** An inner class of an inner class. */
            public class J {
            }

            /**
             * A generic inner class of an inner class.
             *
             * @param <Z>
             *            a type parameter
             */
            public class IG2<Z> {
            }
        }

        /** A static nested class. */
        public static class SN {
            /** An inner class of a static nested class. */
            public class SNI {
            }
        }

        /**
         * A generic inner class.
         *
         * @param <Y>
         *            a type parameter
         */
        public class IG<Y> {
            /** An inner class of a generic inner class. */
            public class K {
            }
        }

        /** An inner class whose name contains '$'. */
        public class I$Dollar {
        }

        /** A field whose type refers to the enclosing class's type variable. */
        public I inScope;

        /** A field whose type refers to the enclosing class's type variable. */
        public I.J inScopeNested;

        /** A field whose type refers to the enclosing class's type variable. */
        public List<I>[] inScopeArray;

        /** A method that declares a local class. */
        public void withLocalClass() {
            /**
             * A generic local class.
             *
             * @param <Q>
             *            a type parameter
             */
            class LocalG<Q> {
                /** A field whose type is the local class. */
                @SuppressWarnings("unused")
                public List<LocalG<Q>> list;
            }
        }
    }

    /**
     * A class with a field of type {@code T[]}, for resolving {@code T} to a nested class.
     *
     * @param <T>
     *            a type parameter
     */
    public static class H<T> {
        /** A field of array type. */
        public T[] arr;
    }

    /** Binds {@code T} to an inner class whose name contains '$'. */
    public static class BoundToDollarInner extends H<G<String>.I$Dollar> {
    }

    /** One field of each common shape of type signature. */
    @SuppressWarnings("rawtypes")
    public static class Fields {
        /** Inner class of a parameterized class. */
        public G<String>.I f1;
        /** Two levels of inner class below a parameterized class. */
        public G<String>.I.J f2;
        /** Parameterized inner class of a parameterized class. */
        public G<String>.IG<Integer> f3;
        /** Inner class of a parameterized inner class. */
        public G<String>.IG<Integer>.K f4;
        /** Static nested class of a generic class. */
        public List<G.SN> f5;
        /** Inner class of a static nested class of a generic class. */
        public List<G.SN.SNI> f6;
        /** Inner classes of a non-generic class. */
        public List<A.B.C> f7;
        /** Inner class of a static nested class. */
        public List<A.S.D> f8;
        /** Inner class of a parameterized class, whose name contains '$'. */
        public G<String>.I$Dollar f9;
        /** Inner class of a non-generic class, whose name contains '$'. */
        public List<A.B$X> f10;
        /** Wildcard type argument on the enclosing class. */
        public G<? extends Number>.I f11;
        /** Array of an inner class of a class parameterized with an array. */
        public G<int[]>.I[] f12;
        /** Nested parameterized inner classes in type arguments. */
        public G<G<String>.I>.IG<G<String>.I[]> f13;
        /** Raw inner class. */
        public List<G.I> f14;
        /** Unbounded wildcards on an inner class and its enclosing class. */
        public Map<String, G<?>.IG<?>>[] f15;
        /** Lower-bounded wildcard. */
        public List<? super G<String>.IG<?>.K> f16;
        /** Generic inner class of an inner class of a parameterized class. */
        public G<String>.I.IG2<Long> f17;
        /** Type annotation on an inner class whose name contains '$'. */
        public G<String>.@TA I$Dollar annotated;
    }

    /**
     * Scan the fixture classes.
     *
     * @return the scan result
     */
    private static ScanResult scan() {
        return new ClassGraph().enableClasspath()
                .acceptClasses(TypeSignatureStringRoundTripTest.class.getName() + "*").enableClassInfo()
                .enableFieldInfo().enableAnnotationInfo().ignoreClassVisibility().scan();
    }

    /**
     * Every field's type signature string, as javac wrote it, is given back when the parsed type signature is
     * rendered as a type signature string.
     */
    @Test
    public void renderingGivesBackTheSignatureJavacWrote() {
        try (var scanResult = scan()) {
            var numChecked = 0;
            for (final var classInfo : scanResult.getAllClasses()) {
                for (final var fieldInfo : classInfo.getDeclaredFieldInfo()) {
                    final var typeSignatureStr = fieldInfo.getTypeSignatureString();
                    if (typeSignatureStr != null) {
                        assertThat(TypeSignature.toTypeSignatureStr(fieldInfo.getTypeSignature()))
                                .as(classInfo.getName() + "." + fieldInfo.getName()).isEqualTo(typeSignatureStr);
                        numChecked++;
                    }
                }
            }
            // 18 fields of Fields, 3 of G, 1 of H and 1 of the local class
            assertThat(numChecked).isEqualTo(23);
        }
    }

    /**
     * After a '.', the whole simple name of the inner class is one suffix, even if it contains a '$'.
     */
    @Test
    public void dollarAfterDotIsPartOfTheSimpleName() {
        try (var scanResult = scan()) {
            final var fields = scanResult.getClassInfo(Fields.class.getName());
            final var f9 = (ClassRefTypeSignature) fields.getFieldInfo("f9").getTypeSignature();
            assertThat(f9.getSuffixes()).containsExactly("G", "I$Dollar");
            assertThat(f9.getFullyQualifiedClassName()).isEqualTo(G.I$Dollar.class.getName());

            // The type annotation belongs to I$Dollar, not to a nonexistent class named Dollar
            final var annotated = (ClassRefTypeSignature) fields.getFieldInfo("annotated").getTypeSignature();
            assertThat(annotated.getSuffixTypeAnnotationInfo()).hasSize(2);
            assertThat(annotated.getSuffixTypeAnnotationInfo().get(0)).isEmpty();
            assertThat(annotated.getSuffixTypeAnnotationInfo().get(1).getNames())
                    .containsExactly(TA.class.getName());
        }
    }

    /**
     * Resolving {@code T[]} to an array of an inner class whose name contains '$' gives the array the type
     * signature string that javac would write for it.
     */
    @Test
    public void resolvedArrayOfDollarInnerClass() {
        try (var scanResult = scan()) {
            final var resolved = scanResult.getClassInfo(H.class.getName()).getFieldInfo("arr").getTypeSignature()
                    .resolveTypeVariables(scanResult.getClassInfo(BoundToDollarInner.class.getName()));
            assertThat(((ArrayTypeSignature) resolved).getTypeSignatureString())
                    .isEqualTo("[L" + G.class.getName().replace('.', '/') + "<Ljava/lang/String;>.I$Dollar;");
        }
    }
}
