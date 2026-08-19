package nonapi.io.github.classgraph.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import nonapi.io.github.classgraph.fastzipfilereader.NestedJarHandler;
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
     * A path element written as a URL in a container's own URL protocol is one path element, not two: Tomcat serves
     * a non-exploded WAR through a {@code "war:"} URL, and Spring Boot addresses an entry within an executable jar
     * through a {@code "nested:"} URL, and both of those schemes sit inside a {@code "jar:"} URL.
     */
    @Test
    public void containerURLSchemesAreNotSplitAtTheirScheme() {
        assertThat(JarUtils.smartPathSplit("war:file:/a/app.war*/WEB-INF/classes/:/tmp/jar2.jar", ':', null))
                .containsExactly("war:file:/a/app.war*/WEB-INF/classes/", "/tmp/jar2.jar");
        assertThat(JarUtils.smartPathSplit("jar:nested:/a/app.jar/!BOOT-INF/classes/!/:/tmp/jar2.jar", ':', null))
                .containsExactly("jar:nested:/a/app.jar/!BOOT-INF/classes/!/", "/tmp/jar2.jar");
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

    /** The leafname is everything after the last path separator, and before the nested jar separator. */
    @Test
    public void leafNameStripsDirectoriesAndNestedJarPaths(@TempDir final Path tempDir) throws IOException {
        assertThat(JarUtils.leafName("/a/b/c.jar")).isEqualTo("c.jar");
        assertThat(JarUtils.leafName("c.jar")).isEqualTo("c.jar");
        assertThat(JarUtils.leafName("")).isEmpty();
        // A path within a jarfile has the leafname of the jarfile itself. The jar has to exist on disk, since a '!'
        // only counts as a separator if the path before it names a file
        final String outerJarPath = Files.write(tempDir.resolve("c.jar"), new byte[] { 'P', 'K' }).toString()
                .replace(File.separatorChar, '/');
        assertThat(JarUtils.leafName(outerJarPath + "!/d/e.class")).isEqualTo("c.jar");
        // A jar extracted from within another jar is written to a temp file whose name carries both a unique prefix
        // and the original leafname, and only the original leafname is wanted here
        assertThat(JarUtils
                .leafName("/tmp/ClassGraph--12345" + NestedJarHandler.TEMP_FILENAME_LEAF_SEPARATOR + "inner.jar"))
                        .isEqualTo("inner.jar");
    }

    /**
     * A '!' in a directory name is an ordinary filename character, not a nested jar separator, so it does not end
     * the leafname. Ending the leafname at the first '!' regardless made the leafname of a jar below such a
     * directory the directory's name, so the jar matched no accept or reject criterion and was silently skipped.
     */
    // #903
    @Test
    public void aPlingInADirectoryNameDoesNotEndTheLeafName(@TempDir final Path tempDir) throws IOException {
        final Path dirWithPling = Files.createDirectory(tempDir.resolve("dir!name"));
        final String jarPath = Files.write(dirWithPling.resolve("x.jar"), new byte[] { 'P', 'K' }).toString()
                .replace(File.separatorChar, '/');
        assertThat(JarUtils.leafName(jarPath)).isEqualTo("x.jar");
        // The same holds for a '!' in the jar's own name
        final String jarWithPlingPath = Files.write(tempDir.resolve("y!z.jar"), new byte[] { 'P', 'K' }).toString()
                .replace(File.separatorChar, '/');
        assertThat(JarUtils.leafName(jarWithPlingPath)).isEqualTo("y!z.jar");
    }

    /**
     * The {@code "jar:"} URL scheme spells the separator {@code "!/"}, so a '!' with anything else after it is a
     * filename character, even inside a jarfile's entry names.
     *
     * @param tempDir
     *            a temporary directory to write the jarfile into.
     * @throws IOException
     *             if the jarfile could not be written.
     */
    // #903
    @Test
    public void aPlingNotFollowedBySlashIsNotASeparator(@TempDir final Path tempDir) throws IOException {
        final String outerJarPath = Files.write(tempDir.resolve("outer.jar"), new byte[] { 'P', 'K' }).toString()
                .replace(File.separatorChar, '/');

        // "dir!name/x.txt" is one entry name within outer.jar, so the jarfile's own '!' is the only separator
        final String entryInPlingDir = outerJarPath + "!/dir!name/x.txt";
        assertThat(JarUtils.indexOfNestedJarSeparator(entryInPlingDir)).isEqualTo(outerJarPath.length());
        assertThat(JarUtils.lastIndexOfNestedJarSeparator(entryInPlingDir)).isEqualTo(outerJarPath.length());

        // The innermost separator is the last '!' that really is one, not simply the last '!' in the path
        final String twoDeep = outerJarPath + "!/lib/inner.jar!/dir!name";
        assertThat(JarUtils.lastIndexOfNestedJarSeparator(twoDeep))
                .isEqualTo(twoDeep.indexOf("inner.jar") + "inner.jar".length());

        // A '!' at the end of a path is a separator: it is what a trailing "!/" is left as once
        // FastPathResolver has removed the '/'
        assertThat(JarUtils.indexOfNestedJarSeparator(outerJarPath + "!")).isEqualTo(outerJarPath.length());
        assertThat(JarUtils.lastIndexOfNestedJarSeparator(outerJarPath + "!")).isEqualTo(outerJarPath.length());
    }

    /**
     * ClassGraph accepts the looser form its own API has always taken as well, where a bare '!' separates -- and
     * then every '!' from the outermost one onwards is a separator.
     *
     * @param tempDir
     *            a temporary directory to write the jarfile into.
     * @throws IOException
     *             if the jarfile could not be written.
     */
    // #903
    @Test
    public void aBareOutermostPlingMakesEveryLaterPlingASeparator(@TempDir final Path tempDir) throws IOException {
        final String outerJarPath = Files.write(tempDir.resolve("outer.jar"), new byte[] { 'P', 'K' }).toString()
                .replace(File.separatorChar, '/');

        final String loose = outerJarPath + "!level2.jar!level3.jar!pkg";
        assertThat(JarUtils.indexOfNestedJarSeparator(loose)).isEqualTo(outerJarPath.length());
        assertThat(JarUtils.lastIndexOfNestedJarSeparator(loose)).isEqualTo(loose.lastIndexOf('!'));
        // Such a path is rewritten into the form that the "jar:" URL scheme requires
        assertThat(JarUtils.toJarUrlSeparators(loose)).isEqualTo(outerJarPath + "!/level2.jar!/level3.jar!/pkg");

        // A path already in that form keeps every '!' that belongs to an entry name
        assertThat(JarUtils.toJarUrlSeparators(outerJarPath + "!/dir!name/x.txt"))
                .isEqualTo(outerJarPath + "!/dir!name/x.txt");
        // ... and a trailing separator gets back the '/' that FastPathResolver stripped
        assertThat(JarUtils.toJarUrlSeparators(outerJarPath + "!")).isEqualTo(outerJarPath + "!/");
    }

    /**
     * A relative path may itself begin with something shaped like a URL scheme, since ':' is a legal filename
     * character on every platform but Windows, and a relative path need not begin with a separator. Such a path
     * names a file or directory, not a URL, and the only way to tell the two apart is to test the filesystem.
     *
     * @throws IOException
     *             if the directories or jarfiles could not be written.
     */
    @Test
    @DisabledOnOs(value = OS.WINDOWS, disabledReason = "':' is not a legal character in a Windows filename")
    public void aRelativePathThatNamesSomethingIsNotAURL() throws IOException {
        // The ':' has to fall in the first segment of a relative path for the path to look like a URL, since a
        // scheme may not contain a '/'. An absolute path always begins with a separator, so it is never ambiguous.
        // That leaves the working directory, which is what a relative path is resolved against, as the only place
        // this directory can go.
        final Path colonDir = Paths.get("cgtest:relpath");
        final Path jarInColonDir = colonDir.resolve("x.jar");
        final Path plingDir = colonDir.resolve("dir!name");
        final Path jarInPlingDir = plingDir.resolve("y.jar");
        try {
            Files.createDirectory(colonDir);
            Files.write(jarInColonDir, new byte[] { 'P', 'K' });
            Files.createDirectory(plingDir);
            Files.write(jarInPlingDir, new byte[] { 'P', 'K' });

            // Everything that exists is a path, however much it looks like a URL
            assertThat(JarUtils.hasURLScheme("cgtest:relpath")).isFalse();
            assertThat(JarUtils.hasURLScheme("cgtest:relpath/x.jar")).isFalse();
            // The scheme could only apply to the outermost element, so that is the part that is tested
            assertThat(JarUtils.hasURLScheme("cgtest:relpath/x.jar!/pkg")).isFalse();

            // A '!' in a directory name is still not a separator when the path also looks like a URL
            assertThat(JarUtils.indexOfNestedJarSeparator("cgtest:relpath/dir!name/y.jar")).isEqualTo(-1);
            assertThat(JarUtils.lastIndexOfNestedJarSeparator("cgtest:relpath/dir!name/y.jar")).isEqualTo(-1);
            // ... but a '!' after that directory's jar is
            assertThat(JarUtils.indexOfNestedJarSeparator("cgtest:relpath/dir!name/y.jar!/pkg"))
                    .isEqualTo("cgtest:relpath/dir!name/y.jar".length());

        } finally {
            // Delete leaves before the directories that hold them
            for (final Path path : new Path[] { jarInPlingDir, plingDir, jarInColonDir, colonDir }) {
                Files.deleteIfExists(path);
            }
        }
    }

    /** Nothing in the filesystem answers to a URL, so a URL is read as one. */
    @Test
    public void aPathThatNamesNothingLocalIsReadAsAURL() {
        assertThat(JarUtils.hasURLScheme("http://example.com/x.jar")).isTrue();
        assertThat(JarUtils.hasURLScheme("s3://bucket/x.jar")).isTrue();
        // A relative path that names nothing cannot be told from a URL -- but it fails to open either way
        assertThat(JarUtils.hasURLScheme("cgtest:nothing-is-here")).isTrue();
        // Nothing shaped like a scheme, so the filesystem is never consulted
        assertThat(JarUtils.hasURLScheme("/dir/x.jar")).isFalse();
        assertThat(JarUtils.hasURLScheme("dir/x.jar")).isFalse();
        // A Windows drive designation is a single character, and a scheme is at least two
        assertThat(JarUtils.hasURLScheme("C:/dir/x.jar")).isFalse();
    }

    /** Classfile paths and class names convert to each other. */
    @Test
    public void classfilePathsAndClassNamesConvertToEachOther() {
        assertThat(JarUtils.classfilePathToClassName("java/lang/String.class")).isEqualTo("java.lang.String");
        assertThat(JarUtils.classfilePathToClassName("X.class")).isEqualTo("X");
        assertThat(JarUtils.classNameToClassfilePath("java.lang.String")).isEqualTo("java/lang/String.class");

        // A classfile that has been through a filesystem that upper-cases filenames still names its class
        assertThat(JarUtils.classfilePathToClassName("java/lang/String.CLASS")).isEqualTo("java.lang.String");

        assertThatThrownBy(() -> JarUtils.classfilePathToClassName("java/lang/String"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Not the path of a classfile: java/lang/String");
        assertThatThrownBy(() -> JarUtils.classfilePathToClassName("java/lang/.class"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Not the path of a classfile: java/lang/.class");
    }

    /**
     * The automatic module name is derived from the jar's leafname: the extension and any version suffix are
     * dropped, and the remaining non-alphanumeric characters become dots.
     */
    @Test
    public void automaticModuleNamesAreDerivedFromTheJarName() {
        assertThat(JarUtils.derivedAutomaticModuleName("/a/b/foo.jar")).isEqualTo("foo");
        assertThat(JarUtils.derivedAutomaticModuleName("foo.jar")).isEqualTo("foo");
        assertThat(JarUtils.derivedAutomaticModuleName("/a/b/commons-lang3-3.12.0.jar"))
                .isEqualTo("commons.lang3");
        assertThat(JarUtils.derivedAutomaticModuleName("/a/b/my_lib.jar")).isEqualTo("my.lib");
        // Leading, trailing and repeated dots are all collapsed away
        assertThat(JarUtils.derivedAutomaticModuleName("/a/b/-foo--bar-.jar")).isEqualTo("foo.bar");
        // A jar nested inside another jar is named after the inner jar
        assertThat(JarUtils.derivedAutomaticModuleName("/a/outer.jar!/BOOT-INF/lib/inner-1.0.jar"))
                .isEqualTo("inner");
        // A package root within a jar is named after the jar that contains it, not after the package root
        assertThat(JarUtils.derivedAutomaticModuleName("/a/outer.jar!/BOOT-INF/classes")).isEqualTo("outer");
        // A '!' that is not followed by '/' is part of a name, not a separator, so it ends neither
        // #903
        assertThat(JarUtils.derivedAutomaticModuleName("/a/outer.jar!/dir!name/classes")).isEqualTo("outer");
        assertThat(JarUtils.derivedAutomaticModuleName("/a/outer.jar!/lib/we!rd-1.0.jar")).isEqualTo("we.rd");
    }
}
