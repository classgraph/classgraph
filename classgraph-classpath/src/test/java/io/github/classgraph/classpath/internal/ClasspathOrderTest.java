package io.github.classgraph.classpath.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.abort;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import io.github.classgraph.base.internal.path.FastPathResolver;
import io.github.classgraph.base.internal.path.FileUtils;
import io.github.classgraph.classpath.internal.ClasspathOrderBuilder.Entry;

/** Tests for {@link ClasspathOrderBuilder}. */
public class ClasspathOrderTest {
    /** The scan spec shared by a single test. */
    private final ClasspathSpec classpathSpec = new ClasspathSpec();

    /** The classpath order under test. */
    private final ClasspathOrderBuilder classpathOrder = new ClasspathOrderBuilder(classpathSpec);

    /**
     * Resolve a path the same way {@link ClasspathOrderBuilder} does, so that the expected values do not have to be
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
     * Create an empty file, and return its path in the form {@link ClasspathOrderBuilder} reports it in, which is
     * the canonical path. The canonical form matters because a temporary directory is reached through a symlink on
     * macOS, and through an 8.3 short name on Windows.
     *
     * @param path
     *            the path of the file to create.
     * @return the location of the created file.
     * @throws IOException
     *             if the file could not be created.
     */
    private static String createFile(final Path path) throws IOException {
        return resolve(Files.write(path, new byte[] { 'P', 'K' }).toRealPath().toString());
    }

    /**
     * The classpath entry objects added to the classpath order, in order.
     *
     * @return the classpath entry objects.
     */
    private List<Object> entryObjects() {
        return classpathOrder.getOrder().stream().map(Entry::classpathEntryObj).toList();
    }

    /**
     * The locations of the classpath elements added to the classpath order, in order.
     *
     * @return the locations.
     */
    private List<String> locations() {
        return classpathOrder.getOrder().stream().map(Entry::location).toList();
    }

    /**
     * A piece of work is claimed by the first caller only, and each key is claimed separately. The claimed keys are
     * held per {@link ClasspathOrderBuilder} instance, and every scan builds a new one, so the next scan can claim
     * the same key again. If these were held in a static field, or in a field of the
     * {@link io.github.classgraph.classpath.ClassLoaderHandler} that claims them (handler instances are shared
     * between scans), every scan after the first would silently skip the work.
     */
    @Test
    public void workIsClaimedOncePerScan() {
        assertThat(classpathOrder.claimOncePerScan("system-bundles")).isTrue();
        assertThat(classpathOrder.claimOncePerScan("system-bundles")).isFalse();
        assertThat(classpathOrder.claimOncePerScan("system-bundles")).isFalse();
        // A different key is claimed separately
        assertThat(classpathOrder.claimOncePerScan("something-else")).isTrue();
        // The next scan claims the same key again
        assertThat(new ClasspathOrderBuilder(classpathSpec).claimOncePerScan("system-bundles")).isTrue();
    }

    /**
     * A null or empty classpath entry is not added. An empty entry used to be resolved against the current
     * directory before the emptiness check was applied, which turned it into the current directory and scanned the
     * whole directory tree below it.
     */
    @Test
    public void nullAndEmptyClasspathEntriesAreRejected() {
        assertThat(classpathOrder.addClasspathEntry(null, null, null)).isFalse();
        assertThat(classpathOrder.addClasspathEntry("", null, null)).isFalse();
        assertThat(classpathOrder.addClasspathEntry(new File(""), null, null)).isFalse();
        assertThat(classpathOrder.addClasspathEntries(null, null, null)).isFalse();
        assertThat(classpathOrder.addClasspathEntries(List.of(), null, null)).isFalse();
        assertThat(classpathOrder.addClasspathPathStr(null, null, null)).isFalse();
        assertThat(classpathOrder.addClasspathPathStr("", null, null)).isFalse();
        // A path string that contains only separators splits into no path elements at all
        assertThat(classpathOrder.addClasspathPathStr(File.pathSeparator, null, null)).isFalse();
        assertThat(classpathOrder.getOrder()).isEmpty();
    }

    /** A classpath entry that has already been added is not added a second time. */
    @Test
    public void duplicateClasspathEntriesAreIgnored(@TempDir final Path tempDir) throws IOException {
        final var jar = createFile(tempDir.resolve("x.jar"));
        assertThat(classpathOrder.addClasspathEntry(jar, null, null)).isTrue();
        assertThat(classpathOrder.addClasspathEntry(jar, null, null)).isFalse();
        assertThat(entryObjects()).containsExactly(jar);
        assertThat(locations()).containsExactly(jar);
    }

