package io.github.classgraph.base.internal.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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

    /**
     * A single-character scheme is not treated as a scheme, so that Windows drive letters are not mistaken for one.
     */
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

        final var joined = JarUtils.pathElementsToPathStr("/a/b", "/weird:name/c", "/d");
        assertThat(joined).isEqualTo("/a/b:/weird\\:name/c:/d");

        assertThat(JarUtils.smartPathSplit(joined, ':', null)).containsExactly("/a/b", "/weird:name/c", "/d");
    }

    /** Path elements with no path separator in them round-trip unchanged. */
    @Test
    public void ordinaryPathElementsSurviveRoundTrip() {
        assumeTrue(File.pathSeparatorChar == ':', "Path separators can only be escaped when the separator is ':'");

        final var joined = JarUtils.pathElementsToPathStr("/a/b", "/c", "/d");
        assertThat(joined).isEqualTo("/a/b:/c:/d");
        assertThat(JarUtils.smartPathSplit(joined, ':', null)).containsExactly("/a/b", "/c", "/d");
    }

    /** URL path elements are still not split at the ':' of their scheme. */
    @Test
    public void urlPathElementsAreNotSplitAtTheirScheme() {
        assertThat(JarUtils.smartPathSplit("http://domain/jar1.jar:https://domain/jar2.jar", ':', null))
                .containsExactly("http://domain/jar1.jar", "https://domain/jar2.jar");
    }

    /** A scheme that is only a URL scheme because the user registered it is not split at either. */
    @Test
    public void registeredURLSchemesAreNotSplitAtTheirScheme() {
        assertThat(JarUtils.smartPathSplit("s3://bucket/jar1.jar:/tmp/jar2.jar", ':', Set.of()))
                .containsExactly("s3", "//bucket/jar1.jar", "/tmp/jar2.jar");
        assertThat(JarUtils.smartPathSplit("s3://bucket/jar1.jar:/tmp/jar2.jar", ':', Set.of("s3")))
                .containsExactly("s3://bucket/jar1.jar", "/tmp/jar2.jar");
    }

    /**
     * A path element is trimmed, so whitespace between a separator and the URL scheme that follows it does not stop
     * the scheme from being recognized -- otherwise the path would be split at the scheme's own colon, turning one
     * URL into two path elements that name nothing.
     */
    @Test
    public void whitespaceBeforeAURLSchemeDoesNotHideIt() {
        assertThat(JarUtils.smartPathSplit("/a/jar1.jar: http://domain/jar2.jar", ':', null))
                .containsExactly("/a/jar1.jar", "http://domain/jar2.jar");
        assertThat(JarUtils.smartPathSplit(" http://domain/jar1.jar:/a/jar2.jar", ':', null))
                .containsExactly("http://domain/jar1.jar", "/a/jar2.jar");
        assertThat(JarUtils.smartPathSplit("/a/jar1.jar:  jar:file:/a/jar2.jar!/", ':', null))
                .containsExactly("/a/jar1.jar", "jar:file:/a/jar2.jar!/");
        assertThat(JarUtils.smartPathSplit("/a/jar1.jar: s3://bucket/jar2.jar", ':', Set.of("s3")))
                .containsExactly("/a/jar1.jar", "s3://bucket/jar2.jar");

        // Whitespace in the middle of a path element still does not make what follows it a scheme
        assertThat(JarUtils.smartPathSplit("/a dir/http:x", ':', null)).containsExactly("/a dir/http", "x");
    }

    /**
     * A separator that is a regular expression metacharacter splits the path on the character itself, rather than
     * on whatever the character means as a regular expression.
     */
    @Test
    public void aSeparatorThatIsARegexMetacharacterIsMatchedLiterally() {
        assertThat(JarUtils.smartPathSplit("/a/jar1.jar|/a/jar2.jar", '|', null)).containsExactly("/a/jar1.jar",
                "/a/jar2.jar");
        assertThat(JarUtils.smartPathSplit("a.b.c", '.', null)).containsExactly("a", "b", "c");
    }

    /** Where the path separator is not ':', the path is split on it with no URL scheme handling. */
    @Test
    public void nonColonSeparatorsSplitTheWholePath() {
        assertThat(JarUtils.smartPathSplit("C:\\a.jar;C:\\b.jar", ';', null)).containsExactly("C:\\a.jar",
                "C:\\b.jar");
        // Empty and whitespace-only path elements are dropped, and the rest are trimmed
        assertThat(JarUtils.smartPathSplit("a.jar; ;; b.jar ;", ';', null)).containsExactly("a.jar", "b.jar");
        assertThat(JarUtils.smartPathSplit("a.jar: :: b.jar :", ':', null)).containsExactly("a.jar", "b.jar");
    }

    /** A null or empty path splits into no path elements at all, rather than one empty element. */
    @Test
    public void nullAndEmptyPathsSplitIntoNothing() {
        assertThat(JarUtils.smartPathSplit(null, ':', null)).isEmpty();
        assertThat(JarUtils.smartPathSplit("", ':', null)).isEmpty();
        assertThat(JarUtils.smartPathSplit(":::", ':', null)).isEmpty();
    }

    /**
     * A '!' is only a nested jar separator if the path before it names a file that exists, since '!' is also a
     * legal character in a file or directory name.
     */
    // #903
    @Test
    public void nestedJarSeparatorsAreFoundByTestingTheFilesystem(@TempDir final Path tempDir) throws IOException {
        final var outerJar = Files.write(tempDir.resolve("outer.jar"), new byte[] { 'P', 'K' });
        final var outerJarPath = outerJar.toString().replace(File.separatorChar, '/');

        assertThat(JarUtils.indexOfNestedJarSeparator(outerJarPath)).isEqualTo(-1);
        assertThat(JarUtils.indexOfNestedJarSeparator(outerJarPath + "!/BOOT-INF/classes"))
                .isEqualTo(outerJarPath.length());
        // Every '!' after the outermost separator is a separator too, so the innermost one is the last '!'
        final var twoDeep = outerJarPath + "!/BOOT-INF/lib/inner.jar!/pkg";
        assertThat(JarUtils.indexOfNestedJarSeparator(twoDeep)).isEqualTo(outerJarPath.length());
        assertThat(JarUtils.lastIndexOfNestedJarSeparator(twoDeep)).isEqualTo(twoDeep.lastIndexOf('!'));

        // A '!' in a directory name is not a separator, since the path before it is a directory, not a file
        final var dirWithPling = Files.createDirectory(tempDir.resolve("dir!"));
        final var jarInDir = Files.write(dirWithPling.resolve("x.jar"), new byte[] { 'P', 'K' });
        final var jarInDirPath = jarInDir.toString().replace(File.separatorChar, '/');
        assertThat(JarUtils.indexOfNestedJarSeparator(jarInDirPath)).isEqualTo(-1);
        assertThat(JarUtils.lastIndexOfNestedJarSeparator(jarInDirPath)).isEqualTo(-1);
        // ... but a '!' after that directory's jar is
        assertThat(JarUtils.indexOfNestedJarSeparator(jarInDirPath + "!/pkg")).isEqualTo(jarInDirPath.length());
    }

    /**
     * The filesystem cannot be consulted for a remote URL, so the first '!' in one is taken to be the separator.
     */
    @Test
    public void theFirstPlingInARemoteURLIsTheSeparator() {
        assertThat(JarUtils.indexOfNestedJarSeparator("http://example.com/dir!/x.jar!/pkg"))
                .isEqualTo("http://example.com/dir".length());
        assertThat(JarUtils.indexOfNestedJarSeparator("http://example.com/x.jar")).isEqualTo(-1);
    }

    /** The leafname is everything after the last path separator, and before the first '!'. */
    @Test
    public void leafNameStripsDirectoriesAndNestedJarPaths() {
        assertThat(JarUtils.leafName("/a/b/c.jar")).isEqualTo("c.jar");
        assertThat(JarUtils.leafName("/a/b/c.jar!/d/e.class")).isEqualTo("c.jar");
        assertThat(JarUtils.leafName("c.jar")).isEqualTo("c.jar");
        assertThat(JarUtils.leafName("")).isEmpty();
        // A jar extracted from within another jar is written to a temp file whose name carries both a unique prefix
        // and the original leafname, and only the original leafname is wanted here
        assertThat(
                JarUtils.leafName("/tmp/ClassGraph--12345" + FileUtils.TEMP_FILENAME_LEAF_SEPARATOR + "inner.jar"))
                .isEqualTo("inner.jar");
    }

    /** Classfile paths and class names convert to each other. */
    @Test
    public void classfilePathsAndClassNamesConvertToEachOther() {
        assertThat(JarUtils.classfilePathToClassName("java/lang/String.class")).isEqualTo("java.lang.String");
        assertThat(JarUtils.classfilePathToClassName("X.class")).isEqualTo("X");
        assertThat(JarUtils.classNameToClassfilePath("java.lang.String")).isEqualTo("java/lang/String.class");
        assertThatThrownBy(() -> JarUtils.classfilePathToClassName("java/lang/String"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Classfile path does not end with \".class\": java/lang/String");
    }

    /**
     * The automatic module name is derived from the jar's leafname, following the algorithm documented by
     * {@link java.lang.module.ModuleFinder#of(java.nio.file.Path...)}: the extension and any version suffix are
     * dropped, and the remaining non-alphanumeric characters become dots.
     */
    @Test
    public void automaticModuleNamesAreDerivedFromTheJarName() {
        assertThat(JarUtils.derivedAutomaticModuleName("/a/b/foo.jar")).isEqualTo("foo");
        assertThat(JarUtils.derivedAutomaticModuleName("foo.jar")).isEqualTo("foo");
        assertThat(JarUtils.derivedAutomaticModuleName("/a/b/commons-lang3-3.12.0.jar")).isEqualTo("commons.lang3");
        assertThat(JarUtils.derivedAutomaticModuleName("/a/b/my_lib.jar")).isEqualTo("my.lib");
        // Leading, trailing and repeated dots are all collapsed away
        assertThat(JarUtils.derivedAutomaticModuleName("/a/b/-foo--bar-.jar")).isEqualTo("foo.bar");
        // A jar nested inside another jar is named after the inner jar
        assertThat(JarUtils.derivedAutomaticModuleName("/a/outer.jar!/BOOT-INF/lib/inner-1.0.jar"))
                .isEqualTo("inner");
        // A package root within a jar is named after the jar that contains it, not after the package root
        assertThat(JarUtils.derivedAutomaticModuleName("/a/outer.jar!/BOOT-INF/classes")).isEqualTo("outer");
    }
}
