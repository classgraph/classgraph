package nonapi.io.github.classgraph.fastzipfilereader;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.Resource;
import io.github.classgraph.ScanResult;

/**
 * Tests {@code skip()} on the {@link InputStream} returned for a deflated zip entry.
 *
 * <p>
 * The skip loop used to subtract rather than add the number of bytes read, so it kept reading until the end of the
 * stream (skipping everything, not just the requested number of bytes) and returned a negative count.
 */
public class InflaterInputStreamSkipTest {
    /** The number of bytes to skip. */
    private static final int NUM_BYTES_TO_SKIP = 100;

    /**
     * Build a resource whose content is long and repetitive, so that the zip entry is actually deflated.
     *
     * @return the resource content
     */
    private static String makeContent() {
        final StringBuilder buf = new StringBuilder();
        for (int i = 0; i < 1000; i++) {
            buf.append("0123456789");
        }
        return buf.toString();
    }

    /** skip(n) must skip exactly n bytes and return n. */
    @Test
    public void skipSkipsOnlyTheRequestedNumberOfBytes(@TempDir final File tempDir) throws Exception {
        final String content = makeContent();
        final File jarFile = new File(tempDir, "deflated.jar");
        try (OutputStream fileOut = new FileOutputStream(jarFile);
                ZipOutputStream zipOut = new ZipOutputStream(fileOut)) {
            final ZipEntry entry = new ZipEntry("testpkg/deflated.txt");
            entry.setMethod(ZipEntry.DEFLATED);
            zipOut.putNextEntry(entry);
            zipOut.write(content.getBytes(StandardCharsets.UTF_8));
            zipOut.closeEntry();
        }

        try (ScanResult scanResult = new ClassGraph().overrideClasspath(jarFile.getPath()).acceptPaths("testpkg")
                .scan()) {
            final Resource resource = scanResult.getResourcesWithLeafName("deflated.txt").get(0);
            try (InputStream inputStream = resource.open()) {
                assertThat(inputStream.skip(NUM_BYTES_TO_SKIP)).isEqualTo(NUM_BYTES_TO_SKIP);

                final ByteArrayOutputStream rest = new ByteArrayOutputStream();
                final byte[] readBuf = new byte[4096];
                for (int bytesRead; (bytesRead = inputStream.read(readBuf, 0, readBuf.length)) > 0;) {
                    rest.write(readBuf, 0, bytesRead);
                }
                assertThat(rest.toString("UTF-8")).isEqualTo(content.substring(NUM_BYTES_TO_SKIP));
            }
        }
    }
}