    /**
     * The classpath order can only be added to through the builder, which deduplicates, so the list it returns
     * cannot be changed.
     */
    @Test
    public void orderCannotBeChangedThroughTheReturnedList(@TempDir final Path tempDir) throws IOException {
        final var jar = createFile(tempDir.resolve("x.jar"));
        assertThat(classpathOrder.addClasspathEntry(jar, null, null)).isTrue();
        final var order = classpathOrder.getOrder();
        assertThatThrownBy(() -> order.add(order.get(0))).isInstanceOf(UnsupportedOperationException.class);
        assertThat(locations()).containsExactly(jar);
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
        assertThat(classpathOrder.addClasspathEntry(jarPath, null, null)).isTrue();
        assertThat(locations()).containsExactly(jar);
        // The String and File spellings of the same path are now duplicates
        assertThat(classpathOrder.addClasspathEntry(jar, null, null)).isFalse();
        assertThat(classpathOrder.addClasspathEntry(jarPath.toFile(), null, null)).isFalse();
    }

    /**
     * An automatic package root at the end of a classpath entry is stripped off, since package roots are found
     * automatically during scanning, and leaving it on would scan the same classes twice.
     */
    // #435
    @Test
    public void automaticPackageRootSuffixesAreStripped(@TempDir final Path tempDir) throws IOException {
        final var jar = createFile(tempDir.resolve("x.jar"));
        classpathOrder.setPackageRootPrefixes(List.of("BOOT-INF/classes/"), null);
        assertThat(classpathOrder.addClasspathEntry(jar + "!/BOOT-INF/classes", null, null)).isTrue();
        // The same jar without the package root suffix is now a duplicate
        assertThat(classpathOrder.addClasspathEntry(jar, null, null)).isFalse();
        assertThat(entryObjects()).containsExactly(jar);
    }

    /**
     * A classpath entry given as a {@link URI} whose path holds a character that a URI cannot hold unquoted is
     * still added once its package root suffix has been stripped. The suffix is stripped from the resolved path,
     * which is percent-decoded, so the URI cannot be rebuilt by parsing that path, and the entry used to be dropped
     * when neither {@code new URI(path)} nor {@code new URI("file:" + path)} could parse it.
     */
    @Test
    public void aUriEntryWhosePathNeedsQuotingIsNotDropped(@TempDir final Path tempDir) throws Exception {
        final var dir = Files.createDirectory(tempDir.resolve("lib dir"));
        final var jar = createFile(dir.resolve("x.jar"));
        classpathOrder.setPackageRootPrefixes(List.of("BOOT-INF/classes/"), null);

        final var uri = new URI(Path.of(jar).toUri() + "!/BOOT-INF/classes");
        assertThat(classpathOrder.addClasspathEntry(uri, null, null)).isTrue();
        assertThat(locations()).containsExactly(jar);
        // The same jar without the package root suffix is now a duplicate, so the entry really was added under the
        // path the suffix was stripped from
        assertThat(classpathOrder.addClasspathEntry(jar, null, null)).isFalse();
    }

