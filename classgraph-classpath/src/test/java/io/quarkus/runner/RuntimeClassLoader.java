package io.quarkus.runner;

import java.nio.file.Path;
import java.util.Collection;
import java.util.List;

import org.jspecify.annotations.Nullable;

/**
 * A stand-in for Quarkus' {@code io.quarkus.runner.RuntimeClassLoader}, which served the application's classes out
 * of directories in Quarkus 1.2 and earlier.
 *
 * <p>
 * This class must be in this exact package, with this exact name, because {@code QuarkusClassLoaderHandler}
 * dispatches on the fully-qualified classloader class name.
 */
public class RuntimeClassLoader extends ClassLoader {
    /** The directories that the application's classes are served from, or null if there are none. */
    public @Nullable Collection<Path> applicationClassDirectories;

    /** Constructor. */
    public RuntimeClassLoader() {
        super(/* parent = */ null);
    }

    /**
     * Serve the application's classes out of the given directories.
     *
     * @param directories
     *            the directories.
     * @return this, for chaining.
     */
    public RuntimeClassLoader serving(final Path... directories) {
        applicationClassDirectories = List.of(directories);
        return this;
    }
}
