package io.github.classgraph.issues.issue772;

public class ExampleD {

    public interface J {
        default void j() {
        }
    }

    public interface I1 extends J {
        default void i1() {
        }
    }

    public interface I2 {
        default void i2() {
        }
    }

    public abstract static class Child implements I1, I2 {

    }
}
