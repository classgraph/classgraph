package io.github.classgraph.issues.issue766;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;

import org.junit.jupiter.api.Test;

import io.github.classgraph.ClassGraph;

public class Issue766Test {

    @Test
    public void testURLs() {
        final var url = Issue766Test.class.getResource("/issue766/ProjectWithAnnotations.iar");

        final var fileUrl = "file:" + url.getPath();
        final var jarFileUrl = "jar:file:" + url.getPath();
        final var jarUrl = "jar:///" + url.getPath();

        assertThat(scan("javax.annotation.ManagedBean", fileUrl)).containsOnly("ch.ivyteam.test.MyManagedBean");
        assertThat(scan("javax.annotation.ManagedBean", jarFileUrl)).containsOnly("ch.ivyteam.test.MyManagedBean");
        assertThat(scan("javax.annotation.ManagedBean", jarUrl)).containsOnly("ch.ivyteam.test.MyManagedBean");
    }

    public static Set<String> scan(final String annotation, final String urlStr) {
        final var classGraph = new ClassGraph().overrideClasspath(urlStr).disableNestedJarScanning()
                .enableAnnotationInfo();
        try (var result = classGraph.scan()) {
            return Set.copyOf(result.getClassesWithAnnotation(annotation).getStandardClasses().getNames());
        }
    }
}
