package io.github.classgraph;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Tests how a class is rendered in the extends or implements clause of another class. */
public class ExtendsImplementsClauseRenderingTest {
    /** A non-generic interface, so that {@link Parent} has an implements clause of its own. */
    public interface Marker {
    }

    /** A second non-generic interface, so that {@link Child}'s implements clause names a different one. */
    public interface Tag {
    }

    /** A non-generic superclass with modifiers, a superclass of its own, and an implements clause of its own. */
    public abstract static class Parent extends Exception implements Marker {
        /** serialVersionUID. */
        private static final long serialVersionUID = 1L;
    }

    /** A non-generic class whose extends and implements clauses both name a class that has clauses of its own. */
    public static class Child extends Parent implements Tag {
        /** serialVersionUID. */
        private static final long serialVersionUID = 1L;
    }

    /**
     * An extends or implements clause names only the class, as a Java source declaration does. The named class's
     * own modifiers, class type, type parameters, record parameters and supertypes belong to its own declaration.
     */
    @Test
    public void anExtendsOrImplementsClauseNamesOnlyTheClass() {
        try (ScanResult scanResult = new ClassGraph().enableClassInfo()
                .acceptClasses(Child.class.getName(), Parent.class.getName(), Marker.class.getName(),
                        Tag.class.getName())
                .scan()) {
            assertThat(scanResult.getClassInfo(Child.class.getName()))
                    .hasToString("public static class " + Child.class.getName() + " extends "
                            + Parent.class.getName() + " implements " + Tag.class.getName());
        }
    }
}
