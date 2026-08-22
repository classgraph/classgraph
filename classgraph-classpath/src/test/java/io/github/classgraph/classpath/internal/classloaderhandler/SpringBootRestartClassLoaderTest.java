package io.github.classgraph.classpath.internal.classloaderhandler;

import static io.github.classgraph.classpath.Locations.location;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.devtools.restart.classloader.RestartClassLoader;

import io.github.classgraph.classpath.ClasspathFinder;

/**
 * Tests that the directories that Spring Boot devtools watches for changes are read from the {@code
 * RestartClassLoader} that shades them, ahead of the parent classloader that holds the unchanged copies.
 */
public class SpringBootRestartClassLoaderTest {
    /**
     * Find the classpath element locations of a classloader.
     *
     * @param classLoader
     *            the classloader.
     * @return the classpath element locations.
     */
    private static List<String> locations(final ClassLoader classLoader) {
        try (var classpath = new ClasspathFinder().overrideClassLoaders(classLoader).find()) {
            return classpath.getLocations();
        }
    }

    /**
     * {@code RestartClassLoader} extends {@link URLClassLoader}, and is constructed with the URLs of the
     * directories it watches, so those URLs are its classpath.
     *
     * @param tempDir
     *            a temporary directory to create the watched directory in.
     * @throws IOException
     *             if the directory could not be created.
     */
    @Test
    public void theWatchedDirectoriesAreOnTheClasspath(@TempDir final Path tempDir) throws IOException {
        final var watchedDir = Files.createDirectories(tempDir.resolve("watched"));
        try (var restartClassLoader = new RestartClassLoader(/* parent = */ null, watchedDir.toUri().toURL())) {
            assertThat(locations(restartClassLoader)).containsExactly(location(watchedDir));
        }
    }

    /**
     * The restart classloader shades the directories it watches, so a class that is in both the watched directory
     * and the parent classloader has to be loaded from the watched directory. The watched directory is therefore
     * ahead of the parent's classpath, even though the parent is a parent.
     *
     * @param tempDir
     *            a temporary directory to create the directories in.
     * @throws IOException
     *             if the directories could not be created.
     */
    // #267, #268
    @Test
    public void theWatchedDirectoriesComeBeforeTheParentsClasspath(@TempDir final Path tempDir) throws IOException {
        final var watchedDir = Files.createDirectories(tempDir.resolve("watched"));
        final var parentDir = Files.createDirectories(tempDir.resolve("parent"));
        try (var parent = new URLClassLoader(new URL[] { parentDir.toUri().toURL() }, /* parent = */ null);
                var restartClassLoader = new RestartClassLoader(parent, watchedDir.toUri().toURL())) {
            assertThat(locations(restartClassLoader)).containsExactly(location(watchedDir), location(parentDir));
        }
    }
}
