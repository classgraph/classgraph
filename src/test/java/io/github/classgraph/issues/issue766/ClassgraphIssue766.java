package io.github.classgraph.issues.issue766;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URL;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.ScanResult;

public class ClassgraphIssue766 {

    @Test
    public void testURLs() {
        final URL url = ClassgraphIssue766.class.getResource("/issue766/ProjectWithAnnotations.iar");

        final String fileUrl = "file:" + url.getPath();
        final String jarFileUrl = "jar:file:" + url.getPath();
        final String jarUrl = "jar:///" + url.getPath();

        assertThat(scan("javax.annotation.ManagedBean", fileUrl)).containsOnly("ch.ivyteam.test.MyManagedBean");
        assertThat(scan("javax.annotation.ManagedBean", jarFileUrl)).containsOnly("ch.ivyteam.test.MyManagedBean");
        assertThat(scan("javax.annotation.ManagedBean", jarUrl)).containsOnly("ch.ivyteam.test.MyManagedBean");
    }

    public static Set<String> scan(final String annotation, final String urlStr) {
        final ClassGraph classGraph = new ClassGraph()
                .overrideClasspath(Set.<String> of(urlStr)).disableNestedJarScanning().enableAnnotationInfo();
        try (ScanResult result = classGraph.scan()) {
            return result.getClassesWithAnnotation(annotation).getStandardClasses().getNames().stream()
                    .collect(Collectors.toSet());
        }
    }
}
