package io.github.classgraph.issues.issue739;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;

import org.junit.jupiter.api.Test;

import io.github.classgraph.ClassGraph;

public class Issue739Test {
    @Test
    void wildcardPathSupport() {
        final var relPath = "src/test/resources/";
        final HashSet<String> paths = new HashSet<>();
        try (var scanResult = new ClassGraph().enableClasspathEntries(relPath + "*").scan()) {
            scanResult.getAllResources().forEach(r -> {
                final var path = r.toString();
                final var idx = path.indexOf(relPath);
                if (idx >= 0) {
                    paths.add(path.substring(idx + relPath.length()));
                }
            });
        }
        assertThat(paths).contains("record.jar!/pkg/Record.class");
        // As in the java launcher, only the jarfiles in the directory are added, not zipfiles or subdirectories
        assertThat(paths).noneMatch(path -> path.contains(".zip!/"));
        assertThat(paths).doesNotContain("issue673/a.zip");
    }
}
