package io.github.classgraph.base.internal.path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.File;
import java.util.Set;

import org.junit.jupiter.api.Test;

/** Tests for {@link PathList}. */
public class PathListTest {
    /**
     * A path element containing the path separator character is escaped when the path elements are joined, and
     * unescaped when the resulting path string is split again, so that the round trip preserves the path elements.
     */
    @Test
    public void pathSeparatorInPathElementSurvivesRoundTrip() {
        assumeTrue(File.pathSeparatorChar == ':', "Path separators can only be escaped when the separator is ':'");

        final var joined = PathList.join("/a/b", "/weird:name/c", "/d");
        assertThat(joined).isEqualTo("/a/b:/weird\\:name/c:/d");

        assertThat(PathList.split(joined, ':', null)).containsExactly("/a/b", "/weird:name/c", "/d");
    }

    /** Path elements with no path separator in them round-trip unchanged. */
    @Test
    public void ordinaryPathElementsSurviveRoundTrip() {
        assumeTrue(File.pathSeparatorChar == ':', "Path separators can only be escaped when the separator is ':'");

        final var joined = PathList.join("/a/b", "/c", "/d");
        assertThat(joined).isEqualTo("/a/b:/c:/d");
        assertThat(PathList.split(joined, ':', null)).containsExactly("/a/b", "/c", "/d");
    }

    /** URL path elements are still not split at the ':' of their scheme. */
    @Test
    public void urlPathElementsAreNotSplitAtTheirScheme() {
        assertThat(PathList.split("http://domain/jar1.jar:https://domain/jar2.jar", ':', null))
                .containsExactly("http://domain/jar1.jar", "https://domain/jar2.jar");
    }

    /**
     * A path element written as a URL in a container's own URL protocol is one path element, not two: Tomcat serves
     * a non-exploded WAR through a {@code "war:"} URL, and Spring Boot addresses an entry within an executable jar
     * through a {@code "nested:"} URL, and both of those schemes sit inside a {@code "jar:"} URL.
     */
    @Test
    public void containerURLSchemesAreNotSplitAtTheirScheme() {
        assertThat(PathList.split("war:file:/a/app.war*/WEB-INF/classes/:/tmp/jar2.jar", ':', null))
                .containsExactly("war:file:/a/app.war*/WEB-INF/classes/", "/tmp/jar2.jar");
        assertThat(PathList.split("jar:nested:/a/app.jar/!BOOT-INF/classes/!/:/tmp/jar2.jar", ':', null))
                .containsExactly("jar:nested:/a/app.jar/!BOOT-INF/classes/!/", "/tmp/jar2.jar");
    }

    /** A scheme that is only a URL scheme because the user registered it is not split at either. */
    @Test
    public void registeredURLSchemesAreNotSplitAtTheirScheme() {
        assertThat(PathList.split("s3://bucket/jar1.jar:/tmp/jar2.jar", ':', Set.of())).containsExactly("s3",
                "//bucket/jar1.jar", "/tmp/jar2.jar");
        assertThat(PathList.split("s3://bucket/jar1.jar:/tmp/jar2.jar", ':', Set.of("s3")))
                .containsExactly("s3://bucket/jar1.jar", "/tmp/jar2.jar");
    }

    /**
     * A path element is trimmed, so whitespace between a separator and the URL scheme that follows it does not stop
     * the scheme from being recognized -- otherwise the path would be split at the scheme's own colon, turning one
     * URL into two path elements that name nothing.
     */
    @Test
    public void whitespaceBeforeAURLSchemeDoesNotHideIt() {
        assertThat(PathList.split("/a/jar1.jar: http://domain/jar2.jar", ':', null)).containsExactly("/a/jar1.jar",
                "http://domain/jar2.jar");
        assertThat(PathList.split(" http://domain/jar1.jar:/a/jar2.jar", ':', null))
                .containsExactly("http://domain/jar1.jar", "/a/jar2.jar");
        assertThat(PathList.split("/a/jar1.jar:  jar:file:/a/jar2.jar!/", ':', null)).containsExactly("/a/jar1.jar",
                "jar:file:/a/jar2.jar!/");
        assertThat(PathList.split("/a/jar1.jar: s3://bucket/jar2.jar", ':', Set.of("s3")))
                .containsExactly("/a/jar1.jar", "s3://bucket/jar2.jar");

        // Whitespace in the middle of a path element still does not make what follows it a scheme
        assertThat(PathList.split("/a dir/http:x", ':', null)).containsExactly("/a dir/http", "x");
    }

    /**
     * A separator that is a regular expression metacharacter splits the path on the character itself, rather than
     * on whatever the character means as a regular expression.
     */
    @Test
    public void aSeparatorThatIsARegexMetacharacterIsMatchedLiterally() {
        assertThat(PathList.split("/a/jar1.jar|/a/jar2.jar", '|', null)).containsExactly("/a/jar1.jar",
                "/a/jar2.jar");
        assertThat(PathList.split("a.b.c", '.', null)).containsExactly("a", "b", "c");
    }

    /** Where the path separator is not ':', the path is split on it with no URL scheme handling. */
    @Test
    public void nonColonSeparatorsSplitTheWholePath() {
        assertThat(PathList.split("C:\\a.jar;C:\\b.jar", ';', null)).containsExactly("C:\\a.jar", "C:\\b.jar");
        // Empty and whitespace-only path elements are dropped, and the rest are trimmed
        assertThat(PathList.split("a.jar; ;; b.jar ;", ';', null)).containsExactly("a.jar", "b.jar");
        assertThat(PathList.split("a.jar: :: b.jar :", ':', null)).containsExactly("a.jar", "b.jar");
    }

    /** A null or empty path splits into no path elements at all, rather than one empty element. */
    @Test
    public void nullAndEmptyPathsSplitIntoNothing() {
        assertThat(PathList.split(null, ':', null)).isEmpty();
        assertThat(PathList.split("", ':', null)).isEmpty();
        assertThat(PathList.split(":::", ':', null)).isEmpty();
    }
}
