package io.github.classgraph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.io.IOException;
import java.time.DayOfWeek;
import java.util.List;
import java.util.Map;
import java.util.RandomAccess;

import org.junit.jupiter.api.Test;

/**
 * {@link MethodTypeSignature} is the generic type signature of a method: its type parameters, parameter types,
 * result type and thrown exception types. A method that has no {@code Signature} attribute gets a type descriptor
 * parsed from its parameter and return types instead.
 */
public class MethodTypeSignatureTest {
    /** Methods with type parameters, parameters, result types and throws clauses. */
    public static class Methods {
        /**
         * A generic method.
         *
         * @param <T>
         *            the type of the value.
         * @param value
         *            the value.
         * @param count
         *            the number of times to map the value.
         * @return the map.
         * @throws IOException
         *             never.
         */
        public <T extends Number & Comparable<T>> Map<String, T> generic(final T value, final int count)
                throws IOException {
            return Map.of(String.valueOf(count), value);
        }

        /**
         * A method that throws a type variable rather than a named exception type.
         *
         * @param <E>
         *            the type of the exception thrown.
         * @throws E
         *             never.
         */
        public <E extends Exception> void throwsATypeVariable() throws E {
            // Does not throw
        }

        /**
         * A method whose signature names classes that its type descriptor does not, since they appear only as type
         * arguments, which are erased from the descriptor.
         *
         * @param <U>
         *            the type of the values.
         * @param values
         *            the values.
         * @return the map.
         */
        public <U extends Comparable<U>> Map<DayOfWeek, U> typeArgumentsOnly(final List<RandomAccess> values) {
            return Map.of();
        }

        /**
         * A method that uses no type variables or parameterized types, so it has no {@code Signature} attribute.
         */
        public void plain() {
            // Does nothing
        }
    }

    /**
     * Scan the fixture class.
     *
     * @return the scan result.
     */
    private static ScanResult scanFixture() {
        return new ClassGraph().enableClassInfo().enableClasspath().acceptClasses(Methods.class.getName())
                .enableMethodInfo().scan();
    }

    /**
     * Get the {@link MethodInfo} for a fixture method.
     *
     * @param scanResult
     *            the scan result.
     * @param methodName
     *            the name of the method.
     * @return the {@link MethodInfo}.
     */
    private static MethodInfo methodInfo(final ScanResult scanResult, final String methodName) {
        final var classInfo = scanResult.getClassInfo(Methods.class.getName());
        assertThat(classInfo).as("ClassInfo for " + Methods.class.getName()).isNotNull();
        return classInfo.getMethodInfo(methodName).getSingleMethod(methodName);
    }

    /**
     * Get the type signature of a fixture method.
     *
     * @param scanResult
     *            the scan result.
     * @param methodName
     *            the name of the method.
     * @return the type signature.
     */
    private static MethodTypeSignature typeSignature(final ScanResult scanResult, final String methodName) {
        final var typeSignature = methodInfo(scanResult, methodName).getTypeSignature();
        assertThat(typeSignature).as("Type signature of " + methodName).isNotNull();
        return typeSignature;
    }

    /** Every part of a generic method's declaration is read from its signature. */
    @Test
    public void theTypeParametersParameterTypesResultTypeAndThrowsTypesAreRead() {
        try (var scanResult = scanFixture()) {
            final var typeSig = typeSignature(scanResult, "generic");

            assertThat(typeSig.getTypeParameters()).extracting(Object::toString)
                    .containsExactly("T extends java.lang.Number & java.lang.Comparable<T>");
            assertThat(typeSig.getParameterTypeSignatures()).extracting(Object::toString).containsExactly("T",
                    "int");
            assertThat(typeSig.getResultType()).hasToString("java.util.Map<java.lang.String, T>");
            // A method signature only lists the exceptions it throws if one of them is a type variable or a
            // parameterized type; the rest are listed in the method's Exceptions attribute instead
            assertThat(typeSig.getThrowsSignatures()).isEmpty();
            assertThat(methodInfo(scanResult, "generic").getThrownExceptionNames())
                    .containsExactly(IOException.class.getName());

            // Only a method with an explicit receiver parameter can have annotations on that parameter
            assertThat(typeSig.getReceiverTypeAnnotationInfo()).isNull();
        }
    }

