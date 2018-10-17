package io.github.classgraph.issues.issue260;

public class Outer {
    public P createAnonymous(final String test) {
        return new P("a", "b") {
            @Override
            String test() {
                return test;
            }
        };
    }
}