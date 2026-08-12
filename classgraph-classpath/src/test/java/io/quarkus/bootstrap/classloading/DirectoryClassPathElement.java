package io.quarkus.bootstrap.classloading;

import java.nio.file.Path;

/**
 * A stand-in for Quarkus' {@code io.quarkus.bootstrap.classloading.DirectoryClassPathElement}, one directory on the
 * classpath of an application built with Quarkus prior to 3.11. The handler matches this class by its
 * fully-qualified name, so it must be in this exact package with this exact name.
 */
public class DirectoryClassPathElement {
    /** The directory that this classpath element serves. */
    public final Path root;

    /**
     * Constructor.
     *
     * @param root
     *            the directory that this classpath element serves.
     */
    public DirectoryClassPathElement(final Path root) {
        this.root = root;
    }
}
