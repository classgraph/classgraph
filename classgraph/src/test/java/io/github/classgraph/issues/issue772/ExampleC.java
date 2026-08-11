package io.github.classgraph.issues.issue772;

public abstract class ExampleC implements AutoCloseable {

    @Override
    public abstract void close();

    public abstract static class Child extends ExampleC implements MyCloseable {

    }
}
