/*
 * This file is part of ClassGraph.
 *
 * Author: Luke Hutchison
 *
 * Hosted at: https://github.com/classgraph/classgraph
 *
 * --
 *
 * The MIT License (MIT)
 *
 * Copyright (c) 2026 Luke Hutchison
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
 * documentation files (the "Software"), to deal in the Software without restriction, including without
 * limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the
 * Software, and to permit persons to whom the Software is furnished to do so, subject to the following
 * conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies or substantial portions
 * of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED
 * TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL
 * THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF
 * CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER
 * DEALINGS IN THE SOFTWARE.
 */
package io.github.classgraph.vfs.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests that a temporary file can be created for a nested jar whose entry name contains characters that are legal
 * in a zip entry name but not in a filename. A nested jar that is too large to be extracted to RAM is spilled to a
 * temporary file named after the zip entry it came from, so every such character has to be replaced first.
 */
class TempFilenameSanitizationTest {
    /**
     * Every ASCII character that Windows rejects in a filename (the control characters, and {@code " * / < > ? \
     * |}), plus {@code :}, which Windows accepts but treats as the start of an NTFS alternate data stream. Linux
     * and macOS reject only {@code /}, which is left out here, since the temporary file is named after the part of
     * the entry name after its last {@code /}. (Measured on real GitHub Actions runners for all three platforms.)
     */
    private static final String UNSAFE_CHARS = "\b\t\n\f\r\"*:<>?\\|";

    /** A nested jar whose entry name is not a valid filename must still get a temporary file. */
    @Test
    void unsafeCharactersInEntryNameAreReplaced() throws Exception {
        final var tempFile = TempFile.create("BOOT-INF/lib/na" + UNSAFE_CHARS + "me.jar");
        try {
            assertThat(tempFile).exists();
            for (var i = 0; i < UNSAFE_CHARS.length(); i++) {
                assertThat(tempFile.getName()).doesNotContain(UNSAFE_CHARS.substring(i, i + 1));
            }
        } finally {
            assertThat(TempFile.delete(tempFile)).isTrue();
        }
    }

    /**
     * A zip entry name can be any length, but a filename is limited to 255 bytes on Linux and macOS, and to 255
     * UTF-16 characters on Windows, so a nested jar with a long name must still get a temporary file.
     */
    @Test
    void aLongEntryNameIsShortened() throws Exception {
        final var tempFile = TempFile.create("BOOT-INF/lib/" + "é".repeat(300) + ".jar");
        try {
            assertThat(tempFile).exists();
            assertThat(tempFile.getName()).endsWith("é.jar");
        } finally {
            assertThat(TempFile.delete(tempFile)).isTrue();
        }
    }

    /**
     * A temporary file holds the content of a jar that may be private, such as a nested jar inflated out of a jar
     * that only its owner can read, or a jar downloaded from an authenticated repository. It is created in the
     * shared temporary directory, so only its owner may read it, whatever the umask.
     */
    @Test
    void aTempFileIsReadableOnlyByItsOwner() throws Exception {
        assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"),
                "The default filesystem does not support POSIX file permissions");
        final var tempFile = TempFile.create("BOOT-INF/lib/private.jar");
        try {
            assertThat(Files.getPosixFilePermissions(tempFile.toPath()))
                    .containsExactlyInAnyOrder(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
        } finally {
            assertThat(TempFile.delete(tempFile)).isTrue();
        }
    }

    /**
     * A zip entry name can hold a surrogate that is not part of a pair, which no filesystem can encode, so it is
     * replaced. A surrogate pair is a valid character, and is kept.
     */
    @Test
    void anUnpairedSurrogateInEntryNameIsReplaced() throws Exception {
        final var tempFile = TempFile.create("BOOT-INF/lib/x\uD800y\uDFFFz😀.jar");
        try {
            assertThat(tempFile).exists();
            assertThat(tempFile.getName()).endsWith("x_y_z😀.jar");
        } finally {
            assertThat(TempFile.delete(tempFile)).isTrue();
        }
    }

