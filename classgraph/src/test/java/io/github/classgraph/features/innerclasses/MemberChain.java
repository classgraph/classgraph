package io.github.classgraph.features.innerclasses;

/**
 * A chain of member classes, {@code MemberChain$B$C}, and a member class {@code MemberChain$D} that only this
 * classfile names.
 */
public class MemberChain {
    /** The middle class. */
    public static class B {
        /** The innermost class. */
        public static class C {
        }
    }

    /** A member class that no other class names. */
    public static class D {
    }
}
