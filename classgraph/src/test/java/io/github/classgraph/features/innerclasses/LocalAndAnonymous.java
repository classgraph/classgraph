package io.github.classgraph.features.innerclasses;

/**
 * A member class with a local class and an anonymous class declared in one of its methods.
 */
public class LocalAndAnonymous {
    /** The member class. */
    public static class Member {
        /**
         * Declare a local class and an anonymous class.
         *
         * @return an instance of each
         */
        public Object[] declare() {
            class Local {
            }
            // N.B. this must remain an anonymous class (LocalAndAnonymous$Member$1), not a lambda
            final Object anonymous = new Object() {
            };
            return new Object[] { new Local(), anonymous };
        }
    }
}
