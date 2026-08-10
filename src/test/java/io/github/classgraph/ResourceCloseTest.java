package io.github.classgraph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.io.IOException;

import org.junit.jupiter.api.Test;

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
}
