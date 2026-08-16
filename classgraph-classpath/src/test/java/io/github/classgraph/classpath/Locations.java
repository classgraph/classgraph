package io.github.classgraph.classpath;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;

/**
 * The location that a file or directory on the classpath is reported as by {@link Classpath#getLocations()}.
 *
 * <p>
 * A test cannot assume that the path it created a file at is already in that form, because a location is canonical:
 * a temporary directory is reached through a symbolic link on macOS, and through an 8.3 short name on Windows.
 */
public final class Locations {
    /** Not instantiable. */
    private Locations() {
    }

    /**
     * The location that a file or directory is reported as.
     *
     * @param path
     *            the file or directory.
     * @return the location.
     */
    public static String location(final Path path) {
        try {
            return path.toRealPath().toString().replace(File.separatorChar, '/');
        } catch (final IOException e) {
            // The file is not there, so there is no canonical form of its path
            return path.toString().replace(File.separatorChar, '/');
        }
    }

    /**
     * The location that a file or directory is reported as.
     *
     * @param file
     *            the file or directory.
     * @return the location.
     */
    public static String location(final File file) {
        return location(file.toPath());
    }
}
