package io.github.classgraph.base.internal.path;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/** Tests for {@link PathSyntax}. */
public class PathSyntaxTest {
    /**
     * A '!' is only a nested jar separator if the path before it names a file that exists, since '!' is also a
     * legal character in a file or directory name.
     */
    // #903
    @Test
    public void nestedJarSeparatorsAreFoundByTestingTheFilesystem(@TempDir final Path tempDir) throws IOException {
        final var outerJar = Files.write(tempDir.resolve("outer.jar"), new byte[] { 'P', 'K' });
        final var outerJarPath = outerJar.toString().replace(File.separatorChar, '/');

        assertThat(PathSyntax.indexOfNestedJarSeparator(outerJarPath)).isEqualTo(-1);
        assertThat(PathSyntax.indexOfNestedJarSeparator(outerJarPath + "!/BOOT-INF/classes"))
                .isEqualTo(outerJarPath.length());
        // Every '!' after the outermost separator is a separator too, so the innermost one is the last '!'
        final var twoDeep = outerJarPath + "!/BOOT-INF/lib/inner.jar!/pkg";
        assertThat(PathSyntax.indexOfNestedJarSeparator(twoDeep)).isEqualTo(outerJarPath.length());
        assertThat(PathSyntax.lastIndexOfNestedJarSeparator(twoDeep)).isEqualTo(twoDeep.lastIndexOf('!'));

        // A '!' in a directory name is not a separator, since the path before it is a directory, not a file
        final var dirWithPling = Files.createDirectory(tempDir.resolve("dir!"));
        final var jarInDir = Files.write(dirWithPling.resolve("x.jar"), new byte[] { 'P', 'K' });
        final var jarInDirPath = jarInDir.toString().replace(File.separatorChar, '/');
        assertThat(PathSyntax.indexOfNestedJarSeparator(jarInDirPath)).isEqualTo(-1);
        assertThat(PathSyntax.lastIndexOfNestedJarSeparator(jarInDirPath)).isEqualTo(-1);
        // ... but a '!' after that directory's jar is
        assertThat(PathSyntax.indexOfNestedJarSeparator(jarInDirPath + "!/pkg")).isEqualTo(jarInDirPath.length());
    }

    /**
     * The {@code "jar:"} URL scheme spells the separator {@code "!/"}, so a '!' with anything else after it is a
     * filename character, even inside a jarfile's entry names.
     */
    // #903
    @Test
    public void aPlingNotFollowedBySlashIsNotASeparator(@TempDir final Path tempDir) throws IOException {
        final var outerJar = Files.write(tempDir.resolve("outer.jar"), new byte[] { 'P', 'K' });
        final var outerJarPath = outerJar.toString().replace(File.separatorChar, '/');

        // "dir!name/x.txt" is one entry name within outer.jar, so the jarfile's own '!' is the only separator
        final var entryInPlingDir = outerJarPath + "!/dir!name/x.txt";
        assertThat(PathSyntax.indexOfNestedJarSeparator(entryInPlingDir)).isEqualTo(outerJarPath.length());
        assertThat(PathSyntax.lastIndexOfNestedJarSeparator(entryInPlingDir)).isEqualTo(outerJarPath.length());
        // ... so sanitizing such a path must not insert a '/' after that '!', which would name a different entry
        assertThat(PathSyntax.sanitizeEntryPath(outerJarPath + "!/dir!name/./x.txt", false, false))
                .isEqualTo(entryInPlingDir);

        // The innermost separator is the last '!' that really is one, not simply the last '!' in the path
        final var twoDeep = outerJarPath + "!/lib/inner.jar!/dir!name";
        assertThat(PathSyntax.lastIndexOfNestedJarSeparator(twoDeep))
                .isEqualTo(twoDeep.indexOf("inner.jar") + "inner.jar".length());

        // A '!' at the end of a path is a separator: it is what a trailing "!/" is left as once
        // FastPathResolver#stripTrailingSeparators has removed the '/'
        assertThat(PathSyntax.indexOfNestedJarSeparator(outerJarPath + "!")).isEqualTo(outerJarPath.length());
        assertThat(PathSyntax.lastIndexOfNestedJarSeparator(outerJarPath + "!")).isEqualTo(outerJarPath.length());
    }

