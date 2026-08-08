package nonapi.io.github.classgraph.fastzipfilereader;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.classgraph.ClassGraph;

/**
 * Tests {@code skip()} on the {@link InputStream} returned for a deflated zip
 * entry.
 *
 * <p>
 * The skip loop used to subtract rather than add the number of bytes read, so
 * it kept reading until the end of the stream (skipping everything, not just
 * the requested number of bytes) and returned a negative count.
 */
public class InflaterInputStreamSkipTest {
    /** The number of bytes to skip. */
    private static final int NUM_BYTES_TO_SKIP = 100;

    /**
     * Build a resource whose content is long and repetitive, so that the zip entry
     * is actually deflated.
     *
     * @return the resource content
     */
    private static String makeContent() {
        return "0123456789".repeat(1000);
    }

    /** skip(n) must skip exactly n bytes and return n. */
    @Test
    public void skipSkipsOnlyTheRequestedNumberOfBytes(@TempDir final File tempDir) throws Exception {
        final var content = makeContent();
        final var jarFile = new File(tempDir, "deflated.jar");
        try (var fileOut = new FileOutputStream(jarFile); var zipOut = new ZipOutputStream(fileOut)) {
            final var entry = new ZipEntry("testpkg/deflated.txt");
            entry.setMethod(ZipEntry.DEFLATED);
            zipOut.putNextEntry(entry);
            zipOut.write(content.getBytes(StandardCharsets.UTF_8));
            zipOut.closeEntry();
        }

        try (var scanResult = new ClassGraph().overrideClasspath(jarFile.getPath()).acceptPaths("testpkg").scan()) {
            final var resource = scanResult.getResourcesWithLeafName("deflated.txt").get(0);
            try (var inputStream = resource.open()) {
                assertThat(inputStream.skip(NUM_BYTES_TO_SKIP)).isEqualTo(NUM_BYTES_TO_SKIP);

                final var rest = new ByteArrayOutputStream();
                final var readBuf = new byte[4096];
                for (int bytesRead; (bytesRead = inputStream.read(readBuf, 0, readBuf.length)) > 0;) {
                    rest.write(readBuf, 0, bytesRead);
                }
                assertThat(rest.toString(StandardCharsets.UTF_8)).isEqualTo(content.substring(NUM_BYTES_TO_SKIP));
            }
        }
    }
}