    /**
     * A classpath entry given as a {@link URI}, {@link URL} or {@link File} keeps its type once its package root
     * suffix has been stripped, and a URI or URL keeps its "file:" scheme and the percent encoding of its path, so
     * that it still names the archive the suffix was stripped from, both as an object and when the scanner turns
     * its string form back into a path.
     */
    @ParameterizedTest
    @CsvSource({ "URI, app.war", "URI, my app.war", "URI, a%20b.war", "URL, app.war", "URL, my app.war",
            "URL, a%20b.war", "File, app.war", "File, my app.war", "File, a%20b.war" })
    public void strippingAPackageRootSuffixKeepsTheTypeAndSchemeOfTheEntry(final String type, final String warName,
            @TempDir final Path tempDir) throws Exception {
        final var war = createFile(tempDir.resolve(warName));
        classpathOrder.setPackageRootPrefixes(List.of("WEB-INF/classes/"), null);
        final var uri = new URI("jar:" + Path.of(war).toUri() + "!/WEB-INF/classes");
        final Object pathElement = switch (type) {
        case "URI" -> uri;
        case "URL" -> uri.toURL();
        default -> new File(war + "!/WEB-INF/classes");
        };
        assertThat(classpathOrder.addClasspathEntry(pathElement, null, null)).isTrue();
        assertThat(locations()).containsExactly(war);

        final var entryObj = entryObjects().get(0);
        assertThat(entryObj).isExactlyInstanceOf(pathElement.getClass());
        if (entryObj instanceof final URI entryURI) {
            assertThat(entryURI.getScheme()).isEqualTo("file");
            assertThat(Path.of(entryURI)).isEqualTo(Path.of(war));
        } else if (entryObj instanceof final URL entryURL) {
            assertThat(entryURL.getProtocol()).isEqualTo("file");
            assertThat(Path.of(entryURL.toURI())).isEqualTo(Path.of(war));
        } else {
            assertThat(entryObj).isEqualTo(new File(war));
        }
        // The scanner opens a classpath entry that is not a Path by resolving its string form
        assertThat(FastPathResolver.resolveFilePath(FileUtils.currDirPath(), entryObj.toString())).isEqualTo(war);
    }

    /**
     * A classpath entry given as a "jar:" {@link URI} or {@link URL} that is still nested inside another archive
     * once its package root suffix has been stripped keeps its "jar:" scheme, so that it still names the archive
     * entry rather than a file whose name contains "!/". A URI scheme is not case sensitive, so "JAR:" is kept too,
     * as "jar:".
     */
    @ParameterizedTest
    @CsvSource({ "jar, false", "jar, true", "JAR, false" })
    public void strippingAPackageRootSuffixKeepsTheJarSchemeOfANestedEntry(final String scheme, final boolean asURL,
            @TempDir final Path tempDir) throws Exception {
        final var jar = createFile(tempDir.resolve("outer.jar"));
        classpathOrder.setPackageRootPrefixes(List.of("WEB-INF/classes/"), null);
        final var uri = new URI(scheme + ":" + Path.of(jar).toUri() + "!/app.war!/WEB-INF/classes");
        assertThat(classpathOrder.addClasspathEntry(asURL ? uri.toURL() : uri, null, null)).isTrue();
        assertThat(locations()).containsExactly(jar + "!/app.war");

        final var entryObj = entryObjects().get(0);
        assertThat(entryObj).isExactlyInstanceOf(asURL ? URL.class : URI.class);
        assertThat(entryObj.toString()).isEqualTo("jar:" + Path.of(jar).toUri() + "!/app.war");
    }

    /**
     * A dir that no classloader declares as an automatic package root is not stripped from the end of a classpath
     * entry, so the entry is scanned as the package root the caller named.
     */
    @Test
    public void undeclaredPackageRootSuffixesAreNotStripped(@TempDir final Path tempDir) throws IOException {
        final var jar = createFile(tempDir.resolve("x.jar"));
        assertThat(classpathOrder.addClasspathEntry(jar + "!/BOOT-INF/classes", null, null)).isTrue();
        assertThat(entryObjects()).containsExactly(jar + "!/BOOT-INF/classes");
    }

    /**
     * Each classpath entry records the package root prefixes of the classloader it was found by, and passing null
     * resets them to no prefixes at all for the entries added after that.
     */
    @Test
    public void packageRootPrefixesAreRecordedOnEachEntry(@TempDir final Path tempDir) throws IOException {
        final var customPrefixes = List.of("custom/");
        classpathOrder.setPackageRootPrefixes(customPrefixes, null);
        classpathOrder.addClasspathEntry(createFile(tempDir.resolve("a.jar")), null, null);
        classpathOrder.setPackageRootPrefixes(null, null);
        classpathOrder.addClasspathEntry(createFile(tempDir.resolve("b.jar")), null, null);

        assertThat(classpathOrder.getOrder()).hasSize(2);
        assertThat(classpathOrder.getOrder().get(0).packageRootPrefixes()).isSameAs(customPrefixes);
        assertThat(classpathOrder.getOrder().get(1).packageRootPrefixes()).isEmpty();
        // The prefixes in force when the entry was added decide which suffix is stripped from it
        final var jar = createFile(tempDir.resolve("c.jar"));
        classpathOrder.setPackageRootPrefixes(customPrefixes, null);
        assertThat(classpathOrder.addClasspathEntry(jar + "!/custom", null, null)).isTrue();
        assertThat(entryObjects()).endsWith(jar);
    }

