package io.github.classgraph.classpath;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.abort;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
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

import com.sun.net.httpserver.HttpServer;

import io.github.classgraph.vfs.Vfs;
import io.github.classgraph.vfs.VfsEntry;

/** Tests for the public API of the classpath finder. */
public class ClasspathFinderTest {
    /** The classpath of the JVM running the tests contains the directory the test classes were compiled to. */
    @Test
    public void theEnvironmentClasspathIsFound() {
        try (var classpath = new ClasspathFinder().find()) {
            assertThat(classpath.getLocations()).anyMatch(location -> location.endsWith("/target/test-classes"));
        }
    }

    /** Every entry records the classloader it was found through, and the package roots to look for within it. */
    @Test
    public void entriesRecordTheirClassLoaderAndPackageRoots() {
        try (var classpath = new ClasspathFinder().find()) {
            final var entries = classpath.getEntries();
            assertThat(entries).isNotEmpty();
            for (final ClasspathEntry entry : entries) {
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
        final var classpath = new ClasspathFinder().overrideClasspath(first + File.pathSeparator + second).find();
        assertThat(classpath.getLocations()).containsExactly(first.getPath().replace(File.separatorChar, '/'),
                second.getPath().replace(File.separatorChar, '/'));
        // Modules are not scanned when the classpath is overridden
        assertThat(classpath.getModules()).isEmpty();
    }

    /** Each classpath element of an overridden classpath is passed through unsplit by the non-String overloads. */
    @Test
    public void theClasspathCanBeOverriddenWithIndividualElements() {
        final var jar = new File("only.jar").getAbsoluteFile();
        final var expected = List.of(jar.getPath().replace(File.separatorChar, '/'));
        assertThat(new ClasspathFinder().overrideClasspath((Object) jar).find().getLocations()).isEqualTo(expected);
        assertThat(new ClasspathFinder().overrideClasspath(List.of(jar)).find().getLocations()).isEqualTo(expected);
        // A single Path is one classpath entry, not a sequence of its name elements
        assertThat(new ClasspathFinder().overrideClasspath(jar.toPath()).find().getLocations()).isEqualTo(expected);
    }

    /** An empty classpath override is a caller error, rather than a silent scan of nothing. */
    @Test
    public void anEmptyClasspathOverrideIsRejected() {
        assertThatThrownBy(() -> new ClasspathFinder().overrideClasspath(""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ClasspathFinder().overrideClasspath(new Object[0]))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ClasspathFinder().overrideClasspath(List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ClasspathFinder().overrideClassLoaders())
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** A classloader is passed to {@code overrideClassLoaders}, not to {@code overrideClasspath}. */
    @Test
    public void aClassLoaderIsNotAClasspathElement() {
        assertThatThrownBy(
                () -> new ClasspathFinder().overrideClasspath((Object) ClasspathFinderTest.class.getClassLoader()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** The modules the JVM can see are found, and split into the JDK's own modules and everything else. */
    @Test
    public void theModulesAreFoundAndSplitIntoSystemAndNonSystem() {
        final var classpath = new ClasspathFinder().find();
        assertThat(classpath.getSystemModules()).anyMatch(module -> "java.base".equals(module.descriptor().name()));
        assertThat(classpath.getNonSystemModules())
                .noneMatch(module -> module.descriptor().name().startsWith("java."));
        // getModules() lists the system modules first, then the rest
        assertThat(classpath.getModules()).startsWith(classpath.getSystemModules().get(0));
        assertThat(classpath.getModules())
                .hasSize(classpath.getSystemModules().size() + classpath.getNonSystemModules().size());
    }

    /** Module finding can be switched off altogether. */
    @Test
    public void moduleFindingCanBeDisabled() {
        assertThat(new ClasspathFinder().ignoreModules().find().getModules()).isEmpty();
    }

    /** The module path switches the JVM was launched with are reachable from the result. */
    @Test
    public void theModulePathInfoIsReachable() {
        assertThat(new ClasspathFinder().find().getModulePathInfo()).isNotNull();
    }

    /**
     * The jarfiles on the classpath are read through a {@link io.github.classgraph.vfs.Vfs} that the result hands
     * out, so that a classpath element can be read without opening it a second time, and closing the result closes
     * that virtual filesystem.
     */
    @Test
    public void theVfsTheClasspathWasReadThroughIsReachable(@TempDir final Path tempDir) throws IOException {
        final var jar = writeJarWithEntry(tempDir.resolve("lib.jar"), "com/xyz/Widget.class");
        final Vfs vfs;
        try (var classpath = new ClasspathFinder().overrideClasspath((Object) jar).find()) {
            vfs = classpath.getVfs();
            final var location = classpath.getLocations().get(0);
            final var root = vfs.open(location);
            assertThat(root.getEntries()).extracting(VfsEntry::getName).containsExactly("com/xyz/Widget.class");
            // Opening the same location again hands back the root that is already open, rather than reading the
            // jarfile a second time
            assertThat(vfs.open(location)).isSameAs(root);
        }
        // Closing the Classpath closed the Vfs it was read through
        assertThatThrownBy(() -> vfs.open(locationOf(jar))).isInstanceOf(IOException.class);
    }

    /** The result prints one classpath element or module per line. */
    @Test
    public void theClassPathPrintsOneEntryPerLine() {
        final var jar = new File("only.jar").getAbsoluteFile();
        assertThat(new ClasspathFinder().overrideClasspath((Object) jar).find())
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
        try (var outputStream = Files.newOutputStream(jarFile)) {
            // No entries -- the manifest is the whole jar, so all that is left is to write the central
            // directory. finish() does that without closing outputStream, which the try does.
            new JarOutputStream(outputStream, manifest).finish();
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
        try (var classpath = new ClasspathFinder().overrideClasspath(first, other).find()) {
            assertThat(classpath.getLocations()).containsExactly(locationOf(first), locationOf(middle),
                    locationOf(last), locationOf(other));
        }
    }

    /**
     * The classpath elements that a jarfile names are reported relative to the path the jarfile was reached at, not
     * relative to the path the jarfile has once symlinks have been resolved. Otherwise the same jarfile reached
     * through a symlink and directly would be reported as two different classpath elements.
     */
    @Test
    public void manifestClassPathEntriesAreResolvedRelativeToThePathTheJarWasReachedAt(@TempDir final Path tempDir)
            throws IOException {
        final var dir = Files.createDirectory(tempDir.resolve("real"));
        writeJarWithManifest(dir.resolve("named.jar"));
        writeJarWithManifest(dir.resolve("names-another.jar"), "Class-Path", "named.jar");
        final Path linkedDir;
        try {
            linkedDir = Files.createSymbolicLink(tempDir.resolve("link"), dir);
        } catch (IOException | UnsupportedOperationException | SecurityException e) {
            // Creating a symlink needs a privilege that is not granted by default on Windows
            abort("Symlinks cannot be created: " + e);
            return;
        }
        final var namesAnotherViaLink = linkedDir.resolve("names-another.jar").toFile();
        try (var classpath = new ClasspathFinder().overrideClasspath((Object) namesAnotherViaLink).find()) {
            assertThat(classpath.getLocations()).containsExactly(locationOf(namesAnotherViaLink),
                    locationOf(linkedDir.resolve("named.jar").toFile()));
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
        try (var classpath = new ClasspathFinder().overrideClasspath(namesShared, last, shared).find()) {
            assertThat(classpath.getLocations()).containsExactly(locationOf(namesShared), locationOf(shared),
                    locationOf(last));
        }
    }

    /** A jarfile does not name itself as a classpath element, however it refers to itself in its manifest. */
    @Test
    public void aJarThatNamesItselfDoesNotLoop(@TempDir final Path tempDir) throws IOException {
        final var self = writeJarWithManifest(tempDir.resolve("self.jar"), "Class-Path", "self.jar");
        try (var classpath = new ClasspathFinder().overrideClasspath((Object) self).find()) {
            assertThat(classpath.getLocations()).containsExactly(locationOf(self));
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
        try (var classpath = new ClasspathFinder().overrideClasspath((Object) bundle).find()) {
            assertThat(classpath.getLocations()).containsExactly(locationOf(bundle),
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
        try (var classpath = new ClasspathFinder().overrideClasspath((Object) dir.toFile()).find()) {
            assertThat(classpath.getLocations()).containsExactly(locationOf(dir.toFile()), locationOf(libJar));
        }
    }

    /** A classpath element that does not exist, or is not a jarfile, is still reported. */
    @Test
    public void aClasspathElementThatCannotBeOpenedIsStillReported(@TempDir final Path tempDir) throws IOException {
        final var notAJar = Files.writeString(tempDir.resolve("not-a-jar.jar"), "this is not a zipfile");
        final var missing = tempDir.resolve("missing.jar").toFile();
        try (var classpath = new ClasspathFinder().overrideClasspath(notAJar.toFile(), missing).find()) {
            assertThat(classpath.getLocations()).containsExactly(locationOf(notAJar.toFile()), locationOf(missing));
        }
    }

    /**
     * Serve the given bytes over HTTP on the loopback interface, so that a jarfile can be reached by URL without
     * touching the network.
     *
     * @param path
     *            the path to serve the bytes at, e.g. {@code "/lib.jar"}.
     * @param body
     *            the bytes to serve.
     * @return the server, which the caller must stop.
     * @throws IOException
     *             if the server could not be started.
     */
    private static HttpServer serve(final String path, final byte[] body) throws IOException {
        final var server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0),
                /* backlog = */ 1);
        server.createContext(path, exchange -> {
            exchange.sendResponseHeaders(200, body.length);
            try (var responseBody = exchange.getResponseBody()) {
                responseBody.write(body);
            }
        });
        server.start();
        return server;
    }

    /**
     * A classpath element named by a URL is only read if its scheme has been enabled. It is reported either way,
     * but until the scheme is enabled the jarfile it names is not fetched, so the elements it declares are not
     * found.
     *
     * @param tempDir
     *            a temporary directory to build the jarfile in.
     * @throws IOException
     *             if the jarfile could not be built, or the server could not be started.
     */
    @Test
    public void aUrlClasspathElementIsOnlyReadIfItsSchemeIsEnabled(@TempDir final Path tempDir) throws IOException {
        final var jarBytes = Files.readAllBytes(
                writeJarWithManifest(tempDir.resolve("served.jar"), "Class-Path", "declared.jar").toPath());
        final var server = serve("/served.jar", jarBytes);
        try {
            final var jarURL = "http://" + server.getAddress().getHostString() + ":" + server.getAddress().getPort()
                    + "/served.jar";
            final var declaredURL = jarURL.replace("served.jar", "declared.jar");

            // The scheme has not been enabled, so the jarfile is not fetched and its manifest is not read
            try (var classpath = new ClasspathFinder().overrideClasspath((Object) jarURL).find()) {
                assertThat(classpath.getLocations()).containsExactly(jarURL);
            }

            // With the scheme enabled, the jarfile is fetched, and the element its manifest declares is found too
            try (var classpath = new ClasspathFinder().enableURLScheme("http").overrideClasspath((Object) jarURL)
                    .find()) {
                assertThat(classpath.getLocations()).containsExactly(jarURL, declaredURL);
            }
        } finally {
            server.stop(0);
        }
    }

    /** Closing the result more than once has no further effect. */
    @Test
    public void theClassPathCanBeClosedTwice(@TempDir final Path tempDir) throws IOException {
        final var jar = writeJarWithManifest(tempDir.resolve("closed-twice.jar"));
        final var classpath = new ClasspathFinder().overrideClasspath((Object) jar).find();
        classpath.close();
        classpath.close();
        // The classpath elements can still be read after the jarfiles have been closed
        assertThat(classpath.getLocations()).containsExactly(locationOf(jar));
    }
}
