package io.github.classgraph;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.Serial;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * {@link ClassInfo#toStringWithSimpleNames()} rendered a generic class by delegating to {@link ClassTypeSignature},
 * passing the already-simplified class name but {@code useSimpleNames = false}, so the type parameter bounds,
 * superclass and superinterfaces were left fully qualified. The non-generic branch of
 * {@link ClassInfo#toString(boolean, StringBuilder)} simplifies all of them.
 */
public class GenericClassSimpleNamesTest {
    /**
     * A generic class with a bounded type parameter, a superclass and a superinterface.
     */
    // The type parameter, and the redundant superinterface, are both part of the fixture
    @SuppressWarnings("unused")
    public static class Generic<T extends Number> extends ArrayList<String> implements List<String> {
        /** serialVersionUID. */
        @Serial
        private static final long serialVersionUID = 1L;
    }

    /**
     * A non-generic class with a superclass and a superinterface, for comparison.
     */
    // The superinterface is redundant on purpose: the test checks how superinterfaces are rendered
    @SuppressWarnings("unused")
    public static class NonGeneric extends ArrayList<String> implements List<String> {
        /** serialVersionUID. */
        @Serial
        private static final long serialVersionUID = 1L;
    }

    /**
     * Simple names are used throughout the rendering of a generic class, not just for the class name.
     */
    @Test
    public void genericClassUsesSimpleNamesThroughout() {
        try (var scanResult = new ClassGraph()
                .acceptPackagesNonRecursive(GenericClassSimpleNamesTest.class.getPackage().getName())
                .enableAllInfo().scan()) {
            final var generic = scanResult.getClassInfo(Generic.class.getName());
            assertThat(generic).isNotNull();
            assertThat(generic.toStringWithSimpleNames())
                    .isEqualTo("public static class Generic<T extends Number> extends ArrayList<String> "
                            + "implements List<String>");
            assertThat(generic.toString()).isEqualTo("public static class " + Generic.class.getName()
                    + "<T extends java.lang.Number> extends java.util.ArrayList<java.lang.String> "
                    + "implements java.util.List<java.lang.String>");

            final var nonGeneric = scanResult.getClassInfo(NonGeneric.class.getName());
            assertThat(nonGeneric).isNotNull();
            assertThat(nonGeneric.toStringWithSimpleNames()).contains("extends ArrayList");
        }
    }
}
