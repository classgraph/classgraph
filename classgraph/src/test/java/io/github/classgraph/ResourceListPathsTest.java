package io.github.classgraph;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * {@link ResourceList#getPathsRelativeToClasspathElement()} called {@link Resource#getPath()} rather than
 * {@link Resource#getPathRelativeToClasspathElement()}, so it returned the same paths as
 * {@link ResourceList#getPaths()}.
 */
public class ResourceListPathsTest {
    /**
     * The two path forms differ for a resource in a versioned section of a multi-release jar: the path is relative
     * to the package root, with the version prefix dropped, while the path relative to the classpath element is the
     * name of the entry that was actually read, version prefix and all.
     */
    @Test
    public void pathsRelativeToClasspathElementRetainTheVersionPrefix() {
        final var jarURL = ResourceListPathsTest.class.getClassLoader().getResource("multi-release-jar.jar");
        try (var scanResult = new ClassGraph().acceptPackages("mrj").overrideClasspath(jarURL).scan()) {
            final var resources = scanResult.getAllResources();
            assertThat(resources.getPaths()).containsExactly("mrj/Cls.class");
            assertThat(resources.getPathsRelativeToClasspathElement())
                    .containsExactly("META-INF/versions/9/mrj/Cls.class");
        }
    }
}