    /**
     * Each classpath entry records the lib dir prefixes of the classloader it was found by, and passing null resets
     * them to no lib dirs at all for the entries added after that.
     */
    @Test
    public void libDirPrefixesAreRecordedOnEachEntry(@TempDir final Path tempDir) throws IOException {
        final var customPrefixes = List.of("custom/lib/");
        classpathOrder.setLibDirPrefixes(customPrefixes, null);
        classpathOrder.addClasspathEntry(createFile(tempDir.resolve("a.jar")), null, null);
        classpathOrder.setLibDirPrefixes(null, null);
        classpathOrder.addClasspathEntry(createFile(tempDir.resolve("b.jar")), null, null);

        assertThat(classpathOrder.getOrder()).hasSize(2);
        assertThat(classpathOrder.getOrder().get(0).libDirPrefixes()).isSameAs(customPrefixes);
        assertThat(classpathOrder.getOrder().get(1).libDirPrefixes()).isEmpty();
    }

    /**
     * A prefix is brought into the form the rest of ClassGraph relies on, a relative path ending in a slash, and a
     * null prefix, one that names no dir, and one that duplicates an earlier one are dropped.
     */
    @Test
    public void prefixesAreNormalized() {
        assertThat(ClasspathOrderBuilder.normalizePrefixes(
                Arrays.asList("WEB-INF\\classes", "/a//b/", null, "", "/", "WEB-INF/classes/", "./c/../d", "../"),
                null)).containsExactly("WEB-INF/classes/", "a/b/", "d/");
    }

    /**
     * Prefixes that are already in normal form are kept in the same order, and an immutable list of them is
     * returned unchanged, so the built-in handlers, which declare theirs with {@link List#of}, cost nothing. A list
     * that can change is copied.
     */
    @Test
    public void prefixesInNormalFormAreKept() {
        final var prefixes = List.of("b/", "a/");
        assertThat(ClasspathOrderBuilder.normalizePrefixes(prefixes, null)).isSameAs(prefixes);

        final List<String> mutablePrefixes = new ArrayList<>(prefixes);
        final var normalized = ClasspathOrderBuilder.normalizePrefixes(mutablePrefixes, null);
        mutablePrefixes.clear();
        assertThat(normalized).containsExactly("b/", "a/");
    }

    /** A directory with a "/*" suffix is expanded into one classpath entry per file in the directory. */
    @Test
    public void wildcardDirectoriesAreExpanded(@TempDir final Path tempDir) throws IOException {
        final var jarA = createFile(tempDir.resolve("a.jar"));
        final var jarB = createFile(tempDir.resolve("b.jar"));
        assertThat(classpathOrder.addClasspathEntry(tempDir + "/*", null, null)).isTrue();
        assertThat(entryObjects()).containsExactlyInAnyOrder(jarA, jarB);
    }

    /**
     * A "/*" suffix adds only the directory entries whose names end in ".jar" or ".JAR", as the java launcher does.
     * The launcher decides by the name alone, so a hidden jarfile and a directory named like a jarfile are added
     * too, and a subdirectory, a zipfile or a ".Jar" file is not.
     */
    @Test
    public void wildcardDirectoriesAddOnlyJarfiles(@TempDir final Path tempDir) throws IOException {
        final var jarA = createFile(tempDir.resolve("a.jar"));
        final var jarB = createFile(tempDir.resolve("b.JAR"));
        final var hiddenJar = createFile(tempDir.resolve(".hidden.jar"));
        final var dirJar = resolve(Files.createDirectory(tempDir.resolve("dir.jar")).toRealPath().toString());
        Files.createDirectory(tempDir.resolve("sub"));
        createFile(tempDir.resolve("c.zip"));
        createFile(tempDir.resolve("d.txt"));
        createFile(tempDir.resolve("jar"));
        createFile(tempDir.resolve("e.jar.bak"));
        createFile(tempDir.resolve("f.Jar"));
        assertThat(classpathOrder.addClasspathEntry(tempDir + "/*", null, null)).isTrue();
        assertThat(entryObjects()).containsExactlyInAnyOrder(jarA, jarB, hiddenJar, dirJar);
    }

