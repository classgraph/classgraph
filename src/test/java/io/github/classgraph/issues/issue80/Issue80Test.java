package io.github.classgraph.issues.issue80;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.Test;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.ScanResult;

public class Issue80Test {
    @Test
    public void issue80() {
        try (ScanResult scanResult = new ClassGraph().enableSystemPackages().enableClassInfo().scan()) {
            assertThat(scanResult.getAllStandardClasses().getNames()).contains("java.util.ArrayList");
        }
    }
}
