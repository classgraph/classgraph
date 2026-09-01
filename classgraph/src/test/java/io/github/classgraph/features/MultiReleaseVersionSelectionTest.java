package io.github.classgraph.features;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.ScanResult;

/**
 * Only one versioned section of a multi-release jarfile is ever visible.
 *
 * <p>
 * A multi-release jarfile presents a single view: the entries of the versioned sections up to the version of the
 * running JVM, each masking the entry of the same unversioned path in every older section and in the base of the
 * jarfile. Nothing else stored beneath {@code META-INF/versions/} is part of that view -- not a section for a
 * version newer than the running JVM, whose classfiles this JVM could not even load, not a section whose version is
 * not a version number this JVM would select, and not any section at all in a jarfile whose manifest does not have
 * the {@code Multi-Release} key. Those entries are not visible as resources either, since a versioned section is
 * not a place a classloader reads resources from.
 *
 * <p>
 * {@code disableMultiReleaseVersions()} turns the whole mechanism off, and every entry is then reported under the
 * path it is stored under, versioned sections included.
 */
public class MultiReleaseVersionSelectionTest {
    /** The path of an entry that is stored in the base of the jarfile and in each versioned section. */
    private static final String OVERRIDDEN = "pkg/overridden.txt";

    /**
     * The entries of the probe jarfile, in the order they are written to it, mapped to their content.
     *
     * <p>
     * The entry of the section for version 9 is written before the base entry it masks, so that a scan that reports
     * the right content cannot be doing so just by taking the first entry with a given name.
     */
    private static final Map<String, String> ENTRIES = new LinkedHashMap<>();

    static {
        ENTRIES.put("META-INF/versions/9/" + OVERRIDDEN, "9");
        ENTRIES.put(OVERRIDDEN, "base");
        // A section for a version far newer than any JVM that could be running this test
        ENTRIES.put("META-INF/versions/9999/" + OVERRIDDEN, "9999");
        ENTRIES.put("META-INF/versions/9999/pkg/only-in-9999.txt", "9999");
        // Version numbers that no JVM selects: multi-release sections start at 9, and a section has to be named by
        // a version number
        ENTRIES.put("META-INF/versions/8/pkg/only-in-8.txt", "8");
        ENTRIES.put("META-INF/versions/latest/pkg/only-in-latest.txt", "latest");
        // "The intention is that the META-INF directory cannot be versioned":
        // http://mail.openjdk.java.net/pipermail/jigsaw-dev/2018-October/013954.html
        ENTRIES.put("META-INF/versions/9/META-INF/not-versioned.txt", "9");
        // A classfile that a scan must not read as a class of a package named after the directories it is under
        ENTRIES.put("META-INF/versions/9999/pkg/Cls.class", "not a classfile");
    }

    /**
     * A multi-release jarfile shows the versioned section that the running JVM selects, and nothing else stored
     * beneath {@code META-INF/versions/}.
     *
     * @param tempDir
     *            a temporary directory to build in.
     * @throws IOException
     *             if the jarfile could not be built.
     */
    @Test
    public void onlyTheSelectedVersionedSectionIsVisible(@TempDir final Path tempDir) throws IOException {
        final var jar = makeJar(tempDir, /* isMultiRelease = */ true);
        try (var scanResult = new ClassGraph().enableClasspathEntries(jar).enableClassInfo().scan()) {
            // The entry of the section for version 9 masks the base entry, and is reported under the unversioned
            // path, which is where a classloader would read it from
            assertThat(scanResult.getAllResources().getPaths()).containsExactlyInAnyOrder("META-INF/MANIFEST.MF",
                    OVERRIDDEN);
            assertThat(contentOf(scanResult, OVERRIDDEN)).isEqualTo("9");
            assertThat(scanResult.getResourcesWithPath(OVERRIDDEN).get(0).getPathRelativeToContainer())
                    .isEqualTo("META-INF/versions/9/" + OVERRIDDEN);
            assertThat(scanResult.getAllClasses()).isEmpty();
        }
    }

