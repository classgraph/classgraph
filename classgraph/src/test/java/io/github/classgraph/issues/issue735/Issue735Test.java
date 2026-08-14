package io.github.classgraph.issues.issue735;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import io.github.classgraph.ArrayTypeSignature;
import io.github.classgraph.ClassGraph;
import io.github.classgraph.ClassInfo;
import io.github.classgraph.ClassRefTypeSignature;
import io.github.classgraph.ScanResult;

public class Issue735Test {
    interface Base<T> {
        T get();
    }

    static class Derived1 implements Base<String> {
        @Override
        public String get() {
            return null;
        }
    }

    abstract static class Derived2 implements Base<String> {
    }

    @Test
    void genericSuperclass() {
        try (var scanResult = new ClassGraph().acceptPackages(Issue735Test.class.getPackage().getName())
                .enableAllInfo().ignoreClassVisibility().ignoreMethodVisibility().scan()) {
            final var ci1 = scanResult.getClassInfo(Derived1.class.getName());
            assertThat(ci1.getMethodInfo().get(0).getTypeSignatureOrTypeDescriptor().getResultType().toString())
                    .isEqualTo(String.class.getName());
            final var ci2 = scanResult.getClassInfo(Derived2.class.getName());
            assertThat(ci2.getMethodInfo().get(0).getTypeSignatureOrTypeDescriptor().getResultType().toString())
                    .isEqualTo("T");
        }
    }

    // ---------------------------------------------------------------------------------------------------------
    // TypeSignature#resolveTypeVariables(ClassInfo)

    /** Declares the type variables that the tests below resolve. */
    interface Generic<T> {
        T getT();

        List<T> getListOfT();

        T[] getArrayOfT();

        /**
         * A generic method whose own type parameter T shadows the interface's type parameter T.
         */
        // A type parameter that shadows the interface's own type parameter is the fixture
        @SuppressWarnings("hiding")
        <T> T identity(T t);

        void setT(T t);
    }

    /** Has a field of the type variable type. */
    abstract static class GenericField<T> {
        T field;
    }

    /** Binds Generic's T to String directly. */
    abstract static class BoundToString implements Generic<String> {
    }

    /** Passes its own type parameter U straight through to Generic. */
    abstract static class PassesThrough<U> implements Generic<U> {
    }

    /**
     * Binds PassesThrough's U to Integer, which must compose into a binding of Generic's T to Integer.
     */
    abstract static class BoundToIntegerIndirectly extends PassesThrough<Integer> {
    }

    /** Binds Generic's T to a parameterized type that contains a wildcard. */
    abstract static class BoundToWildcardedType implements Generic<List<? extends Number>> {
    }

    /** Binds Generic's T to a parameterized type that contains an unbounded wildcard. */
    abstract static class BoundToUnboundedWildcardedType implements Generic<List<?>> {
    }

    /** Binds Generic's T to a parameterized type that contains a lower-bounded wildcard. */
    abstract static class BoundToLowerBoundedWildcardedType implements Generic<List<? super Integer>> {
    }

    /** Binds Generic's T to an inner class of a parameterized outer class. */
    abstract static class BoundToInnerOfParameterizedOuter implements Generic<Outer<Short>.Inner> {
    }

    /** Uses Generic in raw form, so supplies no type argument for T. */
    @SuppressWarnings("rawtypes")
    abstract static class BoundRaw implements Generic {
    }

    /** Binds GenericField's T to Long. */
    abstract static class FieldBoundToLong extends GenericField<Long> {
    }

    /**
     * Binds Generic's T to a class that is itself within the scanned package, so has a {@link ClassInfo}.
     */
    abstract static class BoundToScannedClass implements Generic<Derived1> {
    }

    /** Declares two type parameters, so that positional mapping can be checked. */
    interface Pair<A, B> {
        A getA();

        B getB();

        Map<A, List<B>> getNested();

        A[][] getGrid();

        int getInt();

        String getString();
    }

    /** Binds Pair's A and B directly. */
    abstract static class PairBound implements Pair<String, Integer> {
    }

    /** Swaps its own two type parameters when passing them on to Pair. */
    abstract static class PairSwapped<X, Y> implements Pair<Y, X> {
    }

