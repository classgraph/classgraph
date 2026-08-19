package io.github.classgraph.classpath;

import static io.github.classgraph.classpath.Locations.location;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;

import io.github.classgraph.vfs.Vfs;
import io.github.classgraph.vfs.VfsEntry;
import io.github.classgraph.vfs.VfsSpec;

/** Tests for the forms a classpath element can be found in, and for opening it in each of them. */
public class ClasspathEntryTest {
    /** The path of the only entry in the jarfiles written by {@link #writeJar(Path)}. */
    private static final String ENTRY_PATH = "com/xyz/Widget.class";

    /**
     * Write a jarfile containing a single entry.
     *
     * @param jarFile
     *            the jarfile to write.
     * @return the jarfile.
     * @throws IOException
     *             if the jarfile could not be written.
     */
    private static Path writeJar(final Path jarFile) throws IOException {
        try (var outputStream = Files.newOutputStream(jarFile);
                var zipOutputStream = new ZipOutputStream(outputStream)) {
            zipOutputStream.putNextEntry(new ZipEntry(ENTRY_PATH));
            zipOutputStream.closeEntry();
        }
        return jarFile;
    }

    /**
     * Find a classpath consisting of a single classpath element, named by the given object.
     *
     * @param classpathElement
     *            the object naming the classpath element.
     * @return the classpath.
     */
    private static Classpath findClasspath(final Object classpathElement) {
        return new ClasspathFinder().overrideClasspath(classpathElement).find();
    }

    /**
     * A classpath element is wrapped in the subclass that matches the form it was found in, so that nothing has to
     * be recovered by parsing its location back into an object.
     */
    @Test
    public void eachFormOfClasspathElementIsWrappedInItsOwnSubclass(@TempDir final Path tempDir)
            throws IOException {
        final var jar = writeJar(tempDir.resolve("lib.jar"));
        final var location = location(jar);

        try (var classpath = findClasspath(location)) {
            assertThat(classpath.getEntries()).singleElement().isInstanceOf(ClasspathEntry.OfPathString.class)
                    .returns(location, ClasspathEntry::getLocation);
        }
        try (var classpath = findClasspath(jar.toFile())) {
            assertThat(classpath.getEntries()).singleElement().isInstanceOf(ClasspathEntry.OfFile.class)
                    .returns(location, ClasspathEntry::getLocation);
        }
        try (var classpath = findClasspath(jar)) {
            assertThat(classpath.getEntries()).singleElement().isInstanceOf(ClasspathEntry.OfPath.class)
                    .returns(location, ClasspathEntry::getLocation);
        }
        try (var classpath = findClasspath(jar.toUri())) {
            assertThat(classpath.getEntries()).singleElement().isInstanceOf(ClasspathEntry.OfURI.class)
                    .returns(location, ClasspathEntry::getLocation);
        }
        try (var classpath = findClasspath(jar.toUri().toURL())) {
            assertThat(classpath.getEntries()).singleElement().isInstanceOf(ClasspathEntry.OfURL.class)
                    .returns(location, ClasspathEntry::getLocation);
        }
    }

    /** Whichever form a classpath element was found in, opening it reads the same classpath element. */
    @Test
    public void aClasspathElementIsOpenedInTheFormItWasFoundIn(@TempDir final Path tempDir) throws IOException {
        final var jar = writeJar(tempDir.resolve("lib.jar"));
        for (final Object classpathElement : new Object[] { jar.toString(), jar.toFile(), jar, jar.toUri(),
                jar.toUri().toURL() }) {
            try (var classpath = findClasspath(classpathElement)) {
                final var entry = classpath.getEntries().get(0);
                assertThat(entry.open(classpath.getVfs())).extracting(VfsEntry::getName)
                        .containsExactly(ENTRY_PATH);
            }
        }
    }

