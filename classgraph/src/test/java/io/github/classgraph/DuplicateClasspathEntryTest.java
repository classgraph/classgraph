package io.github.classgraph;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The same jar or directory can be named on the classpath in several different ways -- as a path, as a
 * {@code file:} URL, as a {@code jar:} URL, or with redundant path segments. However it is spelled, it is one
 * classpath element: it is opened and scanned once, it is listed once in the classpath order, and each of its
 * resources is found once.
 */
public class DuplicateClasspathEntryTest {
    /**
     * The jar that is added to the classpath under several names.
     *
     * @return the path of the jar.
     * @throws URISyntaxException
     *             if the jar's URL is not a valid URI.
     */
    private static Path jarPath() throws URISyntaxException {
        final var jarURL = DuplicateClasspathEntryTest.class.getResource("/record.jar");
        assertThat(jarURL).as("test jar on the classpath").isNotNull();
        return Path.of(jarURL.toURI());
    }

    /** A jar named as a path, as a {@code file:} URL and as a {@code jar:} URL is one classpath element. */
    @Test
    public void aJarNamedSeveralWaysIsOneClasspathElement() throws URISyntaxException {
        final var jarPath = jarPath();
        final var jarURI = jarPath.toUri();
        try (var scanResult = new ClassGraph()
                .enableClasspathEntries(jarPath.toString(), jarURI.toString(), "jar:" + jarURI + "!/")
                .enableClassInfo().scan()) {
            assertThat(scanResult.getClasspathURIs()).hasSize(1);
            // The class in the jar is found once, rather than masking copies of itself
            assertThat(scanResult.getAllClasses().getNames()).containsExactly("pkg.Record");
            assertThat(scanResult.getAllResources().findDuplicatePaths()).isEmpty();
        }
    }

    /**
     * A directory named as a path, as a {@code file:} URL, with a trailing separator, and with a redundant
     * {@code .} segment is one classpath element.
     *
     * @param tempDir
     *            a temporary directory to use as the classpath element.
     * @throws IOException
     *             if the directory's contents could not be written.
     */
    @Test
    public void aDirectoryNamedSeveralWaysIsOneClasspathElement(@TempDir final Path tempDir) throws IOException {
        final var dir = tempDir.resolve("classes");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("resource.txt"), "contents");

        try (var scanResult = new ClassGraph()
                .enableClasspathEntries(dir.toString(), dir.toUri().toString(), dir + java.io.File.separator,
                        dir.getParent() + java.io.File.separator + "." + java.io.File.separator + "classes")
                .scan()) {
            assertThat(scanResult.getClasspathURIs()).hasSize(1);
            assertThat(scanResult.getAllResources().getPaths()).containsExactly("resource.txt");
        }
    }
}