    /**
     * Binds PairSwapped's X and Y, so Pair's A must compose to Integer and B to String.
     */
    abstract static class PairSwappedBound extends PairSwapped<String, Integer> {
    }

    /**
     * Extends one generic class and implements another generic interface, both of which name their type parameter
     * {@code T}, so the two bindings must not collide.
     */
    abstract static class BoundOnBothBranches extends GenericField<Long> implements Generic<String> {
    }

    /**
     * A self-referential ("f-bounded") type parameter, which must not send resolution into infinite recursion.
     */
    interface Recursive<R extends Recursive<R>> {
        R self();
    }

    /** Binds Recursive's R to itself. */
    abstract static class RecursiveBound implements Recursive<RecursiveBound> {
    }

    /**
     * A generic class with a generic inner (non-static) class, whose type variable is the outer class'.
     */
    static class Outer<O> {
        class Inner {
            O getO() {
                return null;
            }
        }
    }

    /** Binds Outer's O to Short. */
    static class OuterBound extends Outer<Short> {
    }

    /** The scan result, shared by the resolution tests. */
    private static ScanResult scanResult;

    @BeforeAll
    static void scan() {
        scanResult = new ClassGraph().acceptPackages(Issue735Test.class.getPackage().getName()).enableAllInfo()
                .ignoreClassVisibility().ignoreMethodVisibility().ignoreFieldVisibility().scan();
    }

    @AfterAll
    static void closeScanResult() {
        scanResult.close();
    }

    /**
     * Get the declared result type of a method of {@link Generic}, resolved against a context class.
     *
     * @param methodName
     *            the name of the method of {@link Generic}.
     * @param contextClass
     *            the class to resolve type variables against.
     * @return the resolved result type, as a string.
     */
    private static String resolvedResultType(final String methodName, final Class<?> contextClass) {
        final var methodInfo = scanResult.getClassInfo(Generic.class.getName()).getMethodInfo(methodName).get(0);
        return methodInfo.getTypeSignatureOrTypeDescriptor().getResultType()
                .resolveTypeVariables(scanResult.getClassInfo(contextClass.getName())).toString();
    }

    /** Without resolution, the declared types are the type variables themselves. */
    @Test
    void unresolvedTypesAreTypeVariables() {
        final var generic = scanResult.getClassInfo(Generic.class.getName());
        assertThat(
                generic.getMethodInfo("getT").get(0).getTypeSignatureOrTypeDescriptor().getResultType().toString())
                .isEqualTo("T");
        assertThat(generic.getMethodInfo("getListOfT").get(0).getTypeSignatureOrTypeDescriptor().getResultType()
                .toString()).isEqualTo("java.util.List<T>");
        assertThat(generic.getMethodInfo("getArrayOfT").get(0).getTypeSignatureOrTypeDescriptor().getResultType()
                .toString()).isEqualTo("T[]");
    }

    /**
     * The case from the issue: a type variable bound directly by the context class.
     */
    @Test
    void typeVariableIsResolvedAgainstDirectSubtype() {
        assertThat(resolvedResultType("getT", BoundToString.class)).isEqualTo("java.lang.String");
    }

    /** A type variable in type argument position. */
    @Test
    void typeVariableIsResolvedInTypeArgumentPosition() {
        assertThat(resolvedResultType("getListOfT", BoundToString.class))
                .isEqualTo("java.util.List<java.lang.String>");
    }

    /** A type variable as the element type of an array. */
    @Test
    void typeVariableIsResolvedAsArrayElementType() {
        assertThat(resolvedResultType("getArrayOfT", BoundToString.class)).isEqualTo("java.lang.String[]");
    }

