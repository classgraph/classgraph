package io.github.classgraph.issues.issue78;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.Test;

import io.github.classgraph.ClassGraph;

public class Issue78Test {
    @Test
    public void issue78() {
        assertThat(new ClassGraph().whitelistClasses(Issue78Test.class.getName()).scan().getAllClasses().getNames())
                .containsOnly(Issue78Test.class.getName());
    }
}