    /**
     * ClassGraph accepts the looser form its own API has always taken as well, where a bare '!' separates -- and
     * then every '!' from the outermost one onwards is a separator.
     */
    // #903
    @Test
    public void aBareOutermostPlingMakesEveryLaterPlingASeparator(@TempDir final Path tempDir) throws IOException {
        final var outerJar = Files.write(tempDir.resolve("outer.jar"), new byte[] { 'P', 'K' });
        final var outerJarPath = outerJar.toString().replace(File.separatorChar, '/');

        final var loose = outerJarPath + "!level2.jar!level3.jar!pkg";
        assertThat(PathSyntax.indexOfNestedJarSeparator(loose)).isEqualTo(outerJarPath.length());
        assertThat(PathSyntax.lastIndexOfNestedJarSeparator(loose)).isEqualTo(loose.lastIndexOf('!'));
        // Such a path is rewritten into the form that the "jar:" URL scheme requires
        assertThat(PathSyntax.toJarUrlSeparators(loose)).isEqualTo(outerJarPath + "!/level2.jar!/level3.jar!/pkg");

        // A path already in that form keeps every '!' that belongs to an entry name
        assertThat(PathSyntax.toJarUrlSeparators(outerJarPath + "!/dir!name/x.txt"))
                .isEqualTo(outerJarPath + "!/dir!name/x.txt");
        // ... and a trailing separator gets back the '/' that FastPathResolver stripped
        assertThat(PathSyntax.toJarUrlSeparators(outerJarPath + "!")).isEqualTo(outerJarPath + "!/");
    }

    /**
     * The filesystem cannot be consulted for a remote URL, so the first '!' in one is taken to be the separator.
     */
    @Test
    public void theFirstPlingInARemoteURLIsTheSeparator() {
        assertThat(PathSyntax.indexOfNestedJarSeparator("http://example.com/dir!/x.jar!/pkg"))
                .isEqualTo("http://example.com/dir".length());
        assertThat(PathSyntax.indexOfNestedJarSeparator("http://example.com/x.jar")).isEqualTo(-1);
    }

    /**
     * A relative path may itself begin with something shaped like a URL scheme, since ':' is a legal filename
     * character on every platform but Windows, and a relative path need not begin with a separator. Such a path
     * names a file or directory, not a URL, and the only way to tell the two apart is to test the filesystem.
     */
    @Test
    @DisabledOnOs(value = OS.WINDOWS, disabledReason = "':' is not a legal character in a Windows filename")
    public void aRelativePathThatNamesSomethingIsNotAURL() throws IOException {
        // The ':' has to fall in the first segment of a relative path for the path to look like a URL, since a
        // scheme may not contain a '/'. An absolute path always begins with a separator, so it is never ambiguous.
        // That leaves the working directory, which is what a relative path is resolved against, as the only place
        // this directory can go.
        final var colonDir = Path.of("cgtest:relpath");
        final var jarInColonDir = colonDir.resolve("x.jar");
        final var plingDir = colonDir.resolve("dir!name");
        final var jarInPlingDir = plingDir.resolve("y.jar");
        try {
            Files.createDirectory(colonDir);
            Files.write(jarInColonDir, new byte[] { 'P', 'K' });
            Files.createDirectory(plingDir);
            Files.write(jarInPlingDir, new byte[] { 'P', 'K' });

            // Everything that exists is a path, however much it looks like a URL
            assertThat(PathSyntax.hasURLScheme("cgtest:relpath")).isFalse();
            assertThat(PathSyntax.hasURLScheme("cgtest:relpath/x.jar")).isFalse();
            // The scheme could only apply to the outermost element, so that is the part that is tested
            assertThat(PathSyntax.hasURLScheme("cgtest:relpath/x.jar!/pkg")).isFalse();

            // A '!' in a directory name is still not a separator when the path also looks like a URL
            assertThat(PathSyntax.indexOfNestedJarSeparator("cgtest:relpath/dir!name/y.jar")).isEqualTo(-1);
            assertThat(PathSyntax.lastIndexOfNestedJarSeparator("cgtest:relpath/dir!name/y.jar")).isEqualTo(-1);
            // ... but a '!' after that directory's jar is
            assertThat(PathSyntax.indexOfNestedJarSeparator("cgtest:relpath/dir!name/y.jar!/pkg"))
                    .isEqualTo("cgtest:relpath/dir!name/y.jar".length());

        } finally {
            // Delete leaves before the directories that hold them
            for (final var path : new Path[] { jarInPlingDir, plingDir, jarInColonDir, colonDir }) {
                Files.deleteIfExists(path);
            }
        }
    }

    /** Nothing in the filesystem answers to a URL, so a URL is read as one. */
    @Test
    public void aPathThatNamesNothingLocalIsReadAsAURL() {
        assertThat(PathSyntax.hasURLScheme("http://example.com/x.jar")).isTrue();
        assertThat(PathSyntax.hasURLScheme("s3://bucket/x.jar")).isTrue();
        // A relative path that names nothing cannot be told from a URL -- but it fails to open either way
        assertThat(PathSyntax.hasURLScheme("cgtest:nothing-is-here")).isTrue();
        // Nothing shaped like a scheme, so the filesystem is never consulted
        assertThat(PathSyntax.hasURLScheme("/dir/x.jar")).isFalse();
        assertThat(PathSyntax.hasURLScheme("dir/x.jar")).isFalse();
        // A Windows drive designation is a single character, and a scheme is at least two
        assertThat(PathSyntax.hasURLScheme("C:/dir/x.jar")).isFalse();
    }

