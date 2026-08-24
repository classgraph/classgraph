package io.github.classgraph.issues.issue766;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;

import org.junit.jupiter.api.Test;

import io.github.classgraph.ClassGraph;

public class Issue766Test {

    /**
     * The three URL spellings of the same jarfile name the same classpath element. The package root within the
     * jarfile has to be named explicitly, since no classloader is involved in finding an overridden classpath, and
     * so nothing knows that this jarfile's classes live under "classes/".
     */
    @Test
    public void testURLs() {
        final var url = Issue766Test.class.getResource("/issue766/ProjectWithAnnotations.iar");

        final var fileUrl = "file:" + url.getPath() + "!/classes";
        final var jarFileUrl = "jar:file:" + url.getPath() + "!/classes";
        final var jarUrl = "jar:///" + url.getPath() + "!/classes";

        assertThat(scan("javax.annotation.ManagedBean", fileUrl)).containsOnly("ch.ivyteam.test.MyManagedBean");
        assertThat(scan("javax.annotation.ManagedBean", jarFileUrl)).containsOnly("ch.ivyteam.test.MyManagedBean");
        assertThat(scan("javax.annotation.ManagedBean", jarUrl)).containsOnly("ch.ivyteam.test.MyManagedBean");
    }

    public static Set<String> scan(final String annotation, final String urlStr) {
        final var classGraph = new ClassGraph().enableClasspathEntries(urlStr).disableNestedJarScanning()
                .enableAnnotationInfo();
        try (var result = classGraph.scan()) {
            return Set.copyOf(result.getClassesWithAnnotation(annotation).getStandardClasses().getNames());
        }
    }
}
