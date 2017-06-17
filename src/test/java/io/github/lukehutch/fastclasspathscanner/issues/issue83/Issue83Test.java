package io.github.lukehutch.fastclasspathscanner.issues.issue83;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.StrictAssertions.assertThat;

import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;

import org.junit.Test;

import io.github.lukehutch.fastclasspathscanner.FastClasspathScanner;

public class Issue83Test {
    private static final URL jarPathURL = Issue83Test.class.getClassLoader().getResource("nested-jars-level1.zip");

    @Test
    public void jarWhitelist() {
        assertThat(jarPathURL).isNotNull();
        final ArrayList<String> paths = new ArrayList<>();
        new FastClasspathScanner("jar:nested-jars-level1.zip")
                .addClassLoader(new URLClassLoader(new URL[] { jarPathURL }))
                .matchFilenamePattern(".*", (final String relativePath, final byte[] fileContents) -> {
                    paths.add(relativePath);
                }).scan();
        assertThat(paths).contains("level2.jar");
    }

    @Test
    public void jarBlacklist() {
        assertThat(jarPathURL).isNotNull();
        final ArrayList<String> paths = new ArrayList<>();
        new FastClasspathScanner("-jar:nested-jars-level1.zip")
                .addClassLoader(new URLClassLoader(new URL[] { jarPathURL }))
                .matchFilenamePattern(".*", (final String relativePath, final byte[] fileContents) -> {
                    paths.add(relativePath);
                }).scan();
        assertThat(paths).doesNotContain("level2.jar");
    }
}
