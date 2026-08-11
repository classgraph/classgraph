package io.github.classgraph.base.internal.utils;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for {@link URLPathEncoder}.
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

    /**
     * "file://" and "file:///" carry an empty authority, which must not accumulate extra slashes.
     */
    @Test
    public void emptyAuthorityDoesNotAccumulateSlashes() {
        assertThat(URLPathEncoder.normalizeURLPath("file://tmp/x.jar")).isEqualTo("file:///tmp/x.jar");
        assertThat(URLPathEncoder.normalizeURLPath("file:///tmp/x.jar")).isEqualTo("file:///tmp/x.jar");
    }

    /**
     * The {@code "//"} that starts a UNC path is part of the path, not the empty authority in front of it, so it
     * has to survive. Collapsing every leading slash down to one turned {@code "file:////server/share/x"} into
     * {@code "file:///server/share/x"}, which names a local path rather than the share it came from.
     */
    @Test
    public void aUNCPathKeepsTheSlashesThatStartIt() {
        assertThat(URLPathEncoder.normalizeURLPath("file:////server/share/x.jar"))
                .isEqualTo("file:////server/share/x.jar");
        // The same path with no "file:" prefix already reaches this spelling, and still does
        assertThat(URLPathEncoder.normalizeURLPath("//server/share/x.jar"))
                .isEqualTo("file:////server/share/x.jar");
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
        final var jarPath = slashes(Files.createFile(tempDir.resolve("x.jar")));
        final var jarUrl = URLPathEncoder.normalizeURLPath(jarPath);
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
        final var dir = Files.createDirectories(tempDir.resolve("with!bang"));
        final var jarPath = slashes(Files.createFile(dir.resolve("x.jar")));
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

    /** Characters that are not URL-safe are percent-encoded, and '/' is left alone. */
    @Test
    public void unsafeCharactersAreEncoded() {
        assertThat(URLPathEncoder.encodePath("/tmp/a b.jar")).isEqualTo("/tmp/a%20b.jar");
        assertThat(URLPathEncoder.encodePath("/tmp/a[1].jar")).isEqualTo("/tmp/a%5b1%5d.jar");
        // Non-ASCII characters are encoded as their UTF-8 bytes
        assertThat(URLPathEncoder.encodePath("/tmp/é.jar")).isEqualTo("/tmp/%c3%a9.jar");
    }

    /** The "safe" and "extra" URL character rules, plus '/', are left as they are. */
    @Test
    public void safeCharactersAreNotEncoded() {
        final var safeChars = "abcXYZ019$-_.+!*'(),/";
        assertThat(URLPathEncoder.encodePath(safeChars)).isEqualTo(safeChars);
    }

    /** A ':' is only left alone where it belongs to a URL scheme prefix. */
    @Test
    public void colonIsOnlyKeptInASchemePrefix() {
        assertThat(URLPathEncoder.encodePath("file:/tmp/x.jar")).isEqualTo("file:/tmp/x.jar");
        assertThat(URLPathEncoder.encodePath("jar:file:/tmp/x.jar")).isEqualTo("jar:file:/tmp/x.jar");
        assertThat(URLPathEncoder.encodePath("jrt:/java.base")).isEqualTo("jrt:/java.base");
        // A ':' anywhere else is encoded, since it is not safe in a path segment
        assertThat(URLPathEncoder.encodePath("/tmp/a:b.jar")).isEqualTo("/tmp/a%3ab.jar");
    }

    /** Percent-escaped sequences are decoded, in either case, and back into UTF-8 characters. */
    @Test
    public void escapedCharactersAreDecoded() {
        assertThat(URLPathEncoder.decodePath("/tmp/a%20b.jar")).isEqualTo("/tmp/a b.jar");
        assertThat(URLPathEncoder.decodePath("/tmp/a%5b1%5D.jar")).isEqualTo("/tmp/a[1].jar");
        assertThat(URLPathEncoder.decodePath("/tmp/%C3%A9.jar")).isEqualTo("/tmp/é.jar");
        assertThat(URLPathEncoder.decodePath("/tmp/x.jar")).isEqualTo("/tmp/x.jar");
        assertThat(URLPathEncoder.decodePath("")).isEmpty();
    }

    /**
     * {@code '+'} means a space only in the query string, not in the path -- a jar named "a+b.jar" is a real
     * filename, and decoding its '+' as a space would stop it from being found.
     */
    // #468
    @Test
    public void plusIsOnlyASpaceInTheQueryString() {
        assertThat(URLPathEncoder.decodePath("/tmp/a+b.jar")).isEqualTo("/tmp/a+b.jar");
        assertThat(URLPathEncoder.decodePath("/tmp/a+b.jar?x=1+2")).isEqualTo("/tmp/a+b.jar?x=1 2");
    }

    /**
     * A '%' that does not introduce two hexadecimal digits is not an escape sequence, so it is passed through as it
     * is, rather than decoding to a wrong character or throwing.
     */
    @Test
    public void malformedEscapeSequencesArePassedThrough() {
        assertThat(URLPathEncoder.decodePath("/tmp/100%zz.jar")).isEqualTo("/tmp/100%zz.jar");
        // A '%' too close to the end of the string to be followed by two hexadecimal digits is passed through the
        // same way. This used to drop the '%' but keep the digits after it, silently renaming the path
        assertThat(URLPathEncoder.decodePath("/tmp/100%2")).isEqualTo("/tmp/100%2");
        assertThat(URLPathEncoder.decodePath("/tmp/100%")).isEqualTo("/tmp/100%");
    }

    /** Encoding and then decoding a path returns the original path. */
    @Test
    public void encodingThenDecodingRoundTrips() {
        final var path = "/tmp/a b[1]+é.jar";
        assertThat(URLPathEncoder.decodePath(URLPathEncoder.encodePath(path))).isEqualTo(path);
    }

    /**
     * The server of a UNC path has to be in the path of a {@code "file:"} URI, not in its authority, otherwise
     * {@link java.net.URL} reads the URI back as a local path with the server dropped. Verified on Windows: opening
     * {@code file://server/share/x} fails with {@code FileNotFoundException: \share\x}, while
     * {@code file:////server/share/x} reads the file the UNC path names.
     */
    @Test
    public void aUNCServerIsMovedFromTheAuthorityIntoThePath() {
        assertThat(URLPathEncoder.moveUNCServerIntoPath(URI.create("file://server/share/x")))
                .isEqualTo(URI.create("file:////server/share/x"));
        // A directory URI keeps its trailing slash
        assertThat(URLPathEncoder.moveUNCServerIntoPath(URI.create("file://server/share/dir/")))
                .isEqualTo(URI.create("file:////server/share/dir/"));
        // Percent escapes in the path are carried over as they are, rather than being decoded or double-encoded
        assertThat(URLPathEncoder.moveUNCServerIntoPath(URI.create("file://server/share/a%20b")))
                .isEqualTo(URI.create("file:////server/share/a%20b"));
    }

    /** A URI that has no authority to move is returned unchanged, whatever its scheme. */
    @Test
    public void aURIWithNoAuthorityIsUnchanged() {
        for (final var uri : new String[] { "file:///tmp/x.jar", "file:/tmp/x.jar", "file:////server/share/x",
                "jar:file:///tmp/x.jar!/a/b", "jrt:/java.base/java/lang/Object.class" }) {
            assertThat(URLPathEncoder.moveUNCServerIntoPath(URI.create(uri))).isEqualTo(URI.create(uri));
        }
        // Only a "file:" URI names a path on the local filesystem, so an authority is left alone for any other
        // scheme -- there it is a real host, not a UNC server
        assertThat(URLPathEncoder.moveUNCServerIntoPath(URI.create("https://host/a/b")))
                .isEqualTo(URI.create("https://host/a/b"));
    }
}
