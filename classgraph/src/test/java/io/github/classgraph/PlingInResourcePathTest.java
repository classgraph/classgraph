package io.github.classgraph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A '!' in a resource path is an ordinary character, both in the path a resource is stored under and in the path it
 * is looked up by, whatever the part of the path before the '!' would name on the host.
 */
public class PlingInResourcePathTest {
    /**
     * Write a jarfile holding one empty entry per given name.
     *
     * @param jar
     *            the jarfile to write
     * @param names
     *            the entry names
     * @return the jarfile
     * @throws IOException
     *             if the jarfile could not be written
     */
    private static Path writeJar(final Path jar, final String... names) throws IOException {
        try (var jout = new JarOutputStream(Files.newOutputStream(jar))) {
            for (final var name : names) {
                jout.putNextEntry(new JarEntry(name));
                jout.closeEntry();
            }
        }
        return jar;
    }

    /**
     * Assert that both lookups by path find exactly one resource, with the given path.
     *
     * @param scanResult
     *            the scan result
     * @param query
     *            the path to look up
     * @param expectedPath
     *            the path the resource is expected to have
     */
    private static void assertFound(final ScanResult scanResult, final String query, final String expectedPath) {
        assertThat(scanResult.getResourcesWithPath(query).getPaths()).as("getResourcesWithPath(%s)", query)
                .containsExactly(expectedPath);
        assertThat(scanResult.getResourcesWithPathIgnoringAccept(query).getPaths())
                .as("getResourcesWithPathIgnoringAccept(%s)", query).containsExactly(expectedPath);
    }

    /**
     * A resource path shaped like a URL is not a URL, so the '!' in it does not separate a jarfile from an entry.
     *
     * @param tempDir
     *            a temporary directory to write the jarfile into
     * @throws IOException
     *             if the jarfile could not be written
     */
    @Test
    public void aResourcePathShapedLikeAURLIsFound(@TempDir final Path tempDir) throws IOException {
        final var jar = writeJar(tempDir.resolve("test.jar"), "ab:c!d/./e.txt");
        try (var scanResult = new ClassGraph().enableClasspathEntries(jar.toString()).scan()) {
            assertFound(scanResult, "ab:c!d/e.txt", "ab:c!d/e.txt");
            assertFound(scanResult, "ab:c!d/./e.txt", "ab:c!d/e.txt");
        }
    }

    /**
     * A resource path whose part before a '!' names a file on the host is looked up the same as any other path.
     *
     * @param tempDir
     *            a temporary directory to write the jarfile and the host file into
     * @throws IOException
     *             if a file could not be written
     */
    @Test
    public void aResourcePathNamingAHostFileBeforeAPlingIsFound(@TempDir final Path tempDir) throws IOException {
        // A resource path containing a Windows drive letter is not an absolute path there, so the host file cannot
        // be named this way
        assumeTrue(File.separatorChar == '/');
        final var hostPath = Files.write(tempDir.resolve("f"), new byte[] { 'P', 'K' }).toString();
        // Stored without any segment to sanitize, so only the lookup has to read the '!' as an ordinary character
        final var stored = hostPath.substring(1) + "!a/b.txt";
        final var jar = writeJar(tempDir.resolve("test.jar"), stored);
        try (var scanResult = new ClassGraph().enableClasspathEntries(jar.toString()).scan()) {
            assertFound(scanResult, stored, stored);
            assertFound(scanResult, hostPath + "!a/./b.txt", stored);
        }
    }
}
