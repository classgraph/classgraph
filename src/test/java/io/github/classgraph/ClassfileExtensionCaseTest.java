package io.github.classgraph;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Scans classfiles whose {@code ".class"} extension is not written in lower case.
 *
 * <p>
 * A classfile that has been through a filesystem or archiving tool that upper-cases filenames keeps its class name
 * and its position in the directory tree, and can still be read, so it is scanned like any other classfile. Every
 * test in here scans a real classfile out of a real directory or jarfile.
 */
public class ClassfileExtensionCaseTest {
    /** The name of the class whose classfile is copied into each classpath element and then scanned for. */
    private static final String CLASS_NAME = ClassfileExtensionCaseTest.class.getName();

    /** The path of that classfile within a classpath element, without its extension. */
    private static final String CLASSFILE_PATH_NO_EXTENSION = CLASS_NAME.replace('.', '/');

    /** The path of that classfile within a classpath element. */
    private static final String CLASSFILE_PATH = CLASSFILE_PATH_NO_EXTENSION + ".class";

    /**
     * The spellings of the {@code ".class"} extension that have to be read as a classfile.
     *
     * @return the extensions.
     */
    static Stream<String> extensions() {
        return Stream.of(".class", ".CLASS", ".Class");
    }

    /**
     * A classfile in a directory is scanned whatever the case of its extension.
     *
     * @param extension
     *            the spelling of the extension to write the classfile with.
     * @param tempDir
     *            a temporary directory to build in.
     * @throws IOException
     *             if the classpath element could not be built.
     */
    @ParameterizedTest
    @MethodSource("extensions")
    public void classfileInADirectoryIsScanned(final String extension, @TempDir final Path tempDir)
            throws IOException {
        final Path dir = makeClassfileDir(tempDir, extension);
        try (ScanResult scanResult = new ClassGraph().overrideClasspath(dir.toString()).enableClassInfo().scan()) {
            assertThat(scanResult.getAllClasses().getNames()).containsExactly(CLASS_NAME);
            assertThat(scanResult.getAllResources().classFilesOnly().getPaths())
                    .containsExactly(CLASSFILE_PATH_NO_EXTENSION + extension);
        }
    }

    /**
     * A classfile in a jarfile is scanned whatever the case of its extension.
     *
     * @param extension
     *            the spelling of the extension to write the classfile with.
     * @param tempDir
     *            a temporary directory to build in.
     * @throws IOException
     *             if the classpath element could not be built.
     */
    @ParameterizedTest
    @MethodSource("extensions")
    public void classfileInAJarIsScanned(final String extension, @TempDir final Path tempDir) throws IOException {
        final Path jar = makeJar(tempDir, CLASSFILE_PATH_NO_EXTENSION + extension);
        try (ScanResult scanResult = new ClassGraph().overrideClasspath(jar.toString()).enableClassInfo().scan()) {
            assertThat(scanResult.getAllClasses().getNames()).containsExactly(CLASS_NAME);
            assertThat(scanResult.getAllResources().classFilesOnly().getPaths())
                    .containsExactly(CLASSFILE_PATH_NO_EXTENSION + extension);
        }
    }

    /**
     * A class accepted by name is found whatever the case of the extension of its classfile, and a class rejected
     * by name is rejected whatever the case of the extension of its classfile. Accept and reject criteria are built
     * from class names, so they name a classfile with a lower-case extension.
     *
     * @param extension
     *            the spelling of the extension to write the classfile with.
     * @param tempDir
     *            a temporary directory to build in.
     * @throws IOException
     *             if the classpath element could not be built.
     */
    @ParameterizedTest
    @MethodSource("extensions")
    public void classAcceptedOrRejectedByNameMatchesTheClassfile(final String extension,
            @TempDir final Path tempDir) throws IOException {
        final String dir = makeClassfileDir(tempDir, extension).toString();
        try (ScanResult scanResult = new ClassGraph().overrideClasspath(dir).enableClassInfo()
                .acceptClasses(CLASS_NAME).scan()) {
            assertThat(scanResult.getAllClasses().getNames()).containsExactly(CLASS_NAME);
        }
        try (ScanResult scanResult = new ClassGraph().overrideClasspath(dir).enableClassInfo()
                .rejectClasses(CLASS_NAME).scan()) {
            assertThat(scanResult.getAllClasses().getNames()).isEmpty();
        }
    }

