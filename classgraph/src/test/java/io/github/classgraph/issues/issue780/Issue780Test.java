package io.github.classgraph.issues.issue780;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import io.github.classgraph.ClassGraph;

public class Issue780Test {
    @Test
    public void getResourcesWithPathShouldNeverReturnNull() {
        try (var result = new ClassGraph().scan()) {
            for (var i = 0; i < 10; i++) {
                assertThat(result.getResourcesWithPath("/some/non/existing/path")).isNotNull().isEmpty();
            }
        }
    }
}
