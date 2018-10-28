package io.github.classgraph.issues.issue78;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.Test;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.ScanResult;

public class Issue78Test {
    @Test
    public void issue78() {
        try (ScanResult scanResult = new ClassGraph().whitelistClasses(Issue78Test.class.getName()).scan()) {
            assertThat(scanResult.getAllClasses().getNames())
                    .containsExactlyInAnyOrder(Issue78Test.class.getName());
        }
    }
}
