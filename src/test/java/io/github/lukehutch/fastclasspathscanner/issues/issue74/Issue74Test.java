package io.github.lukehutch.fastclasspathscanner.issues.issue74;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.Test;

import io.github.lukehutch.fastclasspathscanner.FastClasspathScanner;

public class Issue74Test {
    public interface Function {
    }

    public abstract class FunctionAdapter implements Function {
    }

    public class ExtendsFunctionAdapter extends FunctionAdapter {
    }

    public class ImplementsFunction implements Function {
    }

    @Test
    public void issue74() {
        assertThat(new FastClasspathScanner(Issue74Test.class.getPackage().getName()).scan()
                .getNamesOfClassesImplementing(Function.class.getName())).containsOnly(
                        FunctionAdapter.class.getName(), ImplementsFunction.class.getName(),
                        ExtendsFunctionAdapter.class.getName());
    }
}
