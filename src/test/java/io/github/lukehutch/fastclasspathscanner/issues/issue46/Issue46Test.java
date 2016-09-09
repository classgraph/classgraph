package io.github.lukehutch.fastclasspathscanner.issues.issue46;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.Test;

import io.github.lukehutch.fastclasspathscanner.FastClasspathScanner;

public class Issue46Test {
    @Test
    public void issue46Test() {
        final String jarPath = Issue46Test.class.getClassLoader().getResource("nested-jars-level1.jar").getPath()
                + "!level2.jar!level3.jar!classpath1/classpath2";
        assertThat(new FastClasspathScanner().overrideClasspath(jarPath).scan().getNamesOfAllClasses())
                .containsOnly("com.test.Test");
    }
}
