package io.github.classgraph.vfs;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The first open and read in a JVM stays free of the JDK features that take milliseconds to set up the first time
 * they are used. A lambda on this path takes the first lambda in the JVM through the JDK's lambda linkage, and
 * compiling a regular expression loads the classes of the regular expression engine.
 */
class FirstOpenClassLoadingTest {
    /** The name of the file read in a jarfile and in a directory. */
    private static final String ENTRY_NAME = "a.txt";

    /**
     * Open a path in a new JVM, and read a file from it.
     */
    static final class Child {
        /**
         * Open the path given by the first argument.
         *
         * @param args
         *            the path to open.
         * @throws IOException
         *             if the path cannot be read.
         */
        public static void main(final String[] args) throws IOException {
            try (var vfs = new Vfs()) {
                vfs.open(args[0]).load(ENTRY_NAME);
            }
        }
    }

    /**
     * Run {@link Child} in a new JVM, and check which classes it loaded.
     *
     * @param path
     *            the path to open.
     * @param tempDir
     *            where to write the class loading log.
     * @throws Exception
     *             if the new JVM could not be run or did not succeed.
     */
    private static void checkFirstOpen(final Path path, final Path tempDir) throws Exception {
        final var classLoadLog = tempDir.resolve("class-load.log");
        final var process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "--enable-native-access=ALL-UNNAMED", "-Xlog:class+load:file=" + classLoadLog, "-cp",
                System.getProperty("java.class.path"), Child.class.getName(), path.toString())
                .redirectErrorStream(true).start();
        final var output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(process.waitFor()).as("Child JVM output:%n%s", output).isZero();
        final var loaded = Files.readAllLines(classLoadLog);
        assertThat(loaded).as("the class loading log").anyMatch(line -> line.contains(Child.class.getName()));
        assertThat(loaded).as("lambdas of ClassGraph classes")
                .noneMatch(line -> line.contains("io.github.classgraph") && line.contains("$$Lambda"));
        assertThat(loaded).as("the regular expression for percent escapes")
                .noneMatch(line -> line.contains("FastPathResolver$PercentEscapes"));
    }

    /**
     * Opening a jarfile and reading an entry.
     *
     * @param tempDir
     *            a temporary directory.
     * @throws Exception
     *             if the new JVM could not be run or did not succeed.
     */
    @Test
    void openingAJarfileUsesNoLambdas(@TempDir final Path tempDir) throws Exception {
        final var jarfile = tempDir.toRealPath().resolve("first-open.jar");
        try (var jar = new JarOutputStream(Files.newOutputStream(jarfile))) {
            jar.putNextEntry(new JarEntry(ENTRY_NAME));
            jar.write(new byte[64]);
            jar.closeEntry();
        }
        checkFirstOpen(jarfile, tempDir);
    }

    /**
     * Opening a directory and reading a file.
     *
     * @param tempDir
     *            a temporary directory.
     * @throws Exception
     *             if the new JVM could not be run or did not succeed.
     */
    @Test
    void openingADirectoryUsesNoLambdas(@TempDir final Path tempDir) throws Exception {
        final var dir = Files.createDirectory(tempDir.toRealPath().resolve("first-open"));
        Files.write(dir.resolve(ENTRY_NAME), new byte[64]);
        checkFirstOpen(dir, tempDir);
    }
}
