package io.github.classgraph.vfs.internal.zip;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.classgraph.vfs.Vfs;

/**
 * A NUL character in a zip entry name is an ordinary character, as it is to the JDK's zip reader, so an entry whose
 * name contains one is not reported at a path that the JDK cannot find, and cannot take the place of another entry.
 */
public class NulInEntryNameTest {
    /**
     * Entry names holding NUL characters are reported unchanged, apart from the removal of {@code "."} segments.
     *
     * @param tempDir
     *            a temporary directory to write the zipfile into
     * @throws Exception
     *             if the zipfile could not be written or read
     */
    @Test
    public void nulCharactersInEntryNamesAreKept(@TempDir final File tempDir) throws Exception {
        final var zipFile = new File(tempDir, "test.jar");
        try (var zout = new ZipOutputStream(new FileOutputStream(zipFile))) {
            for (final var name : new String[] { "pkg/Res.txt", "pkg\0\0Res.txt", "./a\0b.txt" }) {
                zout.putNextEntry(new ZipEntry(name));
                zout.closeEntry();
            }
        }
        final List<String> names = new ArrayList<>();
        try (var vfs = new Vfs()) {
            for (final var entry : vfs.open(zipFile.getPath()).getEntries()) {
                names.add(entry.getPathFromRoot());
            }
        }
        assertThat(names).containsExactlyInAnyOrder("pkg/Res.txt", "pkg\0\0Res.txt", "a\0b.txt");
    }
}
