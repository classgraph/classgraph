package io.github.classgraph;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

/** Tests for {@link TypeVariableSignature#equalsIgnoringTypeParams(TypeSignature)}. */
public class TypeVariableSignatureTest {
    /** The class that bounds the type variable {@code T}. */
    public static class ClassBound {
    }

    /** The interface that bounds the type variable {@code U}. */
    public interface InterfaceBound {
    }

    /**
     * A class with a type variable of every kind of bound, and fields of the types that the type variables can be
     * reconciled with.
     *
     * @param <T>
     *            a type variable bounded by a class.
     * @param <U>
     *            a type variable bounded by an interface.
     * @param <V>
     *            a type variable with no bound.
     * @param <W>
     *            a type variable bounded by another type variable.
     * @param <L>
     *            a type variable bounded by a parameterized class.
     */
    public static class Generic<T extends ClassBound, U extends InterfaceBound, V, W extends T, //
            L extends List<String>> {
        /** A field of the class-bounded type variable's type. */
        public T classBounded;

        /** A field of the interface-bounded type variable's type. */
        public U interfaceBounded;

        /** A field of the unbounded type variable's type. */
        public V unbounded;

        /** A field of the type-variable-bounded type variable's type. */
        public W variableBounded;

        /** A field of the parameterized-class-bounded type variable's type. */
        public L parameterizedBounded;

        /** A field of the class that bounds {@code T}. */
        public ClassBound classBoundField;

        /** A field of the interface that bounds {@code U}. */
        public InterfaceBound interfaceBoundField;

        /** A field of a class that bounds none of the type variables. */
        public String stringField;

        /** A field of the class that bounds {@code L}, but with a different type argument. */
        public List<Integer> integerListField;
    }

    /**
     * A subclass that passes its own type variable as the type argument of its superclass. The superclass is a
     * top-level class, so that its type argument is a type argument of the class reference itself rather than of a
     * nested class suffix.
     *
     * @param <S>
     *            a type variable that is used as a type argument of the superclass.
     */
    public static class Sub<S extends ClassBound> extends TypeVariableSignatureTestBase<S> {
    }

    /** The name of the class that declares the type variables. */
    private static final String GENERIC = Generic.class.getName();

    /**
     * Scan the fixture class.
     *
     * @return the scan result, which the caller must close.
     */
    private static ScanResult scan() {
        return new ClassGraph().acceptClasses(GENERIC).enableAllInfo().scan();
    }

    /**
     * Get the type signature of one of the fixture class' fields.
     *
     * @param scanResult
     *            the scan result.
     * @param fieldName
     *            the field name.
     * @return the field's type signature.
     */
    private static TypeSignature fieldType(final ScanResult scanResult, final String fieldName) {
        return scanResult.getClassInfo(GENERIC).getFieldInfo(fieldName).getTypeSignatureOrTypeDescriptor();
    }

    /**
     * Get the type variable that is the type of one of the fixture class' fields.
     *
     * @param scanResult
     *            the scan result.
     * @param fieldName
     *            the field name.
     * @return the field's type, as a type variable.
     */
    private static TypeVariableSignature typeVariable(final ScanResult scanResult, final String fieldName) {
        return (TypeVariableSignature) fieldType(scanResult, fieldName);
    }

    /**
     * A type variable that a class type signature uses within itself -- in the bound of one of the class' own type
     * parameters, or as a type argument of the superclass -- resolves against the class that declares it. The class
     * type signature used to be parsed with no defining class name, on the assumption that it could not refer to
     * its own type variables, so these type variables could not be resolved at all.
     */
    @Test
    public void aTypeVariableUsedWithinAClassTypeSignatureResolvesAgainstThatClass() {
        try (ScanResult scanResult = new ClassGraph().acceptClasses(GENERIC, Sub.class.getName()).enableAllInfo()
                .scan()) {
            // The bound of "W extends T" is the type variable T, which is declared by the same class
            final List<TypeParameter> typeParameters = scanResult.getClassInfo(GENERIC).getTypeSignature()
                    .getTypeParameters();
            final TypeVariableSignature bound = (TypeVariableSignature) typeParameters.get(3).getClassBound();
            assertThat(bound.getName()).isEqualTo("T");
            assertThat(bound.getClassName()).isEqualTo(GENERIC);
            assertThat(bound.resolve().toString()).isEqualTo("T extends " + ClassBound.class.getName());

            // The type argument of the superclass of "class Sub<S extends ClassBound>" is the type variable S
            final TypeVariableSignature superclassTypeArgument = (TypeVariableSignature) scanResult
                    .getClassInfo(Sub.class.getName()).getTypeSignature().getSuperclassSignature()
                    .getTypeArguments().get(0).getTypeSignature();
            assertThat(superclassTypeArgument.getName()).isEqualTo("S");
            assertThat(superclassTypeArgument.resolve().toString())
                    .isEqualTo("S extends " + ClassBound.class.getName());
        }
    }

