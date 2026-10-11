package io.github.classgraph;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * {@link ClassInfo} and {@link ClassMemberInfo} are sealed: the only subclass of {@link ClassInfo} is
 * {@link ArrayClassInfo}, and every {@link ClassMemberInfo} is a {@link FieldInfo} or a {@link MethodInfo}, so a
 * {@code switch} over a {@link ClassMemberInfo} can list both kinds without a default case.
 */
public class ClassInfoAndClassMemberInfoAreSealedTest {
    /** Each class is sealed, and permits exactly its known subclasses. */
    @Test
    public void theClassesAreSealed() {
        assertThat(ClassInfo.class.getPermittedSubclasses()).as("the permitted subclasses of ClassInfo")
                .containsExactly(ArrayClassInfo.class);
        assertThat(ClassMemberInfo.class.getPermittedSubclasses()).as("the permitted subclasses of ClassMemberInfo")
                .containsExactlyInAnyOrder(FieldInfo.class, MethodInfo.class);
    }

    // TODO: once the tests compile at Java 21 or later, add a test with a pattern switch over a ClassMemberInfo
    // that lists FieldInfo and MethodInfo and has no default case, so that the compiler checks the switch is
    // exhaustive.
}
