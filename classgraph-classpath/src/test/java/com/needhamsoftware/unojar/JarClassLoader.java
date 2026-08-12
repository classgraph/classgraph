package com.needhamsoftware.unojar;

import org.jspecify.annotations.Nullable;

/** Stand-in for Uno-Jar's {@code JarClassLoader}, which loads an application from a single jar. */
public class JarClassLoader extends ClassLoader {
    /** The path of the jar the application runs from, or null if the classloader does not know it. */
    private final @Nullable String oneJarPath;

    /**
     * Constructor.
     *
     * @param oneJarPath
     *            the path of the jar the application runs from, or null if the classloader does not know it.
     */
    public JarClassLoader(final @Nullable String oneJarPath) {
        super(/* parent = */ null);
        this.oneJarPath = oneJarPath;
    }

    /**
     * The path of the jar the application runs from.
     *
     * @return the path, or null if the classloader does not know it.
     */
    public @Nullable String getOneJarPath() {
        return oneJarPath;
    }
}
