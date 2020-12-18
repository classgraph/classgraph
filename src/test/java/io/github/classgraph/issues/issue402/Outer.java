package io.github.classgraph.issues.issue402;

/**
 * Nested types.
 */
class Outer {
    class Middle {
        class Inner1 {
        }
    }

    static class MiddleStatic {
        class Inner2 {
        }

        static class InnerStatic {
        }
    }

    class MiddleGeneric<T> {
        class InnerGeneric<U> {
        }
    }
}
