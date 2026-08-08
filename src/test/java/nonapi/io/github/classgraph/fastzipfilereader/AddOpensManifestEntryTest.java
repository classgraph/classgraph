package nonapi.io.github.classgraph.fastzipfilereader;

import static org.assertj.core.api.Assertions.assertThat;

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
 * Tests that the {@code Add-Exports} and {@code Add-Opens} manifest entries are each parsed into their own field.
 * The {@code Add-Opens} value used to be stored in {@code addExportsManifestEntryValue}, so it overwrote any
 * {@code Add-Exports} value, and {@code addOpensManifestEntryValue} was always null.
 */
public class AddOpensManifestEntryTest {
    /** The Add-Exports value written to the test manifest. */
    private static final String ADD_EXPORTS = "java.base/jdk.internal.misc";

    /** The Add-Opens value written to the test manifest. */
    private static final String ADD_OPENS = "java.base/java.lang";

    /**
     * Build a jar whose manifest declares both Add-Exports and Add-Opens, and read its manifest values.
     *
     * @param tempDir
     *            a temporary directory to write the jar into
     * @return the logical zipfile for the jar
     * @throws Exception
     *             if the jar could not be written or read
     */
    private static LogicalZipFile readManifestOfTestJar(final File tempDir) throws Exception {
        final File jarFile = new File(tempDir, "add-opens.jar");
        try (OutputStream fileOut = new FileOutputStream(jarFile);
                ZipOutputStream zipOut = new ZipOutputStream(fileOut)) {
            zipOut.putNextEntry(new ZipEntry("META-INF/MANIFEST.MF"));
            zipOut.write(("Manifest-Version: 1.0\r\n" //
                    + "Add-Exports: " + ADD_EXPORTS + "\r\n" //
                    + "Add-Opens: " + ADD_OPENS + "\r\n" //
                    + "\r\n").getBytes(StandardCharsets.UTF_8));
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

    /** Add-Exports and Add-Opens must not overwrite each other. */
    @Test
    public void addExportsAndAddOpensAreParsedSeparately(@TempDir final File tempDir) throws Exception {
        final LogicalZipFile logicalZipFile = readManifestOfTestJar(tempDir);
        assertThat(logicalZipFile.addExportsManifestEntryValue).isEqualTo(ADD_EXPORTS);
        assertThat(logicalZipFile.addOpensManifestEntryValue).isEqualTo(ADD_OPENS);
    }
}
