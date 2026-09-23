package io.github.classgraph.features;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.classgraph.ClassGraph;

/**
 * A Spring Boot jar is scanned only if its manifest puts its classes and lib jars in the standard locations, since
 * those are the only locations that are looked in.
 */
class SpringBootLayoutTest {
    /** The path of the resource in each jarfile. */
    private static final String RESOURCE_PATH = "springbootlayout/resource.txt";

    /**
     * Write a jarfile with a Spring Boot manifest and one resource.
     *
     * @param dir
     *            the directory to write the jarfile into.
     * @param springBootClasses
     *            the value of the {@code Spring-Boot-Classes} manifest attribute.
     * @return the jarfile.
     * @throws IOException
     *             if the jarfile could not be written.
     */
    private static Path writeJar(final Path dir, final String springBootClasses) throws IOException {
        final var manifest = new Manifest();
        final var mainAttributes = manifest.getMainAttributes();
        mainAttributes.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        mainAttributes.putValue("Spring-Boot-Classes", springBootClasses);
        final var jarFile = dir.resolve("app.jar");
        try (var outputStream = Files.newOutputStream(jarFile);
                var jarOutputStream = new JarOutputStream(outputStream, manifest)) {
            jarOutputStream.putNextEntry(new ZipEntry(RESOURCE_PATH));
            jarOutputStream.write("resource".getBytes(StandardCharsets.UTF_8));
            jarOutputStream.closeEntry();
        }
        return jarFile;
    }

    /**
     * Scan a jarfile, and say whether its resource was found.
     *
     * @param jarFile
     *            the jarfile.
     * @return true if the resource was found.
     */
    private static boolean resourceFound(final Path jarFile) {
        try (var scanResult = new ClassGraph().enableClasspathEntries(jarFile).acceptPaths("springbootlayout")
                .scan()) {
            return !scanResult.getResourcesWithPath(RESOURCE_PATH).isEmpty();
        }
    }

    /** A jarfile that declares the standard location for its classes is scanned. */
    @Test
    void aStandardLayoutIsScanned(@TempDir final Path dir) throws IOException {
        assertThat(resourceFound(writeJar(dir, "BOOT-INF/classes/"))).isTrue();
    }

    /** A jarfile that declares a nonstandard location for its classes is skipped. */
    @Test
    void aNonstandardLayoutIsSkipped(@TempDir final Path dir) throws IOException {
        assertThat(resourceFound(writeJar(dir, "custom/classes/"))).isFalse();
    }
}
