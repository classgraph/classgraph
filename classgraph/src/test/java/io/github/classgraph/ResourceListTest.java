package io.github.classgraph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIOException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link ResourceList} is the list of {@link Resource} objects returned by a scan, and provides the bulk content
 * accessors that read every resource in the list in turn.
 */
public class ResourceListTest {
    /** The names of the files in the fixture directory, and their content. */
    private static final List<String> FILE_NAMES = List.of("a.txt", "b.txt", "c.txt");

    /** The directory of files that the tests scan. */
    private static Path fixtureDir;

    /** A second directory, containing a file whose path collides with one in {@link #fixtureDir}. */
    private static Path collidingDir;

    /**
     * Write the fixture files. The content of each file is its name without the extension, repeated as many times
     * as the file's position in {@link #FILE_NAMES}, so that content and length differ between files.
     *
     * @param tempDir
     *            the temporary directory to write into.
     * @throws IOException
     *             if a file could not be written.
     */
    @BeforeAll
    public static void writeFixtureFiles(@TempDir final Path tempDir) throws IOException {
        fixtureDir = Files.createDirectory(tempDir.resolve("fixture"));
        for (var i = 0; i < FILE_NAMES.size(); i++) {
            Files.writeString(fixtureDir.resolve(FILE_NAMES.get(i)), contentOf(FILE_NAMES.get(i)));
        }
        collidingDir = Files.createDirectory(tempDir.resolve("colliding"));
        Files.writeString(collidingDir.resolve("a.txt"), "a different a.txt");
    }

    /**
     * The expected content of a fixture file.
     *
     * @param fileName
     *            the file name.
     * @return the file's content.
     */
    private static String contentOf(final String fileName) {
        final var letter = fileName.substring(0, 1);
        return letter.repeat(FILE_NAMES.indexOf(fileName) + 1);
    }

    /**
     * Scan the fixture directory.
     *
     * @return the scan result.
     */
    private static ScanResult scanFixtureDir() {
        return new ClassGraph().overrideClasspath(fixtureDir.toString()).scan();
    }

    /** A {@link ResourceList} can be built from an existing collection of resources. */
    @Test
    public void aResourceListCanBeBuiltFromACollection() {
        try (var scanResult = scanFixtureDir()) {
            final var allResources = scanResult.getAllResources();
            assertThat(allResources.getPaths()).containsExactlyInAnyOrderElementsOf(FILE_NAMES);

            final var copy = new ResourceList(allResources);
            assertThat(copy).containsExactlyElementsOf(allResources);

            // Unlike the list returned by a scan, a list built this way is modifiable
            copy.clear();
            assertThat(copy).isEmpty();
            assertThat(new ResourceList(4)).isEmpty();
        }
    }

    /** Each resource is passed to the consumer as a byte array, and closed afterwards. */
    @Test
    public void everyResourceIsPassedToTheConsumerAsAByteArray() throws IOException {
        try (var scanResult = scanFixtureDir()) {
            final var resources = scanResult.getAllResources();
            final var content = new TreeMap<String, String>();
            assertThat(resources.forEachByteArray((resource, byteArray) -> content.put(resource.getPath(),
                    new String(byteArray, StandardCharsets.UTF_8)))).isSameAs(resources);
            assertThat(content).containsOnlyKeys(FILE_NAMES).containsEntry("b.txt", contentOf("b.txt"));

            // Without the "IgnoringIOException" suffix, an IOException from the consumer propagates
            assertThatIOException().isThrownBy(() -> resources.forEachByteArray((resource, byteArray) -> {
                throw new IOException("consumer failed on " + resource.getPath());
            })).withMessageStartingWith("consumer failed on ");

            // With it, the failing resource is skipped and the iteration continues
            final var visited = new ArrayList<String>();
            assertThat(resources.forEachByteArrayIgnoringIOException((resource, byteArray) -> {
                if (resource.getPath().equals("b.txt")) {
                    throw new IOException("consumer failed on " + resource.getPath());
                }
                visited.add(resource.getPath());
            })).isSameAs(resources);
            assertThat(visited).containsExactlyInAnyOrder("a.txt", "c.txt");
        }
    }

