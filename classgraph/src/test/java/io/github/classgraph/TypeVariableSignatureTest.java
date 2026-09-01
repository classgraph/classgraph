package io.github.classgraph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Tests for {@link TypeVariableSignature}, the reference to a type variable such as the {@code T} in {@code T[]}.
 */
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

        /** A field of the type that every type can be reconciled with. */
        public Object objectField;

        /** A field of the class that bounds {@code L}, but with a different type argument. */
        public List<Integer> integerListField;

        /**
         * A method that declares its own type variable, to check that it is resolved against the method rather than
         * against the class.
         *
         * @param <M>
         *            a type variable declared by the method.
         * @param param
         *            a parameter of the method's own type variable's type.
         * @return the parameter.
         */
        public <M extends ClassBound> M methodScoped(final M param) {
            return param;
        }
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
        return new ClassGraph().enableClasspath().acceptClasses(GENERIC).enableClassInfo().enableFieldInfo()
                .enableMethodInfo().enableAnnotationInfo().enableStaticFinalFieldConstantInitializerValues()
                .ignoreClassVisibility().ignoreFieldVisibility().ignoreMethodVisibility().scan();
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

    /** A type variable knows its own name, and the name of the class that it is used in. */
    @Test
    public void aTypeVariableKnowsItsNameAndTheClassItIsUsedIn() {
        try (var scanResult = scan()) {
            final var typeVariable = typeVariable(scanResult, "classBounded");
            assertThat(typeVariable.getName()).isEqualTo("T");
            assertThat(typeVariable.toString()).isEqualTo("T");
            assertThat(typeVariable.getClassName()).isEqualTo(GENERIC);
            assertThat(typeVariable.getClassInfo().getName()).isEqualTo(GENERIC);
        }
    }

    /** A type variable resolves to the type parameter of the same name declared by the enclosing class. */
    @Test
    public void aTypeVariableResolvesToTheTypeParameterOfItsClass() {
        try (var scanResult = scan()) {
            assertThat(typeVariable(scanResult, "classBounded").resolve().toString())
                    .isEqualTo("T extends " + ClassBound.class.getName());
            assertThat(typeVariable(scanResult, "interfaceBounded").resolve().toString())
                    .isEqualTo("U extends " + InterfaceBound.class.getName());
            assertThat(typeVariable(scanResult, "unbounded").resolve().toString()).isEqualTo("V");
            assertThat(typeVariable(scanResult, "variableBounded").resolve().toString()).isEqualTo("W extends T");
            assertThat(typeVariable(scanResult, "classBounded").toStringWithTypeBound())
                    .isEqualTo("T extends " + ClassBound.class.getName());
        }
    }

    /**
     * A type variable that a class type signature uses within itself -- in the bound of one of the class' own type
     * parameters, or as a type argument of the superclass -- resolves against the class that declares it.
     */
    @Test
    public void aTypeVariableUsedWithinAClassTypeSignatureResolvesAgainstThatClass() {
        try (var scanResult = new ClassGraph().enableClasspath().acceptClasses(GENERIC, Sub.class.getName())
                .enableClassInfo().enableFieldInfo().enableMethodInfo().enableAnnotationInfo()
                .enableStaticFinalFieldConstantInitializerValues().ignoreClassVisibility().ignoreFieldVisibility()
                .ignoreMethodVisibility().scan()) {
            // The bound of "W extends T" is the type variable T, which is declared by the same class
            final var typeParameters = scanResult.getClassInfo(GENERIC).getTypeSignature().getTypeParameters();
            final var bound = (TypeVariableSignature) typeParameters.get(3).getClassBound();
            assertThat(bound.getName()).isEqualTo("T");
            assertThat(bound.getClassName()).isEqualTo(GENERIC);
            assertThat(bound.resolve().toString()).isEqualTo("T extends " + ClassBound.class.getName());

            // The type argument of the superclass of "class Sub<S extends ClassBound>" is the type variable S
            final var superclassTypeArgument = (TypeVariableSignature) scanResult.getClassInfo(Sub.class.getName())
                    .getTypeSignature().getSuperclassSignature().getTypeArguments().get(0).getTypeSignature();
            assertThat(superclassTypeArgument.getName()).isEqualTo("S");
            assertThat(superclassTypeArgument.resolve().toString())
                    .isEqualTo("S extends " + ClassBound.class.getName());
        }
    }

    /** A type variable declared by a method resolves to the method's own type parameter. */
    @Test
    public void aTypeVariableResolvesToTheTypeParameterOfItsMethod() {
        try (var scanResult = scan()) {
            final var method = scanResult.getClassInfo(GENERIC).getMethodInfo("methodScoped").get(0);
            final var returnType = (TypeVariableSignature) method.getTypeSignatureOrTypeDescriptor()
                    .getResultType();
            assertThat(returnType.getName()).isEqualTo("M");
            assertThat(returnType.resolve().toString()).isEqualTo("M extends " + ClassBound.class.getName());
        }
    }

    /** A type variable that is used outside any class resolves to an unbounded type parameter of its own name. */
    // #706
    @Test
    public void aTypeVariableWithNoEnclosingClassResolvesToAnUnboundedTypeParameter()
            throws TypeSignatureParseException {
        final var typeVariable = TypeVariableSignature.parse(new TypeSignatureParser("TQ;"),
                /* definingClassName = */ null);
        assertThat(typeVariable.getName()).isEqualTo("Q");
        assertThat(typeVariable.resolve().toString()).isEqualTo("Q");
        assertThat(typeVariable.toStringWithTypeBound()).isEqualTo("Q");
    }

    /** A type variable of a class that was not scanned cannot be resolved. */
    @Test
    public void aTypeVariableOfAClassThatWasNotScannedCannotBeResolved() throws TypeSignatureParseException {
        final var typeVariable = TypeVariableSignature.parse(new TypeSignatureParser("TT;"),
                "com.example.NotScanned");
        assertThatThrownBy(typeVariable::resolve).isInstanceOf(IllegalStateException.class)
                .hasMessage("Could not find ClassInfo object for com.example.NotScanned");
        // The unresolvable bound is left out of the string representation, rather than the call throwing
        assertThat(typeVariable.toStringWithTypeBound()).isEqualTo("T");
    }

    /** Parsing reads one type variable, and records it in the parser state so that signatures can link to it. */
    @Test
    public void parsingReadsOneTypeVariableAndRecordsItInTheParserState() throws TypeSignatureParseException {
        final var parser = new TypeSignatureParser("TT;TU;");
        final var first = TypeVariableSignature.parse(parser, GENERIC);
        final var second = TypeVariableSignature.parse(parser, GENERIC);
        assertThat(first.getName()).isEqualTo("T");
        assertThat(second.getName()).isEqualTo("U");
        assertThat(new ArrayList<Object>((List<?>) parser.getState())).containsExactly(first, second);

        // Anything that is not a type variable is left for the caller to parse
        final var classTypeParser = new TypeSignatureParser("Ljava/lang/String;");
        assertThat(TypeVariableSignature.parse(classTypeParser, GENERIC)).isNull();
        assertThat(classTypeParser.getPosition()).isZero();
    }

    /** A type variable with no name, or with no terminating semicolon, is a parse error. */
    @Test
    public void aMalformedTypeVariableIsAParseError() {
        assertThatThrownBy(() -> TypeVariableSignature.parse(new TypeSignatureParser("T;"), GENERIC))
                .isInstanceOf(TypeSignatureParseException.class)
                .hasMessageContaining("Could not parse type variable signature");
        assertThatThrownBy(() -> TypeVariableSignature.parse(new TypeSignatureParser("TT"), GENERIC))
                .isInstanceOf(TypeSignatureParseException.class);
    }

    /** Two type variables are equal if they have the same name and the same type annotations. */
    @Test
    public void equalityIsByNameAndTypeAnnotations() throws TypeSignatureParseException {
        final var typeVariable = TypeVariableSignature.parse(new TypeSignatureParser("TT;"), GENERIC);
        final var sameName = TypeVariableSignature.parse(new TypeSignatureParser("TT;"),
                "com.example.SomeOtherClass");
        final var differentName = TypeVariableSignature.parse(new TypeSignatureParser("TU;"), GENERIC);

        // The defining class is not part of the identity of a type variable
        assertThat(typeVariable).isEqualTo(typeVariable).isEqualTo(sameName).isNotEqualTo(differentName)
                .isNotEqualTo("T");
        assertThat(typeVariable).hasSameHashCodeAs(sameName);
    }

    /** Every type variable can be reconciled with {@link Object}, whatever its bounds. */
    @Test
    public void everyTypeVariableCanBeReconciledWithObject() {
        try (var scanResult = scan()) {
            final var objectType = fieldType(scanResult, "objectField");
            assertThat(typeVariable(scanResult, "classBounded").equalsIgnoringTypeParams(objectType)).isTrue();
            assertThat(typeVariable(scanResult, "interfaceBounded").equalsIgnoringTypeParams(objectType)).isTrue();
            assertThat(typeVariable(scanResult, "unbounded").equalsIgnoringTypeParams(objectType)).isTrue();
        }
    }

    /** A type variable with no bound of its own can be reconciled with any class. */
    @Test
    public void anUnboundedTypeVariableCanBeReconciledWithAnyClass() {
        try (var scanResult = scan()) {
            // An unbounded type variable is written into the classfile with java.lang.Object as its bound
            assertThat(typeVariable(scanResult, "unbounded").resolve().getClassBound().toString())
                    .isEqualTo("java.lang.Object");
            assertThat(typeVariable(scanResult, "unbounded")
                    .equalsIgnoringTypeParams(fieldType(scanResult, "stringField"))).isTrue();
        }
    }

    /** A type variable can be reconciled with its class or interface bound, but not with an unrelated class. */
    @Test
    public void aTypeVariableCanBeReconciledWithItsClassOrInterfaceBound() {
        try (var scanResult = scan()) {
            final var classBoundType = fieldType(scanResult, "classBoundField");
            final var interfaceBoundType = fieldType(scanResult, "interfaceBoundField");
            final var stringType = fieldType(scanResult, "stringField");

            assertThat(typeVariable(scanResult, "classBounded").equalsIgnoringTypeParams(classBoundType)).isTrue();
            assertThat(typeVariable(scanResult, "classBounded").equalsIgnoringTypeParams(stringType)).isFalse();
            assertThat(typeVariable(scanResult, "interfaceBounded").equalsIgnoringTypeParams(interfaceBoundType))
                    .isTrue();
            assertThat(typeVariable(scanResult, "interfaceBounded").equalsIgnoringTypeParams(stringType)).isFalse();

            // Comparing a class reference with a type variable gives the same answer either way round
            assertThat(classBoundType.equalsIgnoringTypeParams(typeVariable(scanResult, "classBounded"))).isTrue();
            assertThat(stringType.equalsIgnoringTypeParams(typeVariable(scanResult, "classBounded"))).isFalse();
        }
    }

    /** A type variable bounded by another type variable can be reconciled with that type variable's own bound. */
    @Test
    public void aTypeVariableBoundedByATypeVariableCanBeReconciledWithThatVariablesBound() {
        try (var scanResult = scan()) {
            assertThat(typeVariable(scanResult, "variableBounded")
                    .equalsIgnoringTypeParams(fieldType(scanResult, "classBoundField"))).isTrue();
            assertThat(typeVariable(scanResult, "variableBounded")
                    .equalsIgnoringTypeParams(fieldType(scanResult, "stringField"))).isFalse();
        }
    }

    /** The type arguments of the bound are ignored when a type variable is reconciled with a class. */
    @Test
    public void theTypeArgumentsOfTheBoundAreIgnored() {
        try (var scanResult = scan()) {
            // The bound is List<String>, and the class it is compared with is List<Integer>
            assertThat(typeVariable(scanResult, "parameterizedBounded")
                    .equalsIgnoringTypeParams(fieldType(scanResult, "integerListField"))).isTrue();
        }
    }

    /** A type variable can be reconciled with a type variable that is written the same way. */
    @Test
    public void aTypeVariableCanBeReconciledWithTheSameTypeVariable() {
        try (var scanResult = scan()) {
            final var classBounded = typeVariable(scanResult, "classBounded");
            assertThat(classBounded.equalsIgnoringTypeParams(classBounded)).isTrue();
            assertThat(classBounded.equalsIgnoringTypeParams(typeVariable(scanResult, "interfaceBounded")))
                    .isFalse();
            assertThat(classBounded.equalsIgnoringTypeParams(null)).isFalse();
        }
    }

    /** A type variable that could not be resolved can be reconciled with any class. */
    @Test
    public void anUnresolvableTypeVariableCanBeReconciledWithAnyClass() throws TypeSignatureParseException {
        try (var scanResult = scan()) {
            final var typeVariable = TypeVariableSignature.parse(new TypeSignatureParser("TT;"),
                    "com.example.NotScanned");
            typeVariable.setScanResult(scanResult);
            assertThat(typeVariable.equalsIgnoringTypeParams(fieldType(scanResult, "stringField"))).isTrue();
        }
    }

    /** A type variable has no internal structure, so a type annotation on it must have an empty type path. */
    @Test
    public void aTypeAnnotationOnATypeVariableMustHaveAnEmptyTypePath() {
        try (var scanResult = scan()) {
            final var typeVariable = typeVariable(scanResult, "classBounded");
            final var typePath = List.of(new Classfile.TypePathNode((short) 0, (short) 0));
            assertThatThrownBy(() -> typeVariable.addTypeAnnotation(typePath, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Type variable should have empty typePath");
        }
    }

    /** Any classes referenced by a type variable are already referenced by its enclosing method or class. */
    @Test
    public void aTypeVariableAddsNoReferencedClassNames() {
        try (var scanResult = scan()) {
            final var refdClassNames = new HashSet<String>();
            typeVariable(scanResult, "classBounded").findReferencedClassNames(refdClassNames);
            assertThat(refdClassNames).isEmpty();
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