    /** A method type signature renders as the method declaration it was compiled from, without the method name. */
    @Test
    public void aMethodTypeSignatureRendersAsAMethodDeclaration() {
        try (var scanResult = scanFixture()) {
            final var typeSig = typeSignature(scanResult, "generic");
            assertThat(typeSig).hasToString("<T extends java.lang.Number & java.lang.Comparable<T>> "
                    + "java.util.Map<java.lang.String, T> (T, int)");
            assertThat(typeSig.toStringWithSimpleNames())
                    .isEqualTo("<T extends Number & Comparable<T>> Map<String, T> (T, int)");

            // A throws clause is rendered when the signature has one
            assertThat(typeSignature(scanResult, "throwsATypeVariable").toStringWithSimpleNames())
                    .isEqualTo("<E extends Exception> void () throws E");

            // A method with no type parameters and no throws clause renders as just its result and parameter types
            assertThat(methodInfo(scanResult, "plain").getTypeDescriptor()).hasToString("void ()");
        }
    }

    /**
     * A type variable used in a method's signature is linked back to the signature it appears in, so that it
     * resolves to the type parameter the method declares, rather than to a type parameter of the enclosing class or
     * to nothing.
     */
    @Test
    public void aTypeVariableResolvesToTheTypeParameterTheMethodDeclares() {
        try (var scanResult = scanFixture()) {
            final var parameterType = typeSignature(scanResult, "generic").getParameterTypeSignatures().get(0);
            assertThat(parameterType).isInstanceOf(TypeVariableSignature.class);
            assertThat(((TypeVariableSignature) parameterType).resolve())
                    .hasToString("T extends java.lang.Number & java.lang.Comparable<T>");
        }
    }

    /** A throws clause may name a type variable, as well as a class. */
    @Test
    public void aThrowsClauseMayNameATypeVariable() {
        try (var scanResult = scanFixture()) {
            final var typeSig = typeSignature(scanResult, "throwsATypeVariable");
            assertThat(typeSig.getThrowsSignatures()).extracting(Object::toString).containsExactly("E");
            assertThat(typeSig).hasToString("<E extends java.lang.Exception> void () throws E");
        }
    }

    /** A method with no {@code Signature} attribute gets a type descriptor instead. */
    @Test
    public void aMethodWithNoSignatureAttributeGetsATypeDescriptor() {
        try (var scanResult = scanFixture()) {
            final var plain = methodInfo(scanResult, "plain");
            assertThat(plain.getTypeSignatureString()).isNull();
            assertThat(plain.getTypeSignature()).isNull();

            final var typeDescriptor = plain.getTypeDescriptor();
            assertThat(typeDescriptor.getTypeParameters()).isEmpty();
            assertThat(typeDescriptor.getParameterTypeSignatures()).isEmpty();
            assertThat(typeDescriptor.getResultType()).hasToString("void");
            assertThat(typeDescriptor.getThrowsSignatures()).isEmpty();

            // The type descriptor is what getTypeSignatureOrTypeDescriptor() falls back to
            assertThat(plain.getTypeSignatureOrTypeDescriptor()).isEqualTo(typeDescriptor);
            assertThat(plain.getTypeSignatureOrTypeDescriptorString()).isEqualTo(plain.getTypeDescriptorString())
                    .isEqualTo("()V");

            // A method that does have a type signature returns it in preference to the type descriptor
            final var generic = methodInfo(scanResult, "generic");
            assertThat(generic.getTypeSignatureOrTypeDescriptor()).isSameAs(generic.getTypeSignature());
            assertThat(generic.getTypeSignatureOrTypeDescriptorString()).isEqualTo(generic.getTypeSignatureString())
                    .isNotEqualTo(generic.getTypeDescriptorString());
        }
    }

    /**
     * The classes named anywhere in a method's signature are all counted as dependencies of the class the method is
     * declared in.
     */
    @Test
    public void theClassesNamedInAMethodSignatureAreDependenciesOfTheDeclaringClass() {
        try (var scanResult = new ClassGraph().enableClassInfo().enableClasspath()
                .acceptClasses(Methods.class.getName()).enableMethodInfo().enableInterClassDependencies()
                .enableExternalClasses().scan()) {
            final var classInfo = scanResult.getClassInfo(Methods.class.getName());
            // Type parameter bounds and type arguments are erased from a method's type descriptor, and the classes
            // they name are not in the constant pool either unless the method body happens to use them, so these
            // classes can only be found by reading the method's signature
            assertThat(classInfo.getClassDependencies().getNames()).contains(
                    // From the bounds of the type parameters
                    Comparable.class.getName(),
                    // From a type argument of the result type
                    DayOfWeek.class.getName(),
                    // From a type argument of a parameter type
                    RandomAccess.class.getName());
        }
    }

