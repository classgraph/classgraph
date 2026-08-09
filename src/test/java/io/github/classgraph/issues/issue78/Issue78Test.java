package io.github.classgraph.issues.issue78;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import io.github.classgraph.ClassGraph;

public class Issue78Test {
    @Test
    public void issue78() {
        try (var scanResult = new ClassGraph().acceptClasses(Issue78Test.class.getName()).scan()) {
            assertThat(scanResult.getAllClasses().getNames()).containsOnly(Issue78Test.class.getName());
        }
    }
}
