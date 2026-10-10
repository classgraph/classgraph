package nonapi.io.github.classgraph.fastzipfilereader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Iterator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import nonapi.io.github.classgraph.concurrency.InterruptionChecker;
import nonapi.io.github.classgraph.reflection.ReflectionUtils;
import nonapi.io.github.classgraph.scanspec.ScanSpec;

/**
 * Tests that a temporary file can be created for a nested jar whose entry name contains characters that are legal
 * in a zip entry name but not in a filename. A nested jar that is too large to be extracted to RAM is spilled to a
 * temporary file named after the zip entry it came from, so every such character has to be replaced first.
 */
public class TempFilenameSanitizationTest {
    /**
     * Every ASCII character that Windows rejects in a filename (the control characters, and {@code " * / < > ? \
     * |}), plus {@code :}, which Windows accepts but treats as the start of an NTFS alternate data stream. Linux
     * and macOS reject only {@code /}. (Measured on real GitHub Actions runners for all three platforms.)
     */
    private static final String UNSAFE_CHARS = "\b\t\n\f\r\"*/:<>?\\|";

    /** A nested jar whose entry name is not a valid filename must still get a temporary file. */
    @Test
    public void unsafeCharactersInEntryNameAreReplaced() throws Exception {
        final NestedJarHandler nestedJarHandler = new NestedJarHandler(new ScanSpec(), new InterruptionChecker(),
                new ReflectionUtils());
        try {
            final File tempFile = nestedJarHandler.makeTempFile("BOOT-INF/lib/na" + UNSAFE_CHARS + "me.jar",
                    /* onlyUseLeafname = */ false);
            assertThat(tempFile).exists();
            for (int i = 0; i < UNSAFE_CHARS.length(); i++) {
                assertThat(tempFile.getName()).doesNotContain(UNSAFE_CHARS.substring(i, i + 1));
            }
        } finally {
            nestedJarHandler.close(/* log = */ null);
        }
    }

    /**
     * A zip entry name can be any length, but a filename is limited to 255 bytes on Linux and macOS, and to 255
     * UTF-16 characters on Windows, so a nested jar with a long name must still get a temporary file.
     */
    @Test
    public void aLongEntryNameIsShortened() throws Exception {
        final StringBuilder longName = new StringBuilder("BOOT-INF/lib/");
        for (int i = 0; i < 300; i++) {
            longName.append('é');
        }
        longName.append(".jar");
        final NestedJarHandler nestedJarHandler = new NestedJarHandler(new ScanSpec(), new InterruptionChecker(),
                new ReflectionUtils());
        try {
            final File tempFile = nestedJarHandler.makeTempFile(longName.toString(),
                    /* onlyUseLeafname = */ true);
            assertThat(tempFile).exists();
            assertThat(tempFile.getName()).endsWith("é.jar");
        } finally {
            nestedJarHandler.close(/* log = */ null);
        }
    }

    /**
     * A temporary file holds the content of a jar that may be private, such as a nested jar inflated out of a jar
     * that only its owner can read, or a jar downloaded from an authenticated repository. It is created in the
     * shared temporary directory, so only its owner may read it, whatever the umask.
     */
    @Test
    public void aTempFileIsReadableOnlyByItsOwner() throws Exception {
        assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"),
                "The default filesystem does not support POSIX file permissions");
        final NestedJarHandler nestedJarHandler = new NestedJarHandler(new ScanSpec(), new InterruptionChecker(),
                new ReflectionUtils());
        try {
            final File tempFile = nestedJarHandler.makeTempFile("BOOT-INF/lib/private.jar",
                    /* onlyUseLeafname = */ true);
            assertThat(Files.getPosixFilePermissions(tempFile.toPath()))
                    .containsExactlyInAnyOrder(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
        } finally {
            nestedJarHandler.close(/* log = */ null);
        }
    }

