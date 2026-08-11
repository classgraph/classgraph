package nonapi.io.github.classgraph.classpath;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import nonapi.io.github.classgraph.classpath.ClasspathOrder.ClasspathEntry;
import nonapi.io.github.classgraph.reflection.ReflectionUtils;
import nonapi.io.github.classgraph.scanspec.ScanSpec;
import nonapi.io.github.classgraph.utils.FastPathResolver;
import nonapi.io.github.classgraph.utils.FileUtils;

/** Tests for {@link ClasspathOrder}. */
public class ClasspathOrderTest {
    /** The scan spec shared by a single test. */
    private final ScanSpec scanSpec = new ScanSpec();

    /** The classpath order under test. */
    private final ClasspathOrder classpathOrder = new ClasspathOrder(scanSpec, new ReflectionUtils());

    /**
     * Resolve a path the same way {@link ClasspathOrder} does, so that the expected values do not have to be
     * written differently for each platform.
     *
     * @param path
     *            the path to resolve.
     * @return the resolved path.
     */
    private static String resolve(final String path) {
        return FastPathResolver.resolve(FileUtils.currDirPath(), path);
    }

    /**
     * Create an empty file, and return its path in the form {@link ClasspathOrder} would resolve it to.
     *
     * @param path
     *            the path of the file to create.
     * @return the resolved path of the created file.
     * @throws IOException
     *             if the file could not be created.
     */
    private static String createFile(final Path path) throws IOException {
        return resolve(Files.write(path, new byte[] { 'P', 'K' }).toString());
    }

    /**
     * The classpath entry objects added to the classpath order, in order.
     *
     * @return the classpath entry objects.
     */
    private List<Object> entryObjects() {
        return classpathOrder.getOrder().stream().map(entry -> entry.classpathEntryObj).toList();
    }

    /**
     * The Equinox system bundles are only added once per scan, so the first caller is told to add them and every
     * later caller is not. The flag is per {@link ClasspathOrder} instance, so the next scan adds them again.
     */
    @Test
    public void equinoxSystemBundlesAreOnlyAddedOncePerScan() {
        assertThat(classpathOrder.tryAddEquinoxSystemBundles()).isTrue();
        assertThat(classpathOrder.tryAddEquinoxSystemBundles()).isFalse();
        assertThat(new ClasspathOrder(scanSpec, new ReflectionUtils()).tryAddEquinoxSystemBundles()).isTrue();
    }

    /**
     * A null or empty classpath entry is not added. An empty entry used to be resolved against the current
     * directory before the emptiness check was applied, which turned it into the current directory and scanned the
     * whole directory tree below it.
     */
    @Test
    public void nullAndEmptyClasspathEntriesAreRejected() {
        assertThat(classpathOrder.addClasspathEntry(null, null, scanSpec, null)).isFalse();
        assertThat(classpathOrder.addClasspathEntry("", null, scanSpec, null)).isFalse();
        assertThat(classpathOrder.addClasspathEntry(new File(""), null, scanSpec, null)).isFalse();
        assertThat(classpathOrder.addClasspathEntries(null, null, scanSpec, null)).isFalse();
        assertThat(classpathOrder.addClasspathEntries(List.of(), null, scanSpec, null)).isFalse();
        assertThat(classpathOrder.addClasspathPathStr(null, null, scanSpec, null)).isFalse();
        assertThat(classpathOrder.addClasspathPathStr("", null, scanSpec, null)).isFalse();
        // A path string that contains only separators splits into no path elements at all
        assertThat(classpathOrder.addClasspathPathStr(File.pathSeparator, null, scanSpec, null)).isFalse();
        assertThat(classpathOrder.getOrder()).isEmpty();
    }

