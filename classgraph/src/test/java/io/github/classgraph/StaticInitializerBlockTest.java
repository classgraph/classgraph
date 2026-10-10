package io.github.classgraph;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Tests that static initializer blocks are excluded from the method queries that select methods by kind, and can
 * only be reached by looking them up by their {@code "<clinit>"} name, and that a lookup by name does not return a
 * superclass's or interface's constructors or static initializer block.
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

    /** An interface with a static initializer block, since its field is not a compile-time constant. */
    public interface InterfaceWithStaticInitializer {
        /** A field assigned by the interface's static initializer block. */
        String INTERFACE_FIELD = String.valueOf(System.nanoTime());
    }

    /** A superclass with a static initializer block and a constructor that its subclass does not declare. */
    public static class Parent {
        /** A field assigned by the static initializer block, so that the block is not optimized away. */
        static final String PARENT_FIELD;

        static {
            PARENT_FIELD = "value";
        }

        /**
         * A constructor with an {@code int} parameter.
         *
         * @param value
         *            ignored
         */
        public Parent(final int value) {
        }
    }

    /** A subclass with no static initializer block, and only a constructor with a {@link String} parameter. */
    public static class Child extends Parent implements InterfaceWithStaticInitializer {
        /**
         * A constructor with a {@link String} parameter.
         *
         * @param value
         *            ignored
         */
        public Child(final String value) {
            super(0);
        }
    }

    /**
     * Scan {@link HasStaticInitializer}, ignoring method visibility so that the private static initializer block is
     * visible.
     *
     * @return the scan result
     */
    private static ScanResult scan() {
        return new ClassGraph().enableClassInfo().enableClasspath()
                .acceptClasses(HasStaticInitializer.class.getName()).enableMethodInfo().ignoreMethodVisibility()
                .scan();
    }

    /**
     * Scan {@link Parent}, {@link Child} and {@link InterfaceWithStaticInitializer}.
     *
     * @param ignoreMethodVisibility
     *            whether to ignore method visibility, which makes the static initializer blocks visible
     * @return the scan result
     */
    private static ScanResult scanParentAndChild(final boolean ignoreMethodVisibility) {
        final var classGraph = new ClassGraph().enableClassInfo().enableClasspath()
                .acceptClasses(Parent.class.getName(), Child.class.getName(),
                        InterfaceWithStaticInitializer.class.getName())
                .enableMethodInfo();
        if (ignoreMethodVisibility) {
            classGraph.ignoreMethodVisibility();
        }
        return classGraph.scan();
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

    /**
     * Constructors and static initializer blocks are not inherited, so looking them up by name returns only those
     * of the class itself, not those of its superclasses or interfaces.
     */
    @Test
    public void nameBasedQueriesDoNotReturnInheritedConstructorsOrStaticInitializers() {
        for (final var ignoreMethodVisibility : new boolean[] { false, true }) {
            try (var scanResult = scanParentAndChild(ignoreMethodVisibility)) {
                final var child = scanResult.getClassInfo(Child.class.getName());
                final var childConstructors = child.getMethodInfo("<init>");
                assertThat(childConstructors).isEqualTo(child.getConstructorInfo());
                assertThat(childConstructors).hasSize(1);
                assertThat(childConstructors.get(0).getClassName()).isEqualTo(Child.class.getName());
                assertThat(childConstructors.get(0).getTypeDescriptorString()).isEqualTo("(Ljava/lang/String;)V");
                assertThat(child.getMethodInfo("<clinit>")).isEmpty();
            }
        }
        try (var scanResult = scanParentAndChild(true)) {
            // The static initializer blocks are still found on the classes that declare them
            final var parentStaticInitializers = scanResult.getClassInfo(Parent.class.getName())
                    .getMethodInfo("<clinit>");
            assertThat(parentStaticInitializers).hasSize(1);
            assertThat(parentStaticInitializers.get(0).getClassName()).isEqualTo(Parent.class.getName());
            assertThat(scanResult.getClassInfo(InterfaceWithStaticInitializer.class.getName())
                    .getMethodInfo("<clinit>")).hasSize(1);
        }
    }
}