    /** A "/*" suffix on anything but a readable directory adds nothing. */
    @Test
    public void wildcardsOnAnythingButADirectoryAreRejected(@TempDir final Path tempDir) throws IOException {
        final var jar = createFile(tempDir.resolve("a.jar"));
        assertThat(classpathOrder.addClasspathEntry(tempDir + "/does-not-exist/*", null, null)).isFalse();
        assertThat(classpathOrder.addClasspathEntry(jar + "/*", null, null)).isFalse();
        assertThat(classpathOrder.getOrder()).isEmpty();
    }

    /**
     * As in the java launcher, a '*' is a wildcard only as a "/*" suffix (or as the whole path), and anywhere else
     * it is part of a file name, which Linux and macOS allow. A path string that contains one names the same file
     * as a {@link Path} with the same name, so it is not dropped for looking like a glob. (Windows does not allow
     * '*' in a file name.)
     */
    @Test
    @DisabledOnOs(OS.WINDOWS)
    public void aStarInsideAPathIsPartOfAFileName(@TempDir final Path tempDir) throws IOException {
        final var dir = Files.createDirectory(tempDir.resolve("a*b"));
        createFile(dir.resolve("r.txt"));
        final var dirLocation = resolve(dir.toRealPath().toString());
        final var jar = createFile(tempDir.resolve("c*d.jar"));

        assertThat(classpathOrder.addClasspathEntry(dir.toString(), null, null)).isTrue();
        // Through a delimited path string, as the java.class.path fallback adds the classpath
        assertThat(classpathOrder.addClasspathPathStr(jar, null, null)).isTrue();
        assertThat(locations()).containsExactly(dirLocation, jar);

        // A path that has a '*' in it is still dropped if there is no file with that name
        assertThat(classpathOrder.addClasspathEntry(tempDir + "/*/r.txt", null, null)).isFalse();
        assertThat(locations()).containsExactly(dirLocation, jar);
    }

    /**
     * On Windows, a UNC path (a path starting with a double slash) is added as a {@link File}, since {@link File}
     * supports UNC paths directly.
     */
    // #705
    @Test
    @EnabledOnOs(OS.WINDOWS)
    public void uncPathsAreAddedAsFilesOnWindows() {
        assertThat(classpathOrder.addClasspathEntry("//server/share/a.jar", null, null)).isTrue();
        assertThat(entryObjects()).containsExactly(new File("//server/share/a.jar"));
    }

    /**
     * There are no UNC paths outside Windows, so a path starting with a double slash is just an absolute path
     * written with a redundant separator, and the extra separator is collapsed.
     */
    @Test
    @DisabledOnOs(OS.WINDOWS)
    public void uncPathsAreOrdinaryPathsOutsideWindows(@TempDir final Path tempDir) throws IOException {
        final var jar = createFile(tempDir.resolve("a.jar"));
        assertThat(classpathOrder.addClasspathEntry("/" + jar, null, null)).isTrue();
        assertThat(entryObjects()).containsExactly(jar);
    }

    /** A classpath element rejected by a user-supplied path filter is not added. */
    @Test
    public void pathFiltersRejectClasspathElements(@TempDir final Path tempDir) throws IOException {
        final var jarA = createFile(tempDir.resolve("a.jar"));
        final var jarB = createFile(tempDir.resolve("b.jar"));
        classpathSpec.filterClasspathElements(path -> !path.endsWith("b.jar"));
        assertThat(classpathOrder.addClasspathEntry(jarA, null, null)).isTrue();
        assertThat(classpathOrder.addClasspathEntry(jarB, null, null)).isFalse();
        assertThat(entryObjects()).containsExactly(jarA);
    }

    /** A classpath element rejected by a user-supplied URL filter is not added. */
    @Test
    public void urlFiltersRejectClasspathElements() throws Exception {
        classpathSpec.filterClasspathElementsByURL(url -> !"example.com".equals(url.getHost()));
        assertThat(classpathOrder.addClasspathEntry(URI.create("http://example.com/a.jar").toURL(), null, null))
                .isFalse();
        assertThat(classpathOrder.addClasspathEntry(URI.create("http://example.org/b.jar").toURL(), null, null))
                .isTrue();
        assertThat(entryObjects()).hasSize(1);
        assertThat(entryObjects().get(0)).isInstanceOf(URL.class).hasToString("http://example.org/b.jar");
    }