    /** The leafname is everything after the last path separator, and before the nested jar separator. */
    @Test
    public void leafNameStripsDirectoriesAndNestedJarPaths(@TempDir final Path tempDir) throws IOException {
        assertThat(PathSyntax.leafName("/a/b/c.jar")).isEqualTo("c.jar");
        assertThat(PathSyntax.leafName("c.jar")).isEqualTo("c.jar");
        assertThat(PathSyntax.leafName("")).isEmpty();
        // A path within a jarfile has the leafname of the jarfile itself. The jar has to exist on disk, since a '!'
        // only counts as a separator if the path before it names a file
        final var outerJarPath = Files.write(tempDir.resolve("c.jar"), new byte[] { 'P', 'K' }).toString()
                .replace(File.separatorChar, '/');
        assertThat(PathSyntax.leafName(outerJarPath + "!/d/e.class")).isEqualTo("c.jar");
        // A jar extracted from within another jar is written to a temp file whose name carries both a unique prefix
        // and the original leafname, and only the original leafname is wanted here
        assertThat(PathSyntax
                .leafName("/tmp/ClassGraph--12345" + PathSyntax.TEMP_FILENAME_LEAF_SEPARATOR + "inner.jar"))
                .isEqualTo("inner.jar");
    }

    /**
     * The temp filename separator is only meaningful within the leafname itself. A "---" in a path nested within a
     * jarfile is not the separator that a temp filename carries, so it must not truncate the leafname of the jar.
     * Searching the whole path for it made the leafname empty in that case, so the jar matched no accept or reject
     * criterion and was silently skipped.
     */
    @Test
    public void aTempSeparatorAfterTheNestedJarSeparatorDoesNotEmptyTheLeafName(@TempDir final Path tempDir)
            throws IOException {
        final var outerJarPath = Files.write(tempDir.resolve("c.jar"), new byte[] { 'P', 'K' }).toString()
                .replace(File.separatorChar, '/');
        assertThat(PathSyntax
                .leafName(outerJarPath + "!/BOOT-INF/lib/a" + PathSyntax.TEMP_FILENAME_LEAF_SEPARATOR + "b.jar"))
                .isEqualTo("c.jar");
        // The same holds for a "---" in a directory name that the leafname is not part of
        final var dirWithTempSep = Files
                .createDirectory(tempDir.resolve("d" + PathSyntax.TEMP_FILENAME_LEAF_SEPARATOR + "e"));
        final var jarPath = Files.write(dirWithTempSep.resolve("f.jar"), new byte[] { 'P', 'K' }).toString()
                .replace(File.separatorChar, '/');
        assertThat(PathSyntax.leafName(jarPath)).isEqualTo("f.jar");
    }

    /**
     * A '!' in a directory name is an ordinary filename character, not a nested jar separator, so it does not end
     * the leafname. Ending the leafname at the first '!' regardless made the leafname of a jar below such a
     * directory the directory's name, so the jar matched no accept or reject criterion and was silently skipped.
     */
    // #903
    @Test
    public void aPlingInADirectoryNameDoesNotEndTheLeafName(@TempDir final Path tempDir) throws IOException {
        final var dirWithPling = Files.createDirectory(tempDir.resolve("dir!name"));
        final var jarPath = Files.write(dirWithPling.resolve("x.jar"), new byte[] { 'P', 'K' }).toString()
                .replace(File.separatorChar, '/');
        assertThat(PathSyntax.leafName(jarPath)).isEqualTo("x.jar");
        // The same holds for a '!' in the jar's own name
        final var jarWithPlingPath = Files.write(tempDir.resolve("y!z.jar"), new byte[] { 'P', 'K' }).toString()
                .replace(File.separatorChar, '/');
        assertThat(PathSyntax.leafName(jarWithPlingPath)).isEqualTo("y!z.jar");
    }

    /** The parent directory of a path is everything up to its last separator. */
    @Test
    public void theParentDirectoryOfAPathIsEverythingUpToItsLastSeparator() {
        assertThat(PathSyntax.getParentDirPath("com/xyz/Widget.class")).isEqualTo("com/xyz");
        assertThat(PathSyntax.getParentDirPath("com/Widget.class")).isEqualTo("com");

        // A path with no separator, or with a separator only at the start, has no parent directory
        assertThat(PathSyntax.getParentDirPath("Widget.class")).isEmpty();
        assertThat(PathSyntax.getParentDirPath("/Widget.class")).isEmpty();
        assertThat(PathSyntax.getParentDirPath("")).isEmpty();

        // A trailing separator makes the whole path the parent directory of the empty name that follows it
        assertThat(PathSyntax.getParentDirPath("com/xyz/")).isEqualTo("com/xyz");

        // Any separator can be used, for paths that were not read from a zipfile
        assertThat(PathSyntax.getParentDirPath("C:\\dir\\Widget.class", '\\')).isEqualTo("C:\\dir");
        assertThat(PathSyntax.getParentDirPath("com/xyz/Widget.class", '\\')).isEmpty();
    }
}