    /** A classpath entry that has already been added is not added a second time. */
    @Test
    public void duplicateClasspathEntriesAreIgnored(@TempDir final Path tempDir) throws IOException {
        final var jar = createFile(tempDir.resolve("x.jar"));
        assertThat(classpathOrder.addClasspathEntry(jar, null, scanSpec, null)).isTrue();
        assertThat(classpathOrder.addClasspathEntry(jar, null, scanSpec, null)).isFalse();
        assertThat(entryObjects()).containsExactly(jar);
        assertThat(classpathOrder.getClasspathEntryUniqueResolvedPaths()).containsExactly(jar);
    }

    /**
     * A {@link Path} classpath entry resolves to the same path as the {@link String} and {@link File} spellings of
     * the same file, so all three deduplicate against each other. A {@link Path} is converted to a URI before its
     * string form is taken, so that a path on a non-default filesystem keeps its scheme, which means a local path
     * arrives at {@link FastPathResolver} in the percent-encoded {@code "file:///"} spelling that
     * {@link Path#toUri()} produces.
     */
    @Test
    public void pathClasspathEntriesResolveToPlainPaths(@TempDir final Path tempDir) throws IOException {
        // Include a space in the filename, since Path#toUri() percent-encodes it
        final var jarPath = tempDir.resolve("x y.jar");
        final var jar = createFile(jarPath);
        assertThat(classpathOrder.addClasspathEntry(jarPath, null, scanSpec, null)).isTrue();
        assertThat(classpathOrder.getClasspathEntryUniqueResolvedPaths()).containsExactly(jar);
        // The String and File spellings of the same path are now duplicates
        assertThat(classpathOrder.addClasspathEntry(jar, null, scanSpec, null)).isFalse();
        assertThat(classpathOrder.addClasspathEntry(jarPath.toFile(), null, scanSpec, null)).isFalse();
    }

    /**
     * An automatic package root at the end of a classpath entry is stripped off, since package roots are found
     * automatically during scanning, and leaving it on would scan the same classes twice.
     */
    // #435
    @Test
    public void automaticPackageRootSuffixesAreStripped(@TempDir final Path tempDir) throws IOException {
        final var jar = createFile(tempDir.resolve("x.jar"));
        assertThat(classpathOrder.addClasspathEntry(jar + "!/BOOT-INF/classes", null, scanSpec, null)).isTrue();
        // The same jar without the package root suffix is now a duplicate
        assertThat(classpathOrder.addClasspathEntry(jar, null, scanSpec, null)).isFalse();
        assertThat(entryObjects()).containsExactly(jar);
    }

    /**
     * Each classpath entry records the package root prefixes of the classloader it was found by, and passing null
     * resets them to the default prefixes for the entries added after that.
     */
    @Test
    public void packageRootPrefixesAreRecordedOnEachEntry(@TempDir final Path tempDir) throws IOException {
        final var customPrefixes = new String[] { "custom/" };
        classpathOrder.setPackageRootPrefixes(customPrefixes);
        classpathOrder.addClasspathEntry(createFile(tempDir.resolve("a.jar")), null, scanSpec, null);
        classpathOrder.setPackageRootPrefixes(null);
        classpathOrder.addClasspathEntry(createFile(tempDir.resolve("b.jar")), null, scanSpec, null);

        assertThat(classpathOrder.getOrder()).hasSize(2);
        assertThat(classpathOrder.getOrder().get(0).packageRootPrefixes).isSameAs(customPrefixes);
        assertThat(classpathOrder.getOrder().get(1).packageRootPrefixes).containsExactly("classes/",
                "test-classes/", "BOOT-INF/classes/", "WEB-INF/classes/");
        // The prefixes in force when the entry was added decide which suffix is stripped from it
        final var jar = createFile(tempDir.resolve("c.jar"));
        classpathOrder.setPackageRootPrefixes(customPrefixes);
        assertThat(classpathOrder.addClasspathEntry(jar + "!/custom", null, scanSpec, null)).isTrue();
        assertThat(entryObjects()).endsWith(jar);
    }

