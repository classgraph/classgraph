package io.github.classgraph.issues.issue772;

@SuppressWarnings("unused")
public abstract class ExampleC implements AutoCloseable {

    public abstract void close();

    public abstract static class Child extends ExampleC implements MyCloseable {

    }
}
