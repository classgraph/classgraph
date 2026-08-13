package io.github.classgraph.vfs.internal.zip;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.classgraph.vfs.Vfs;

/**
 * The End Of Central Directory record is the last record of a zipfile, except that a zipfile comment of up to 65535
 * bytes can follow it, so the record has to be searched for backwards from the end of the file.
 */
public class ZipfileCommentTest {
    /** The name of the entry of the zipfiles written here. */
    private static final String ENTRY_NAME = "testpkg/commented.txt";

    /** The maximum length of a zipfile comment, which is stored in a 16-bit length field. */
    private static final int MAX_COMMENT_LENGTH = 65535;

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
        final var jarFile = new File(tempDir, jarName);
        try (var fileOut = new FileOutputStream(jarFile); var zipOut = new ZipOutputStream(fileOut)) {
            zipOut.setComment("c".repeat(commentLength));
            zipOut.putNextEntry(new ZipEntry(ENTRY_NAME));
            zipOut.write("contents".getBytes(StandardCharsets.UTF_8));
            zipOut.closeEntry();
        }
        try (var vfs = new Vfs()) {
            return vfs.open(jarFile.getPath()).getEntries().stream().map(entry -> entry.getName()).toList();
        }
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
}