    /**
     * An array element type is rebuilt in full, whatever kind of type is substituted into it: another type
     * variable, a parameterized type, a wildcard of any of the three forms, or a reference to an inner class that
     * carries type arguments on the outer class.
     */
    @Test
    void anySubstitutedArrayElementTypeIsRebuilt() {
        assertThat(resolvedResultType("getArrayOfT", PassesThrough.class)).isEqualTo("U[]");
        assertThat(resolvedResultType("getArrayOfT", BoundToWildcardedType.class))
                .isEqualTo("java.util.List<? extends java.lang.Number>[]");
        assertThat(resolvedResultType("getArrayOfT", BoundToUnboundedWildcardedType.class))
                .isEqualTo("java.util.List<?>[]");
        assertThat(resolvedResultType("getArrayOfT", BoundToLowerBoundedWildcardedType.class))
                .isEqualTo("java.util.List<? super java.lang.Integer>[]");
        assertThat(resolvedResultType("getArrayOfT", BoundToInnerOfParameterizedOuter.class))
                .isEqualTo(Outer.class.getName() + "<java.lang.Short>$Inner[]");
    }

    /**
     * The type signature string of a rebuilt array type is the array type it describes, so that two arrays of the
     * same resolved element type are equal.
     */
    @Test
    void aRebuiltArrayTypeSignatureDescribesTheResolvedType() {
        final var resolved = scanResult.getClassInfo(Generic.class.getName()).getMethodInfo("getArrayOfT").get(0)
                .getTypeSignatureOrTypeDescriptor().getResultType()
                .resolveTypeVariables(scanResult.getClassInfo(BoundToString.class.getName()));
        assertThat(((ArrayTypeSignature) resolved).getTypeSignatureString()).isEqualTo("[Ljava/lang/String;");

        // The array type of Pair#getGrid(), resolved to String[][], describes the same type as Generic#getArrayOfT()
        // resolved to String[], with one more dimension
        final var grid = scanResult.getClassInfo(Pair.class.getName()).getMethodInfo("getGrid").get(0)
                .getTypeSignatureOrTypeDescriptor().getResultType()
                .resolveTypeVariables(scanResult.getClassInfo(PairBound.class.getName()));
        assertThat(((ArrayTypeSignature) grid).getTypeSignatureString()).isEqualTo("[[Ljava/lang/String;");
        assertThat(((ArrayTypeSignature) grid).getNestedType()).isEqualTo(resolved);
    }

    /** A binding that has to be composed across two levels of the hierarchy. */
    @Test
    void typeVariableIsResolvedThroughAnIntermediateClass() {
        assertThat(resolvedResultType("getT", BoundToIntegerIndirectly.class)).isEqualTo("java.lang.Integer");
        assertThat(resolvedResultType("getListOfT", BoundToIntegerIndirectly.class))
                .isEqualTo("java.util.List<java.lang.Integer>");
    }

    /**
     * Resolving against the intermediate class itself gives that class' own type variable.
     */
    @Test
    void typeVariableIsResolvedToAnotherTypeVariable() {
        assertThat(resolvedResultType("getT", PassesThrough.class)).isEqualTo("U");
    }

    /** A type argument that contains a wildcard is substituted verbatim. */
    @Test
    void typeArgumentContainingAWildcardIsSubstituted() {
        assertThat(resolvedResultType("getT", BoundToWildcardedType.class))
                .isEqualTo("java.util.List<? extends java.lang.Number>");
    }

    /**
     * A method's own type parameter shadows the interface's type parameter of the same name, so is not bound.
     */
    @Test
    void methodTypeParameterShadowsClassTypeParameter() {
        assertThat(resolvedResultType("identity", BoundToString.class)).isEqualTo("T");
    }

    /** A supertype used in raw form supplies no type argument. */
    @Test
    void rawSupertypeLeavesTypeVariableUnresolved() {
        assertThat(resolvedResultType("getT", BoundRaw.class)).isEqualTo("T");
    }

    /**
     * A context class that is not a subtype of the declaring class supplies no type argument.
     */
    @Test
    void unrelatedContextClassLeavesTypeVariableUnresolved() {
        assertThat(resolvedResultType("getT", FieldBoundToLong.class)).isEqualTo("T");
    }

