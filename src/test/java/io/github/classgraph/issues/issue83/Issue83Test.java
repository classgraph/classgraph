package io.github.classgraph.issues.issue83;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URL;
import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.Resource;
import io.github.classgraph.ResourceList;
import io.github.classgraph.ScanResult;

public class Issue83Test {
    private static final URL jarPathURL = Issue83Test.class.getClassLoader().getResource("nested-jars-level1.zip");

    @Test
    public void jarWhitelist() {
        assertThat(jarPathURL).isNotNull();
        final List<String> paths = new ArrayList<>();
        try (ScanResult scanResult = new ClassGraph().overrideClasspath(jarPathURL)
                .whitelistJars("nested-jars-level1.zip").scan()) {
            final ResourceList resources = scanResult.getAllResources();
            for (final Resource res : resources) {
                paths.add(res.getPath());
            }
            assertThat(paths).contains("level2.jar");
        }
    }

    @Test
    public void jarBlacklist() {
        assertThat(jarPathURL).isNotNull();
        final ArrayList<String> paths = new ArrayList<>();
        try (ScanResult scanResult = new ClassGraph().overrideClasspath(jarPathURL)
                .blacklistJars("nested-jars-level1.zip").scan()) {
            final ResourceList resources = scanResult.getAllResources();
            for (final Resource res : resources) {
                paths.add(res.getPath());
            }
            assertThat(paths).doesNotContain("level2.jar");
        }
    }
}
