package com.ibm.ws.classloader;

import org.jspecify.annotations.Nullable;

/**
 * Stand-in for WebSphere traditional's {@code CompoundClassLoader}, which serves an application's classes from the
 * classpath its {@code getClassPath()} method reports. It is not a {@code URLClassLoader}.
 */
public class CompoundClassLoader extends ClassLoader {
    /** The application's classpath, or null if the classloader has none. */
    private final @Nullable String classPath;

    /**
     * Constructor.
     *
     * @param classPath
     *            the application's classpath, or null if the classloader has none.
     */
    public CompoundClassLoader(final @Nullable String classPath) {
        super(/* parent = */ null);
        this.classPath = classPath;
    }

    /**
     * The application's classpath.
     *
     * @return the classpath, or null if the classloader has none.
     */
    public @Nullable String getClassPath() {
        return classPath;
    }
}