    /**
     * A resolved type signature is fully wired up to the {@link ScanResult}, so a substituted node that was built
     * during resolution can still resolve its own {@link ClassInfo}.
     */
    @Test
    void resolvedTypeSignatureHasScanResult() {
        final var resolved = scanResult.getClassInfo(Generic.class.getName()).getMethodInfo("getListOfT").get(0)
                .getTypeSignatureOrTypeDescriptor().getResultType()
                .resolveTypeVariables(scanResult.getClassInfo(BoundToScannedClass.class.getName()));
        assertThat(resolved.toString()).isEqualTo("java.util.List<" + Derived1.class.getName() + ">");
        final var typeArgument = (ClassRefTypeSignature) ((ClassRefTypeSignature) resolved).getTypeArguments()
                .get(0).getTypeSignature();
        assertThat(typeArgument.getClassInfo()).isNotNull();
        assertThat(typeArgument.getClassInfo().getName()).isEqualTo(Derived1.class.getName());
    }

    /** Method parameter types are resolved by the same method. */
    @Test
    void methodParameterTypeIsResolved() {
        final var setT = scanResult.getClassInfo(Generic.class.getName()).getMethodInfo("setT").get(0);
        assertThat(setT.getParameterInfo().get(0).getTypeSignatureOrTypeDescriptor()
                .resolveTypeVariables(scanResult.getClassInfo(BoundToString.class.getName())).toString())
                .isEqualTo("java.lang.String");
    }

    /** Field types are resolved by the same method. */
    @Test
    void fieldTypeIsResolved() {
        final var fieldType = scanResult.getClassInfo(GenericField.class.getName()).getFieldInfo("field")
                .getTypeSignatureOrTypeDescriptor();
        assertThat(fieldType.toString()).isEqualTo("T");
        assertThat(fieldType.resolveTypeVariables(scanResult.getClassInfo(FieldBoundToLong.class.getName()))
                .toString()).isEqualTo("java.lang.Long");
    }

    /**
     * Get the declared result type of a method of {@link Pair}, resolved against a context class.
     *
     * @param methodName
     *            the name of the method of {@link Pair}.
     * @param contextClass
     *            the class to resolve type variables against.
     * @return the resolved result type, as a string.
     */
    private static String resolvedPairResultType(final String methodName, final Class<?> contextClass) {
        return scanResult.getClassInfo(Pair.class.getName()).getMethodInfo(methodName).get(0)
                .getTypeSignatureOrTypeDescriptor().getResultType()
                .resolveTypeVariables(scanResult.getClassInfo(contextClass.getName())).toString();
    }

    /**
     * Several type parameters are mapped to their type arguments by position, not by name.
     */
    @Test
    void severalTypeParametersAreMappedByPosition() {
        assertThat(resolvedPairResultType("getA", PairBound.class)).isEqualTo("java.lang.String");
        assertThat(resolvedPairResultType("getB", PairBound.class)).isEqualTo("java.lang.Integer");
    }

    /**
     * An intermediate class that swaps its type parameters must produce swapped bindings.
     */
    @Test
    void typeParametersSwappedByAnIntermediateClassAreComposedInTheRightOrder() {
        assertThat(resolvedPairResultType("getA", PairSwappedBound.class)).isEqualTo("java.lang.Integer");
        assertThat(resolvedPairResultType("getB", PairSwappedBound.class)).isEqualTo("java.lang.String");
    }

    /**
     * Type variables nested inside type arguments of type arguments are substituted.
     */
    @Test
    void typeVariablesAreSubstitutedAtEveryDepth() {
        assertThat(resolvedPairResultType("getNested", PairBound.class))
                .isEqualTo("java.util.Map<java.lang.String, java.util.List<java.lang.Integer>>");
        assertThat(resolvedPairResultType("getNested", PairSwappedBound.class))
                .isEqualTo("java.util.Map<java.lang.Integer, java.util.List<java.lang.String>>");
    }

    /**
     * A multi-dimensional array's element type is substituted, and the dimensionality is preserved.
     */
    @Test
    void multiDimensionalArrayElementTypeIsResolved() {
        assertThat(resolvedPairResultType("getGrid", PairBound.class)).isEqualTo("java.lang.String[][]");
    }

