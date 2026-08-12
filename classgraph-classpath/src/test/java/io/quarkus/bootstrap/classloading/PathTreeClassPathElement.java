package io.quarkus.bootstrap.classloading;

import org.jspecify.annotations.Nullable;

/**
 * A stand-in for the classpath elements used since Quarkus 3.11, which report the jar or directory they serve
 * through {@code getRoot()} rather than through a field. The handler matches these by the presence of that method,
 * not by class name.
 */
public class PathTreeClassPathElement {
    /** The jar or directory that this classpath element serves, or null if it serves nothing on disk. */
    private final @Nullable Object root;

    /**
     * Constructor.
     *
     * @param root
     *            the jar or directory that this classpath element serves, or null if it serves nothing on disk.
     */
    public PathTreeClassPathElement(final @Nullable Object root) {
        this.root = root;
    }

    /**
     * The jar or directory that this classpath element serves.
     *
     * @return the root, or null if it serves nothing on disk.
     */
    public @Nullable Object getRoot() {
        return root;
    }
}
