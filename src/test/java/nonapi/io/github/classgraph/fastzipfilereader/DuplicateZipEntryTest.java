package nonapi.io.github.classgraph.fastzipfilereader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.ResourceList;
import io.github.classgraph.ScanResult;
import nonapi.io.github.classgraph.utils.VersionFinder;

/**
 * Reads jarfiles that hold two entries with the same name. {@link ZipOutputStream} will not write such a jarfile,
 * but other tools will, and {@link JarFile}, and so every classloader that reads a jarfile, finds the last of the
 * two in the central directory. ClassGraph has to find the same one.
 */
public class DuplicateZipEntryTest {
    /**
     * Write a jarfile, then give each entry whose name is a key of {@code renames} the name it maps to. The two
     * names of each pair must be the same length, and must not occur anywhere else in the jarfile.
     *
     * @param jar
     *            the jarfile to write
     * @param renames
     *            pairs of names: the name an entry is written under, then the name it is given afterward
     * @param names
     *            the names of the entries
     * @param contents
     *            the content of each entry
     * @throws IOException
     *             if the jarfile could not be written
     */
    private static void writeJar(final File jar, final String[] renames, final String[] names,
            final byte[][] contents) throws IOException {
        try (ZipOutputStream zipOut = new ZipOutputStream(new FileOutputStream(jar))) {
            for (int i = 0; i < names.length; i++) {
                zipOut.putNextEntry(new ZipEntry(names[i]));
                zipOut.write(contents[i]);
                zipOut.closeEntry();
            }
        }
        // Each name occurs twice, in the local file header and in the central directory, and nothing that is
        // checksummed covers either of them, so renaming them in place makes a valid jarfile
        String content = new String(Files.readAllBytes(jar.toPath()), StandardCharsets.ISO_8859_1);
        for (int i = 0; i < renames.length; i += 2) {
            content = content.replace(renames[i], renames[i + 1]);
        }
        Files.write(jar.toPath(), content.getBytes(StandardCharsets.ISO_8859_1));
    }

    /**
     * Write a jarfile with text entries, then rename entries, as {@link #writeJar(File, String[], String[],
     * byte[][])} does.
     *
     * @param jar
     *            the jarfile to write
     * @param renames
     *            pairs of names: the name an entry is written under, then the name it is given afterward
     * @param namesAndContents
     *            pairs of strings: the name of an entry, then its content
     * @throws IOException
     *             if the jarfile could not be written
     */
    private static void writeJar(final File jar, final String[] renames, final String... namesAndContents)
            throws IOException {
        final String[] names = new String[namesAndContents.length / 2];
        final byte[][] contents = new byte[names.length][];
        for (int i = 0; i < names.length; i++) {
            names[i] = namesAndContents[2 * i];
            contents[i] = namesAndContents[2 * i + 1].getBytes(StandardCharsets.UTF_8);
        }
        writeJar(jar, renames, names, contents);
    }

    /**
     * The content of the single resource with the given path.
     *
     * @param classpath
     *            the classpath to scan
     * @param path
     *            the path of the resource
     * @return the content of the resource, as a string
     * @throws IOException
     *             if the resource could not be read
     */
    private static String contentOfOnlyResource(final String classpath, final String path) throws IOException {
        try (ScanResult scanResult = new ClassGraph().overrideClasspath(classpath).acceptPaths("").scan()) {
            final ResourceList resources = scanResult.getResourcesWithPath(path);
            assertThat(resources).hasSize(1);
            return resources.get(0).getContentAsString();
        }
    }

