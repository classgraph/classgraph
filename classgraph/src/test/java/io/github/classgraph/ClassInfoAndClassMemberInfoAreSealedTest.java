package io.github.classgraph;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * {@link ClassInfo} and {@link ClassMemberInfo} are sealed: the only subclass of {@link ClassInfo} is
 * {@link ArrayClassInfo}, and every {@link ClassMemberInfo} is a {@link FieldInfo} or a {@link MethodInfo}, so a
 * {@code switch} over a {@link ClassMemberInfo} can list both kinds without a default case.
 */
public class ClassInfoAndClassMemberInfoAreSealedTest {
    /** A class with one field and one method. */
    public static class FieldAndMethod {
        /** A field. */
        public int field;

        /** A method. */
        public void method() {
        }
    }

    /** Each class is sealed, and permits exactly its known subclasses. */
    @Test
    public void theClassesAreSealed() {
        assertThat(ClassInfo.class.getPermittedSubclasses()).as("the permitted subclasses of ClassInfo")
                .containsExactly(ArrayClassInfo.class);
        assertThat(ClassMemberInfo.class.getPermittedSubclasses()).as("the permitted subclasses of ClassMemberInfo")
                .containsExactlyInAnyOrder(FieldInfo.class, MethodInfo.class);
    }

    /**
     * A pattern switch over a {@link ClassMemberInfo} that lists {@link FieldInfo} and {@link MethodInfo} needs no
     * default case: {@link #kind(ClassMemberInfo)} does not compile unless the compiler can prove that the switch
     * is exhaustive.
     */
    @Test
    public void aPatternSwitchOverAClassMemberInfoNeedsNoDefaultCase() {
        try (var scanResult = new ClassGraph().enableClasspath().acceptClasses(FieldAndMethod.class.getName())
                .enableClassInfo().enableFieldInfo().enableMethodInfo().scan()) {
            final ClassInfo classInfo = scanResult.getClassInfo(FieldAndMethod.class.getName());
            assertThat(classInfo).isNotNull();
            assertThat(kind(classInfo.getDeclaredFieldInfo("field"))).isEqualTo("field");
            assertThat(kind(classInfo.getDeclaredMethodInfo("method").get(0))).isEqualTo("method");
        }
    }

    /**
     * Name the kind of a class member, with a pattern switch that has no default case.
     *
     * @param classMemberInfo
     *            the class member.
     * @return the kind of the class member.
     */
    private static String kind(final ClassMemberInfo classMemberInfo) {
        return switch (classMemberInfo) {
        case FieldInfo _ -> "field";
        case MethodInfo _ -> "method";
        };
    }
}
