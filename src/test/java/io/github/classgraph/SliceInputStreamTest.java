package io.github.classgraph;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Tests for the {@link InputStream} returned by {@link Resource#open()} for a resource whose bytes are not
 * deflated, i.e. a file in a directory classpath element, or a stored zip entry.
 */
public class SliceInputStreamTest {
    /** A file in the root of the test resources directory. */
    private static final String TEXT_FILE = "file-content-test.txt";

    /** The contents of {@link #TEXT_FILE}. */
    private static final byte[] TEXT_FILE_CONTENT = "File contents".getBytes(StandardCharsets.UTF_8);

    /**
     * Scan the root of the test resources directory, which is a directory classpath element.
     *
     * @return the scan result.
     */
    private static ScanResult scanTestResourcesDir() {
        return new ClassGraph().acceptPathsNonRecursive("").scan();
    }

    /**
     * Get the one resource with the given path from a scan result.
     *
     * @param scanResult
     *            the scan result.
     * @param path
     *            the resource path.
     * @return the resource.
     */
    private static Resource resource(final ScanResult scanResult, final String path) {
        final List<Resource> resources = scanResult.getResourcesWithPath(path);
        assertThat(resources).as("resources with path " + path).hasSize(1);
        return resources.get(0);
    }

    /** The single-byte {@code read()} method returns byte values, not the number of bytes read. */
    @Test
    public void theContentCanBeReadOneByteAtATime() throws IOException {
        try (ScanResult scanResult = scanTestResourcesDir()) {
            final Resource resource = resource(scanResult, TEXT_FILE);
            try (InputStream inputStream = resource.open()) {
                final byte[] read = new byte[TEXT_FILE_CONTENT.length];
                for (int i = 0; i < TEXT_FILE_CONTENT.length; i++) {
                    final int byteVal = inputStream.read();
                    assertThat(byteVal).as("byte " + i).isBetween(0, 255);
                    read[i] = (byte) byteVal;
                }
                assertThat(read).isEqualTo(TEXT_FILE_CONTENT);
                // The stream is now at EOF
                assertThat(inputStream.read()).isEqualTo(-1);
            }
            resource.close();
        }
    }

    /**
     * Closing an {@link InputStream} that has already been closed has no effect, as required by
     * {@link InputStream#close()} -- in particular it does not close the resource a second time, which would close
     * a stream that had since been opened on the same resource.
     */
    @Test
    public void closingAnInputStreamIsIdempotent() throws IOException {
        try (ScanResult scanResult = scanTestResourcesDir()) {
            final Resource resource = resource(scanResult, TEXT_FILE);
            final InputStream inputStream = resource.open();
            inputStream.close();

            // Reopen the same resource, then close the stale stream again -- the second close must be a no-op
            try (InputStream reopened = resource.open()) {
                inputStream.close();
                final byte[] read = new byte[TEXT_FILE_CONTENT.length];
                assertThat(reopened.read(read)).isEqualTo(TEXT_FILE_CONTENT.length);
                assertThat(read).isEqualTo(TEXT_FILE_CONTENT);
            }
            resource.close();
        }
    }
}
