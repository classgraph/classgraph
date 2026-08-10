package nonapi.io.github.classgraph.utils;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;

import org.junit.jupiter.api.Test;

/**
 * Tests for {@link URLPathEncoder#normalizeURLPath(String)}.
 *
 * <p>
 * All the paths used here are free of Windows drive letters, so the expected results are the same on every platform.
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

    /** A path containing a nested jar separator gets the "jar:" prefix added back. */
    @Test
    public void nestedJarPathsGetJarPrefix() {
        assertThat(URLPathEncoder.normalizeURLPath("/tmp/x.jar!/BOOT-INF/classes"))
                .isEqualTo("jar:file:///tmp/x.jar!/BOOT-INF/classes");
        assertThat(URLPathEncoder.normalizeURLPath("file:/tmp/x.jar!/BOOT-INF/classes"))
                .isEqualTo("jar:file:///tmp/x.jar!/BOOT-INF/classes");
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
