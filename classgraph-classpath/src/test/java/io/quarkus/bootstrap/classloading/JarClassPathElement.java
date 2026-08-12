package io.quarkus.bootstrap.classloading;

import java.io.File;

/**
 * A stand-in for Quarkus' {@code io.quarkus.bootstrap.classloading.JarClassPathElement}, one jar on the classpath
 * of an application built with Quarkus prior to 3.11. The handler matches this class by its fully-qualified name,
 * so it must be in this exact package with this exact name.
 */
public class JarClassPathElement {
    /** The jar that this classpath element serves. */
    public final File file;

    /**
     * Constructor.
     *
     * @param file
     *            the jar that this classpath element serves.
     */
    public JarClassPathElement(final File file) {
        this.file = file;
    }
}
