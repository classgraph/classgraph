package io.github.lukehutch.fastclasspathscanner.issues.issue83;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URL;
import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import io.github.lukehutch.fastclasspathscanner.FastClasspathScanner;

public class Issue83Test {
    private static final URL jarPathURL = Issue83Test.class.getClassLoader().getResource("nested-jars-level1.zip");

    @Test
    public void jarWhitelist() {
        assertThat(jarPathURL).isNotNull();
        final List<String> paths = new ArrayList<>();
        new FastClasspathScanner().overrideClasspath(jarPathURL).whitelistJars("nested-jars-level1.zip").scan()
                .getAllResources().forEach(res -> paths.add(res.getPath()));
        assertThat(paths).contains("level2.jar");
    }

    @Test
    public void jarBlacklist() {
        assertThat(jarPathURL).isNotNull();
        final ArrayList<String> paths = new ArrayList<>();
        new FastClasspathScanner().overrideClasspath(jarPathURL).blacklistJars("nested-jars-level1.zip").scan()
                .getAllResources().forEach(res -> paths.add(res.getPath()));
        assertThat(paths).doesNotContain("level2.jar");
    }
}