    /**
     * A classpath element found at a relative path is opened at the location it is reported at, so that the same
     * jarfile is not read a second time under the relative name it was found as.
     */
    @Test
    public void aClasspathElementFoundAtARelativePathIsOpenedAtItsLocation() throws IOException {
        // The jarfile is written below the working directory rather than in a temporary directory, because a path
        // can only be relativized against the working directory when the two share a root, and on Windows the
        // temporary directory is usually on a different drive
        final var dir = Files.createTempDirectory(Files.createDirectories(Path.of("target")), "relative-element-")
                .toAbsolutePath();
        try {
            final var jar = writeJar(dir.resolve("lib.jar"));
            final var relativeJar = Path.of("").toAbsolutePath().relativize(jar);
            assertThat(relativeJar.isAbsolute()).isFalse();

            for (final Object classpathElement : new Object[] { relativeJar, relativeJar.toFile() }) {
                try (var classpath = findClasspath(classpathElement)) {
                    final var entry = classpath.getEntries().get(0);
                    assertThat(Path.of(entry.getLocation()).toRealPath()).isEqualTo(jar.toRealPath());
                    assertThat(entry.open(classpath.getVfs()))
                            .isSameAs(classpath.getVfs().open(entry.getLocation()));
                }
            }
        } finally {
            Files.deleteIfExists(dir.resolve("lib.jar"));
            Files.deleteIfExists(dir);
        }
    }

    /**
     * A classpath element found as a {@link Path} in a filesystem other than the default one is opened through that
     * filesystem rather than through its location, so it is still reached by a {@link Vfs} that refuses to fetch
     * anything over its location's URL scheme.
     */
    @Test
    public void aClasspathElementInAnotherFilesystemIsOpenedThroughThatFilesystem() throws IOException {
        try (FileSystem fileSystem = Jimfs.newFileSystem(Configuration.unix())) {
            final var jar = writeJar(fileSystem.getPath("/lib.jar"));

            try (var classpath = findClasspath(jar)) {
                final var entry = classpath.getEntries().get(0);
                assertThat(entry).isInstanceOf(ClasspathEntry.OfPath.class);
                assertThat(entry.getLocation()).isEqualTo(jar.toUri().toString()).startsWith("jimfs://");
                assertThat(entry.open(classpath.getVfs())).extracting(VfsEntry::getName)
                        .containsExactly(ENTRY_PATH);
                try (var denyingVfs = new Vfs(new VfsSpec().disableURLScheme("jimfs"))) {
                    // Nothing is fetched over a denied scheme, so the location alone does not reach the element
                    assertThatThrownBy(() -> denyingVfs.open(entry.getLocation())).isInstanceOf(IOException.class);
                    // Opening the Path reads through its own filesystem, which fetches nothing, so the element is
                    // still reached -- which is the whole reason the Path is kept, rather than the element being
                    // flattened to its location and that being parsed back
                    final var root = entry.open(denyingVfs);
                    assertThat(root).extracting(VfsEntry::getName).containsExactly(ENTRY_PATH);
                    // A root that is already open is not fetched again, so the location now names it
                    assertThat(denyingVfs.open(entry.getLocation())).isSameAs(root);
                }
            }
        }
    }

    /** A classpath element is equal to another that was found in the same form at the same location. */
    @Test
    public void classpathElementsFoundInDifferentFormsAreNotEqual(@TempDir final Path tempDir) throws IOException {
        final var jar = writeJar(tempDir.resolve("lib.jar"));
        try (var asPath = findClasspath(jar);
                var asPathAgain = findClasspath(jar);
                var asFile = findClasspath(jar.toFile())) {
            final var entry = asPath.getEntries().get(0);
            assertThat(entry).isEqualTo(asPathAgain.getEntries().get(0))
                    .hasSameHashCodeAs(asPathAgain.getEntries().get(0)).isNotEqualTo(asFile.getEntries().get(0));
            assertThat(entry).hasToString(entry.getLocation());
        }
    }

    /** A classpath element cannot be opened without a virtual filesystem to open it through. */
    @Test
    public void openingAClasspathElementNeedsAVfs(@TempDir final Path tempDir) throws IOException {
        final var jar = writeJar(tempDir.resolve("lib.jar"));
        try (var classpath = findClasspath(jar)) {
            final var entry = classpath.getEntries().get(0);
            assertThatThrownBy(() -> entry.open(null)).isInstanceOf(NullPointerException.class);
        }
    }

    /** Iterating a classpath iterates its classpath elements, in the order they would be searched. */
    @Test
    public void aClasspathIteratesItsClasspathElements(@TempDir final Path tempDir) throws IOException {
        final var first = writeJar(tempDir.resolve("first.jar"));
        final var second = writeJar(tempDir.resolve("second.jar"));
        try (var classpath = new ClasspathFinder().overrideClasspath(first, second).find()) {
            assertThat(classpath).containsExactlyElementsOf(classpath.getEntries())
                    .extracting(ClasspathEntry::getLocation).containsExactly(location(first), location(second));
        }
    }
}
