package io.github.classgraph.issues.issue735;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import io.github.classgraph.ArrayTypeSignature;
import io.github.classgraph.ClassGraph;
import io.github.classgraph.ClassInfo;
import io.github.classgraph.ClassRefTypeSignature;
import io.github.classgraph.ReferenceTypeSignature;
import io.github.classgraph.ScanResult;
import io.github.classgraph.TypeSignature;

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
        try (var scanResult = new ClassGraph().enableClasspath()
                .acceptPackages(Issue735Test.class.getPackage().getName()).enableClassInfo().enableFieldInfo()
                .enableMethodInfo().enableAnnotationInfo().enableStaticFinalFieldConstantInitializerValues()
                .ignoreClassVisibility().ignoreFieldVisibility().ignoreMethodVisibility().ignoreClassVisibility()
                .ignoreMethodVisibility().scan()) {
            final var ci1 = scanResult.getClassInfo(Derived1.class.getName());
            // Derived1 also has a compiler-generated bridge method get() with result type Object, which sorts
            // before the declared method, since methods of the same name are sorted by type descriptor
            final var get1 = ci1.getMethodInfo("get").stream().filter(mi -> !mi.isBridge()).findFirst()
                    .orElseThrow();
            assertThat(get1.getTypeSignatureOrTypeDescriptor().getResultType().toString())
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

    /**
     * Extends PassesThrough in raw form, so has no generic signature, and supplies no type argument for Generic's
     * T, even though PassesThrough itself does.
     */
    @SuppressWarnings("rawtypes")
    abstract static class RawThroughPassesThrough extends PassesThrough {
    }

    /** Like {@link RawThroughPassesThrough}, but has a generic signature of its own. */
    @SuppressWarnings("rawtypes")
    abstract static class GenericRawThroughPassesThrough<X> extends PassesThrough {
        X field;
    }

    /** A generic class that binds Generic's T to String, regardless of its own type parameter. */
    abstract static class GenericBoundToString<U> implements Generic<String> {
        U field;
    }

    /** Extends GenericBoundToString in raw form, so the binding of Generic's T to String is erased. */
    @SuppressWarnings("rawtypes")
    abstract static class RawThroughGenericBoundToString extends GenericBoundToString {
    }

    /** A non-generic interface that binds Generic's T to String. */
    interface InterfaceBoundToString extends Generic<String> {
    }

    /** A generic class that implements a non-generic interface binding Generic's T to String. */
    abstract static class GenericImplementingInterfaceBoundToString<U> implements InterfaceBoundToString {
        U field;
    }

    /**
     * Extends GenericImplementingInterfaceBoundToString in raw form, so the binding of Generic's T to String is
     * erased, even though it is made by a non-generic interface.
     */
    @SuppressWarnings("rawtypes")
    abstract static class RawThroughGenericImplementingInterfaceBoundToString
            extends GenericImplementingInterfaceBoundToString {
    }

    /** Extends a non-generic class, so has no generic signature, but inherits that class' binding. */
    abstract static class NonGenericSubclassOfBoundToString extends BoundToString {
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

    /** Uses its type parameter as the element type of arrays. */
    interface ArrayElement<E> {
        E[] getArray();

        E[][] getGrid();

        List<E[]> getListOfArrays();
    }

    /** Binds ArrayElement's E to an array type. */
    abstract static class ArrayElementBoundToStringArray implements ArrayElement<String[]> {
    }

    /** Binds ArrayElement's E to an array of a primitive type. */
    abstract static class ArrayElementBoundToIntArray implements ArrayElement<int[]> {
    }

    /** Binds ArrayElement's E to an array of its own type parameter. */
    abstract static class ArrayElementBoundToArrayOfTypeParameter<V> implements ArrayElement<V[]> {
    }

    /**
     * Binds ArrayElementBoundToArrayOfTypeParameter's V to String, which must compose into a binding of
     * ArrayElement's E to String[].
     */
    abstract static class ArrayElementBoundToStringArrayIndirectly
            extends ArrayElementBoundToArrayOfTypeParameter<String> {
    }

    /** A type annotation. */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.TYPE_USE)
    @interface TA {
    }

    /** Has fields whose types carry a type annotation in each position that substitution rebuilds. */
    static class Annotated<T> {
        @TA
        List<T> annotatedParameterizedType;

        List<@TA T> annotatedTypeVariableInTypeArgument;

        @TA
        T annotatedTypeVariable;

        T @TA [] annotatedArrayDimension;

        @TA
        T[] annotatedArrayElement;

        Map.@TA Entry<T, String> annotatedNestedClass;

        List<@TA ? extends T> annotatedWildcard;
    }

    /** Binds Annotated's T to String. */
    static class AnnotatedBoundToString extends Annotated<String> {
    }

    /** Binds Annotated's T to an array type. */
    static class AnnotatedBoundToStringArray extends Annotated<String[]> {
    }

    /** Binds Annotated's T to a nested class. */
    static class AnnotatedBoundToNestedClass extends Annotated<Map.Entry<String, Integer>> {
    }

    /** Binds Annotated's T to its own type parameter. */
    static class AnnotatedBoundToTypeParameter<W> extends Annotated<W> {
    }

    /** The scan result, shared by the resolution tests. */
    private static ScanResult scanResult;

    @BeforeAll
    static void scan() {
        scanResult = new ClassGraph().enableClasspath().acceptPackages(Issue735Test.class.getPackage().getName())
                .enableClassInfo().enableFieldInfo().enableMethodInfo().enableAnnotationInfo()
                .enableStaticFinalFieldConstantInitializerValues().ignoreClassVisibility().ignoreFieldVisibility()
                .ignoreMethodVisibility().ignoreClassVisibility().ignoreMethodVisibility().ignoreFieldVisibility()
                .scan();
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

        // A nested class is separated from its enclosing class by '$', as in the class name, unless an enclosing
        // class has type arguments, in which case the separator is '.', exactly as javac writes it
        final var inner = scanResult.getClassInfo(Generic.class.getName()).getMethodInfo("getArrayOfT").get(0)
                .getTypeSignatureOrTypeDescriptor().getResultType()
                .resolveTypeVariables(scanResult.getClassInfo(BoundToInnerOfParameterizedOuter.class.getName()));
        assertThat(((ArrayTypeSignature) inner).getTypeSignatureString())
                .isEqualTo("[L" + Outer.class.getName().replace('.', '/') + "<Ljava/lang/Short;>.Inner;");
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
     * A generic supertype used in raw form erases every binding above it (JLS 4.8), so a type variable bound above
     * it is left unresolved, rather than resolved to the raw supertype's own type variable or to a binding that the
     * compiler erases.
     */
    @Test
    void rawSupertypeAboveTheContextClassLeavesTypeVariableUnresolved() {
        assertThat(List.of(resolvedResultType("getT", RawThroughPassesThrough.class),
                resolvedResultType("getT", GenericRawThroughPassesThrough.class),
                resolvedResultType("getT", RawThroughGenericBoundToString.class),
                resolvedResultType("getT", RawThroughGenericImplementingInterfaceBoundToString.class)))
                .containsExactly("T", "T", "T", "T");
        // Resolved against the generic classes themselves, their bindings still apply
        assertThat(resolvedResultType("getT", GenericBoundToString.class)).isEqualTo("java.lang.String");
        assertThat(resolvedResultType("getT", GenericImplementingInterfaceBoundToString.class))
                .isEqualTo("java.lang.String");
    }

    /** A non-generic superclass is not a raw type, so the walk continues through it. */
    @Test
    void nonGenericSuperclassPassesBindingsThrough() {
        assertThat(resolvedResultType("getT", NonGenericSubclassOfBoundToString.class))
                .isEqualTo("java.lang.String");
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

    /**
     * Get the declared result type of a method of {@link ArrayElement}, resolved against a context class.
     *
     * @param methodName
     *            the name of the method of {@link ArrayElement}.
     * @param contextClass
     *            the class to resolve type variables against.
     * @return the resolved result type.
     */
    private static TypeSignature resolvedArrayElementResultType(final String methodName,
            final Class<?> contextClass) {
        return scanResult.getClassInfo(ArrayElement.class.getName()).getMethodInfo(methodName).get(0)
                .getTypeSignatureOrTypeDescriptor().getResultType()
                .resolveTypeVariables(scanResult.getClassInfo(contextClass.getName()));
    }

    /**
     * An array type substituted for the element type of an array type adds its dimensions to those of the array
     * type, so {@code E[]} with {@code E := String[]} is {@code String[][]}, an array type whose element type is
     * {@code String}.
     */
    @Test
    void arrayTypeSubstitutedForArrayElementTypeAddsItsDimensions() {
        final var array = (ArrayTypeSignature) resolvedArrayElementResultType("getArray",
                ArrayElementBoundToStringArray.class);
        assertThat(array.toString()).isEqualTo("java.lang.String[][]");
        assertThat(array.getNumDimensions()).isEqualTo(2);
        assertThat(array.getElementTypeSignature().toString()).isEqualTo("java.lang.String");
        assertThat(array.getTypeSignatureString()).isEqualTo("[[Ljava/lang/String;");
        assertThat(array.getArrayClassInfo().getName()).isEqualTo("java.lang.String[][]");

        assertThat(resolvedArrayElementResultType("getGrid", ArrayElementBoundToIntArray.class).toString())
                .isEqualTo("int[][][]");
        assertThat(
                resolvedArrayElementResultType("getListOfArrays", ArrayElementBoundToStringArray.class).toString())
                .isEqualTo("java.util.List<java.lang.String[][]>");
        assertThat(resolvedArrayElementResultType("getArray", ArrayElementBoundToStringArrayIndirectly.class)
                .toString()).isEqualTo("java.lang.String[][]");
    }

    /** The rendering of {@link TA} in a type signature. */
    private static final String AT_TA = "@" + TA.class.getName();

    /**
     * Get the type of a field of {@link Annotated}.
     *
     * @param fieldName
     *            the name of the field.
     * @return the type of the field.
     */
    private static TypeSignature annotatedFieldType(final String fieldName) {
        return scanResult.getClassInfo(Annotated.class.getName()).getFieldInfo(fieldName)
                .getTypeSignatureOrTypeDescriptor();
    }

    /**
     * Get the type of a field of {@link Annotated}, resolved against a context class.
     *
     * @param fieldName
     *            the name of the field.
     * @param contextClass
     *            the class to resolve type variables against.
     * @return the resolved type of the field.
     */
    private static TypeSignature resolvedAnnotatedFieldType(final String fieldName, final Class<?> contextClass) {
        return annotatedFieldType(fieldName).resolveTypeVariables(scanResult.getClassInfo(contextClass.getName()));
    }

    /**
     * Get the type argument that a subclass of {@link Annotated} supplies for its type parameter.
     *
     * @param contextClass
     *            the subclass.
     * @return the type argument's type signature.
     */
    private static ReferenceTypeSignature superclassTypeArgument(final Class<?> contextClass) {
        // Annotated is a nested class, so its type arguments are those of the last suffix
        final var suffixTypeArguments = scanResult.getClassInfo(contextClass.getName()).getTypeSignature()
                .getSuperclassSignature().getSuffixTypeArguments();
        return suffixTypeArguments.get(suffixTypeArguments.size() - 1).get(0).getTypeSignature();
    }

    /** A type annotation on a type that substitution rebuilds stays where it was. */
    @Test
    void typeAnnotationsOnRebuiltTypesAreKept() {
        assertThat(annotatedFieldType("annotatedParameterizedType").toString())
                .isEqualTo(AT_TA + " java.util.List<T>");
        final var parameterizedType = (ClassRefTypeSignature) resolvedAnnotatedFieldType(
                "annotatedParameterizedType", AnnotatedBoundToString.class);
        assertThat(parameterizedType.toString()).isEqualTo(AT_TA + " java.util.List<java.lang.String>");
        assertThat(parameterizedType.getTypeAnnotationInfo().getNames()).containsExactly(TA.class.getName());

        assertThat(annotatedFieldType("annotatedArrayDimension").toString()).isEqualTo("T " + AT_TA + " []");
        final var arrayDimension = resolvedAnnotatedFieldType("annotatedArrayDimension",
                AnnotatedBoundToString.class);
        assertThat(arrayDimension.toString()).isEqualTo("java.lang.String " + AT_TA + " []");
        assertThat(arrayDimension.getTypeAnnotationInfo().getNames()).containsExactly(TA.class.getName());

        assertThat(annotatedFieldType("annotatedNestedClass").toString())
                .isEqualTo("java.util.Map$" + AT_TA + " Entry<T, java.lang.String>");
        final var nestedClass = (ClassRefTypeSignature) resolvedAnnotatedFieldType("annotatedNestedClass",
                AnnotatedBoundToString.class);
        assertThat(nestedClass.toString())
                .isEqualTo("java.util.Map$" + AT_TA + " Entry<java.lang.String, java.lang.String>");
        assertThat(nestedClass.getSuffixTypeAnnotationInfo().get(0).getNames()).containsExactly(TA.class.getName());

        assertThat(annotatedFieldType("annotatedWildcard").toString())
                .isEqualTo("java.util.List<" + AT_TA + " ? extends T>");
        final var wildcard = (ClassRefTypeSignature) resolvedAnnotatedFieldType("annotatedWildcard",
                AnnotatedBoundToString.class);
        assertThat(wildcard.toString()).isEqualTo("java.util.List<" + AT_TA + " ? extends java.lang.String>");
        assertThat(wildcard.getTypeArguments().get(0).getTypeAnnotationInfo().getNames())
                .containsExactly(TA.class.getName());
    }

    /**
     * A type annotation on a use of a type variable is placed on the type that replaces the type variable, without
     * changing the type that the context class binds the type variable to.
     */
    @Test
    void typeAnnotationsOnTypeVariablesArePlacedOnTheSubstitutedType() {
        assertThat(annotatedFieldType("annotatedTypeVariable").toString()).isEqualTo(AT_TA + " T");
        final var typeVariable = resolvedAnnotatedFieldType("annotatedTypeVariable", AnnotatedBoundToString.class);
        assertThat(typeVariable.toString()).isEqualTo(AT_TA + " java.lang.String");
        assertThat(typeVariable.getTypeAnnotationInfo().getNames()).containsExactly(TA.class.getName());

        assertThat(annotatedFieldType("annotatedTypeVariableInTypeArgument").toString())
                .isEqualTo("java.util.List<" + AT_TA + " T>");
        final var typeArgument = (ClassRefTypeSignature) resolvedAnnotatedFieldType(
                "annotatedTypeVariableInTypeArgument", AnnotatedBoundToString.class);
        assertThat(typeArgument.toString()).isEqualTo("java.util.List<" + AT_TA + " java.lang.String>");
        assertThat(typeArgument.getTypeArguments().get(0).getTypeSignature().getTypeAnnotationInfo().getNames())
                .containsExactly(TA.class.getName());

        assertThat(annotatedFieldType("annotatedArrayElement").toString()).isEqualTo(AT_TA + " T[]");
        assertThat(resolvedAnnotatedFieldType("annotatedArrayElement", AnnotatedBoundToString.class).toString())
                .isEqualTo(AT_TA + " java.lang.String[]");

        // A type variable can be replaced by another type variable
        assertThat(
                resolvedAnnotatedFieldType("annotatedTypeVariable", AnnotatedBoundToTypeParameter.class).toString())
                .isEqualTo(AT_TA + " W");
        assertThat(resolvedAnnotatedFieldType("annotatedTypeVariableInTypeArgument",
                AnnotatedBoundToTypeParameter.class).toString()).isEqualTo("java.util.List<" + AT_TA + " W>");

        // The type annotation applies to the type as a whole, which for a nested class is the innermost class
        final var nestedClass = (ClassRefTypeSignature) resolvedAnnotatedFieldType("annotatedTypeVariable",
                AnnotatedBoundToNestedClass.class);
        assertThat(nestedClass.toString())
                .isEqualTo("java.util.Map$" + AT_TA + " Entry<java.lang.String, java.lang.Integer>");
        assertThat(nestedClass.getTypeAnnotationInfo()).isEmpty();
        assertThat(nestedClass.getSuffixTypeAnnotationInfo().get(0).getNames()).containsExactly(TA.class.getName());

        // The type that the context class binds the type variable to is not changed
        final var binding = (ClassRefTypeSignature) superclassTypeArgument(AnnotatedBoundToString.class);
        assertThat(binding.toString()).isEqualTo("java.lang.String");
        assertThat(binding.getTypeAnnotationInfo()).isEmpty();
        final var nestedClassBinding = (ClassRefTypeSignature) superclassTypeArgument(
                AnnotatedBoundToNestedClass.class);
        assertThat(nestedClassBinding.getSuffixTypeAnnotationInfo().get(0)).isEmpty();
        // Nor is the type signature that was resolved
        assertThat(annotatedFieldType("annotatedTypeVariable").toString()).isEqualTo(AT_TA + " T");
    }

    /**
     * When an array type is substituted for the element type of an array type, the type annotations of the
     * dimensions of the array type are on the outer dimensions of the result, and those of the substituted array
     * type are on the inner dimensions.
     */
    @Test
    void typeAnnotationsOnMergedArrayDimensionsAreKept() {
        // T @TA [] with T := String[] is an array of String[] whose own (outer) dimension is annotated
        assertThat(
                resolvedAnnotatedFieldType("annotatedArrayDimension", AnnotatedBoundToStringArray.class).toString())
                .isEqualTo("java.lang.String " + AT_TA + " [][]");
        // @TA T[] with T := String[] is an array of String @TA [], so the inner dimension is annotated
        final var arrayElement = (ArrayTypeSignature) resolvedAnnotatedFieldType("annotatedArrayElement",
                AnnotatedBoundToStringArray.class);
        assertThat(arrayElement.toString()).isEqualTo("java.lang.String[] " + AT_TA + " []");
        assertThat(arrayElement.getTypeAnnotationInfo()).isEmpty();
        assertThat(arrayElement.getNestedType().getTypeAnnotationInfo().getNames())
                .containsExactly(TA.class.getName());
        // @TA T with T := String[] annotates the array type, giving String @TA []
        assertThat(
                resolvedAnnotatedFieldType("annotatedTypeVariable", AnnotatedBoundToStringArray.class).toString())
                .isEqualTo("java.lang.String " + AT_TA + " []");
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
