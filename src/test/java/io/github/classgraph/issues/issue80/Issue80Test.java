package io.github.classgraph.issues.issue80;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.Test;

import io.github.classgraph.ClassGraph;

public class Issue80Test {
    @Test
    public void issue80() {
        assertThat(
                new ClassGraph().enableSystemPackages().enableClassInfo().scan().getAllStandardClasses().getNames())
                        .contains("java.util.ArrayList");
    }
}