    /** A directory with a "/*" suffix is expanded into one classpath entry per file in the directory. */
    @Test
    public void wildcardDirectoriesAreExpanded(@TempDir final Path tempDir) throws IOException {
        final var jarA = createFile(tempDir.resolve("a.jar"));
        final var jarB = createFile(tempDir.resolve("b.jar"));
        assertThat(classpathOrder.addClasspathEntry(tempDir + "/*", null, scanSpec, null)).isTrue();
        assertThat(entryObjects()).containsExactlyInAnyOrder(jarA, jarB);
    }

    /** A "/*" suffix on anything but a readable directory adds nothing. */
    @Test
    public void wildcardsOnAnythingButADirectoryAreRejected(@TempDir final Path tempDir) throws IOException {
        final var jar = createFile(tempDir.resolve("a.jar"));
        assertThat(classpathOrder.addClasspathEntry(tempDir + "/does-not-exist/*", null, scanSpec, null)).isFalse();
        assertThat(classpathOrder.addClasspathEntry(jar + "/*", null, scanSpec, null)).isFalse();
        // A '*' is only a wildcard as a "/*" suffix, not as a glob in the middle of the path
        assertThat(classpathOrder.addClasspathEntry(tempDir + "/*/a.jar", null, scanSpec, null)).isFalse();
        assertThat(classpathOrder.getOrder()).isEmpty();
    }

    /**
     * On Windows, a UNC path (a path starting with a double slash) is added as a {@link File}, since {@link File}
     * supports UNC paths directly.
     */
    // #705
    @Test
    @EnabledOnOs(OS.WINDOWS)
    public void uncPathsAreAddedAsFilesOnWindows() {
        assertThat(classpathOrder.addClasspathEntry("//server/share/a.jar", null, scanSpec, null)).isTrue();
        assertThat(entryObjects()).containsExactly(new File("//server/share/a.jar"));
    }

    /**
     * There are no UNC paths outside Windows, so a path starting with a double slash is just an absolute path
     * written with a redundant separator, and the extra separator is collapsed.
     */
    @Test
    @DisabledOnOs(OS.WINDOWS)
    public void uncPathsAreOrdinaryPathsOutsideWindows() {
        assertThat(classpathOrder.addClasspathEntry("//server/share/a.jar", null, scanSpec, null)).isTrue();
        assertThat(entryObjects()).containsExactly("/server/share/a.jar");
    }

    /** A classpath element rejected by a user-supplied path filter is not added. */
    @Test
    public void pathFiltersRejectClasspathElements(@TempDir final Path tempDir) throws IOException {
        final var jarA = createFile(tempDir.resolve("a.jar"));
        final var jarB = createFile(tempDir.resolve("b.jar"));
        scanSpec.filterClasspathElements(path -> !path.endsWith("b.jar"));
        assertThat(classpathOrder.addClasspathEntry(jarA, null, scanSpec, null)).isTrue();
        assertThat(classpathOrder.addClasspathEntry(jarB, null, scanSpec, null)).isFalse();
        assertThat(entryObjects()).containsExactly(jarA);
    }

    /** A classpath element rejected by a user-supplied URL filter is not added. */
    @Test
    public void urlFiltersRejectClasspathElements() throws Exception {
        scanSpec.filterClasspathElementsByURL(url -> !"example.com".equals(url.getHost()));
        assertThat(classpathOrder.addClasspathEntry(URI.create("http://example.com/a.jar").toURL(), null, scanSpec,
                null)).isFalse();
        assertThat(classpathOrder.addClasspathEntry(URI.create("http://example.org/b.jar").toURL(), null, scanSpec,
                null)).isTrue();
        assertThat(entryObjects()).hasSize(1);
        assertThat(entryObjects().get(0)).isInstanceOf(URL.class).hasToString("http://example.org/b.jar");
    }