    /**
     * A jarfile found by expanding a wildcarded directory is a classpath element in its own right, so a path filter
     * that rejects it keeps it out of the classpath, as it would if the jarfile had been listed explicitly.
     */
    @Test
    public void pathFiltersRejectJarfilesInAWildcardedDirectory(@TempDir final Path tempDir) throws IOException {
        final var jarA = createFile(tempDir.resolve("a.jar"));
        createFile(tempDir.resolve("b.jar"));
        classpathSpec.filterClasspathElements(path -> !path.endsWith("b.jar"));
        assertThat(classpathOrder.addClasspathEntry(tempDir + "/*", null, null)).isTrue();
        assertThat(locations()).containsExactly(jarA);
    }

    /**
     * A jarfile found by expanding a wildcarded directory is tested against the URL filters too, as the
     * {@code file:} URL of the jarfile.
     */
    @Test
    public void urlFiltersRejectJarfilesInAWildcardedDirectory(@TempDir final Path tempDir) throws IOException {
        final var jarA = createFile(tempDir.resolve("a.jar"));
        createFile(tempDir.resolve("b.jar"));
        classpathSpec.filterClasspathElementsByURL(url -> !url.getPath().endsWith("b.jar"));
        assertThat(classpathOrder.addClasspathEntry(tempDir + "/*", null, null)).isTrue();
        assertThat(locations()).containsExactly(jarA);
    }

    /** A URL classpath entry is kept as a {@link URL}, so that its scheme can be handled during scanning. */
    @Test
    public void urlClasspathEntriesAreKeptAsURLs() throws Exception {
        assertThat(classpathOrder.addClasspathEntry(URI.create("http://example.com/a.jar").toURL(), null, null))
                .isTrue();
        // The same URL is a duplicate, whether it arrives as a URL or as a string
        assertThat(classpathOrder.addClasspathEntry("http://example.com/a.jar", null, null)).isFalse();
        assertThat(entryObjects()).hasSize(1);
        assertThat(entryObjects().get(0)).isInstanceOf(URL.class);
    }

    /** A {@link File} classpath entry is recorded as the {@link File} object it arrived as. */
    @Test
    public void fileClasspathEntriesAreRecordedAsFiles(@TempDir final Path tempDir) throws IOException {
        final var jar = createFile(tempDir.resolve("a.jar"));
        final var file = new File(jar);
        assertThat(classpathOrder.addClasspathEntry(file, null, null)).isTrue();
        assertThat(entryObjects()).containsExactly(file);
        assertThat(locations()).containsExactly(jar);
    }

    /**
     * A wildcarded classpath entry is expanded into the jarfiles in the directory it names, whether it arrives as a
     * path string or as a {@link File} or {@link Path} object.
     */
    @Test
    public void wildcardedClasspathEntriesAreExpandedWhateverObjectTheyArriveAs(@TempDir final Path tempDir)
            throws IOException {
        final var jar = createFile(tempDir.resolve("a.jar"));
        final var wildcardedForms = new ArrayList<Object>(List.of(tempDir + "/*", new File(tempDir.toFile(), "*")));
        try {
            wildcardedForms.add(tempDir.resolve("*"));
        } catch (final InvalidPathException e) {
            // A wildcard can only arrive as a Path on a filesystem whose path names may contain '*', which the
            // Windows filesystem's may not
        }
        for (final Object wildcarded : wildcardedForms) {
            final var order = new ClasspathOrderBuilder(classpathSpec);
            assertThat(order.addClasspathEntry(wildcarded, null, null)).isTrue();
            assertThat(order.getOrder().stream().map(Entry::location).toList()).containsExactly(jar);
        }
    }

    /** A delimited classpath string is split into its path elements, which are then added in order. */
    @Test
    public void delimitedClasspathStringsAreSplit(@TempDir final Path tempDir) throws IOException {
        final var jarA = createFile(tempDir.resolve("a.jar"));
        final var jarB = createFile(tempDir.resolve("b.jar"));
        assertThat(classpathOrder.addClasspathPathStr(jarA + File.pathSeparator + jarB, null, null)).isTrue();
        assertThat(entryObjects()).containsExactly(jarA, jarB);
    }

