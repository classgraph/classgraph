package io.github.classgraph;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import io.github.classgraph.base.internal.path.FastPathResolver;
import io.github.classgraph.base.internal.path.URLPaths;

/**
 * Scans real directories and jarfiles whose paths contain the characters that have to be escaped in a URL, and
 * checks that what ClassGraph hands back still names a readable file.
 *
 * <p>
 * These are the cases where a difference between Linux, macOS and Windows shows up as a class that is not found
 * rather than as an odd-looking string, so the CI matrix runs this on all three platforms.
 */
public class AwkwardPathNamesTest {
    /** The name of the class whose classfile is copied into each awkward path and then scanned for. */
    private static final String CLASS_NAME = AwkwardPathNamesTest.class.getName();

    /** The path of that classfile within a classpath element. */
    private static final String CLASSFILE_PATH = CLASS_NAME.replace('.', '/') + ".class";

    /**
     * Directory names containing the characters that have to be escaped in a URL, or that used to be mistaken for
     * syntax. ':' and '?' are not tested, since Windows does not allow them in a filename.
     *
     * @return the directory names.
     */
    // A space is escaped as %20, '+' means a space in a query string, '%' introduces an escape sequence, '#'
    // starts a URL fragment, a non-ASCII letter has to be escaped as its UTF-8 bytes (#468), and '!' used to be
    // read as a nested jar separator wherever it appeared (#903), which a name ending in '!' tests most directly,
    // since it puts a "!/" in the path
    static Stream<String> awkwardNames() {
        return Stream.of("plain", "with space", "with+plus", "with%25percent", "with#hash", "café", "with!bang",
                "ends-with-bang!");
    }

    /**
     * A directory classpath element in an awkward path, named by its path, by its URL, and as the resolver rewrites
     * it.
     *
     * @param dirName
     *            the awkward directory name.
     * @param tempDir
     *            a temporary directory to build in.
     * @throws IOException
     *             if the classpath element could not be built.
     */
    @ParameterizedTest
    @MethodSource("awkwardNames")
    public void directoryInAnAwkwardPathIsScanned(final String dirName, @TempDir final Path tempDir)
            throws IOException {
        final var dir = makeClassfileDir(tempDir, dirName);
        assertThatScanFindsTheClass(dir.toString());
        assertThatScanFindsTheClass(dir.toUri().toString());
        // The path the resolver produces for the directory must still name the directory
        assertThat(Path.of(FastPathResolver.resolve(dir.toUri().toString()))).isDirectory();
    }

    /**
     * A jarfile classpath element in an awkward path, named by its path, by its URL, and by its {@code "jar:"} URL,
     * with the URL of a resource inside it read back afterwards.
     *
     * @param dirName
     *            the awkward directory name.
     * @param tempDir
     *            a temporary directory to build in.
     * @throws IOException
     *             if the classpath element could not be built.
     */
    @ParameterizedTest
    @MethodSource("awkwardNames")
    public void jarInAnAwkwardPathIsScanned(final String dirName, @TempDir final Path tempDir) throws IOException {
        final var jar = makeJar(tempDir, dirName);
        assertThatScanFindsTheClass(jar.toString());
        assertThatScanFindsTheClass(jar.toUri().toString());
        assertThatScanFindsTheClass("jar:" + jar.toUri() + "!/");

        // An accept criterion matches the leafname of the jar, which used to end at the first '!' in the path, so a
        // jar below a directory whose name contains a '!' was matched by its directory's name and silently skipped
        // #903
        try (var scanResult = new ClassGraph().enableClasspathEntries(jar.toString()).enableClassInfo()
                .acceptJars("probe.jar").scan()) {
            assertThat(scanResult.getAllClasses().getNames()).as("accepting probe.jar in %s", dirName)
                    .containsExactly(CLASS_NAME);
        }

        // The URL of a resource within the jar has to name something that can be read back
        try (var scanResult = new ClassGraph().enableClasspathEntries(jar.toString()).scan()) {
            final var resource = scanResult.getResourcesWithExtension("class").get(0);
            final var connection = resource.getURL().openConnection();
            // The JDK caches the JarFile behind a "jar:" URL, and a cached JarFile is never closed, which would
            // leave the jarfile open and so stop the temporary directory from being deleted on Windows
            connection.setUseCaches(false);
            try (var inputStream = connection.getInputStream()) {
                assertThat(inputStream.readAllBytes()).isNotEmpty();
            }
        }
    }

