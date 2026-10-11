package io.github.classgraph.features.innerclasses;

/**
 * Inner classes and static nested classes, used as annotated types in {@link AnnotatedNestedFieldTypes},
 * {@link AnnotatedNestedMethodTypes} and {@link AnnotatedNestedClassTypes}.
 */
public class NestedTypes {
    /** An inner class. */
    public class Inner {
    }

    /** A static nested class. */
    public static class StaticNested {
    }

    /** An inner class with an inner class. */
    public class Inner2 {
        /** An inner class of an inner class. */
        public class Deep {
        }
    }

    /** A static nested class with an inner class. */
    public static class StaticNested2 {
        /** An inner class of a static nested class. */
        public class Deep {
        }
    }
}
