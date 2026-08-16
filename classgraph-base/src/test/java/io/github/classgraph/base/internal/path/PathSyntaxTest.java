package io.github.classgraph.base.internal.path;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
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
     * The filesystem cannot be consulted for a remote URL, so the first '!' in one is taken to be the separator.
     */
    @Test
    public void theFirstPlingInARemoteURLIsTheSeparator() {
        assertThat(PathSyntax.indexOfNestedJarSeparator("http://example.com/dir!/x.jar!/pkg"))
                .isEqualTo("http://example.com/dir".length());
        assertThat(PathSyntax.indexOfNestedJarSeparator("http://example.com/x.jar")).isEqualTo(-1);
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
