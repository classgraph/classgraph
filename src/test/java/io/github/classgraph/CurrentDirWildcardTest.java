package io.github.classgraph;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The classpath entry {@code "*"} names the jarfiles in the current directory. When user.dir could not be read,
 * the current directory path is empty, and {@code "*"} was turned into {@code ""}, which before JDK 25 does not
 * exist, so nothing was found.
 */
public class CurrentDirWildcardTest {
    /**
     * Print the classpath that the entry {@code "*"} gives when user.dir cannot be read.
     *
     * @param args
     *            unused.
     */
    public static void main(final String[] args) {
        // On JDK 8 the default filesystem reads user.dir when it is first created, and throws if user.dir is
        // unset, so it has to be created before user.dir is cleared
        FileSystems.getDefault();
        System.clearProperty("user.dir");
        for (final File file : new ClassGraph().overrideClasspath("*").getClasspathFiles()) {
            System.out.println(file.getName());
        }
    }

    /** A wildcard finds the jarfiles in the current directory when user.dir cannot be read. */
    @Test
    public void aWildcardFindsTheJarfilesInTheCurrentDirectoryWhenUserDirCannotBeRead(@TempDir final Path tempDir)
            throws IOException, InterruptedException {
        try (OutputStream out = Files.newOutputStream(tempDir.resolve("a.jar"));
                ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("marker.txt"));
            zip.write("marker".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        final Process process = new ProcessBuilder(
                System.getProperty("java.home") + File.separator + "bin" + File.separator + "java", "-cp",
                System.getProperty("java.class.path"), CurrentDirWildcardTest.class.getName())
                        .directory(tempDir.toFile()).redirectErrorStream(true).start();
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (InputStream in = process.getInputStream()) {
            final byte[] buf = new byte[8192];
            for (int n; (n = in.read(buf)) > 0;) {
                output.write(buf, 0, n);
            }
        }
        assertThat(process.waitFor()).isZero();
        assertThat(new String(output.toByteArray(), StandardCharsets.UTF_8).trim()).isEqualTo("a.jar");
    }
}