    /**
     * A type containing no type variables is returned unchanged, as the very same object.
     */
    @Test
    void typesWithoutTypeVariablesAreReturnedUnchanged() {
        final var pair = scanResult.getClassInfo(Pair.class.getName());
        final var contextClass = scanResult.getClassInfo(PairBound.class.getName());
        for (final String methodName : new String[] { "getInt", "getString" }) {
            final var resultType = pair.getMethodInfo(methodName).get(0).getTypeSignatureOrTypeDescriptor()
                    .getResultType();
            assertThat(resultType.resolveTypeVariables(contextClass)).isSameAs(resultType);
        }
    }

    /**
     * Two type variables that share the name {@code T} but are declared by different classes must be resolved
     * independently of each other.
     */
    @Test
    void identicallyNamedTypeVariablesOfDifferentClassesDoNotCollide() {
        final var contextClass = scanResult.getClassInfo(BoundOnBothBranches.class.getName());
        assertThat(scanResult.getClassInfo(Generic.class.getName()).getMethodInfo("getT").get(0)
                .getTypeSignatureOrTypeDescriptor().getResultType().resolveTypeVariables(contextClass).toString())
                .isEqualTo("java.lang.String");
        assertThat(scanResult.getClassInfo(GenericField.class.getName()).getFieldInfo("field")
                .getTypeSignatureOrTypeDescriptor().resolveTypeVariables(contextClass).toString())
                .isEqualTo("java.lang.Long");
    }

    /** A self-referential type parameter resolves without recursing forever. */
    @Test
    void selfReferentialTypeParameterIsResolved() {
        assertThat(scanResult.getClassInfo(Recursive.class.getName()).getMethodInfo("self").get(0)
                .getTypeSignatureOrTypeDescriptor().getResultType()
                .resolveTypeVariables(scanResult.getClassInfo(RecursiveBound.class.getName())).toString())
                .isEqualTo(RecursiveBound.class.getName());
    }

    /**
     * A type variable that an inner class inherits from its enclosing class is left unresolved: the classfile
     * records it as declared by the inner class itself, and the inner class is not on the supertype chain of a
     * subclass of the enclosing class, so no type argument is ever supplied for it.
     */
    @Test
    void typeVariableOfEnclosingClassIsNotResolved() {
        final var resultType = scanResult.getClassInfo(Outer.Inner.class.getName()).getMethodInfo("getO").get(0)
                .getTypeSignatureOrTypeDescriptor().getResultType();
        assertThat(resultType.toString()).isEqualTo("O");
        assertThat(resultType.resolveTypeVariables(scanResult.getClassInfo(OuterBound.class.getName())).toString())
                .isEqualTo("O");
    }

    /**
     * Resolving against the declaring class itself leaves its own type variables unbound.
     */
    @Test
    void declaringClassAsContextLeavesTypeVariablesUnresolved() {
        assertThat(resolvedResultType("getT", Generic.class)).isEqualTo("T");
        assertThat(resolvedResultType("getListOfT", Generic.class)).isEqualTo("java.util.List<T>");
    }

    /**
     * Resolution is repeatable: it must not mutate the type signature it is called on.
     */
    @Test
    void resolutionDoesNotMutateTheOriginalTypeSignature() {
        final var resultType = scanResult.getClassInfo(Generic.class.getName()).getMethodInfo("getListOfT").get(0)
                .getTypeSignatureOrTypeDescriptor().getResultType();
        final var contextClass = scanResult.getClassInfo(BoundToString.class.getName());
        assertThat(resultType.resolveTypeVariables(contextClass).toString())
                .isEqualTo("java.util.List<java.lang.String>");
        assertThat(resultType.toString()).isEqualTo("java.util.List<T>");
        assertThat(resultType
                .resolveTypeVariables(scanResult.getClassInfo(BoundToIntegerIndirectly.class.getName())).toString())
                .isEqualTo("java.util.List<java.lang.Integer>");
        assertThat(resultType.toString()).isEqualTo("java.util.List<T>");
    }

    /** A null context class is rejected. */
    @Test
    void nullContextClassIsRejected() {
        final var resultType = scanResult.getClassInfo(Generic.class.getName()).getMethodInfo("getT").get(0)
                .getTypeSignatureOrTypeDescriptor().getResultType();
        assertThatThrownBy(() -> resultType.resolveTypeVariables(null)).isInstanceOf(NullPointerException.class)
                .hasMessage("contextClass must not be null");
    }
}
