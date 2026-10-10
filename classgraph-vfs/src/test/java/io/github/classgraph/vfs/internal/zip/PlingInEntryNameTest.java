package io.github.classgraph.vfs.internal.zip;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.classgraph.vfs.Vfs;

/**
 * A '!' in a zip entry name is an ordinary character. It is a nested jar separator only in a path on the host,
 * where it follows the path of a jarfile, and an entry name is a name within an archive, not a path on the host. So
 * how an entry name is read must not depend on what files exist on the host, and reading it must not test the host
 * filesystem at all.
 */
public class PlingInEntryNameTest {
    /**
     * Write a zipfile holding one empty entry per given name.
     *
     * @param tempDir
     *            the directory to write the zipfile into
     * @param names
     *            the entry names
     * @return the zipfile
     * @throws Exception
     *             if the zipfile could not be written
     */
    private static File writeZip(final File tempDir, final String... names) throws Exception {
        final var zipFile = new File(tempDir, "test.jar");
        try (var zout = new ZipOutputStream(new FileOutputStream(zipFile))) {
            for (final var name : names) {
                zout.putNextEntry(new ZipEntry(name));
                zout.closeEntry();
            }
        }
        return zipFile;
    }

    /**
     * List the names of the entries of a zipfile, as the zipfile reader reports them.
     *
     * @param zipFile
     *            the zipfile
     * @return the entry names
     * @throws Exception
     *             if the zipfile could not be read
     */
    private static List<String> entryNames(final File zipFile) throws Exception {
        final List<String> names = new ArrayList<>();
        try (var vfs = new Vfs()) {
            for (final var entry : vfs.open(zipFile.getPath()).getEntries()) {
                names.add(entry.getPathFromRoot());
            }
        }
        return names;
    }

    /**
     * An entry name shaped like a URL is not a URL, so the '!' in it does not separate a jarfile from an entry.
     *
     * @param tempDir
     *            a temporary directory to write the zipfile into
     * @throws Exception
     *             if the zipfile could not be written or read
     */
    @Test
    public void aPlingInAnEntryNameShapedLikeAURLIsAnOrdinaryCharacter(@TempDir final File tempDir)
            throws Exception {
        assertThat(entryNames(writeZip(tempDir, "ab:c!d/./e.txt"))).containsExactly("ab:c!d/e.txt");
    }

    /**
     * An entry name whose part before a '!' names a file on the host is read the same as any other entry name.
     *
     * @param tempDir
     *            a temporary directory to write the zipfile and the host file into
     * @throws Exception
     *             if the zipfile could not be written or read
     */
    @Test
    public void aPlingAfterTheNameOfAHostFileIsAnOrdinaryCharacter(@TempDir final File tempDir) throws Exception {
        // An entry name containing a Windows drive letter is not an absolute path there, so the host file cannot
        // be named this way
        assumeTrue(File.separatorChar == '/');
        final var hostFile = new File(tempDir, "f");
        Files.write(hostFile.toPath(), new byte[] { 'P', 'K' });
        final var hostPath = hostFile.getPath();
        assertThat(entryNames(writeZip(tempDir, hostPath + "!a/./b.txt")))
                .containsExactly(hostPath.substring(1) + "!a/b.txt");
    }

    /**
     * Reading an entry name takes time in proportion to its length, however many '!' characters it holds.
     *
     * @param tempDir
     *            a temporary directory to write the zipfile into
     * @throws Exception
     *             if the zipfile could not be written or read
     */
    @Test
    public void manyPlingsInEntryNamesDoNotSlowReading(@TempDir final File tempDir) throws Exception {
        final var names = new String[20];
        for (var i = 0; i < names.length; i++) {
            names[i] = "!".repeat(65000) + i;
        }
        final var zipFile = writeZip(tempDir, names);
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> assertThat(entryNames(zipFile)).hasSize(20));
    }
}