    /**
     * A jarfile whose manifest does not have the {@code Multi-Release} key has no versioned sections, whatever it
     * stores beneath {@code META-INF/versions/}: the entries there mask nothing, because a classloader reading the
     * jarfile would not look for them.
     *
     * @param tempDir
     *            a temporary directory to build in.
     * @throws IOException
     *             if the jarfile could not be built.
     */
    @Test
    public void aJarfileThatIsNotMultiReleaseHasNoVersionedSections(@TempDir final Path tempDir)
            throws IOException {
        final var jar = makeJar(tempDir, /* isMultiRelease = */ false);
        try (var scanResult = new ClassGraph().enableClasspathEntries(jar).enableClassInfo().scan()) {
            assertThat(scanResult.getAllResources().getPaths()).containsExactlyInAnyOrder("META-INF/MANIFEST.MF",
                    OVERRIDDEN);
            // The base entry keeps its own content: the entry of the "version 9" section does not override it
            assertThat(contentOf(scanResult, OVERRIDDEN)).isEqualTo("base");
            assertThat(scanResult.getResourcesWithPath(OVERRIDDEN).get(0).getPathRelativeToContainer())
                    .isEqualTo(OVERRIDDEN);
            assertThat(scanResult.getAllClasses()).isEmpty();
        }
    }

    /**
     * {@code disableMultiReleaseVersions()} reports every entry under the path it is stored under, so every
     * versioned section is visible, whether or not the jarfile is a multi-release jarfile.
     *
     * @param tempDir
     *            a temporary directory to build in.
     * @throws IOException
     *             if the jarfile could not be built.
     */
    @Test
    public void disablingMultiReleaseVersionsShowsEverySection(@TempDir final Path tempDir) throws IOException {
        for (final var isMultiRelease : new boolean[] { true, false }) {
            final var jar = makeJar(tempDir.resolve(Boolean.toString(isMultiRelease)), isMultiRelease);
            try (var scanResult = new ClassGraph().enableClasspathEntries(jar).enableClassInfo()
                    .disableMultiReleaseVersions().scan()) {
                final List<String> everyEntry = new ArrayList<>(ENTRIES.keySet());
                everyEntry.add("META-INF/MANIFEST.MF");
                assertThat(scanResult.getAllResources().getPaths()).containsExactlyInAnyOrderElementsOf(everyEntry);
                assertThat(contentOf(scanResult, OVERRIDDEN)).isEqualTo("base");
                assertThat(contentOf(scanResult, "META-INF/versions/9/" + OVERRIDDEN)).isEqualTo("9");
                // The classfile is stored where no class could be loaded from, so it is a resource and not a class
                assertThat(scanResult.getAllClasses()).isEmpty();
            }
        }
    }

    /**
     * Read the content of the one resource at a path.
     *
     * @param scanResult
     *            the scan result to read it from.
     * @param path
     *            the path of the resource.
     * @return the content of the resource, as a string.
     * @throws IOException
     *             if the resource could not be read.
     */
    private static String contentOf(final ScanResult scanResult, final String path) throws IOException {
        final var resources = scanResult.getResourcesWithPath(path);
        assertThat(resources).hasSize(1);
        return new String(resources.get(0).load(), StandardCharsets.UTF_8);
    }

    /**
     * Create the probe jarfile.
     *
     * @param dir
     *            the directory to create it in.
     * @param isMultiRelease
     *            whether to write the {@code Multi-Release} key into the manifest.
     * @return the jarfile.
     * @throws IOException
     *             if the jarfile could not be created.
     */
    private static Path makeJar(final Path dir, final boolean isMultiRelease) throws IOException {
        Files.createDirectories(dir);
        final var jar = dir.resolve("probe.jar");
        try (var jarOut = new JarOutputStream(Files.newOutputStream(jar))) {
            write(jarOut, "META-INF/MANIFEST.MF",
                    "Manifest-Version: 1.0\r\n" + (isMultiRelease ? "Multi-Release: true\r\n" : "") + "\r\n");
            for (final var entry : ENTRIES.entrySet()) {
                write(jarOut, entry.getKey(), entry.getValue());
            }
        }
        return jar;
    }

    /**
     * Write one entry to a jarfile.
     *
     * @param jarOut
     *            the jarfile to write to.
     * @param entryName
     *            the name to store the entry under.
     * @param content
     *            the content of the entry.
     * @throws IOException
     *             if the entry could not be written.
     */
    private static void write(final JarOutputStream jarOut, final String entryName, final String content)
            throws IOException {
        jarOut.putNextEntry(new JarEntry(entryName));
        jarOut.write(content.getBytes(StandardCharsets.UTF_8));
        jarOut.closeEntry();
    }
}
