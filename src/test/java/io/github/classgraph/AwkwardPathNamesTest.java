package io.github.classgraph;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;

import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import nonapi.io.github.classgraph.utils.FastPathResolver;

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
    // read as a nested jar separator wherever it appeared (#903)
    static Stream<String> awkwardNames() {
        return Stream.of("plain", "with space", "with+plus", "with%25percent", "with#hash", "café", "with!bang");
    }

    /**
     * A directory classpath element in an awkward path, named by its path, by its URL, and as the resolver
     * rewrites it.
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
        final Path dir = makeClassfileDir(tempDir, dirName);
        assertThatScanFindsTheClass(dir.toString());
        assertThatScanFindsTheClass(dir.toUri().toString());
        // The path the resolver produces for the directory must still name the directory
        assertThat(Paths.get(FastPathResolver.resolve(dir.toUri().toString()))).isDirectory();
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
        final Path jar = makeJar(tempDir, dirName);
        assertThatScanFindsTheClass(jar.toString());
        assertThatScanFindsTheClass(jar.toUri().toString());
        assertThatScanFindsTheClass("jar:" + jar.toUri() + "!/");

        // The URL of a resource within the jar has to name something that can be read back
        try (ScanResult scanResult = new ClassGraph().overrideClasspath(jar.toString()).scan()) {
            final URL url = scanResult.getResourcesWithExtension("class").get(0).getURL();
            final URLConnection connection = url.openConnection();
            // The JDK caches the JarFile behind a "jar:" URL, and a cached JarFile is never closed, which would
            // leave the jarfile open and so stop the temporary directory from being deleted on Windows
            connection.setUseCaches(false);
            try (InputStream inputStream = connection.getInputStream()) {
                assertThat(readAllBytes(inputStream)).isNotEmpty();
            }
        }
    }

    /**
     * Scan the given classpath element, and assert that the probe class was found in it.
     *
     * @param classpathElement
     *            the classpath element to scan.
     */
    private static void assertThatScanFindsTheClass(final String classpathElement) {
        try (ScanResult scanResult = new ClassGraph().overrideClasspath(classpathElement).enableClassInfo()
                .scan()) {
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
        final Path dir = tempDir.resolve(dirName);
        final Path classfile = dir.resolve(CLASSFILE_PATH);
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
        final Path dir = Files.createDirectories(tempDir.resolve(dirName));
        final Path jar = dir.resolve("probe.jar");
        try (JarOutputStream jarOut = new JarOutputStream(Files.newOutputStream(jar))) {
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
            return readAllBytes(inputStream);
        }
    }

    /**
     * Read a stream to its end.
     *
     * @param inputStream
     *            the stream to read.
     * @return the bytes read.
     * @throws IOException
     *             if the stream could not be read.
     */
    private static byte[] readAllBytes(final InputStream inputStream) throws IOException {
        final ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        final byte[] buf = new byte[8192];
        for (int numRead = inputStream.read(buf); numRead != -1; numRead = inputStream.read(buf)) {
            byteArrayOutputStream.write(buf, 0, numRead);
        }
        return byteArrayOutputStream.toByteArray();
    }
}
