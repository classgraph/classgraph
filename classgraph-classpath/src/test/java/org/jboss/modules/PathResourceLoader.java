package org.jboss.modules;

/**
 * Stand-in for JBoss' {@code PathResourceLoader}, which reads a module's resources from a root -- either a
 * {@link java.nio.file.Path} on disk, or a file in the JBoss virtual filesystem.
 */
public class PathResourceLoader {
    /** The root that the module's resources are read from. */
    public final Object root;

    /**
     * Constructor.
     *
     * @param root
     *            the root that the module's resources are read from.
     */
    public PathResourceLoader(final Object root) {
        this.root = root;
    }
}
