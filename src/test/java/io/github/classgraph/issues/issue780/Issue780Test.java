package io.github.classgraph.issues.issue780;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.ScanResult;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class Issue780Test {
    /**
     * Issue 780.
     */
    @Test
    public void getResourcesWithPathShouldNeverReturnNull() {
        try (ScanResult result = new ClassGraph().scan()) {
            for (int i = 0; i < 10; i++) {
                assertThat(result.getResourcesWithPath("/some/non/existing/path")).isNotNull().isEmpty();
            }
        }
    }
}
