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
 * Tests that a jarfile written by a tool that lower-cased its entry names is still read as having a manifest.
 * {@code java.util.zip.ZipFile} finds that manifest too, since it matches both {@code "META-INF/"} and
 * {@code "MANIFEST.MF"} a character at a time with the case bit masked off, but ClassGraph used to match the name
 * exactly, so it dropped the {@code Class-Path} of such a jarfile along with everything else its manifest says.
 */
public class CaseFoldedManifestNameTest {
    /** The manifest name that a jarfile normally stores its manifest under. */
    private static final String CANONICAL_NAME = "META-INF/MANIFEST.MF";

    /** The same name with every character lower-cased. */
    private static final String LOWER_CASED_NAME = "meta-inf/manifest.mf";

    /**
     * Build a jar holding a manifest under each of the given names, and read its Class-Path manifest entry. The
     * Class-Path of each manifest is the name it is stored under, so that the value read says which manifest was
     * the one that was parsed.
     *
     * @param tempDir
     *            a temporary directory to write the jar into
     * @param manifestNames
     *            the names to store a manifest under, in the order they are written to the jar
     * @return the value of the Class-Path manifest entry, or null if no manifest was found
     * @throws Exception
     *             if the jar could not be written or read
     */
    private static String readClassPathManifestEntry(final File tempDir, final String... manifestNames)
            throws Exception {
        final File jarFile = new File(tempDir, "case-folded-manifest.jar");
        try (OutputStream fileOut = new FileOutputStream(jarFile);
                ZipOutputStream zipOut = new ZipOutputStream(fileOut)) {
            for (final String manifestName : manifestNames) {
                zipOut.putNextEntry(new ZipEntry(manifestName));
                zipOut.write(("Manifest-Version: 1.0\r\n" //
                        + "Class-Path: " + manifestName + "\r\n" //
                        + "\r\n").getBytes(StandardCharsets.UTF_8));
                zipOut.closeEntry();
            }
        }

        final NestedJarHandler nestedJarHandler = new NestedJarHandler(new ScanSpec(), new InterruptionChecker(),
                new ReflectionUtils());
        try {
            final Entry<LogicalZipFile, String> logicalZipFileAndPackageRoot = nestedJarHandler.nestedPathToLogicalZipFileAndPackageRootMap
                    .get(jarFile.getPath(), /* log = */ null);
            return logicalZipFileAndPackageRoot.getKey().classPathManifestEntryValue;
        } finally {
            // The manifest values have already been read into fields of the LogicalZipFile, so the zipfile can be
            // closed. (The jarfile must not be left open, otherwise the temporary directory cannot be deleted on
            // Windows.)
            nestedJarHandler.close(/* log = */ null);
        }
    }

    /** A manifest stored under a lower-cased name must still be read as the manifest. */
    @Test
    public void aManifestStoredUnderALowerCasedNameIsStillTheManifest(@TempDir final File tempDir)
            throws Exception {
        assertThat(readClassPathManifestEntry(tempDir, LOWER_CASED_NAME)).isEqualTo(LOWER_CASED_NAME);
    }

    /**
     * A jarfile holding a manifest under both names must be read as having the one stored under the canonical name,
     * even though the other one comes first.
     */
    @Test
    public void theManifestUnderTheCanonicalNameIsThePreferredOne(@TempDir final File tempDir) throws Exception {
        assertThat(readClassPathManifestEntry(tempDir, LOWER_CASED_NAME, CANONICAL_NAME))
                .isEqualTo(CANONICAL_NAME);
    }
}
