package io.github.classgraph.issues.issue772;

/**
 * Test case A for selecting the 'Close' method of Child. Rather simple case of symmetrical extending classes.
 */
@SuppressWarnings("unused")
public abstract class ExampleA implements AutoCloseable {

    public abstract static class Child extends ExampleA implements MyCloseable {

    }
}
