package io.github.lukehutch.fastclasspathscanner.issues.issue80;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.Test;

import io.github.lukehutch.fastclasspathscanner.FastClasspathScanner;

public class Issue80Test {
    @Test
    public void issue80() {
        // TODO: this test will fail in JDK9+, because there is no rt.jar
        assertThat(new FastClasspathScanner("!!", "java.util").scan().getNamesOfAllStandardClasses())
                .contains("java.util.ArrayList");
        assertThat(new FastClasspathScanner("java.util").scan().getNamesOfAllStandardClasses()).isEmpty();
    }
}
