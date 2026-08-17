package nonapi.io.github.classgraph.fastzipfilereader;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map.Entry;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import nonapi.io.github.classgraph.concurrency.InterruptionChecker;
import nonapi.io.github.classgraph.reflection.ReflectionUtils;
import nonapi.io.github.classgraph.scanspec.ScanSpec;

/**
 * The End Of Central Directory record is the last record of a zipfile, except that a zipfile comment of up to 65535
 * bytes can follow it, so the record has to be searched for backwards from the end of the file.
 */
public class ZipfileCommentTest {
    /** The name of the entry of the zipfiles written here. */
    private static final String ENTRY_NAME = "testpkg/commented.txt";

    /** The maximum length of a zipfile comment, which is stored in a 16-bit length field. */
    private static final int MAX_COMMENT_LENGTH = 65535;

    /** The four bytes of the End Of Central Directory signature, {@code PK\05\06}, as UTF-8 characters. */
    private static final String EOCD_SIGNATURE = "PK\005\006";

    /**
     * Write a jarfile holding a single entry, with a comment of the given length.
     *
     * @param tempDir
     *            the directory to write the jarfile into
     * @param jarName
     *            the name of the jarfile to write
     * @param commentLength
     *            the length of the zipfile comment
     * @return the names of the entries read back from the jarfile
     * @throws Exception
     *             if the jarfile could not be written or read
     */
    private static List<String> entryNamesReadBack(final File tempDir, final String jarName,
            final int commentLength) throws Exception {
        return entryNamesReadBack(tempDir, jarName, filler(commentLength));
    }

    /**
     * A string of the given number of filler characters.
     *
     * @param length
     *            the number of characters
     * @return the filler string
     */
    private static String filler(final int length) {
        final StringBuilder buf = new StringBuilder();
        for (int i = 0; i < length; i++) {
            buf.append('c');
        }
        return buf.toString();
    }

    /**
     * Write a jarfile holding a single entry, with the given comment.
     *
     * @param tempDir
     *            the directory to write the jarfile into
     * @param jarName
     *            the name of the jarfile to write
     * @param comment
     *            the zipfile comment
     * @return the names of the entries read back from the jarfile
     * @throws Exception
     *             if the jarfile could not be written or read
     */
    private static List<String> entryNamesReadBack(final File tempDir, final String jarName, final String comment)
            throws Exception {
        final File jarFile = new File(tempDir, jarName);
        try (OutputStream fileOut = new FileOutputStream(jarFile);
                ZipOutputStream zipOut = new ZipOutputStream(fileOut)) {
            zipOut.setComment(comment);
            zipOut.putNextEntry(new ZipEntry(ENTRY_NAME));
            zipOut.write("contents".getBytes(StandardCharsets.UTF_8));
            zipOut.closeEntry();
        }

        final NestedJarHandler nestedJarHandler = new NestedJarHandler(new ScanSpec(), new InterruptionChecker(),
                new ReflectionUtils());
        final List<String> entryNames = new ArrayList<>();
        try {
            final Entry<LogicalZipFile, String> logicalZipFileAndPackageRoot = nestedJarHandler.nestedPathToLogicalZipFileAndPackageRootMap
                    .get(jarFile.getPath(), /* log = */ null);
            for (final FastZipEntry zipEntry : logicalZipFileAndPackageRoot.getKey().entries) {
                entryNames.add(zipEntry.entryName);
            }
        } finally {
            // The jarfile must not be left open, otherwise the temporary directory cannot be deleted on Windows
            nestedJarHandler.close(/* log = */ null);
        }
        return entryNames;
    }

    /** A zipfile with no comment ends with the End Of Central Directory record. */
    @Test
    public void noComment(@TempDir final File tempDir) throws Exception {
        assertThat(entryNamesReadBack(tempDir, "no-comment.jar", 0)).containsExactly(ENTRY_NAME);
    }

    /** A comment long enough to push the End Of Central Directory record out of the initial search window. */
    @Test
    public void longComment(@TempDir final File tempDir) throws Exception {
        assertThat(entryNamesReadBack(tempDir, "long-comment.jar", 1000)).containsExactly(ENTRY_NAME);
    }

    /** A comment of the maximum length that the 16-bit comment length field can describe. */
    @Test
    public void maximumLengthComment(@TempDir final File tempDir) throws Exception {
        assertThat(entryNamesReadBack(tempDir, "max-comment.jar", MAX_COMMENT_LENGTH)).containsExactly(ENTRY_NAME);
    }

    /**
     * A comment is arbitrary bytes, so it can hold the End Of Central Directory signature itself. The backwards
     * scan reaches that copy before the real record, and only the comment length written after the signature tells
     * the two apart: the real record's 22-byte header plus its comment length reaches exactly the end of the file.
     * This comment is short enough that both copies fall inside the initial 32-byte search window.
     */
    @Test
    public void aCommentHoldingTheEndOfCentralDirectorySignature(@TempDir final File tempDir) throws Exception {
        assertThat(entryNamesReadBack(tempDir, "eocd-in-comment.jar", EOCD_SIGNATURE + filler(22)))
                .containsExactly(ENTRY_NAME);
    }

    /**
     * A comment holding the End Of Central Directory signature, long enough that the real record is only found by
     * the second search, which reads the last 64kB of the file in one chunk and scans back through it.
     */
    @Test
    public void aLongCommentHoldingTheEndOfCentralDirectorySignature(@TempDir final File tempDir) throws Exception {
        assertThat(entryNamesReadBack(tempDir, "eocd-in-long-comment.jar", EOCD_SIGNATURE + filler(1000)))
                .containsExactly(ENTRY_NAME);
    }
}