    /**
     * Each bulk add method returns true only if it added at least one classpath element, as a single add does: a
     * list or path string that holds only duplicates, or only paths that are not there, adds nothing.
     */
    @Test
    public void bulkAddsReportWhetherAnythingWasAdded(@TempDir final Path tempDir) throws IOException {
        final var jarA = createFile(tempDir.resolve("a.jar"));
        final var missing = tempDir.resolve("missing.jar").toString();
        assertThat(classpathOrder.addClasspathEntries(List.of(jarA), null, null)).isTrue();
        assertThat(classpathOrder.addClasspathEntries(List.of(jarA, missing), null, null)).isFalse();
        assertThat(classpathOrder.addClasspathPathStr(jarA + File.pathSeparator + missing, null, null)).isFalse();
        assertThat(classpathOrder.addClasspathEntryObject(List.of(jarA, missing), null, null)).isFalse();
        assertThat(entryObjects()).containsExactly(jarA);
    }

    /** A classpath object found by reflection may be an array, an {@link Iterable}, or a single path or URL. */
    @Test
    public void classpathEntryObjectsOfEveryShapeAreUnwrapped(@TempDir final Path tempDir) throws IOException {
        final var jarA = createFile(tempDir.resolve("a.jar"));
        final var jarB = createFile(tempDir.resolve("b.jar"));
        final var jarC = createFile(tempDir.resolve("c.jar"));
        final var jarD = createFile(tempDir.resolve("d.jar"));

        assertThat(classpathOrder.addClasspathEntryObject(new String[] { jarA }, null, null)).isTrue();
        assertThat(classpathOrder.addClasspathEntryObject(List.of(new File(jarB)), null, null)).isTrue();
        // An object of no recognized type has toString() called on it, which may yield a delimited path string
        assertThat(classpathOrder.addClasspathEntryObject(jarC + File.pathSeparator + jarD, null, null)).isTrue();
        assertThat(classpathOrder.addClasspathEntryObject(null, null, null)).isFalse();
        // Nothing is added from an array of duplicates, and the return value says so
        assertThat(classpathOrder.addClasspathEntryObject(new String[] { jarA }, null, null)).isFalse();

        // Each element is kept in the form it arrived in, so the one that arrived as a File is still a File
        assertThat(entryObjects()).containsExactly(jarA, new File(jarB), jarC, jarD);
    }

    /**
     * The location of a classpath element is its resolved path, whatever object the classpath element arrived as. A
     * {@link Path} spells a path with the platform's separator, so its own string form uses backslashes on Windows,
     * and is relative if the path it was built from was relative; a {@link URI} keeps its {@code file:} scheme.
     * None of those is the form a classpath element is reported in, so the resolved path is recorded alongside the
     * object the classpath element arrived as.
     */
    @Test
    public void classpathElementLocationsAreResolvedPaths(@TempDir final Path tempDir) throws IOException {
        final var jarA = createFile(tempDir.resolve("a.jar"));
        final var jarB = createFile(tempDir.resolve("b.jar"));
        final var jarC = createFile(tempDir.resolve("c.jar"));
        final var absolutePath = Path.of(jarA);
        final Path relativePath;
        try {
            relativePath = Path.of("").toAbsolutePath().relativize(Path.of(jarB));
        } catch (final IllegalArgumentException e) {
            // On Windows there is no relative path from one drive to another, so the temporary directory has to be
            // on the same drive as the current directory for there to be one
            abort("There is no relative path to the temporary directory: " + e);
            return;
        }
        assertThat(relativePath.isAbsolute()).isFalse();
        final var uri = Path.of(jarC).toUri();
        assertThat(classpathOrder.addClasspathEntry(absolutePath, null, null)).isTrue();
        assertThat(classpathOrder.addClasspathEntry(relativePath, null, null)).isTrue();
        assertThat(classpathOrder.addClasspathEntry(uri, null, null)).isTrue();

        assertThat(locations()).containsExactly(jarA, jarB, jarC);
        // The objects the classpath elements arrived as are kept as they were, since the scanner needs the
        // filesystem of a Path and the scheme of a URI
        assertThat(entryObjects()).containsExactly(absolutePath, relativePath, uri);
    }

    /** An entry's string form names the classloader it was found through. */
    @Test
    public void entryStringNamesTheClassLoader() {
        final var classLoader = getClass().getClassLoader();
        final var entry = new Entry("/a/b.jar", "/a/b.jar", classLoader.toString(), List.of(), List.of());
        assertThat(entry).hasToString("/a/b.jar [" + classLoader + "]");
    }
}
