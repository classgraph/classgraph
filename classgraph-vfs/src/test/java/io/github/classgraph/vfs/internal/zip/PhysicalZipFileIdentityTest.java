package io.github.classgraph.vfs.internal.zip;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.classgraph.base.internal.concurrency.InterruptionChecker;
import io.github.classgraph.vfs.VfsSpec;
import io.github.classgraph.vfs.Vfs;

/**
 * A zipfile on disk can be opened through either the {@link File} API or the {@link java.nio.file.Path} API, and
 * can be opened more than once. Whichever way it was opened, it is the same zipfile, and is identified by its path.
 */
public class PhysicalZipFileIdentityTest {
    /**
     * Write a jarfile holding a single entry.
     *
     * @param jarFile
     *            the jarfile to write
     * @throws Exception
     *             if the jarfile could not be written
     */
    private static void writeJar(final File jarFile) throws Exception {
        try (var fileOut = new FileOutputStream(jarFile); var zipOut = new ZipOutputStream(fileOut)) {
            zipOut.putNextEntry(new ZipEntry("testpkg/test.txt"));
            zipOut.write("contents".getBytes(StandardCharsets.UTF_8));
            zipOut.closeEntry();
        }
    }

    /**
     * Two zipfiles opened on the same path are the same zipfile, whether they were opened through the {@link File}
     * API or the {@link java.nio.file.Path} API, and a zipfile opened on a different path is a different zipfile.
     *
     * @param tempDir
     *            a directory to write the jarfiles into
     * @throws Exception
     *             if a jarfile could not be written or opened
     */
    @Test
    public void aZipfileIsIdentifiedByItsPath(@TempDir final File tempDir) throws Exception {
        final var jarFile = new File(tempDir, "physical-zipfile.jar");
        writeJar(jarFile);
        final var otherJarFile = new File(tempDir, "other-physical-zipfile.jar");
        writeJar(otherJarFile);

        final var vfs = new Vfs(new VfsSpec(), new InterruptionChecker());
        try {
            final var fromFile = new PhysicalZipFile(jarFile, vfs, /* log = */ null);
            final var fromPath = new PhysicalZipFile(jarFile.toPath(), vfs, /* log = */ null);
            final var fromOtherFile = new PhysicalZipFile(otherJarFile, vfs, /* log = */ null);

            assertThat(fromFile).isEqualTo(fromFile).isEqualTo(fromPath).hasSameHashCodeAs(fromPath)
                    .isNotEqualTo(fromOtherFile).isNotEqualTo(jarFile.getPath());

            // The two zipfiles are equal even though only one of them has a File, and only the other has a Path
            assertThat(fromFile.getFile()).isEqualTo(jarFile);
            assertThat(fromFile.getPath()).isNull();
            assertThat(fromPath.getPath()).isEqualTo(jarFile.toPath());
            assertThat(fromPath.getFile()).isNull();

            // A zipfile names itself by its path
            assertThat(fromFile.getPathString()).endsWith(jarFile.getName());
            assertThat(fromFile).hasToString(fromFile.getPathString());
            assertThat(fromPath).hasToString(fromFile.getPathString());
            assertThat(fromFile.length()).isEqualTo(jarFile.length());
        } finally {
            // The jarfiles must not be left open, otherwise the temporary directory cannot be deleted on Windows
            vfs.close(/* log = */ null);
        }
    }

    /**
     * Opening the same zipfile twice produces two separate handles on it, each with its own file handle and memory
     * mapping, so closing one of them must not close the other -- which means the two of them are not the same
     * logical zipfile, even though they are the same physical zipfile.
     *
     * @param tempDir
     *            a directory to write the jarfile into
     * @throws Exception
     *             if the jarfile could not be written or opened
     */
    @Test
    public void separateOpeningsOfAZipfileAreSeparateHandles(@TempDir final File tempDir) throws Exception {
        final var jarFile = new File(tempDir, "opened-twice.jar");
        writeJar(jarFile);

        final var vfs = new Vfs(new VfsSpec(), new InterruptionChecker());
        final var otherVfs = new Vfs(new VfsSpec(), new InterruptionChecker());
        try {
            final var logicalZipFile = JarOpener.openJarFile(jarFile, vfs, /* log = */ null).zipFile();
            final var openedAgain = JarOpener.openJarFile(jarFile, otherVfs, /* log = */ null).zipFile();

            // The two openings are the same physical zipfile, but not the same handle on it
            assertThat(logicalZipFile.getPhysicalFile()).isEqualTo(openedAgain.getPhysicalFile());
            assertThat(logicalZipFile.getPath()).isEqualTo(openedAgain.getPath());
            assertThat(logicalZipFile).isEqualTo(logicalZipFile).isNotEqualTo(openedAgain)
                    .isNotEqualTo(jarFile.getPath());
        } finally {
            vfs.close(/* log = */ null);
            otherVfs.close(/* log = */ null);
        }
    }
}
