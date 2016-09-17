package io.github.lukehutch.fastclasspathscanner.issues.issue78;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.lukehutch.fastclasspathscanner.FastClasspathScanner;

public class Issue78Test {
    public static void main(final String[] args) {
        assertThat(new FastClasspathScanner(Issue78Test.class.getName()).scan().getNamesOfAllStandardClasses())
                .containsOnly(Issue78Test.class.getName());
    }
}
