package nonapi.io.github.classgraph.fastzipfilereader;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import nonapi.io.github.classgraph.concurrency.InterruptionChecker;
import nonapi.io.github.classgraph.reflection.ReflectionUtils;
import nonapi.io.github.classgraph.scanspec.ScanSpec;

/** A central directory record with the wrong signature is reported with the signature it has. */
public class CentralDirectorySignatureTest {
    /**
     * A signature with its top bit set is reported in hex as the unsigned value it is, not as a negative number.
     *
     * @param tempDir
     *            the directory to write the jar into
     * @throws Exception
     *             if the jar could not be written
     */
    @Test
    public void aBadSignatureIsReportedAsUnsignedHex(@TempDir final File tempDir) throws Exception {
        final ByteArrayOutputStream zipBytes = new ByteArrayOutputStream();
        try (ZipOutputStream zipOut = new ZipOutputStream(zipBytes)) {
            zipOut.putNextEntry(new ZipEntry("testpkg/entry.txt"));
            zipOut.write("contents".getBytes(StandardCharsets.UTF_8));
            zipOut.closeEntry();
        }
        final byte[] bytes = zipBytes.toByteArray();
        // Change the central directory signature 0x02014b50, stored little-endian as "PK\1\2", to 0x82014b50
        boolean patched = false;
        for (int i = 0; i + 4 <= bytes.length && !patched; i++) {
            if (bytes[i] == 'P' && bytes[i + 1] == 'K' && bytes[i + 2] == 1 && bytes[i + 3] == 2) {
                bytes[i + 3] = (byte) 0x82;
                patched = true;
            }
        }
        final File jarFile = new File(tempDir, "bad-signature.jar");
        try (OutputStream fileOut = new FileOutputStream(jarFile)) {
            fileOut.write(bytes);
        }

        final NestedJarHandler nestedJarHandler = new NestedJarHandler(new ScanSpec(), new InterruptionChecker(),
                new ReflectionUtils());
        try {
            assertThatThrownBy(() -> nestedJarHandler.nestedPathToLogicalZipFileAndPackageRootMap
                    .get(jarFile.getPath(), /* log = */ null))
                            .hasStackTraceContaining("Invalid central directory signature: 0x82014b50");
        } finally {
            // The jarfile must not be left open, otherwise the temporary directory cannot be deleted on Windows
            nestedJarHandler.close(/* log = */ null);
        }
    }
}
