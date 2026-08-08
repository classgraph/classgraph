package io.github.classgraph;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * {@link ClassGraphClassLoader#findClass(String)} delegates to the override classloaders (which is where the
 * overridden classpath ends up), but {@code getResource}, {@code getResources} and {@code getResourceAsStream} only
 * delegated to the environment and added classloaders, so a resource that is on the overridden classpath but was
 * not accepted by the scan spec could not be found through the classloader at all.
 */
public class ClassGraphClassLoaderOverrideResourceTest {
    /** The path of the resource that is placed on the overridden classpath. */
    private static final String RESOURCE_PATH = "not-accepted-dir/override-only-resource.txt";

    /** The content of the resource that is placed on the overridden classpath. */
    private static final String RESOURCE_CONTENT = "override-only";

    /** Resources on the overridden classpath are found, even if they were not accepted by the scan spec. */
    @Test
    public void resourceAccessorsUseOverrideClassLoaders() throws IOException {
        final Path tempDir = Files.createTempDirectory("classgraph-test");
        try {
            final File resourceFile = new File(tempDir.toFile(), RESOURCE_PATH);
            assertThat(resourceFile.getParentFile().mkdirs()).isTrue();
            Files.write(resourceFile.toPath(), RESOURCE_CONTENT.getBytes(Charset.forName("UTF-8")));

            // Accept a path that does not contain the resource, so that the resource is not in the ScanResult
            try (ScanResult scanResult = new ClassGraph().overrideClasspath(tempDir.toFile().getPath())
                    .acceptPaths("accepted-dir").scan()) {
                assertThat(scanResult.getResourcesWithPath(RESOURCE_PATH)).isEmpty();

                final ClassGraphClassLoader classLoader = new ClassGraphClassLoader(scanResult);

                final URL resource = classLoader.getResource(RESOURCE_PATH);
                assertThat(resource).isNotNull();

                final List<URL> resources = new ArrayList<>();
                for (final Enumeration<URL> resourceEnum = classLoader
                        .getResources(RESOURCE_PATH); resourceEnum.hasMoreElements();) {
                    resources.add(resourceEnum.nextElement());
                }
                assertThat(resources).containsExactly(resource);

                try (InputStream inputStream = classLoader.getResourceAsStream(RESOURCE_PATH)) {
                    assertThat(inputStream).isNotNull();
                    final byte[] buf = new byte[RESOURCE_CONTENT.length()];
                    assertThat(inputStream.read(buf)).isEqualTo(buf.length);
                    assertThat(new String(buf, Charset.forName("UTF-8"))).isEqualTo(RESOURCE_CONTENT);
                }
            }
        } finally {
            deleteRecursively(tempDir.toFile());
        }
    }

    /**
     * Delete a file or directory and everything below it.
     *
     * @param file
     *            the file or directory to delete
     */
    private static void deleteRecursively(final File file) {
        final File[] subFiles = file.listFiles();
        if (subFiles != null) {
            for (final File subFile : subFiles) {
                deleteRecursively(subFile);
            }
        }
        if (!file.delete()) {
            file.deleteOnExit();
        }
    }
}
