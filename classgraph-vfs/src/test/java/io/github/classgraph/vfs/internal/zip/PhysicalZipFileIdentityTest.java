package io.github.classgraph.vfs.internal.zip;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.FileInputStream;
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
 * can be opened more than once. Each opening is a zipfile of its own, which is named by its path but not identified
 * by it: a zipfile read from a stream is named by a label that other zipfiles can share.
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
     * Each opening of a zipfile is a zipfile of its own, whether it was opened through the {@link File} API or the
     * {@link java.nio.file.Path} API: two openings of the same path have the same name, but are not equal.
     *
     * @param tempDir
     *            a directory to write the jarfiles into
     * @throws Exception
     *             if a jarfile could not be written or opened
     */
    @Test
    public void twoOpeningsOfOneZipfileAreDifferentZipfiles(@TempDir final File tempDir) throws Exception {
        final var jarFile = new File(tempDir, "physical-zipfile.jar");
        writeJar(jarFile);
        final var otherJarFile = new File(tempDir, "other-physical-zipfile.jar");
        writeJar(otherJarFile);

        final var vfs = new Vfs(new VfsSpec(), new InterruptionChecker());
        try {
            final var fromFile = new PhysicalZipFile(jarFile, vfs, /* log = */ null);
            final var fromPath = new PhysicalZipFile(jarFile.toPath(), vfs, /* log = */ null);
            final var fromOtherFile = new PhysicalZipFile(otherJarFile, vfs, /* log = */ null);
            try {
                assertThat(fromFile).isEqualTo(fromFile).isNotEqualTo(fromPath).isNotEqualTo(fromOtherFile)
                        .isNotEqualTo(jarFile.getPath());

                // Only one of the two openings has a File, and only the other has a Path
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
                // The jarfiles must not be left open, otherwise the temporary directory cannot be deleted on
                // Windows. Whatever constructs a physical zipfile owns it, and closing the Vfs does not close it
                fromFile.close();
                fromPath.close();
                fromOtherFile.close();
            }
        } finally {
            vfs.close(/* log = */ null);
        }
    }

    /**
     * Two different jarfiles read from streams under the same name share that name, but are two different zipfiles.
     *
     * @param tempDir
     *            a directory to write the jarfiles into
     * @throws Exception
     *             if a jarfile could not be written or read
     */
    @Test
    public void twoStreamsReadUnderOneNameAreDifferentZipfiles(@TempDir final File tempDir) throws Exception {
        final var jarFile = new File(tempDir, "first.jar");
        writeJar(jarFile);
        final var otherJarFile = new File(tempDir, "second.jar");
        try (var fileOut = new FileOutputStream(otherJarFile); var zipOut = new ZipOutputStream(fileOut)) {
            zipOut.putNextEntry(new ZipEntry("otherpkg/other.txt"));
            zipOut.write("other contents".getBytes(StandardCharsets.UTF_8));
            zipOut.closeEntry();
        }

        final var vfs = new Vfs(new VfsSpec(), new InterruptionChecker());
        try (var in = new FileInputStream(jarFile); var otherIn = new FileInputStream(otherJarFile)) {
            final var first = new PhysicalZipFile(in, jarFile.length(), "lib.jar", vfs, /* log = */ null);
            final var second = new PhysicalZipFile(otherIn, otherJarFile.length(), "lib.jar", vfs,
                    /* log = */ null);

            assertThat(first.getPathString()).isEqualTo(second.getPathString());
            assertThat(first.length()).isNotEqualTo(second.length());
            assertThat(first).isNotEqualTo(second);
        } finally {
            vfs.close(/* log = */ null);
        }
    }

    /**
     * Opening the same zipfile twice produces two separate handles on it, each with its own file handle and memory
     * mapping, so closing one of them must not close the other -- which means the two of them are not the same
     * logical zipfile, even though they were opened from the same file.
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
            final var openedJar = JarOpener.openJarFile(jarFile, vfs, /* log = */ null);
            final var openedJarAgain = JarOpener.openJarFile(jarFile, otherVfs, /* log = */ null);
            try {
                final var logicalZipFile = openedJar.zipFile();
                final var openedAgain = openedJarAgain.zipFile();

                // The two openings are of the same file, but are not the same handle on it
                assertThat(logicalZipFile.getPhysicalFile()).isEqualTo(openedAgain.getPhysicalFile());
                assertThat(logicalZipFile.getPath()).isEqualTo(openedAgain.getPath());
                assertThat(logicalZipFile).isEqualTo(logicalZipFile).isNotEqualTo(openedAgain)
                        .isNotEqualTo(jarFile.getPath());
            } finally {
                // The jarfile must not be left open, otherwise the temporary directory cannot be deleted on
                // Windows. The caller owns the physical zipfiles that were opened, which closing the Vfs does not
                // close
                openedJar.ownedPhysicalZipFile().close();
                openedJarAgain.ownedPhysicalZipFile().close();
            }
        } finally {
            vfs.close(/* log = */ null);
            otherVfs.close(/* log = */ null);
        }
    }
}
