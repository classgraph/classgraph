package io.github.classgraph.issues.issue804;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.ScanResult;

public class Issue804Test {

    private static final String NESTED_EXAMPLE_CLASS = "org.springframework.util.ResourceUtils";

    @Test
    void scanningNestedJarsInPathsContainingSpacesShouldNeverFail(@TempDir final Path tempDir) throws IOException {
        final var targetJar = createSpringBootJarInExampleDirectory(tempDir, "directory with spaces");

        try (var scanResult = scanJar(targetJar)) {
            assertThat(scanResult.getClassInfo(NESTED_EXAMPLE_CLASS)).isNotNull();
        }
    }

    @Test
    void scanningNestedJarsInPathsContainingHashesShouldNeverFail(@TempDir final Path tempDir) throws IOException {
        final var targetJar = createSpringBootJarInExampleDirectory(tempDir, "directory-without-spaces#123");

        try (var scanResult = scanJar(targetJar)) {
            assertThat(scanResult.getClassInfo(NESTED_EXAMPLE_CLASS)).isNotNull();
        }
    }

    @Test
    void scanningNestedJarsInPathsContainingSpacesAndHashesShouldNeverFail(@TempDir final Path tempDir)
            throws IOException {
        final var targetJar = createSpringBootJarInExampleDirectory(tempDir, "directory with spaces #123");

        try (var scanResult = scanJar(targetJar)) {
            assertThat(scanResult.getClassInfo(NESTED_EXAMPLE_CLASS)).isNotNull();
        }
    }

    private static Path createSpringBootJarInExampleDirectory(final Path temporaryDirectory,
            final String directoryName) throws IOException {
        final var directoryWithSpaces = temporaryDirectory.resolve(directoryName);
        Files.createDirectories(directoryWithSpaces);
        final var nestedJar = directoryWithSpaces.resolve("spring-boot-fully-executable-jar.jar");
        try (var nestedJarsExample = Issue804Test.class.getClassLoader()
                .getResourceAsStream("spring-boot-fully-executable-jar.jar")) {
            Files.copy(nestedJarsExample, nestedJar);
        }
        return nestedJar;
    }

    /**
     * Scan the jarfile nested within the Spring Boot jarfile that {@link #NESTED_EXAMPLE_CLASS} is in. The nested
     * jarfile has to be named explicitly, since no classloader is involved in finding an overridden classpath, and
     * so nothing knows that a Spring Boot jarfile keeps its dependencies in "BOOT-INF/lib".
     *
     * @param targetJar
     *            the Spring Boot jarfile.
     * @return the scan result.
     */
    private static ScanResult scanJar(final Path targetJar) {
        return new ClassGraph().enableClassInfo()
                .enableClasspathEntries(targetJar.toUri() + "!/BOOT-INF/lib/spring-core-4.3.13.RELEASE.jar").scan();
    }

}
