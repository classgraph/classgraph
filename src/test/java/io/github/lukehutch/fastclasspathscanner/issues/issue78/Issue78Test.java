package io.github.lukehutch.fastclasspathscanner.issues.issue78;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.Test;

import io.github.lukehutch.fastclasspathscanner.FastClasspathScanner;

public class Issue78Test {
    @Test
    public void issue78() {
        assertThat(new FastClasspathScanner(Issue78Test.class.getName()).scan().getNamesOfAllStandardClasses())
                .containsOnly(Issue78Test.class.getName());
    }
}
