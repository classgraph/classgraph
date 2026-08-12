package io.quarkus.bootstrap.runner;

import java.nio.file.Path;

/**
 * A stand-in for Quarkus' {@code io.quarkus.bootstrap.runner.JarResource}, one jar of an application packaged as a
 * fast jar. The handler matches this class by its fully-qualified name, so it must be in this exact package with
 * this exact name.
 */
public class JarResource {
    /** The jar that this resource is served from. */
    public final Path jarPath;

    /**
     * Constructor.
     *
     * @param jarPath
     *            the jar that this resource is served from.
     */
    public JarResource(final Path jarPath) {
        this.jarPath = jarPath;
    }
}
