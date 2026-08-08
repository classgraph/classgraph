package io.github.classgraph;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * {@link ResourceList#getPathsRelativeToClasspathElement()} called
 * {@link Resource#getPath()} rather than
 * {@link Resource#getPathRelativeToClasspathElement()}, so it returned the same
 * paths as {@link ResourceList#getPaths()}, with the package root prefix
 * stripped.
 */
public class ResourceListPathsTest {
    /** Paths relative to the classpath element retain the package root prefix. */
    @Test
    public void pathsRelativeToClasspathElementRetainPackageRoot() {
        final var jarURL = ResourceListPathsTest.class.getClassLoader()
                .getResource("spring-boot-fully-executable-jar.jar");

        try (var scanResult = new ClassGraph().acceptPathsNonRecursive("hello")
                .overrideClasspath("jar:" + jarURL + "!/BOOT-INF/classes").scan()) {
            final var resources = scanResult.getAllResources();
            assertThat(resources.getPaths()).contains("hello/HelloController.class");
            assertThat(resources.getPathsRelativeToClasspathElement())
                    .contains("BOOT-INF/classes/hello/HelloController.class");
        }
    }
}
