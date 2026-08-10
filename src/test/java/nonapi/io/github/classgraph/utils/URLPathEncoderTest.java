package nonapi.io.github.classgraph.utils;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for {@link URLPathEncoder#normalizeURLPath(String)}.
 *
 * <p>
 * Apart from the tests that need a real file on disk, all the paths used here are free of Windows drive letters, so
 * the expected results are the same on every platform.
 */
public class URLPathEncoderTest {
    /** A bare absolute path is turned into a "file://" URL. */
    @Test
    public void bareAbsolutePathIsPrefixed() {
        assertThat(URLPathEncoder.normalizeURLPath("/tmp/x.jar")).isEqualTo("file:///tmp/x.jar");
    }

    /**
     * A path that already carries a "file:" prefix must have all five characters of that prefix stripped before the
     * canonical prefix is added back. Stripping only four left the ':' behind, which was then percent-encoded,
     * producing "file:%3a/tmp/x.jar".
     */
    @Test
    public void fileSchemePrefixIsStrippedInFull() {
        assertThat(URLPathEncoder.normalizeURLPath("file:/tmp/x.jar")).isEqualTo("file:///tmp/x.jar");
    }

    /** "file://" and "file:///" carry an empty authority, which must not accumulate extra slashes. */
    @Test
    public void emptyAuthorityDoesNotAccumulateSlashes() {
        assertThat(URLPathEncoder.normalizeURLPath("file://tmp/x.jar")).isEqualTo("file:///tmp/x.jar");
        assertThat(URLPathEncoder.normalizeURLPath("file:///tmp/x.jar")).isEqualTo("file:///tmp/x.jar");
    }

    /** A "jar:file:" prefix is stripped by both branches in turn. */
    @Test
    public void jarAndFileSchemePrefixesAreBothStripped() {
        assertThat(URLPathEncoder.normalizeURLPath("jar:file:/tmp/x.jar")).isEqualTo("file:///tmp/x.jar");
        assertThat(URLPathEncoder.normalizeURLPath("jar:/tmp/x.jar")).isEqualTo("file:///tmp/x.jar");
    }

    /**
     * A path containing a nested jar separator gets the "jar:" prefix added back. The jar has to exist on disk,
     * since a '!' only counts as a separator if the path before it names a file.
     */
    // #903
    @Test
    public void nestedJarPathsGetJarPrefix(@TempDir final Path tempDir) throws IOException {
        final String jarPath = slashes(Files.createFile(tempDir.resolve("x.jar")));
        final String jarUrl = URLPathEncoder.normalizeURLPath(jarPath);
        assertThat(jarUrl).startsWith("file:///").endsWith("/x.jar");
        assertThat(URLPathEncoder.normalizeURLPath(jarPath + "!/BOOT-INF/classes"))
                .isEqualTo("jar:" + jarUrl + "!/BOOT-INF/classes");
        assertThat(URLPathEncoder.normalizeURLPath("file:" + jarPath + "!/BOOT-INF/classes"))
                .isEqualTo("jar:" + jarUrl + "!/BOOT-INF/classes");
    }

    /**
     * A '!' that is part of a directory or file name is an ordinary character, not a nested jar separator, so no
     * '/' may be inserted after it -- doing so names a different path, which cannot be opened.
     */
    // #903
    @Test
    public void bangInADirectoryNameIsNotASeparator(@TempDir final Path tempDir) throws IOException {
        final Path dir = Files.createDirectories(tempDir.resolve("with!bang"));
        final String jarPath = slashes(Files.createFile(dir.resolve("x.jar")));
        // The directory itself is not a jarfile, so nothing is nested and no "jar:" prefix is added
        assertThat(URLPathEncoder.normalizeURLPath(jarPath)).startsWith("file:///").endsWith("/with!bang/x.jar");
        // Only the '!' that separates the jar from the path within it is a separator
        assertThat(URLPathEncoder.normalizeURLPath(jarPath + "!/probepkg/Probe.class")).startsWith("jar:file:///")
                .endsWith("/with!bang/x.jar!/probepkg/Probe.class");
    }

    /**
     * The path of a file, with the platform's separator turned into the '/' that URLs use.
     *
     * @param path
     *            the path
     * @return the path as a string, using '/' as the separator
     */
    private static String slashes(final Path path) {
        return path.toString().replace(File.separatorChar, '/');
    }

    /** Schemes that are passed through untouched. */
    @Test
    public void nonFileSchemesArePassedThrough() {
        assertThat(URLPathEncoder.normalizeURLPath("jrt:/java.base")).isEqualTo("jrt:/java.base");
        assertThat(URLPathEncoder.normalizeURLPath("http://example.com/x.jar"))
                .isEqualTo("http://example.com/x.jar");
        assertThat(URLPathEncoder.normalizeURLPath("https://example.com/x.jar"))
                .isEqualTo("https://example.com/x.jar");
    }

    /**
     * The whole point of normalizing is to produce something the {@link URI} constructor accepts, so check that the
     * result parses and keeps the path intact. {@code ClasspathElementZip#getURI()} throws if this fails.
     */
    @Test
    public void normalizedPathsParseAsURIs() throws Exception {
        assertThat(new URI(URLPathEncoder.normalizeURLPath("file:/tmp/x.jar")).getPath()).isEqualTo("/tmp/x.jar");
        assertThat(new URI(URLPathEncoder.normalizeURLPath("/tmp/x.jar")).getPath()).isEqualTo("/tmp/x.jar");
        // A relative path stays relative, so it is opaque rather than hierarchical
        assertThat(new URI(URLPathEncoder.normalizeURLPath("tmp/x.jar")).toString()).isEqualTo("file:tmp/x.jar");
    }

    /**
     * A '%' that does not introduce two hexadecimal digits is not an escape sequence, so it is passed through as it
     * is.
     */
    @Test
    public void aPercentThatIsNotAnEscapeSequenceIsPassedThrough() {
        assertThat(URLPathEncoder.decodePath("/tmp/100%zz.jar")).isEqualTo("/tmp/100%zz.jar");
        // A '%' too close to the end of the string to be followed by two hexadecimal digits is passed through the
        // same way. This used to drop the '%' but keep the digits after it, silently renaming the path
        assertThat(URLPathEncoder.decodePath("/tmp/100%2")).isEqualTo("/tmp/100%2");
        assertThat(URLPathEncoder.decodePath("/tmp/100%")).isEqualTo("/tmp/100%");
    }
}
