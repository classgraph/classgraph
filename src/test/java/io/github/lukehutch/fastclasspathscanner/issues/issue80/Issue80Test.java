package io.github.lukehutch.fastclasspathscanner.issues.issue80;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.Test;

import io.github.lukehutch.fastclasspathscanner.FastClasspathScanner;

public class Issue80Test {
    @Test
    public void issue80() {
        assertThat(new FastClasspathScanner().enableSystemPackages().enableClassInfo().scan()
                .getAllStandardClasses().getNames()).contains("java.util.ArrayList");
    }
}
