package io.github.classgraph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Tests for closing a {@link Resource}. */
public class ResourceCloseTest {
    /** A file in the root of the test resources directory. */
    private static final String TEXT_FILE = "file-content-test.txt";

    /**
     * Closing a resource after the {@link ScanResult} it came from has been closed is a no-op. A resource found by
     * {@link ScanResult#getResourcesWithPathIgnoringAccept(String)} need not be an accepted resource, so it is not
     * one of the resources that the {@link ScanResult} closes, and it is still open when the scan result closes.
     *
     * @throws IOException
     *             if the resource could not be opened
     */
    @Test
    public void closingAResourceAfterTheScanResultIsClosedIsANoOp() throws IOException {
        final Resource resource;
        try (ScanResult scanResult = new ClassGraph().acceptPathsNonRecursive("").scan()) {
            final ResourceList resources = scanResult.getResourcesWithPathIgnoringAccept(TEXT_FILE);
            assertThat(resources).as("resources with path " + TEXT_FILE).hasSize(1);
            resource = resources.get(0);
            resource.open();
        }
        assertThatCode(resource::close).doesNotThrowAnyException();
    }

    /**
     * A resource whose {@link ScanResult} has been closed reports that the same way every time, rather than
     * reporting it once and then reporting that the resource is already open, since a check that fails has to
     * leave the resource closed.
     */
    @Test
    public void aResourceOfAClosedScanResultFailsTheSameWayEveryTime() {
        final Resource resource;
        try (ScanResult scanResult = new ClassGraph().acceptPathsNonRecursive("").scan()) {
            final ResourceList resources = scanResult.getResourcesWithPathIgnoringAccept(TEXT_FILE);
            assertThat(resources).as("resources with path " + TEXT_FILE).hasSize(1);
            resource = resources.get(0);
        }
        for (int attempt = 0; attempt < 2; attempt++) {
            assertThatThrownBy(resource::open).as("open").isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("ScanResult is closed");
            assertThatThrownBy(resource::read).as("read").isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("ScanResult is closed");
            assertThatThrownBy(resource::load).as("load").isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("ScanResult is closed");
        }
    }

    /**
     * A resource that could not be opened is left closed, rather than being left marked as open, so that opening it
     * can be tried again, and so that anything acquired for the failed attempt is released.
     *
     * @param tempDir
     *            a temporary directory to write the test file to
     * @throws IOException
     *             if the test file cannot be written or deleted
     */
    @Test
    public void aResourceThatCouldNotBeOpenedIsLeftClosed(@TempDir final Path tempDir) throws IOException {
        final Path file = Files.write(tempDir.resolve("deleted.txt"),
                "File contents".getBytes(StandardCharsets.UTF_8));
        try (ScanResult scanResult = new ClassGraph().acceptPathsNonRecursive("").overrideClasspath(tempDir)
                .scan()) {
            final ResourceList resources = scanResult.getResourcesWithPath("deleted.txt");
            assertThat(resources).hasSize(1);
            final Resource resource = resources.get(0);
            Files.delete(file);
            // The file is gone, so every attempt to read the resource fails with an IOException, and each attempt
            // fails the same way -- a resource left marked as open by the first attempt would make the second one
            // throw IllegalStateException instead
            for (int attempt = 0; attempt < 2; attempt++) {
                assertThatThrownBy(resource::open).as("open").isInstanceOf(IOException.class);
                assertThatThrownBy(resource::read).as("read").isInstanceOf(IOException.class);
                assertThatThrownBy(resource::load).as("load").isInstanceOf(IOException.class);
            }
        }
    }
}