    /**
     * The signature of a constructor, as it is named in a {@code CONSTANT_NameAndType_info} constant pool entry, is
     * the string {@code "<init>"} rather than a signature, and is read as a method that takes no parameters and
     * returns void.
     *
     * @throws TypeSignatureParseException
     *             if the signature could not be parsed.
     */
    @Test
    public void theSignatureOfAConstructorIsAMethodThatTakesNoParametersAndReturnsVoid()
            throws TypeSignatureParseException {
        final var typeSig = MethodTypeSignature.parse("<init>", /* definingClassName = */ null);
        assertThat(typeSig.getTypeParameters()).isEmpty();
        assertThat(typeSig.getParameterTypeSignatures()).isEmpty();
        assertThat(typeSig.getResultType()).hasToString("void");
        assertThat(typeSig.getThrowsSignatures()).isEmpty();
        assertThat(typeSig).hasToString("void ()");
    }

    /** Method type signatures are equal if their type parameters, parameter types, result and throws are equal. */
    @Test
    public void signaturesAreComparedByTypeParametersParameterTypesResultAndThrows() {
        try (var scanResult = scanFixture(); var scanResult2 = scanFixture()) {
            final var typeSig = typeSignature(scanResult, "generic");
            final var sameTypeSig = typeSignature(scanResult2, "generic");
            assertThat(sameTypeSig).isNotSameAs(typeSig).isEqualTo(typeSig).hasSameHashCodeAs(typeSig);

            final var otherTypeSig = typeSignature(scanResult, "throwsATypeVariable");
            assertThat(typeSig).isEqualTo(typeSig).isNotEqualTo(otherTypeSig).isNotEqualTo(null)
                    .isNotEqualTo(typeSig.toString());
        }
    }

    /**
     * A type annotation cannot be added to a method type signature as a whole -- the type parameters, parameter
     * types, result type and throws types each take their own type annotations.
     */
    @Test
    public void aTypeAnnotationCannotBeAddedToAMethodTypeSignatureAsAWhole() {
        try (var scanResult = scanFixture()) {
            final var typeSig = typeSignature(scanResult, "generic");
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> typeSig.addTypeAnnotation(List.of(), null));
        }
    }

    /** A method type signature does not name a class of its own, so it has neither a class name nor a class. */
    @Test
    public void aMethodTypeSignatureDoesNotNameAClass() {
        try (var scanResult = scanFixture()) {
            final var typeSig = typeSignature(scanResult, "generic");
            assertThatExceptionOfType(UnsupportedOperationException.class).isThrownBy(typeSig::getClassName);
            assertThatExceptionOfType(UnsupportedOperationException.class).isThrownBy(typeSig::getClassInfo);
        }
    }

    /** A method type signature that cannot be parsed is rejected with a {@link TypeSignatureParseException}. */
    @Test
    public void aMalformedMethodTypeSignatureIsRejected() {
        // The parameter list is not terminated
        assertThatExceptionOfType(TypeSignatureParseException.class)
                .isThrownBy(() -> MethodTypeSignature.parse("(Ljava/lang/String;", /* definingClassName = */ null))
                .withMessageContaining("Ran out of input while parsing method signature");

        // "Q" is not a type signature, so it is not a parameter type
        assertThatExceptionOfType(TypeSignatureParseException.class)
                .isThrownBy(() -> MethodTypeSignature.parse("(Q)V", /* definingClassName = */ null))
                .withMessageContaining("Missing method parameter type signature");

        // "Q" is not a type signature, so it is not a result type
        assertThatExceptionOfType(TypeSignatureParseException.class)
                .isThrownBy(() -> MethodTypeSignature.parse("()Q", /* definingClassName = */ null))
                .withMessageContaining("Missing method result type signature");

        // "Q" is neither a class reference nor a type variable, so it cannot follow "^"
        assertThatExceptionOfType(TypeSignatureParseException.class)
                .isThrownBy(() -> MethodTypeSignature.parse("()V^Q", /* definingClassName = */ null))
                .withMessageContaining("Missing type variable signature");

        // The throws clause is the last thing in a signature, so nothing may follow it
        assertThatExceptionOfType(TypeSignatureParseException.class)
                .isThrownBy(
                        () -> MethodTypeSignature.parse("()V^Ljava/lang/Error;X", /* definingClassName = */ null))
                .withMessageContaining("Extra characters at end of type descriptor");
    }
}
