package nonapi.io.github.classgraph.fastzipfilereader;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
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
 * Tests that a zip entry whose local header declares an extra field longer than {@link Short#MAX_VALUE} bytes can
 * still be read.
 *
 * <p>
 * The filename length and extra field length in a zip local header are unsigned 16-bit values. They used to be read
 * with {@code readShort()}, which sign-extends, so a length of 32768 or more was read as a negative number, and the
 * computed start position of the entry's data pointed before the local header instead of after it.
 */
public class FastZipEntryLargeExtraFieldTest {
    /** The contents of the test resource. */
    private static final String RESOURCE_CONTENT = "extra-field-test";

    /** The length of the extra field to write, chosen to exceed Short.MAX_VALUE. */
    private static final int EXTRA_FIELD_LEN = 40000;

    /**
     * Build an extra field with a single unknown-tag header block, which {@link ZipEntry#setExtra(byte[])} accepts
     * and passes through unchanged.
     *
     * @return the extra field bytes
     */
    private static byte[] makeExtraField() {
        final byte[] extra = new byte[EXTRA_FIELD_LEN];
        // Header ID 0x9999 (unrecognized), little-endian
        extra[0] = (byte) 0x99;
        extra[1] = (byte) 0x99;
        // Data size = EXTRA_FIELD_LEN - 4, little-endian
        final int dataSize = EXTRA_FIELD_LEN - 4;
        extra[2] = (byte) dataSize;
        extra[3] = (byte) (dataSize >> 8);
        return extra;
    }

    /** A jar entry preceded by a >32KB extra field must still be readable. */
    @Test
    public void entryWithExtraFieldLongerThanShortMaxValue(@TempDir final File tempDir) throws IOException {
        final File jarFile = new File(tempDir, "large-extra-field.jar");
        try (OutputStream fileOut = new FileOutputStream(jarFile);
                ZipOutputStream zipOut = new ZipOutputStream(fileOut)) {
            final ZipEntry entry = new ZipEntry("testpkg/resource.txt");
            entry.setExtra(makeExtraField());
            zipOut.putNextEntry(entry);
            zipOut.write(RESOURCE_CONTENT.getBytes(StandardCharsets.UTF_8));
            zipOut.closeEntry();
        }

        try (ScanResult scanResult = new ClassGraph().overrideClasspath(jarFile.getPath()).acceptPaths("testpkg")
                .scan()) {
            final Resource resource = scanResult.getResourcesWithLeafName("resource.txt").get(0);
            assertThat(new String(resource.load(), StandardCharsets.UTF_8)).isEqualTo(RESOURCE_CONTENT);
        }
    }
}
