package io.github.classgraph.issues.issue739;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.Resource;
import io.github.classgraph.ScanResult;

public class Issue739 {
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
        assertThat(paths).contains("issue673/a.zip");
        assertThat(paths).contains("multi-release-jar.src.zip!/multi-release-jar/src/main/java-9/module-info.java");
        assertThat(paths).contains("zip64.zip!/10046");
        assertThat(paths).contains("record.jar!/pkg/Record.class");
    }
}