    /** Each resource is passed to the consumer as an {@link java.io.InputStream}, and closed afterwards. */
    @Test
    public void everyResourceIsPassedToTheConsumerAsAnInputStream() throws IOException {
        try (var scanResult = scanFixtureDir()) {
            final var resources = scanResult.getAllResources();
            final var content = new TreeMap<String, String>();
            assertThat(resources.forEachInputStream((resource, inputStream) -> content.put(resource.getPath(),
                    new String(inputStream.readAllBytes(), StandardCharsets.UTF_8)))).isSameAs(resources);
            assertThat(content).containsOnlyKeys(FILE_NAMES).containsEntry("c.txt", contentOf("c.txt"));

            assertThatIOException().isThrownBy(() -> resources.forEachInputStream((resource, inputStream) -> {
                throw new IOException("consumer failed on " + resource.getPath());
            })).withMessageStartingWith("consumer failed on ");

            final var visited = new ArrayList<String>();
            assertThat(resources.forEachInputStreamIgnoringIOException((resource, inputStream) -> {
                if (resource.getPath().equals("b.txt")) {
                    throw new IOException("consumer failed on " + resource.getPath());
                }
                visited.add(resource.getPath());
            })).isSameAs(resources);
            assertThat(visited).containsExactlyInAnyOrder("a.txt", "c.txt");
        }
    }

    /** Each resource is passed to the consumer as a {@link java.nio.ByteBuffer}, and released afterwards. */
    @Test
    public void everyResourceIsPassedToTheConsumerAsAByteBuffer() throws IOException {
        try (var scanResult = scanFixtureDir()) {
            final var resources = scanResult.getAllResources();
            final var lengths = new TreeMap<String, Integer>();
            assertThat(resources.forEachByteBuffer(
                    (resource, byteBuffer) -> lengths.put(resource.getPath(), byteBuffer.remaining())))
                    .isSameAs(resources);
            assertThat(lengths).containsOnlyKeys(FILE_NAMES).containsEntry("c.txt", contentOf("c.txt").length());

            assertThatIOException().isThrownBy(() -> resources.forEachByteBuffer((resource, byteBuffer) -> {
                throw new IOException("consumer failed on " + resource.getPath());
            })).withMessageStartingWith("consumer failed on ");

            final var visited = new ArrayList<String>();
            assertThat(resources.forEachByteBufferIgnoringIOException((resource, byteBuffer) -> {
                if (resource.getPath().equals("b.txt")) {
                    throw new IOException("consumer failed on " + resource.getPath());
                }
                visited.add(resource.getPath());
            })).isSameAs(resources);
            assertThat(visited).containsExactlyInAnyOrder("a.txt", "c.txt");
        }
    }

    /** Closing a {@link ResourceList} closes every resource in it, and a closed resource can be reopened. */
    @Test
    public void closingTheListClosesEveryResource() throws IOException {
        try (var scanResult = scanFixtureDir()) {
            final var resources = scanResult.getAllResources();
            for (final var resource : resources) {
                resource.open();
            }
            resources.close();

            // Closing a resource releases its InputStream, but the resource can still be read again afterwards
            assertThat(resources.get("a.txt").get(0).loadAsString()).isEqualTo(contentOf("a.txt"));
            resources.close();
        }
    }

    /** Two classpath elements that contain the same resource path produce a duplicate path entry. */
    @Test
    public void resourcesWithTheSamePathInDifferentClasspathElementsAreDuplicates() {
        try (var scanResult = new ClassGraph().overrideClasspath(fixtureDir.toString(), collidingDir.toString())
                .scan()) {
            final var duplicatePaths = scanResult.getAllResources().findDuplicatePaths();
            assertThat(duplicatePaths).hasSize(1);
            assertThat(duplicatePaths.get(0).getKey()).isEqualTo("a.txt");
            assertThat(duplicatePaths.get(0).getValue()).hasSize(2);

            // Every path is a key of the map form, whether or not it is duplicated
            assertThat(scanResult.getAllResources().asMap()).containsOnlyKeys(FILE_NAMES);
        }
    }
}
