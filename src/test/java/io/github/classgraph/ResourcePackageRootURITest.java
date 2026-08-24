package io.github.classgraph;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;

import org.junit.jupiter.api.Test;

/**
 * {@link Resource#getURI()} separated the URI of the classpath element from the path of a resource within it with
 * {@code "!/"}, even when the classpath element was a package root within a jarfile rather than the jarfile itself,
 * giving a URL with two {@code "!/"} separators but only one archive in it, which does not resolve.
 */
public class ResourcePackageRootURITest {
    /**
     * A package root within a jarfile is a directory, not a jarfile nested inside it, so a resource beneath it is
     * separated from it by {@code "/"}, and the URL resolves.
     *
     * @throws IOException
     *             if the resource could not be read through its URL.
     */
    @Test
    public void aResourceBeneathAPackageRootHasAResolvableURL() throws IOException {
        final URL jarURL = ResourcePackageRootURITest.class.getClassLoader()
                .getResource("spring-boot-fully-executable-jar.jar");
        try (ScanResult scanResult = new ClassGraph().acceptPathsNonRecursive("classes/hello")
                .overrideClasspath(jarURL + "!/BOOT-INF").scan()) {
            final ResourceList resources = scanResult.getAllResources();
            assertThat(resources.getPaths()).contains("classes/hello/HelloController.class");
            final Resource resource = resources.get("classes/hello/HelloController.class").get(0);
            assertThat(resource.getURI().toString())
                    .endsWith("spring-boot-fully-executable-jar.jar!/BOOT-INF/classes/hello/HelloController.class");
            try (InputStream inputStream = resource.getURL().openStream()) {
                // A classfile starts with the 0xCAFEBABE magic number
                assertThat(inputStream.read()).isEqualTo(0xCA);
            }
        }
    }
}
