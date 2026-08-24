package io.github.classgraph;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Tests that static initializer blocks are excluded from the method queries that select methods by kind, and can
 * only be reached by looking them up by their {@code "<clinit>"} name.
 */
public class StaticInitializerBlockTest {
    /** A class with a static initializer block, a constructor and a normal method. */
    public static class HasStaticInitializer {
        /** A field assigned by the static initializer block, so that the block is not optimized away. */
        static final String FIELD;

        static {
            FIELD = "value";
        }

        /** A normal method. */
        public void normalMethod() {
        }
    }

    /**
     * Scan {@link HasStaticInitializer}, ignoring method visibility so that the private static initializer block is
     * visible.
     *
     * @return the scan result
     */
    private static ScanResult scan() {
        return new ClassGraph().enableClasspath().acceptClasses(HasStaticInitializer.class.getName())
                .enableMethodInfo().ignoreMethodVisibility().scan();
    }

    /** None of the kind-based method queries return the static initializer block. */
    @Test
    public void kindBasedQueriesExcludeStaticInitializer() {
        try (var scanResult = scan()) {
            final var classInfo = scanResult.getClassInfo(HasStaticInitializer.class.getName());
            assertThat(classInfo.getDeclaredMethodInfo().getNames()).containsExactly("normalMethod");
            assertThat(classInfo.getDeclaredConstructorInfo().getNames()).containsExactly("<init>");
            assertThat(classInfo.getDeclaredMethodAndConstructorInfo().getNames())
                    .containsExactlyInAnyOrder("<init>", "normalMethod");
            assertThat(classInfo.getMethodAndConstructorInfo().getNames()).doesNotContain("<clinit>");
        }
    }

    /** Looking a method up by name ignores its kind, so it can find the static initializer block. */
    @Test
    public void nameBasedQueriesFindStaticInitializer() {
        try (var scanResult = scan()) {
            final var classInfo = scanResult.getClassInfo(HasStaticInitializer.class.getName());
            assertThat(classInfo.getDeclaredMethodInfo("<clinit>").getNames()).containsExactly("<clinit>");
            assertThat(classInfo.getMethodInfo("<clinit>").getNames()).containsExactly("<clinit>");
            assertThat(classInfo.getDeclaredMethodInfo("<init>").getNames()).containsExactly("<init>");
        }
    }
}
