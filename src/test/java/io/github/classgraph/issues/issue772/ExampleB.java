package io.github.classgraph.issues.issue772;

@SuppressWarnings("unused")
public abstract class ExampleB implements MyCloseable {

    public abstract static class Child extends ExampleB implements AutoCloseable {

    }
}