    /**
     * Of two entries with the same name, the last one in the central directory is the only one found, as it is
     * the one a classloader reads.
     */
    @Test
    public void theLastOfTwoEntriesWithTheSameNameIsTheOneFound(@TempDir final File tempDir) throws IOException {
        final File jar = new File(tempDir, "dup.jar");
        writeJar(jar, new String[] { "dup-2.txt", "dup-1.txt" }, "dup-1.txt", "first", "dup-2.txt", "second");
        try (JarFile jarFile = new JarFile(jar);
                InputStream in = jarFile.getInputStream(jarFile.getEntry("dup-1.txt"))) {
            final byte[] buf = new byte[16];
            assertThat(new String(buf, 0, in.read(buf), StandardCharsets.UTF_8)).isEqualTo("second");
        }
        assertThat(contentOfOnlyResource(jar.getPath(), "dup-1.txt")).isEqualTo("second");
    }

    /**
     * Of two versioned entries of a multi-release jarfile with the same name, the last one in the central directory
     * is the one found, as it is the one a classloader reads.
     */
    @Test
    public void theLastOfTwoVersionedEntriesWithTheSameNameIsTheOneFound(@TempDir final File tempDir)
            throws IOException {
        assumeTrue(VersionFinder.JAVA_MAJOR_VERSION >= 9, "Multi-release jars are only resolved from JDK 9");
        final File jar = new File(tempDir, "dup.jar");
        writeJar(jar, new String[] { "dup-2.txt", "dup-1.txt" }, //
                JarFile.MANIFEST_NAME, "Manifest-Version: 1.0\r\nMulti-Release: true\r\n\r\n", //
                "dup-1.txt", "base", //
                "META-INF/versions/9/dup-1.txt", "first", //
                "META-INF/versions/9/dup-2.txt", "second");
        assertThat(contentOfOnlyResource(jar.getPath(), "dup-1.txt")).isEqualTo("second");
    }

    /**
     * Of two nested jarfiles with the same name, the last one in the central directory of the outer jarfile is the
     * one that is opened, as it is the one a classloader reads.
     */
    @Test
    public void theLastOfTwoNestedJarfilesWithTheSameNameIsTheOneOpened(@TempDir final File tempDir)
            throws IOException {
        final File firstInnerJar = new File(tempDir, "first.jar");
        writeJar(firstInnerJar, new String[0], "a.txt", "first");
        final File secondInnerJar = new File(tempDir, "second.jar");
        writeJar(secondInnerJar, new String[0], "a.txt", "second");
        final File outerJar = new File(tempDir, "outer.jar");
        writeJar(outerJar, new String[] { "lib/innex.jar", "lib/inner.jar" },
                new String[] { "lib/inner.jar", "lib/innex.jar" }, new byte[][] {
                        Files.readAllBytes(firstInnerJar.toPath()), Files.readAllBytes(secondInnerJar.toPath()) });
        assertThat(contentOfOnlyResource(outerJar.getPath() + "!/lib/inner.jar", "a.txt")).isEqualTo("second");
    }

    /**
     * Of two manifests stored under the same name, the last one in the central directory is the manifest, as it is
     * the one {@link JarFile#getManifest()} reads.
     */
    @Test
    public void theLastOfTwoManifestsWithTheSameNameIsTheManifest(@TempDir final File tempDir) throws IOException {
        writeJar(new File(tempDir, "first.jar"), new String[0], "a.txt", "first");
        writeJar(new File(tempDir, "second.jar"), new String[0], "a.txt", "second");
        final File jar = new File(tempDir, "dup.jar");
        writeJar(jar, new String[] { "META-INF/MANIFEST.MX", JarFile.MANIFEST_NAME }, //
                JarFile.MANIFEST_NAME, "Manifest-Version: 1.0\r\nClass-Path: first.jar\r\n\r\n", //
                "META-INF/MANIFEST.MX", "Manifest-Version: 1.0\r\nClass-Path: second.jar\r\n\r\n");
        try (JarFile jarFile = new JarFile(jar)) {
            assertThat(jarFile.getManifest().getMainAttributes().getValue("Class-Path")).isEqualTo("second.jar");
        }
        try (ScanResult scanResult = new ClassGraph().overrideClasspath(jar.getPath()).scan()) {
            assertThat(scanResult.getClasspathFiles()).extracting(File::getName).contains("second.jar")
                    .doesNotContain("first.jar");
        }
    }
}
