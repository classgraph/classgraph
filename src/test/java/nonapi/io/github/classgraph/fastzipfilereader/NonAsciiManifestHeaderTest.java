package nonapi.io.github.classgraph.fastzipfilereader;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map.Entry;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import nonapi.io.github.classgraph.concurrency.InterruptionChecker;
import nonapi.io.github.classgraph.reflection.ReflectionUtils;
import nonapi.io.github.classgraph.scanspec.ScanSpec;

/**
 * Tests that a manifest header name containing a byte outside the ASCII range does not prevent the jarfile from
 * being read. Manifest header names are matched case-insensitively through a 256-entry lookup table, which used to
 * be indexed with the raw signed byte, so a byte greater than 0x7f was negative and threw
 * {@link ArrayIndexOutOfBoundsException}, making the whole jarfile unreadable.
 */
public class NonAsciiManifestHeaderTest {
    /** The Class-Path value written to the test manifest. */
    private static final String CLASS_PATH = "lib/dep.jar";

    /**
     * Build a jar whose manifest has a header name holding a byte outside the ASCII range, followed by a
     * {@code Class-Path} header, and read its manifest values.
     *
     * @param tempDir
     *            a temporary directory to write the jar into
     * @return the logical zipfile for the jar
     * @throws Exception
     *             if the jar could not be written or read
     */
    private static LogicalZipFile readManifestOfTestJar(final File tempDir) throws Exception {
        final File jarFile = new File(tempDir, "non-ascii-manifest-header.jar");
        try (OutputStream fileOut = new FileOutputStream(jarFile);
                ZipOutputStream zipOut = new ZipOutputStream(fileOut)) {
            zipOut.putNextEntry(new ZipEntry("META-INF/MANIFEST.MF"));
            final ByteArrayOutputStream manifest = new ByteArrayOutputStream();
            manifest.write("Manifest-Version: 1.0\r\n".getBytes(StandardCharsets.UTF_8));
            // A header name that is the same length as "Class-Path", so that its bytes are compared against that
            // key one by one, but whose last byte is outside the ASCII range
            manifest.write("Class-Pat".getBytes(StandardCharsets.UTF_8));
            manifest.write(0xc3);
            manifest.write(": value\r\n".getBytes(StandardCharsets.UTF_8));
            manifest.write(("Class-Path: " + CLASS_PATH + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            zipOut.write(manifest.toByteArray());
            zipOut.closeEntry();
        }

        final NestedJarHandler nestedJarHandler = new NestedJarHandler(new ScanSpec(), new InterruptionChecker(),
                new ReflectionUtils());
        try {
            final Entry<LogicalZipFile, String> logicalZipFileAndPackageRoot = nestedJarHandler.nestedPathToLogicalZipFileAndPackageRootMap
                    .get(jarFile.getPath(), /* log = */ null);
            return logicalZipFileAndPackageRoot.getKey();
        } finally {
            // The manifest values have already been read into fields of the LogicalZipFile, so the zipfile can be
            // closed. (The jarfile must not be left open, otherwise the temporary directory cannot be deleted on
            // Windows.)
            nestedJarHandler.close(/* log = */ null);
        }
    }

    /** A non-ASCII byte in a manifest header name must be skipped over, not throw. */
    @Test
    public void nonAsciiManifestHeaderNameIsSkipped(@TempDir final File tempDir) throws Exception {
        final LogicalZipFile logicalZipFile = readManifestOfTestJar(tempDir);
        assertThat(logicalZipFile.classPathManifestEntryValue).isEqualTo(CLASS_PATH);
    }
}
