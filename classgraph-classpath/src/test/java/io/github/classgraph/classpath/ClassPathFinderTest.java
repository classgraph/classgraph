package io.github.classgraph.classpath;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Tests for the public API of the classpath finder. */
public class ClassPathFinderTest {
    /** The classpath of the JVM running the tests contains the directory the test classes were compiled to. */
    @Test
    public void theEnvironmentClasspathIsFound() {
        try (var classPath = new ClassPathFinder().find()) {
            assertThat(classPath.getLocations()).anyMatch(location -> location.endsWith("/target/test-classes"));
        }
    }

    /** Every entry records the classloader it was found through, and the package roots to look for within it. */
    @Test
    public void entriesRecordTheirClassLoaderAndPackageRoots() {
        try (var classPath = new ClassPathFinder().find()) {
            final var entries = classPath.getEntries();
            assertThat(entries).isNotEmpty();
            for (final ClassPathEntry entry : entries) {
                assertThat(entry.location()).isNotEmpty();
                assertThat(entry.packageRootPrefixes()).isNotEmpty();
                assertThat(entry.toString()).startsWith(entry.location());
            }
        }
    }

    /** An overridden classpath is reported verbatim, and nothing from the environment is added to it. */
    @Test
    public void anOverriddenClasspathIsUsedInsteadOfTheEnvironment() {
        final var first = new File("first.jar").getAbsoluteFile();
        final var second = new File("second.jar").getAbsoluteFile();
        final var classPath = new ClassPathFinder().overrideClasspath(first + File.pathSeparator + second).find();
        assertThat(classPath.getLocations()).containsExactly(first.getPath().replace(File.separatorChar, '/'),
                second.getPath().replace(File.separatorChar, '/'));
        // Modules are not scanned when the classpath is overridden
        assertThat(classPath.getModules()).isEmpty();
    }

    /** Each classpath element of an overridden classpath is passed through unsplit by the non-String overloads. */
    @Test
    public void theClasspathCanBeOverriddenWithIndividualElements() {
        final var jar = new File("only.jar").getAbsoluteFile();
        final var expected = List.of(jar.getPath().replace(File.separatorChar, '/'));
        assertThat(new ClassPathFinder().overrideClasspath((Object) jar).find().getLocations()).isEqualTo(expected);
        assertThat(new ClassPathFinder().overrideClasspath(List.of(jar)).find().getLocations()).isEqualTo(expected);
        // A single Path is one classpath entry, not a sequence of its name elements
        assertThat(new ClassPathFinder().overrideClasspath(jar.toPath()).find().getLocations()).isEqualTo(expected);
    }

