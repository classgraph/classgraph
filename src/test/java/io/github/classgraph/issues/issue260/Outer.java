package io.github.classgraph.issues.issue260;

/**
 * The Class Outer.
 */
public class Outer {

    /**
     * Creates the anonymous.
     *
     * @param test
     *            the test
     * @return the p
     */
    public P createAnonymous(final String test) {
        return new P("a", "b") {
            @Override
            String test() {
                return test;
            }
        };
    }
}