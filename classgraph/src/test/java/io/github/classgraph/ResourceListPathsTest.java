package io.github.classgraph;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;

import org.junit.jupiter.api.Test;

/**
 * {@link ResourceList#getPathsRelativeToClasspathElement()} called {@link Resource#getPath()} rather than
 * {@link Resource#getPathRelativeToClasspathElement()}, so it returned the same paths as
 * {@link ResourceList#getPaths()}, with the package root prefix stripped.
 */
public class ResourceListPathsTest {
    /**
     * Paths relative to the classpath element retain the package root prefix. The classpath element is the jarfile,
     * and the package root within it is found because the classloader declares it as an automatic package root.
     *
     * @throws IOException
     *             if the classloader could not be closed.
     */
    @Test
    public void pathsRelativeToClasspathElementRetainPackageRoot() throws IOException {
        final var jarURL = ResourceListPathsTest.class.getClassLoader()
                .getResource("spring-boot-fully-executable-jar.jar");

        try (var classLoader = new URLClassLoader(new URL[] { jarURL }, /* parent = */ null);
                var scanResult = new ClassGraph().acceptPathsNonRecursive("hello").overrideClassLoaders(classLoader)
                        .registerClassLoaderHandler(new PackageRootClassLoaderHandler("BOOT-INF/classes/"))
                        .scan()) {
            final var resources = scanResult.getAllResources();
            assertThat(resources.getPaths()).contains("hello/HelloController.class");
            assertThat(resources.getPathsRelativeToClasspathElement())
                    .contains("BOOT-INF/classes/hello/HelloController.class");
        }
    }
}
