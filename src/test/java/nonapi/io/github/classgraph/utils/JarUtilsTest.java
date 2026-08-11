package nonapi.io.github.classgraph.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.File;

import org.junit.jupiter.api.Test;

import nonapi.io.github.classgraph.scanspec.ScanSpec;

/** Tests for {@link JarUtils}. */
public class JarUtilsTest {
    /**
     * A comma is not a legal character in a URL scheme, so a path containing one before the first ':' is not a URL.
     * (The character class in the scheme pattern used to read "+-.", which is a range covering ',' rather than the
     * three literal characters.)
     */
    @Test
    public void commaIsNotPartOfAURLScheme() {
        assertThat(JarUtils.URL_SCHEME_PATTERN.matcher("a,b:/x").matches()).isFalse();
    }

    /** The characters that really are legal in a URL scheme are still accepted. */
    @Test
    public void legalSchemeCharactersStillMatch() {
        assertThat(JarUtils.URL_SCHEME_PATTERN.matcher("http://x").matches()).isTrue();
        assertThat(JarUtils.URL_SCHEME_PATTERN.matcher("a+b:/x").matches()).isTrue();
        assertThat(JarUtils.URL_SCHEME_PATTERN.matcher("a-b:/x").matches()).isTrue();
        assertThat(JarUtils.URL_SCHEME_PATTERN.matcher("a.b:/x").matches()).isTrue();
        assertThat(JarUtils.URL_SCHEME_PATTERN.matcher("a9:/x").matches()).isTrue();
    }

    /** A single-character scheme is not treated as a scheme, so that Windows drive letters are not mistaken for one. */
    @Test
    public void singleCharSchemeDoesNotMatch() {
        assertThat(JarUtils.URL_SCHEME_PATTERN.matcher("C:/x").matches()).isFalse();
    }

    /**
     * A path element containing the path separator character is escaped when the path elements are joined, and
     * unescaped when the resulting path string is split again, so that the round trip preserves the path elements.
     */
    @Test
    public void pathSeparatorInPathElementSurvivesRoundTrip() {
        assumeTrue(File.pathSeparatorChar == ':', "Path separators can only be escaped when the separator is ':'");

        final String joined = JarUtils.pathElementsToPathStr("/a/b", "/weird:name/c", "/d");
        assertThat(joined).isEqualTo("/a/b:/weird\\:name/c:/d");

        assertThat(JarUtils.smartPathSplit(joined, ':', null)).containsExactly("/a/b", "/weird:name/c", "/d");
    }

    /** Path elements with no path separator in them round-trip unchanged. */
    @Test
    public void ordinaryPathElementsSurviveRoundTrip() {
        assumeTrue(File.pathSeparatorChar == ':', "Path separators can only be escaped when the separator is ':'");

        final String joined = JarUtils.pathElementsToPathStr("/a/b", "/c", "/d");
        assertThat(joined).isEqualTo("/a/b:/c:/d");
        assertThat(JarUtils.smartPathSplit(joined, ':', null)).containsExactly("/a/b", "/c", "/d");
    }

    /** URL path elements are still not split at the ':' of their scheme. */
    @Test
    public void urlPathElementsAreNotSplitAtTheirScheme() {
        assertThat(JarUtils.smartPathSplit("http://domain/jar1.jar:https://domain/jar2.jar", ':', null))
                .containsExactly("http://domain/jar1.jar", "https://domain/jar2.jar");
    }

    /**
     * Path elements are trimmed, so whitespace between a separator and a URL scheme is not part of the path
     * element, and does not stop the scheme from being recognized.
     */
    @Test
    public void whitespaceBeforeAURLSchemeDoesNotHideIt() {
        final ScanSpec scanSpec = new ScanSpec();
        scanSpec.enableURLScheme("s3");

        assertThat(JarUtils.smartPathSplit("/a/jar1.jar: http://domain/jar2.jar", ':', null))
                .containsExactly("/a/jar1.jar", "http://domain/jar2.jar");
        assertThat(JarUtils.smartPathSplit(" http://domain/jar1.jar:/a/jar2.jar", ':', null))
                .containsExactly("http://domain/jar1.jar", "/a/jar2.jar");
        assertThat(JarUtils.smartPathSplit("/a/jar1.jar:  jar:file:/a/jar2.jar!/", ':', null))
                .containsExactly("/a/jar1.jar", "jar:file:/a/jar2.jar!/");
        assertThat(JarUtils.smartPathSplit("/a/jar1.jar: s3://bucket/jar2.jar", ':', scanSpec))
                .containsExactly("/a/jar1.jar", "s3://bucket/jar2.jar");

        // Whitespace in the middle of a path element still does not make what follows it a scheme
        assertThat(JarUtils.smartPathSplit("/a dir/http:x", ':', null)).containsExactly("/a dir/http", "x");
    }

    /**
     * The separator is searched for literally, so a separator that happens to be a regular expression
     * metacharacter splits the path where it occurs, and nowhere else.
     */
    @Test
    public void aSeparatorThatIsARegexMetacharacterIsMatchedLiterally() {
        assertThat(JarUtils.smartPathSplit("/a/jar1.jar|/a/jar2.jar", '|', null)).containsExactly("/a/jar1.jar",
                "/a/jar2.jar");
        assertThat(JarUtils.smartPathSplit("a.b.c", '.', null)).containsExactly("a", "b", "c");
    }
}