    /**
     * A jarfile can hold the same classfile twice, spelled with two different extensions -- the second one is
     * masked by the first, exactly as a duplicate of an identical path would be, rather than being scanned a second
     * time under the same class name.
     *
     * @param tempDir
     *            a temporary directory to build in.
     * @throws IOException
     *             if the classpath element could not be built.
     */
    @Test
    public void twoSpellingsOfOneClassfileInAJarAreMasked(@TempDir final Path tempDir) throws IOException {
        final Path jar = makeJar(tempDir, CLASSFILE_PATH, CLASSFILE_PATH_NO_EXTENSION + ".CLASS");
        try (ScanResult scanResult = new ClassGraph().overrideClasspath(jar.toString()).enableClassInfo().scan()) {
            assertThat(scanResult.getAllClasses().getNames()).containsExactly(CLASS_NAME);
        }
    }

    /**
     * A file named only {@code ".class"} has an empty class name, so it is a resource rather than a classfile, and
     * is not scanned as one.
     *
     * @param tempDir
     *            a temporary directory to build in.
     * @throws IOException
     *             if the classpath element could not be built.
     */
    @Test
    public void aFileNamedOnlyDotClassIsAResource(@TempDir final Path tempDir) throws IOException {
        final Path jar = makeJar(tempDir, "pkg/.class");
        try (ScanResult scanResult = new ClassGraph().overrideClasspath(jar.toString()).enableClassInfo().scan()) {
            assertThat(scanResult.getAllClasses().getNames()).isEmpty();
            assertThat(scanResult.getAllResources().getPaths()).containsExactly("pkg/.class");
            assertThat(scanResult.getAllResources().classFilesOnly()).isEmpty();
        }
    }

    /**
     * Create a directory containing one classfile, written with the given extension.
     *
     * @param tempDir
     *            the directory to create it in.
     * @param extension
     *            the spelling of the extension to write the classfile with.
     * @return the directory, which is a usable classpath element.
     * @throws IOException
     *             if the directory could not be created.
     */
    private static Path makeClassfileDir(final Path tempDir, final String extension) throws IOException {
        final Path dir = tempDir.resolve("classes");
        final Path classfile = dir.resolve(CLASSFILE_PATH_NO_EXTENSION + extension);
        Files.createDirectories(classfile.getParent());
        Files.write(classfile, classfileBytes());
        return dir;
    }

    /**
     * Create a jarfile containing this test class' classfile at each of the given paths.
     *
     * @param tempDir
     *            the directory to create it in.
     * @param entryNames
     *            the names to store the classfile under.
     * @return the jarfile.
     * @throws IOException
     *             if the jarfile could not be created.
     */
    private static Path makeJar(final Path tempDir, final String... entryNames) throws IOException {
        final Path jar = tempDir.resolve("probe.jar");
        final byte[] bytes = classfileBytes();
        try (JarOutputStream jarOut = new JarOutputStream(Files.newOutputStream(jar))) {
            for (final String entryName : entryNames) {
                jarOut.putNextEntry(new JarEntry(entryName));
                jarOut.write(bytes);
                jarOut.closeEntry();
            }
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
        try (InputStream inputStream = ClassfileExtensionCaseTest.class.getClassLoader()
                .getResourceAsStream(CLASSFILE_PATH)) {
            assertThat(inputStream).as("classfile %s", CLASSFILE_PATH).isNotNull();
            final ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
            final byte[] buf = new byte[8192];
            for (int numRead = inputStream.read(buf); numRead != -1; numRead = inputStream.read(buf)) {
                byteArrayOutputStream.write(buf, 0, numRead);
            }
            return byteArrayOutputStream.toByteArray();
        }
    }
}
