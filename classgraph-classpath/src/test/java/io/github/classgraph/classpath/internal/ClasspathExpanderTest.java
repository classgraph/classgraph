package io.github.classgraph.classpath.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for {@link ClasspathExpander#libJarsInDir(Path)}.
 */
public class ClasspathExpanderTest {
    /**
     * Create an empty file, and any directories leading to it.
     *
     * @param dir
     *            the directory the path is relative to
     * @param relativePath
     *            the path of the file, relative to {@code dir}
     * @throws IOException
     *             if the file could not be created
     */
    private static void createFile(final Path dir, final String relativePath) throws IOException {
        final Path path = dir.resolve(relativePath);
        Files.createDirectories(path.getParent());
        Files.createFile(path);
    }

    /**
     * The filenames of a list of paths.
     *
     * @param paths
     *            the paths
     * @return the filename of each path, in the order the paths were listed in
     */
    private static List<String> fileNames(final List<Path> paths) {
        final List<String> fileNames = new ArrayList<>();
        for (final Path path : paths) {
            fileNames.add(path.getFileName().toString());
        }
        return fileNames;
    }

    /**
     * A directory lists its entries in whatever order the filesystem stores them, which differs between filesystems
     * and platforms, and changes as files are added and removed. The jars of a lib dir have to be put into a fixed
     * order, otherwise the same directory produces a different classpath order on different machines, and which of
     * two jars containing the same class masks the other varies from run to run.
     */
    @Test
    public void libJarsAreSortedWithinALibDir(@TempDir final Path tempDir) throws IOException {
        for (final String name : new String[] { "zebra.jar", "alpha.jar", "mango.jar", "01first.jar", "beta.jar",
                "yankee.jar", "delta.jar" }) {
            createFile(tempDir, "lib/" + name);
        }
        assertThat(fileNames(ClasspathExpander.libJarsInDir(tempDir))).containsExactly("01first.jar", "alpha.jar",
                "beta.jar", "delta.jar", "mango.jar", "yankee.jar", "zebra.jar");
    }

    /**
     * Each lib dir is sorted on its own, so that the order of {@code AUTOMATIC_LIB_DIR_PREFIXES} still decides
     * which lib dir's jars come first -- sorting the whole result together would interleave the lib dirs, losing
     * that precedence.
     */
    @Test
    public void libDirPrecedenceSurvivesSorting(@TempDir final Path tempDir) throws IOException {
        // "BOOT-INF/lib/" comes before "WEB-INF/lib/", which comes before "lib/", whatever the jars are named
        createFile(tempDir, "BOOT-INF/lib/zzz.jar");
        createFile(tempDir, "BOOT-INF/lib/mmm.jar");
        createFile(tempDir, "WEB-INF/lib/nnn.jar");
        createFile(tempDir, "lib/aaa.jar");
        createFile(tempDir, "lib/bbb.jar");
        assertThat(fileNames(ClasspathExpander.libJarsInDir(tempDir))).containsExactly("mmm.jar", "zzz.jar",
                "nnn.jar", "aaa.jar", "bbb.jar");
    }

    /** Only jarfiles are returned, and only files -- a directory whose name ends in ".jar" is not a jarfile. */
    @Test
    public void onlyJarFilesAreReturned(@TempDir final Path tempDir) throws IOException {
        createFile(tempDir, "lib/a.jar");
        createFile(tempDir, "lib/notajar.txt");
        Files.createDirectories(tempDir.resolve("lib/dir.jar"));
        assertThat(fileNames(ClasspathExpander.libJarsInDir(tempDir))).containsExactly("a.jar");
    }

    /** A directory with no lib dirs at all contributes nothing. */
    @Test
    public void aDirWithNoLibDirsIsEmpty(@TempDir final Path tempDir) throws IOException {
        createFile(tempDir, "com/xyz/Test.class");
        assertThat(ClasspathExpander.libJarsInDir(tempDir)).isEmpty();
    }
}
