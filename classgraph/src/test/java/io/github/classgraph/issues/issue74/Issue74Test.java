package io.github.classgraph.issues.issue74;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import io.github.classgraph.ClassGraph;

public class Issue74Test {
    /**
     * The Interface Function.
     */
    public interface Function {
    }

    /**
     * The Class FunctionAdapter.
     */
    public abstract class FunctionAdapter implements Function {
    }

    /**
     * The Class ExtendsFunctionAdapter.
     */
    public class ExtendsFunctionAdapter extends FunctionAdapter {
    }

    /**
     * The Class ImplementsFunction.
     */
    public class ImplementsFunction implements Function {
    }

    @Test
    public void issue74() {
        try (var scanResult = new ClassGraph().enableClasspath()
                .acceptPackages(Issue74Test.class.getPackage().getName()).scan()) {
            assertThat(scanResult.getAllClassesImplementing(Function.class).getNames()).containsOnly(
                    FunctionAdapter.class.getName(), ImplementsFunction.class.getName(),
                    ExtendsFunctionAdapter.class.getName());
        }
    }
}