    /**
     * A zip entry name can hold a surrogate that is not part of a pair, which no filesystem can encode, so it is
     * replaced. A surrogate pair is a valid character, and is kept.
     */
    @Test
    public void anUnpairedSurrogateInEntryNameIsReplaced() throws Exception {
        final NestedJarHandler nestedJarHandler = new NestedJarHandler(new ScanSpec(), new InterruptionChecker(),
                new ReflectionUtils());
        File tempFile = null;
        try {
            tempFile = nestedJarHandler.makeTempFile("BOOT-INF/lib/x\uD800y\uDFFFz😀.jar",
                    /* onlyUseLeafname = */ true);
            assertThat(tempFile).exists();
            assertThat(tempFile.getName()).endsWith("x_y_z😀.jar");
        } finally {
            nestedJarHandler.close(/* log = */ null);
        }
        assertThat(tempFile).doesNotExist();
    }

    /**
     * Run in a child JVM: create and delete a temporary file for a nested jar with a non-ASCII name, and print the
     * encoding that filenames are stored in, then the name of the temporary file.
     */
    static final class CreateNonAsciiTempFile {
        /** Not instantiable. */
        private CreateNonAsciiTempFile() {
        }

        /**
         * Entry point.
         *
         * @param args
         *            ignored.
         * @throws IOException
         *             if the temporary file could not be created or deleted.
         */
        public static void main(final String[] args) throws IOException {
            final NestedJarHandler nestedJarHandler = new NestedJarHandler(new ScanSpec(),
                    new InterruptionChecker(), new ReflectionUtils());
            final File tempFile;
            try {
                tempFile = nestedJarHandler.makeTempFile("BOOT-INF/lib/café.jar", /* onlyUseLeafname = */ true);
                System.out.println(System.getProperty("sun.jnu.encoding"));
                System.out.println(tempFile.getName());
            } finally {
                nestedJarHandler.close(/* log = */ null);
            }
            if (tempFile.exists()) {
                throw new IOException("Could not delete " + tempFile);
            }
        }
    }

    /**
     * A JVM started under the C locale on Linux stores filenames in ASCII, so it cannot name a file after a nested
     * jar with a non-ASCII name. The non-ASCII characters are replaced instead.
     *
     * @param tempDir
     *            the temporary directory of the child JVM.
     * @throws Exception
     *             if the child JVM could not be run.
     */
    @Test
    public void aNonAsciiEntryNameIsReplacedWhenFilenamesAreAscii(@TempDir final Path tempDir) throws Exception {
        assumeTrue(System.getProperty("os.name").startsWith("Linux"), "The C locale is only tested on Linux");
        final ProcessBuilder processBuilder = new ProcessBuilder(
                Paths.get(System.getProperty("java.home"), "bin", "java").toString(),
                "-Djava.io.tmpdir=" + tempDir, "-cp", System.getProperty("java.class.path"),
                CreateNonAsciiTempFile.class.getName()).redirectErrorStream(true);
        final Iterator<String> envNames = processBuilder.environment().keySet().iterator();
        while (envNames.hasNext()) {
            if (envNames.next().startsWith("LC_")) {
                envNames.remove();
            }
        }
        processBuilder.environment().put("LC_ALL", "C");
        processBuilder.environment().put("LANG", "C");
        final Process process = processBuilder.start();
        final ByteArrayOutputStream outputBytes = new ByteArrayOutputStream();
        try (InputStream inputStream = process.getInputStream()) {
            final byte[] buf = new byte[8192];
            for (int n; (n = inputStream.read(buf)) > 0;) {
                outputBytes.write(buf, 0, n);
            }
        }
        final String output = new String(outputBytes.toByteArray(), StandardCharsets.UTF_8);
        assertThat(process.waitFor()).as("Child JVM output:%n%s", output).isZero();
        // Read the last two lines, since the JVM may print a notice such as "Picked up JAVA_TOOL_OPTIONS" first
        final String[] outputLines = output.split("\r?\n");
        assertThat(outputLines.length).as("Child JVM output:%n%s", output).isGreaterThanOrEqualTo(2);
        assumeTrue(!outputLines[outputLines.length - 2].equals("UTF-8"),
                "The C locale stores filenames in UTF-8 here");
        assertThat(outputLines[outputLines.length - 1]).endsWith("caf_.jar");
        try (DirectoryStream<Path> tempFiles = Files.newDirectoryStream(tempDir)) {
            assertThat(tempFiles).isEmpty();
        }
    }
}
