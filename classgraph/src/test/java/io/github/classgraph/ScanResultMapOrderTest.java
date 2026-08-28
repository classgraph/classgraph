package io.github.classgraph;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The maps returned by {@link ScanResult} are sorted by key, since they are built from hash maps that the scanning
 * threads fill in whatever order they finish in, which would otherwise make iteration order differ between scans of
 * the same classpath.
 */
public class ScanResultMapOrderTest {
    /** The directory of files that {@link #getAllResourcesAsMap()} scans. */
    private static Path fixtureDir;

    /**
     * Write the fixture files, in an order that is not their sorted order.
     *
     * @param tempDir
     *            the temporary directory to write into.
     * @throws IOException
     *             if a file could not be written.
     */
    @BeforeAll
    public static void writeFixtureFiles(@TempDir final Path tempDir) throws IOException {
        fixtureDir = Files.createDirectory(tempDir.resolve("fixture"));
        for (final var fileName : List.of("c.txt", "a.txt", "b.txt")) {
            Files.writeString(fixtureDir.resolve(fileName), fileName);
        }
    }

    /** The map from class name to {@link ClassInfo} is sorted by class name. */
    @Test
    public void getAllClassesAsMap() {
        try (var scanResult = new ClassGraph().enableClasspath().acceptPackages("io.github.classgraph")
                .enableClassInfo().scan()) {
            final var classNameToClassInfo = scanResult.getAllClassesAsMap();
            assertThat(classNameToClassInfo).isNotEmpty();
            assertThat(List.copyOf(classNameToClassInfo.keySet())).isSortedAccordingTo(Comparator.naturalOrder());
        }
    }

    /** The map from resource path to {@link ResourceList} is sorted by resource path. */
    @Test
    public void getAllResourcesAsMap() {
        try (var scanResult = new ClassGraph().enableClasspathEntries(fixtureDir.toString()).scan()) {
            assertThat(scanResult.getAllResourcesAsMap().keySet()).containsExactly("a.txt", "b.txt", "c.txt");
        }
    }
}
