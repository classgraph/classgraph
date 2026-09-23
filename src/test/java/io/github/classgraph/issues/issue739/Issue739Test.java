package io.github.classgraph.issues.issue739;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.Resource;
import io.github.classgraph.ScanResult;

public class Issue739Test {
    @Test
    void wildcardPathSupport() {
        final String relPath = "src/test/resources/";
        final HashSet<String> paths = new HashSet<>();
        try (ScanResult scanResult = new ClassGraph().overrideClasspath(relPath + "*").scan()) {
            scanResult.getAllResources().forEach(new Consumer<Resource>() {
                @Override
                public void accept(final Resource r) {
                    final String path = r.toString();
                    final int idx = path.indexOf(relPath);
                    if (idx >= 0) {
                        paths.add(path.substring(idx + relPath.length()));
                    }
                }
            });
        }
        assertThat(paths).contains("record.jar!/pkg/Record.class");
        // As in the java launcher, only the jarfiles in the directory are added, not zipfiles or subdirectories
        for (final String path : paths) {
            assertThat(path).doesNotContain(".zip!/");
        }
        assertThat(paths).doesNotContain("issue673/a.zip");
    }
}