    /**
     * A jarfile nested within a jarfile, and a package root within a jarfile, each named by its path and by its
     * {@code "jar:"} URL, where the awkward name is a directory within the outer jarfile, and the outer jarfile is
     * either in a plain directory or in a directory with the same awkward name. The scanner decodes such a
     * classpath element to a path and then turns it back into a {@code "jar:"} URL, which used to leave a '%' in
     * the path unescaped, so the URL was decoded again when it was opened and named a file or entry that does not
     * exist.
     *
     * <p>
     * A name ending in '!' is only used for the directory that holds the outer jarfile. Within a jarfile, every
     * {@code "!/"} after the outermost separator is a nested jar separator, so a directory entry whose name ends in
     * '!' cannot be named in a classpath element, however the path is spelled.
     *
     * @param dirName
     *            the awkward directory name.
     * @param tempDir
     *            a temporary directory to build in.
     * @throws IOException
     *             if the classpath elements could not be built.
     */
    @ParameterizedTest
    @MethodSource("awkwardNames")
    public void nestedClasspathElementInAnAwkwardPathIsScanned(final String dirName, @TempDir final Path tempDir)
            throws IOException {
        final var jar = makeJar(tempDir, dirName);
        final var entryDirName = dirName.endsWith("!") ? "plain" : dirName;
        for (final Path outerJar : List.of(tempDir.resolve("outer.jar"), jar.resolveSibling("outer.jar"))) {
            try (var jarOut = new JarOutputStream(Files.newOutputStream(outerJar))) {
                jarOut.putNextEntry(new JarEntry(entryDirName + "/lib/inner.jar"));
                jarOut.write(Files.readAllBytes(jar));
                jarOut.closeEntry();
                jarOut.putNextEntry(new JarEntry(entryDirName + "/classes/" + CLASSFILE_PATH));
                jarOut.write(classfileBytes());
                jarOut.closeEntry();
            }
            for (final String nestedPath : List.of("/lib/inner.jar", "/classes")) {
                assertThatScanFindsTheClass(outerJar + "!/" + entryDirName + nestedPath);
                assertThatScanFindsTheClass(
                        "jar:" + outerJar.toUri() + "!/" + URLPaths.encodePath(entryDirName) + nestedPath);
            }
        }
    }

    /**
     * The URL of a resource within a jarfile below a directory whose name ends in '!'. The JDK ends the jarfile's
     * part of a {@code "jar:"} URL at its first {@code "!/"}, so an unescaped '!' at the end of the directory name
     * ended the jarfile's part there, and the URL named a file that does not exist.
     *
     * @param tempDir
     *            a temporary directory to build in.
     * @throws IOException
     *             if the jarfile could not be built or read.
     */
    @Test
    public void resourceURLOfAJarBelowADirectoryEndingInBangCanBeRead(@TempDir final Path tempDir)
            throws IOException {
        final var jar = makeJar(tempDir, "ends-with-bang!");
        try (var scanResult = new ClassGraph().enableClasspathEntries(jar.toString()).scan()) {
            final var resource = scanResult.getResourcesWithExtension("class").get(0);
            final var connection = resource.getURL().openConnection();
            // A cached JarFile is never closed, which would stop the temporary directory from being deleted on
            // Windows
            connection.setUseCaches(false);
            try (var inputStream = connection.getInputStream()) {
                assertThat(inputStream.readAllBytes()).isEqualTo(classfileBytes());
            }
            // The jarfile's part of the URL, given back to ClassGraph, names the same jarfile
            final var uriStr = resource.getURI().toString();
            assertThatScanFindsTheClass(uriStr.substring(0, uriStr.lastIndexOf("!/") + 2));
        }
    }

    /**
     * A jarfile, and a jarfile nested within one, below a directory whose name ends in '!'. The jar accept and
     * reject criteria are matched against the name of each jarfile in the path, and those names were found by
     * splitting the path at every {@code "!/"}, so the directory name was taken for the name of an enclosing
     * jarfile: accepting the jarfile by its own name skipped it, and rejecting the directory's name rejected it.
     *
     * @param tempDir
     *            a temporary directory to build in.
     * @throws IOException
     *             if the jarfiles could not be built.
     */
    @Test
    public void jarBelowADirectoryEndingInBangIsMatchedByItsOwnName(@TempDir final Path tempDir)
            throws IOException {
        final var jar = makeJar(tempDir, "ends-with-bang!");
        for (final String classpathElement : List.of(jar.toString(), jar.toUri().toString())) {
            assertThatScanFindsTheClass(classpathElement, "probe.jar", null);
            assertThatScanFindsTheClass(classpathElement, null, "ends-with-bang");
            assertThat(scanWithJarFilter(classpathElement, null, "probe.jar")).isEmpty();
        }

        // The same, for a jarfile nested within a jarfile below the directory
        final var outerJar = jar.resolveSibling("outer.jar");
        try (var jarOut = new JarOutputStream(Files.newOutputStream(outerJar))) {
            jarOut.putNextEntry(new JarEntry("lib/inner.jar"));
            jarOut.write(Files.readAllBytes(jar));
            jarOut.closeEntry();
        }
        final var nestedJar = outerJar + "!/lib/inner.jar";
        assertThatScanFindsTheClass(nestedJar, "outer.jar,inner.jar", null);
        assertThatScanFindsTheClass(nestedJar, null, "ends-with-bang");
        assertThat(scanWithJarFilter(nestedJar, "outer.jar", null)).isEmpty();
        assertThat(scanWithJarFilter(nestedJar, null, "inner.jar")).isEmpty();
    }

