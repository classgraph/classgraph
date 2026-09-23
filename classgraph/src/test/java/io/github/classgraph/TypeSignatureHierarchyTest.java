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

    // TODO: once the tests compile at Java 21 or later, add a test with a pattern switch over a TypeSignature that
    // lists BaseTypeSignature, ArrayTypeSignature, ClassRefTypeSignature and TypeVariableSignature and has no default
    // case, so that the compiler checks the switch is exhaustive.
}