    /**
     * A type variable with no bound of its own can be reconciled with any class. A type variable declared without a
     * bound is written into the classfile with java.lang.Object as its bound, so the bound has to be recognized as
     * being reconcilable with anything, the same way that java.lang.Object is when it is the type being compared
     * with.
     */
    @Test
    public void anUnboundedTypeVariableCanBeReconciledWithAnyClass() {
        try (ScanResult scanResult = scan()) {
            assertThat(typeVariable(scanResult, "unbounded").resolve().getClassBound().toString())
                    .isEqualTo("java.lang.Object");
            assertThat(typeVariable(scanResult, "unbounded")
                    .equalsIgnoringTypeParams(fieldType(scanResult, "stringField"))).isTrue();
        }
    }

    /** A type variable can be reconciled with its class or interface bound, but not with an unrelated class. */
    @Test
    public void aTypeVariableCanBeReconciledWithItsClassOrInterfaceBound() {
        try (ScanResult scanResult = scan()) {
            final TypeSignature classBoundType = fieldType(scanResult, "classBoundField");
            final TypeSignature interfaceBoundType = fieldType(scanResult, "interfaceBoundField");
            final TypeSignature stringType = fieldType(scanResult, "stringField");

            assertThat(typeVariable(scanResult, "classBounded").equalsIgnoringTypeParams(classBoundType)).isTrue();
            assertThat(typeVariable(scanResult, "classBounded").equalsIgnoringTypeParams(stringType)).isFalse();
            assertThat(typeVariable(scanResult, "interfaceBounded").equalsIgnoringTypeParams(interfaceBoundType))
                    .isTrue();
            assertThat(typeVariable(scanResult, "interfaceBounded").equalsIgnoringTypeParams(stringType)).isFalse();
        }
    }

    /**
     * A type variable bounded by another type variable can be reconciled with that type variable's own bound. The
     * bound used to be compared with the type variable being compared with, rather than with the class reference on
     * the other side of the comparison, so a type variable of this shape could never be reconciled with anything.
     */
    @Test
    public void aTypeVariableBoundedByATypeVariableCanBeReconciledWithThatVariablesBound() {
        try (ScanResult scanResult = scan()) {
            assertThat(typeVariable(scanResult, "variableBounded")
                    .equalsIgnoringTypeParams(fieldType(scanResult, "classBoundField"))).isTrue();
            assertThat(typeVariable(scanResult, "variableBounded")
                    .equalsIgnoringTypeParams(fieldType(scanResult, "stringField"))).isFalse();
        }
    }

    /**
     * The type arguments of the bound are ignored when a type variable is reconciled with a class, as the name of
     * the method says. The bound used to be compared using equals(), which compares type arguments too.
     */
    @Test
    public void theTypeArgumentsOfTheBoundAreIgnored() {
        try (ScanResult scanResult = scan()) {
            // The bound is List<String>, and the class it is compared with is List<Integer>
            assertThat(typeVariable(scanResult, "parameterizedBounded")
                    .equalsIgnoringTypeParams(fieldType(scanResult, "integerListField"))).isTrue();
        }
    }
}

/**
 * The superclass of {@link TypeVariableSignatureTest.Sub}.
 *
 * @param <B>
 *            the type parameter that the subclass passes its own type variable to.
 */
class TypeVariableSignatureTestBase<B> {
}