    /**
     * Scan the given classpath element with the given jar accept and reject criteria, and assert that the probe
     * class was found in it.
     *
     * @param classpathElement
     *            the classpath element to scan.
     * @param acceptJars
     *            the comma-separated names of the jarfiles to accept, or null to accept all jarfiles.
     * @param rejectJars
     *            the comma-separated names of the jarfiles to reject, or null to reject none.
     */
    private static void assertThatScanFindsTheClass(final String classpathElement,
            final @Nullable String acceptJars, final @Nullable String rejectJars) {
        assertThat(scanWithJarFilter(classpathElement, acceptJars, rejectJars))
                .as("scanning %s, accepting %s, rejecting %s", classpathElement, acceptJars, rejectJars)
                .containsExactly(CLASS_NAME);
    }

    /**
     * Scan the given classpath element with the given jar accept and reject criteria.
     *
     * @param classpathElement
     *            the classpath element to scan.
     * @param acceptJars
     *            the comma-separated names of the jarfiles to accept, or null to accept all jarfiles.
     * @param rejectJars
     *            the comma-separated names of the jarfiles to reject, or null to reject none.
     * @return the names of the classes found.
     */
    private static List<String> scanWithJarFilter(final String classpathElement, final @Nullable String acceptJars,
            final @Nullable String rejectJars) {
        final var classGraph = new ClassGraph().enableClasspathEntries(classpathElement).enableClassInfo();
        if (acceptJars != null) {
            classGraph.acceptJars(acceptJars.split(","));
        }
        if (rejectJars != null) {
            classGraph.rejectJars(rejectJars.split(","));
        }
        try (var scanResult = classGraph.scan()) {
            return scanResult.getAllClasses().getNames();
        }
    }

    /**
     * Scan the given classpath element, and assert that the probe class was found in it.
     *
     * @param classpathElement
     *            the classpath element to scan.
     */
    private static void assertThatScanFindsTheClass(final String classpathElement) {
        try (var scanResult = new ClassGraph().enableClasspathEntries(classpathElement).enableClassInfo().scan()) {
            assertThat(scanResult.getAllClasses().getNames()).as("scanning %s", classpathElement)
                    .containsExactly(CLASS_NAME);
        }
    }

    /**
     * Create a directory with the given name, containing one classfile.
     *
     * @param tempDir
     *            the directory to create it in.
     * @param dirName
     *            the name of the directory to create.
     * @return the directory, which is a usable classpath element.
     * @throws IOException
     *             if the directory could not be created.
     */
    private static Path makeClassfileDir(final Path tempDir, final String dirName) throws IOException {
        final var dir = tempDir.resolve(dirName);
        final var classfile = dir.resolve(CLASSFILE_PATH);
        Files.createDirectories(classfile.getParent());
        Files.write(classfile, classfileBytes());
        return dir;
    }

    /**
     * Create a directory with the given name, containing a jarfile that holds one classfile.
     *
     * @param tempDir
     *            the directory to create it in.
     * @param dirName
     *            the name of the directory to create.
     * @return the jarfile.
     * @throws IOException
     *             if the jarfile could not be created.
     */
    private static Path makeJar(final Path tempDir, final String dirName) throws IOException {
        final var dir = Files.createDirectories(tempDir.resolve(dirName));
        final var jar = dir.resolve("probe.jar");
        try (var jarOut = new JarOutputStream(Files.newOutputStream(jar))) {
            jarOut.putNextEntry(new JarEntry(CLASSFILE_PATH));
            jarOut.write(classfileBytes());
            jarOut.closeEntry();
        }
        return jar;
    }

    /**
     * The bytes of this test class' own classfile, used as the one class that the scans have to find.
     *
     * @return the classfile bytes.
     * @throws IOException
     *             if the classfile could not be read.
     */
    private static byte[] classfileBytes() throws IOException {
        try (InputStream inputStream = AwkwardPathNamesTest.class.getClassLoader()
                .getResourceAsStream(CLASSFILE_PATH)) {
            assertThat(inputStream).as("classfile %s", CLASSFILE_PATH).isNotNull();
            return inputStream.readAllBytes();
        }
    }
}
