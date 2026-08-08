package io.github.classgraph;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URL;

import org.junit.jupiter.api.Test;

/**
 * {@link ResourceList#getPathsRelativeToClasspathElement()} called {@link Resource#getPath()} rather than
 * {@link Resource#getPathRelativeToClasspathElement()}, so it returned the same paths as
 * {@link ResourceList#getPaths()}, with the package root prefix stripped.
 */
public class ResourceListPathsTest {
    /** Paths relative to the classpath element retain the package root prefix. */
    @Test
    public void pathsRelativeToClasspathElementRetainPackageRoot() {
        final URL jarURL = ResourceListPathsTest.class.getClassLoader()
                .getResource("spring-boot-fully-executable-jar.jar");

        try (ScanResult scanResult = new ClassGraph().acceptPathsNonRecursive("hello")
                .overrideClasspath("jar:" + jarURL + "!/BOOT-INF/classes").scan()) {
            final ResourceList resources = scanResult.getAllResources();
            assertThat(resources.getPaths()).contains("hello/HelloController.class");
            assertThat(resources.getPathsRelativeToClasspathElement())
                    .contains("BOOT-INF/classes/hello/HelloController.class");
        }
    }
}
