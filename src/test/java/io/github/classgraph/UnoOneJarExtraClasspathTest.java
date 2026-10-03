package io.github.classgraph;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Uno-Jar and One-Jar take extra classpath entries on the command line, separated by {@code '|'} rather than by
 * the platform's path separator. Each entry was split again at the path separator, so an entry whose name
 * contained the path separator was lost.
 */
public class UnoOneJarExtraClasspathTest {
    /** Clear the classpath properties. */
    @AfterEach
    public void clearClasspathProperties() {
        System.clearProperty("uno-jar.class.path");
        System.clearProperty("one-jar.class.path");
    }

    /** Only {@code '|'} separates the extra classpath entries. */
    @Test
    public void anExtraClasspathEntryIsNotSplitAtThePathSeparator(@TempDir final Path tempDir) throws IOException {
        final File dir0 = Files.createDirectory(tempDir.resolve("lib" + File.pathSeparator + "1")).toRealPath()
                .toFile();
        final File dir1 = Files.createDirectory(tempDir.resolve("classes")).toRealPath().toFile();

        System.setProperty("uno-jar.class.path", dir0 + "|" + dir1);
        assertThat(new ClassGraph().overrideClassLoaders(new com.needhamsoftware.unojar.JarClassLoader())
                .getClasspathFiles()).containsExactly(dir0, dir1);
        System.clearProperty("uno-jar.class.path");

        System.setProperty("one-jar.class.path", dir0 + "|" + dir1);
        assertThat(new ClassGraph().overrideClassLoaders(new com.simontuffs.onejar.JarClassLoader())
                .getClasspathFiles()).containsExactly(dir0, dir1);
    }
}
