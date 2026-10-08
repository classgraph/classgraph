package io.github.classgraph;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * The type signature classes form a sealed hierarchy, so a {@code switch} over a {@link TypeSignature} can list
 * every kind without a default case.
 */
public class TypeSignatureHierarchyTest {
    /** Each abstract class of the hierarchy is sealed, and permits exactly its known subclasses. */
    @Test
    public void theHierarchyIsSealed() {
        assertThat(HierarchicalTypeSignature.class.getPermittedSubclasses()).containsExactlyInAnyOrder(
                TypeSignature.class, ClassTypeSignature.class, MethodTypeSignature.class, TypeArgument.class,
                TypeParameter.class);
        assertThat(TypeSignature.class.getPermittedSubclasses()).containsExactlyInAnyOrder(BaseTypeSignature.class,
                ReferenceTypeSignature.class);
        assertThat(ReferenceTypeSignature.class.getPermittedSubclasses())
                .containsExactlyInAnyOrder(ArrayTypeSignature.class, ClassRefOrTypeVariableSignature.class);
        assertThat(ClassRefOrTypeVariableSignature.class.getPermittedSubclasses())
                .containsExactlyInAnyOrder(ClassRefTypeSignature.class, TypeVariableSignature.class);
    }

    /**
     * A pattern switch over a {@link TypeSignature} that lists each of its concrete subclasses needs no default
     * case: {@link #kind(TypeSignature)} does not compile unless the compiler can prove that the switch is
     * exhaustive.
     *
     * @throws TypeSignatureParseException
     *             if a type signature could not be parsed.
     */
    @Test
    public void aPatternSwitchOverATypeSignatureNeedsNoDefaultCase() throws TypeSignatureParseException {
        assertThat(kind(TypeSignature.parse("I", null))).isEqualTo("base");
        assertThat(kind(TypeSignature.parse("[I", null))).isEqualTo("array");
        assertThat(kind(TypeSignature.parse("Ljava/lang/String;", null))).isEqualTo("class reference");
        assertThat(kind(TypeSignature.parse("TT;", null))).isEqualTo("type variable");
    }

    /**
     * Name the kind of a type signature, with a pattern switch that has no default case.
     *
     * @param typeSignature
     *            the type signature.
     * @return the kind of the type signature.
     */
    private static String kind(final TypeSignature typeSignature) {
        return switch (typeSignature) {
        case BaseTypeSignature _ -> "base";
        case ArrayTypeSignature _ -> "array";
        case ClassRefTypeSignature _ -> "class reference";
        case TypeVariableSignature _ -> "type variable";
        };
    }
}
