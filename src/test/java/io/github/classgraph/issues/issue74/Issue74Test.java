package io.github.classgraph.issues.issue74;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.ScanResult;

/**
 * Issue74Test.
 */
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

    /**
     * Issue 74.
     */
    @Test
    public void issue74() {
        try (ScanResult scanResult = new ClassGraph().whitelistPackages(Issue74Test.class.getPackage().getName())
                .scan()) {
            assertThat(scanResult.getClassesImplementing(Function.class.getName()).getNames()).containsOnly(
                    FunctionAdapter.class.getName(), ImplementsFunction.class.getName(),
                    ExtendsFunctionAdapter.class.getName());
        }
    }
}
