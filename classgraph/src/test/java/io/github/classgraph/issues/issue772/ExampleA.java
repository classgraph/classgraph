package io.github.classgraph.issues.issue772;

/**
 * Test case A for selecting the 'Close' method of Child. Rather simple case of symmetrical extending classes.
 */
// The point of the fixture is that close() is inherited from AutoCloseable, so it keeps that method's
// "throws Exception". javac's "try" lint warns that such a close() could throw InterruptedException, which is
// exactly the shape being tested here. (The warning was dropped after JDK 17, so it is only seen on the oldest
// JDK the build supports.)
@SuppressWarnings("try")
public abstract class ExampleA implements AutoCloseable {

    public abstract static class Child extends ExampleA implements MyCloseable {

    }
}
