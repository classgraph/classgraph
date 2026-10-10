package io.github.classgraph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A '!' in a zip entry name or a resource path is an ordinary character. It is a nested jar separator only in a
 * path on the host, where it follows the path of a jarfile, and an entry name is a name within an archive, not a
 * path on the host. So the path a resource is stored under, and the path it is looked up by, must not depend on
 * what files exist on the host, and reading them must not test the host filesystem at all.
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
        try (JarOutputStream jout = new JarOutputStream(Files.newOutputStream(jar))) {
            for (final String name : names) {
                jout.putNextEntry(new JarEntry(name));
                jout.closeEntry();
            }
        }
        return jar;
    }

    /**
     * Scan a jarfile.
     *
     * @param jar
     *            the jarfile
     * @return the scan result
     */
    private static ScanResult scan(final Path jar) {
        return new ClassGraph().overrideClasspath(jar.toString()).scan();
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
     * An entry name shaped like a URL is not a URL, so the '!' in it does not separate a jarfile from an entry.
     *
     * @param tempDir
     *            a temporary directory to write the jarfile into
     * @throws IOException
     *             if the jarfile could not be written
     */
    @Test
    public void aResourcePathShapedLikeAURLIsFound(@TempDir final Path tempDir) throws IOException {
        final Path jar = writeJar(tempDir.resolve("test.jar"), "ab:c!d/./e.txt");
        try (ScanResult scanResult = scan(jar)) {
            assertThat(scanResult.getAllResources().getPaths()).containsExactly("ab:c!d/e.txt");
            assertFound(scanResult, "ab:c!d/e.txt", "ab:c!d/e.txt");
            assertFound(scanResult, "ab:c!d/./e.txt", "ab:c!d/e.txt");
        }
    }

    /**
     * An entry name or a resource path whose part before a '!' names a file on the host is read the same as any
     * other.
     *
     * @param tempDir
     *            a temporary directory to write the jarfile and the host file into
     * @throws IOException
     *             if a file could not be written
     */
    @Test
    public void aResourcePathNamingAHostFileBeforeAPlingIsFound(@TempDir final Path tempDir) throws IOException {
        // A path containing a Windows drive letter is not an absolute path within a jar, so the host file cannot
        // be named this way
        assumeTrue(File.separatorChar == '/');
        final String hostPath = Files.write(tempDir.resolve("f"), new byte[] { 'P', 'K' }).toString();
        final String stored = hostPath.substring(1) + "!a/b.txt";
        final Path jar = writeJar(tempDir.resolve("test.jar"), hostPath + "!a/./b.txt");
        try (ScanResult scanResult = scan(jar)) {
            assertThat(scanResult.getAllResources().getPaths()).containsExactly(stored);
            assertFound(scanResult, stored, stored);
            assertFound(scanResult, hostPath + "!a/./b.txt", stored);
        }
    }

    /**
     * Reading an entry name takes time in proportion to its length, however many '!' characters it holds.
     *
     * @param tempDir
     *            a temporary directory to write the jarfile into
     * @throws IOException
     *             if the jarfile could not be written
     */
    @Test
    public void manyPlingsInEntryNamesDoNotSlowScanning(@TempDir final Path tempDir) throws IOException {
        final char[] plings = new char[65000];
        Arrays.fill(plings, '!');
        final String[] names = new String[20];
        for (int i = 0; i < names.length; i++) {
            names[i] = new String(plings) + i;
        }
        final Path jar = writeJar(tempDir.resolve("test.jar"), names);
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            try (ScanResult scanResult = scan(jar)) {
                assertThat(scanResult.getAllResources()).hasSize(20);
            }
        });
    }

    /**
     * A '!' in a {@code Bundle-ClassPath} manifest entry is an ordinary character, since the entry is a path within
     * the jarfile, not a path on the host. A path shaped like a URL is not read as one.
     *
     * @param tempDir
     *            a temporary directory to write the jarfiles into
     * @throws IOException
     *             if a jarfile could not be written
     */
    @Test
    public void aPlingInABundleClassPathManifestEntryIsAnOrdinaryCharacter(@TempDir final Path tempDir)
            throws IOException {
        final byte[] innerJar = Files.readAllBytes(writeJar(tempDir.resolve("inner.jar"), "res.txt"));
        final Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(new Attributes.Name("Bundle-ClassPath"), "ab:c!d/./inner.jar");
        final Path jar = tempDir.resolve("bundle.jar");
        try (OutputStream outputStream = Files.newOutputStream(jar);
                JarOutputStream jout = new JarOutputStream(outputStream, manifest)) {
            jout.putNextEntry(new JarEntry("ab:c!d/inner.jar"));
            jout.write(innerJar);
            jout.closeEntry();
        }
        try (ScanResult scanResult = scan(jar)) {
            assertThat(scanResult.getResourcesWithPath("res.txt").getPaths()).containsExactly("res.txt");
        }
    }
}
