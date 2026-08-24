package io.github.classgraph.issues.issue80;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import io.github.classgraph.ClassGraph;

public class Issue80Test {
    @Test
    public void issue80() {
        try (var scanResult = new ClassGraph().enableSystemJars().enableSystemModules().enableClassInfo().scan()) {
            assertThat(scanResult.getAllStandardClasses().getNames()).contains("java.util.ArrayList");
        }
    }
}