    /** A URL classpath entry is kept as a {@link URL}, so that its scheme can be handled during scanning. */
    @Test
    public void urlClasspathEntriesAreKeptAsURLs() throws Exception {
        assertThat(classpathOrder.addClasspathEntry(URI.create("http://example.com/a.jar").toURL(), null, scanSpec,
                null)).isTrue();
        // The same URL is a duplicate, whether it arrives as a URL or as a string
        assertThat(classpathOrder.addClasspathEntry("http://example.com/a.jar", null, scanSpec, null)).isFalse();
        assertThat(entryObjects()).hasSize(1);
        assertThat(entryObjects().get(0)).isInstanceOf(URL.class);
    }

    /** A {@link File} classpath entry is recorded as its path string, not as the {@link File} object. */
    @Test
    public void fileClasspathEntriesAreRecordedAsPathStrings(@TempDir final Path tempDir) throws IOException {
        final var jar = createFile(tempDir.resolve("a.jar"));
        assertThat(classpathOrder.addClasspathEntry(new File(jar), null, scanSpec, null)).isTrue();
        assertThat(entryObjects()).containsExactly(jar);
    }

    /** A delimited classpath string is split into its path elements, which are then added in order. */
    @Test
    public void delimitedClasspathStringsAreSplit(@TempDir final Path tempDir) throws IOException {
        final var jarA = createFile(tempDir.resolve("a.jar"));
        final var jarB = createFile(tempDir.resolve("b.jar"));
        assertThat(classpathOrder.addClasspathPathStr(jarA + File.pathSeparator + jarB, null, scanSpec, null))
                .isTrue();
        assertThat(entryObjects()).containsExactly(jarA, jarB);
    }

    /** A classpath object found by reflection may be an array, an {@link Iterable}, or a single path or URL. */
    @Test
    public void classpathEntryObjectsOfEveryShapeAreUnwrapped(@TempDir final Path tempDir) throws IOException {
        final var jarA = createFile(tempDir.resolve("a.jar"));
        final var jarB = createFile(tempDir.resolve("b.jar"));
        final var jarC = createFile(tempDir.resolve("c.jar"));
        final var jarD = createFile(tempDir.resolve("d.jar"));

        assertThat(classpathOrder.addClasspathEntryObject(new String[] { jarA }, null, scanSpec, null)).isTrue();
        assertThat(classpathOrder.addClasspathEntryObject(List.of(new File(jarB)), null, scanSpec, null)).isTrue();
        // An object of no recognized type has toString() called on it, which may yield a delimited path string
        assertThat(classpathOrder.addClasspathEntryObject(jarC + File.pathSeparator + jarD, null, scanSpec, null))
                .isTrue();
        assertThat(classpathOrder.addClasspathEntryObject(null, null, scanSpec, null)).isFalse();
        // A path string is reported as valid whenever it splits into at least one path element, whether or not any
        // of those elements were added, so a whole array of duplicates is still reported as valid
        assertThat(classpathOrder.addClasspathEntryObject(new String[] { jarA }, null, scanSpec, null)).isTrue();

        assertThat(entryObjects()).containsExactly(jarA, jarB, jarC, jarD);
    }

    /** Classpath entries are equal if they name the same classpath element, whatever classloader found it. */
    @Test
    public void classpathEntriesAreEqualIfTheyNameTheSameElement() {
        final var prefixes = new String[] { "classes/" };
        final var classLoader = getClass().getClassLoader();
        final var entry = new ClasspathEntry("/a/b.jar", classLoader, prefixes);
        final var sameElement = new ClasspathEntry("/a/b.jar", null, prefixes);
        final var otherElement = new ClasspathEntry("/a/c.jar", classLoader, prefixes);

        assertThat(entry).isEqualTo(entry).isEqualTo(sameElement).isNotEqualTo(otherElement)
                .isNotEqualTo("/a/b.jar");
        assertThat(entry).hasSameHashCodeAs(sameElement);
        assertThat(entry).hasToString("/a/b.jar [" + classLoader + "]");
    }
}