    /** An empty classpath override is a caller error, rather than a silent scan of nothing. */
    @Test
    public void anEmptyClasspathOverrideIsRejected() {
        assertThatThrownBy(() -> new ClassPathFinder().overrideClasspath(""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ClassPathFinder().overrideClasspath(new Object[0]))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ClassPathFinder().overrideClasspath(List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ClassPathFinder().overrideClassLoaders())
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** A classloader is passed to {@code overrideClassLoaders}, not to {@code overrideClasspath}. */
    @Test
    public void aClassLoaderIsNotAClasspathElement() {
        assertThatThrownBy(
                () -> new ClassPathFinder().overrideClasspath((Object) ClassPathFinderTest.class.getClassLoader()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** The modules the JVM can see are found, and split into the JDK's own modules and everything else. */
    @Test
    public void theModulesAreFoundAndSplitIntoSystemAndNonSystem() {
        final var classPath = new ClassPathFinder().find();
        assertThat(classPath.getSystemModules()).anyMatch(module -> "java.base".equals(module.descriptor().name()));
        assertThat(classPath.getNonSystemModules())
                .noneMatch(module -> module.descriptor().name().startsWith("java."));
        // getModules() lists the system modules first, then the rest
        assertThat(classPath.getModules()).startsWith(classPath.getSystemModules().get(0));
        assertThat(classPath.getModules())
                .hasSize(classPath.getSystemModules().size() + classPath.getNonSystemModules().size());
    }

    /** Module finding can be switched off altogether. */
    @Test
    public void moduleFindingCanBeDisabled() {
        assertThat(new ClassPathFinder().ignoreModules().find().getModules()).isEmpty();
    }

    /** The module path switches the JVM was launched with are reachable from the result. */
    @Test
    public void theModulePathInfoIsReachable() {
        assertThat(new ClassPathFinder().find().getModulePathInfo()).isNotNull();
    }

    /** The result prints one classpath element or module per line. */
    @Test
    public void theClassPathPrintsOneEntryPerLine() {
        final var jar = new File("only.jar").getAbsoluteFile();
        assertThat(new ClassPathFinder().overrideClasspath((Object) jar).find())
                .hasToString(jar.getPath().replace(File.separatorChar, '/') + "\n");
    }

    /**
     * Write a jarfile that contains nothing but a manifest, with the given main attributes.
     *
     * @param jarFile
     *            the jarfile to write.
     * @param attributeNamesAndValues
     *            the names and values of the main attributes, in alternating order.
     * @return the jarfile.
     * @throws IOException
     *             if the jarfile could not be written.
     */
    private static File writeJarWithManifest(final Path jarFile, final String... attributeNamesAndValues)
            throws IOException {
        final var manifest = new Manifest();
        final var mainAttributes = manifest.getMainAttributes();
        mainAttributes.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        for (var i = 0; i < attributeNamesAndValues.length; i += 2) {
            mainAttributes.putValue(attributeNamesAndValues[i], attributeNamesAndValues[i + 1]);
        }
        try (var outputStream = Files.newOutputStream(jarFile);
                var jarOutputStream = new JarOutputStream(outputStream, manifest)) {
            // No entries -- the manifest is the whole jar
        }
        return jarFile.toFile();
    }

    /**
     * Write a jarfile that contains nothing but an empty entry at the given path.
     *
     * @param jarFile
     *            the jarfile to write.
     * @param entryPath
     *            the path of the entry.
     * @return the jarfile.
     * @throws IOException
     *             if the jarfile could not be written.
     */
    private static File writeJarWithEntry(final Path jarFile, final String entryPath) throws IOException {
        Files.createDirectories(jarFile.getParent());
        try (var outputStream = Files.newOutputStream(jarFile);
                var zipOutputStream = new ZipOutputStream(outputStream)) {
            zipOutputStream.putNextEntry(new ZipEntry(entryPath));
            zipOutputStream.closeEntry();
        }
        return jarFile.toFile();
    }

    /**
     * The location of a file, in the form the classpath finder reports it.
     *
     * @param file
     *            the file.
     * @return the location.
     */
    private static String locationOf(final File file) {
        return file.getPath().replace(File.separatorChar, '/');
    }

    /**
     * The classpath elements named by a jarfile's {@code Class-Path} manifest entry are part of the classpath, and
     * so are the ones that those in turn name. Each of them takes its position directly after the jarfile that
     * named it.
     */
    @Test
    public void manifestClassPathEntriesAreAddedToTheClasspath(@TempDir final Path tempDir) throws IOException {
        final var last = writeJarWithManifest(tempDir.resolve("last.jar"));
        final var middle = writeJarWithManifest(tempDir.resolve("middle.jar"), "Class-Path", "last.jar");
        final var first = writeJarWithManifest(tempDir.resolve("first.jar"), "Class-Path", "middle.jar");
        final var other = writeJarWithManifest(tempDir.resolve("other.jar"));
        try (var classPath = new ClassPathFinder().overrideClasspath(first, other).find()) {
            assertThat(classPath.getLocations()).containsExactly(locationOf(first), locationOf(middle),
                    locationOf(last), locationOf(other));
        }
    }

    /**
     * A classpath element that is reached more than once is listed only at the first position it is reached at,
     * which is the position that decides which copy of a duplicated class is loaded. Here the jarfile that names it
     * comes first, so it is reached through that jarfile's manifest before it is reached directly.
     */
    @Test
    public void aClasspathElementReachedTwiceKeepsItsFirstPosition(@TempDir final Path tempDir) throws IOException {
        final var shared = writeJarWithManifest(tempDir.resolve("shared.jar"));
        final var namesShared = writeJarWithManifest(tempDir.resolve("names-shared.jar"), "Class-Path",
                "shared.jar");
        final var last = writeJarWithManifest(tempDir.resolve("last.jar"));
        try (var classPath = new ClassPathFinder().overrideClasspath(namesShared, last, shared).find()) {
            assertThat(classPath.getLocations()).containsExactly(locationOf(namesShared), locationOf(shared),
                    locationOf(last));
        }
    }

    /** A jarfile does not name itself as a classpath element, however it refers to itself in its manifest. */
    @Test
    public void aJarThatNamesItselfDoesNotLoop(@TempDir final Path tempDir) throws IOException {
        final var self = writeJarWithManifest(tempDir.resolve("self.jar"), "Class-Path", "self.jar");
        try (var classPath = new ClassPathFinder().overrideClasspath((Object) self).find()) {
            assertThat(classPath.getLocations()).containsExactly(locationOf(self));
        }
    }

    /**
     * The classpath elements named by an OSGi bundle jar's {@code Bundle-ClassPath} manifest entry are part of the
     * classpath. Those paths are relative to the root of the bundle jar, so they are reported in the nested form.
     */
    @Test
    public void bundleClassPathEntriesAreAddedToTheClasspath(@TempDir final Path tempDir) throws IOException {
        final var bundle = writeJarWithManifest(tempDir.resolve("bundle.jar"), "Bundle-ClassPath",
                ".,embedded.jar");
        try (var classPath = new ClassPathFinder().overrideClasspath((Object) bundle).find()) {
            assertThat(classPath.getLocations()).containsExactly(locationOf(bundle),
                    locationOf(bundle) + "!/embedded.jar");
        }
    }

    /**
     * The jarfiles in the automatic lib dirs of a classpath element are part of the classpath, whether the element
     * is a jarfile or a directory. Not every classloader lists them as classpath elements of their own.
     */
    @Test
    public void libDirJarsAreAddedToTheClasspath(@TempDir final Path tempDir) throws IOException {
        final var dir = Files.createDirectory(tempDir.resolve("exploded"));
        final var libJar = writeJarWithEntry(dir.resolve("lib").resolve("in-lib-dir.jar"), "resource.txt");
        try (var classPath = new ClassPathFinder().overrideClasspath((Object) dir.toFile()).find()) {
            assertThat(classPath.getLocations()).containsExactly(locationOf(dir.toFile()), locationOf(libJar));
        }
    }

    /** A classpath element that does not exist, or is not a jarfile, is still reported. */
    @Test
    public void aClasspathElementThatCannotBeOpenedIsStillReported(@TempDir final Path tempDir) throws IOException {
        final var notAJar = Files.writeString(tempDir.resolve("not-a-jar.jar"), "this is not a zipfile");
        final var missing = tempDir.resolve("missing.jar").toFile();
        try (var classPath = new ClassPathFinder().overrideClasspath(notAJar.toFile(), missing).find()) {
            assertThat(classPath.getLocations()).containsExactly(locationOf(notAJar.toFile()), locationOf(missing));
        }
    }

    /** Closing the result more than once has no further effect. */
    @Test
    public void theClassPathCanBeClosedTwice(@TempDir final Path tempDir) throws IOException {
        final var jar = writeJarWithManifest(tempDir.resolve("closed-twice.jar"));
        final var classPath = new ClassPathFinder().overrideClasspath((Object) jar).find();
        classPath.close();
        classPath.close();
        // The classpath elements can still be read after the jarfiles have been closed
        assertThat(classPath.getLocations()).containsExactly(locationOf(jar));
    }
}