    /**
     * Start a child JVM, wait at most 5 minutes for it to exit, and check that it exited with status 0. The child
     * JVM is killed if it runs for longer.
     *
     * @param processBuilder
     *            the child JVM to start, with its standard error merged into its standard output.
     * @return everything the child JVM wrote to stdout and stderr.
     * @throws IOException
     *             if the child JVM could not be started, or its output could not be read.
     * @throws InterruptedException
     *             if the thread was interrupted while waiting for the child JVM.
     */
    static String runChildJvm(final ProcessBuilder processBuilder) throws IOException, InterruptedException {
        // The output goes to a file rather than a pipe, so that a child JVM that hangs cannot block the read, and
        // the timeout still applies. The file is not put in the child JVM's temporary directory, which the tests
        // check is left empty.
        final var outputFile = Files.createTempFile("child-jvm", ".out");
        try {
            final var process = processBuilder.redirectOutput(outputFile.toFile()).start();
            final var finished = process.waitFor(5, TimeUnit.MINUTES);
            if (!finished) {
                process.destroyForcibly().waitFor();
            }
            final var output = new String(Files.readAllBytes(outputFile), StandardCharsets.UTF_8);
            assertThat(finished).as("Child JVM did not finish in 5 minutes. Output:%n%s", output).isTrue();
            assertThat(process.exitValue()).as("Child JVM output:%n%s", output).isZero();
            return output;
        } finally {
            Files.deleteIfExists(outputFile);
        }
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
         *             if the temporary file could not be created.
         */
        public static void main(final String[] args) throws IOException {
            final var tempFile = TempFile.create("BOOT-INF/lib/café.jar");
            System.out.println(System.getProperty("sun.jnu.encoding"));
            System.out.println(tempFile.getName());
            if (!TempFile.delete(tempFile)) {
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
    void aNonAsciiEntryNameIsReplacedWhenFilenamesAreAscii(@TempDir final Path tempDir) throws Exception {
        assumeTrue(System.getProperty("os.name").startsWith("Linux"), "The C locale is only tested on Linux");
        final var processBuilder = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-Djava.io.tmpdir=" + tempDir,
                "-cp", System.getProperty("java.class.path"), CreateNonAsciiTempFile.class.getName())
                .redirectErrorStream(true);
        processBuilder.environment().keySet().removeIf(name -> name.startsWith("LC_"));
        processBuilder.environment().put("LC_ALL", "C");
        processBuilder.environment().put("LANG", "C");
        final var output = runChildJvm(processBuilder);
        // Read the last two lines, since the JVM may print a notice such as "Picked up JAVA_TOOL_OPTIONS" first
        final var outputLines = output.lines().toList();
        assertThat(outputLines).as("Child JVM output:%n%s", output).hasSizeGreaterThanOrEqualTo(2);
        assumeTrue(!outputLines.get(outputLines.size() - 2).equals("UTF-8"),
                "The C locale stores filenames in UTF-8 here");
        assertThat(outputLines.get(outputLines.size() - 1)).endsWith("caf_.jar");
        try (var tempFiles = Files.list(tempDir)) {
            assertThat(tempFiles).isEmpty();
        }
    }

    /**
     * Run in a child JVM: try twice to create a temporary file, and print the encoding that filenames are stored
     * in, then the class of the {@link IOException} that each attempt threw, or the name of the file it created.
     */
    static final class CreateTempFileInNonAsciiTempDir {
        /** Not instantiable. */
        private CreateTempFileInNonAsciiTempDir() {
        }

        /**
         * Entry point.
         *
         * @param args
         *            ignored.
         */
        public static void main(final String[] args) {
            System.out.println(System.getProperty("sun.jnu.encoding"));
            for (var i = 0; i < 2; i++) {
                try {
                    final var tempFile = TempFile.create("BOOT-INF/lib/lib.jar");
                    System.out.println(tempFile.getName());
                    TempFile.delete(tempFile);
                } catch (final IOException e) {
                    System.out.println(e.getClass().getName());
                }
            }
        }
    }

    /**
     * A JVM started under the C locale on Linux stores filenames in ASCII, so it cannot represent a temporary
     * directory with a non-ASCII name. Creating a temporary file must then throw an {@link IOException} every time,
     * which skips only the jar being spilled to disk, rather than an {@link Error} that aborts the whole scan.
     *
     * @param parentDir
     *            the directory that holds the temporary directory of the child JVM.
     * @throws Exception
     *             if the child JVM could not be run.
     */
    @Test
    void aTempDirThatFilenamesCannotRepresentThrowsIOException(@TempDir final Path parentDir) throws Exception {
        assumeTrue(System.getProperty("os.name").startsWith("Linux"), "The C locale is only tested on Linux");
        assumeTrue(System.getProperty("sun.jnu.encoding").equals("UTF-8"),
                "This JVM cannot create a directory with a non-ASCII name");
        final var tempDir = Files.createDirectory(parentDir.resolve("tmpé"));
        final var processBuilder = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-Djava.io.tmpdir=" + tempDir,
                "-cp", System.getProperty("java.class.path"), CreateTempFileInNonAsciiTempDir.class.getName())
                .redirectErrorStream(true);
        processBuilder.environment().keySet().removeIf(name -> name.startsWith("LC_"));
        processBuilder.environment().put("LC_ALL", "C");
        processBuilder.environment().put("LANG", "C");
        final var output = runChildJvm(processBuilder);
        // Read the last three lines, since the JVM may print a notice such as "Picked up JAVA_TOOL_OPTIONS" first
        final var outputLines = output.lines().toList();
        assertThat(outputLines).as("Child JVM output:%n%s", output).hasSizeGreaterThanOrEqualTo(3);
        assumeTrue(!outputLines.get(outputLines.size() - 3).equals("UTF-8"),
                "The C locale stores filenames in UTF-8 here");
        assertThat(outputLines.subList(outputLines.size() - 2, outputLines.size()))
                .as("Child JVM output:%n%s", output).containsOnly(IOException.class.getName());
        try (var tempFiles = Files.list(tempDir)) {
            assertThat(tempFiles).isEmpty();
        }
    }
}
