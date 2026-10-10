package io.github.classgraph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIOException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.TreeMap;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

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
        return new ClassGraph().enableClasspathEntries(fixtureDir.toString()).scan();
    }

    /** A {@link ResourceList} can be built from an existing collection of resources. */
    @Test
    public void aResourceListCanBeBuiltFromACollection() {
        try (var scanResult = scanFixtureDir()) {
            final var allResources = scanResult.getAllResources();
            assertThat(allResources.getPaths()).containsExactlyInAnyOrderElementsOf(FILE_NAMES);

            final var copy = new ResourceList(allResources);
            assertThat(copy).containsExactlyElementsOf(allResources);

            // Like every other list the API hands out, a list built this way is unmodifiable
            assertThatExceptionOfType(UnsupportedOperationException.class).isThrownBy(copy::clear);
        }
    }

    /** Each resource is passed to the consumer as a byte array, after it has been loaded and closed. */
    @Test
    public void everyResourceIsPassedToTheConsumerAsAByteArray() throws IOException {
        try (var scanResult = scanFixtureDir()) {
            final var resources = scanResult.getAllResources();
            final var content = new TreeMap<String, String>();
            assertThat(resources.forEachByteArray((resource, byteArray) -> content.put(resource.getPath(),
                    new String(byteArray, StandardCharsets.UTF_8)))).isSameAs(resources);
            assertThat(content).containsOnlyKeys(FILE_NAMES).containsEntry("b.txt", contentOf("b.txt"));

            // Without the "IgnoringIOException" suffix, an IOException from the consumer propagates
            assertThatIOException().isThrownBy(() -> resources.forEachByteArray((resource, _) -> {
                throw new IOException("consumer failed on " + resource.getPath());
            })).withMessageStartingWith("consumer failed on ");

            // With it, the failing resource is skipped and the iteration continues
            final var visited = new ArrayList<String>();
            assertThat(resources.forEachByteArrayIgnoringIOException((resource, _) -> {
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

            assertThatIOException().isThrownBy(() -> resources.forEachInputStream((resource, _) -> {
                throw new IOException("consumer failed on " + resource.getPath());
            })).withMessageStartingWith("consumer failed on ");

            final var visited = new ArrayList<String>();
            assertThat(resources.forEachInputStreamIgnoringIOException((resource, _) -> {
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

            assertThatIOException().isThrownBy(() -> resources.forEachByteBuffer((resource, _) -> {
                throw new IOException("consumer failed on " + resource.getPath());
            })).withMessageStartingWith("consumer failed on ");

            final var visited = new ArrayList<String>();
            assertThat(resources.forEachByteBufferIgnoringIOException((resource, _) -> {
                if (resource.getPath().equals("b.txt")) {
                    throw new IOException("consumer failed on " + resource.getPath());
                }
                visited.add(resource.getPath());
            })).isSameAs(resources);
            assertThat(visited).containsExactlyInAnyOrder("a.txt", "c.txt");
        }
    }

    /** A call of one of the {@code forEach*} helpers on a {@link ResourceList}. */
    @FunctionalInterface
    private interface ForEachCall {
        /**
         * Run the helper on the list, with a consumer that does nothing.
         *
         * @param resources
         *            the list to run the helper on.
         * @throws IOException
         *             if the helper throws it.
         */
        void run(ResourceList resources) throws IOException;
    }

    /** The {@code forEach*} helpers, each called with a consumer that does nothing. */
    enum ForEachHelper {
        /** {@link ResourceList#forEachByteArray(ResourceList.ByteArrayConsumer)}. */
        BYTE_ARRAY(resources -> resources.forEachByteArray((_, _) -> {
        })),
        /** {@link ResourceList#forEachByteArrayIgnoringIOException(ResourceList.ByteArrayConsumer)}. */
        BYTE_ARRAY_IGNORING_IO_EXCEPTION(resources -> resources.forEachByteArrayIgnoringIOException((_, _) -> {
        })),
        /** {@link ResourceList#forEachInputStream(ResourceList.InputStreamConsumer)}. */
        INPUT_STREAM(resources -> resources.forEachInputStream((_, _) -> {
        })),
        /** {@link ResourceList#forEachInputStreamIgnoringIOException(ResourceList.InputStreamConsumer)}. */
        INPUT_STREAM_IGNORING_IO_EXCEPTION(resources -> resources.forEachInputStreamIgnoringIOException((_, _) -> {
        })),
        /** {@link ResourceList#forEachByteBuffer(ResourceList.ByteBufferConsumer)}. */
        BYTE_BUFFER(resources -> resources.forEachByteBuffer((_, _) -> {
        })),
        /** {@link ResourceList#forEachByteBufferIgnoringIOException(ResourceList.ByteBufferConsumer)}. */
        BYTE_BUFFER_IGNORING_IO_EXCEPTION(resources -> resources.forEachByteBufferIgnoringIOException((_, _) -> {
        }));

        /** The call of the helper. */
        private final ForEachCall call;

        /**
         * Constructor.
         *
         * @param call
         *            the call of the helper.
         */
        ForEachHelper(final ForEachCall call) {
            this.call = call;
        }
    }

    /**
     * A helper that cannot open a resource because the caller already has it open as an {@link java.io.InputStream}
     * throws, and leaves the caller's stream open.
     *
     * @param helper
     *            the helper to run.
     * @throws IOException
     *             if the resource could not be opened or read.
     */
    @ParameterizedTest
    @EnumSource(ForEachHelper.class)
    public void aHelperDoesNotCloseAStreamTheCallerHasOpen(final ForEachHelper helper) throws IOException {
        try (var scanResult = scanFixtureDir()) {
            final var resource = scanResult.getAllResources().get("c.txt").get(0);
            try (var heldStream = resource.open()) {
                assertThatIllegalStateException()
                        .isThrownBy(() -> helper.call.run(new ResourceList(List.of(resource))))
                        .withMessageStartingWith("Resource is already open");
                // The caller's stream is still open, and the resource still counts as open
                assertThat(new String(heldStream.readAllBytes(), StandardCharsets.UTF_8))
                        .isEqualTo(contentOf("c.txt"));
                assertThatIllegalStateException().isThrownBy(resource::load);
            }
        }
    }

    /**
     * A helper that cannot open a resource because the caller already has it open as a buffer throws, and leaves
     * the caller's buffer open.
     *
     * @param helper
     *            the helper to run.
     * @throws IOException
     *             if the resource could not be read.
     */
    @ParameterizedTest
    @EnumSource(ForEachHelper.class)
    public void aHelperDoesNotReleaseABufferTheCallerHasOpen(final ForEachHelper helper) throws IOException {
        try (var scanResult = scanFixtureDir()) {
            final var resource = scanResult.getAllResources().get("c.txt").get(0);
            try (var heldBuffer = resource.read()) {
                assertThatIllegalStateException()
                        .isThrownBy(() -> helper.call.run(new ResourceList(List.of(resource))))
                        .withMessageStartingWith("Resource is already open");
                // The caller's buffer has not been released, and the resource still counts as open
                final var byteBuffer = heldBuffer.getByteBuffer();
                assertThat(byteBuffer).isNotNull();
                assertThat(Objects.requireNonNull(byteBuffer).remaining()).isEqualTo(contentOf("c.txt").length());
                assertThatIllegalStateException().isThrownBy(resource::load);
            }
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
        try (var scanResult = new ClassGraph()
                .enableClasspathEntries(fixtureDir.toString(), collidingDir.toString()).scan()) {
            final var duplicatePaths = scanResult.getAllResources().findDuplicatePaths();
            assertThat(duplicatePaths).hasSize(1);
            assertThat(duplicatePaths.get(0).getKey()).isEqualTo("a.txt");
            assertThat(duplicatePaths.get(0).getValue()).hasSize(2);
            // The entries are unmodifiable, like the list that holds them
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> duplicatePaths.get(0).setValue(ResourceList.emptyList()));

            // Every path is a key of the map form, whether or not it is duplicated
            assertThat(scanResult.getAllResources().asMap()).containsOnlyKeys(FILE_NAMES);
        }
    }

    /**
     * {@link ResourceList#get(String)} normalizes the path the same way as
     * {@link ScanResult#getResourcesWithPath(String)} and {@link ScanResult#getResourcesWithPathIgnoringAccept}, so
     * all three find a resource from the same loosely written path.
     */
    @Test
    public void getNormalizesThePathLikeTheScanResultLookups() {
        try (var scanResult = scanFixtureDir()) {
            final var expected = scanResult.getAllResources().get("a.txt");
            assertThat(expected).hasSize(1);
            for (final var path : List.of("a.txt", "/a.txt", "a.txt/", "//a.txt", "./a.txt", "x/../a.txt",
                    "/./x//../a.txt/")) {
                assertThat(scanResult.getAllResources().get(path)).as(path).isEqualTo(expected);
                assertThat(scanResult.getResourcesWithPath(path)).as(path).isEqualTo(expected);
                assertThat(scanResult.getResourcesWithPathIgnoringAccept(path)).as(path).isEqualTo(expected);
            }
            // A path that normalizes to the package root names no resource
            assertThat(scanResult.getAllResources().get("/")).isEmpty();
        }
    }

    /**
     * A '!' in a resource path is an ordinary character to every path lookup, so a path that starts with what looks
     * like a URL scheme and contains "!!" is not read as a URL with a nested jar separator. (':' is not a legal
     * filename character on Windows.)
     *
     * @param tempDir
     *            the temporary directory to write the fixture into.
     * @throws IOException
     *             if the fixture could not be written.
     */
    @Test
    @DisabledOnOs(OS.WINDOWS)
    public void aBangInAResourcePathIsAnOrdinaryCharacter(@TempDir final Path tempDir) throws IOException {
        Files.writeString(tempDir.resolve("foo:a!!b.txt"), "x");
        try (var scanResult = new ClassGraph().enableClasspathEntries(tempDir.toString()).scan()) {
            assertThat(scanResult.getAllResources().get("foo:a!!b.txt").getPaths()).containsExactly("foo:a!!b.txt");
            assertThat(scanResult.getResourcesWithPathIgnoringAccept("/foo:a!!b.txt").getPaths())
                    .containsExactly("foo:a!!b.txt");
            // The first three calls search the list, and later calls use the map built by getAllResourcesAsMap
            for (var i = 0; i < 5; i++) {
                assertThat(scanResult.getResourcesWithPath("/foo:a!!b.txt").getPaths()).as("call %d", i)
                        .containsExactly("foo:a!!b.txt");
            }
        }
    }

    /**
     * {@link ScanResult#getAllResourcesAsMap()} is the {@link ResourceList#asMap()} form of
     * {@link ScanResult#getAllResources()}, built once and then returned from every call.
     */
    @Test
    public void getAllResourcesAsMapIsTheMapFormOfGetAllResources() {
        try (var scanResult = scanFixtureDir()) {
            final var map = scanResult.getAllResourcesAsMap();
            assertThat(map).isEqualTo(scanResult.getAllResources().asMap());
            assertThat(map).containsOnlyKeys(FILE_NAMES);
            assertThat(scanResult.getAllResourcesAsMap()).isSameAs(map);
        }
    }
}
