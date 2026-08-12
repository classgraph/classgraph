package org.eclipse.osgi.internal.baseadaptor;

import org.jspecify.annotations.Nullable;

/** Stand-in for the OSGi {@code DefaultClassLoader}, the classloader of an OSGi bundle. */
public class DefaultClassLoader extends ClassLoader {
    /** The classpath manager that holds the bundle's classpath, or null if the bundle has none. */
    private final @Nullable ClasspathManager classpathManager;

    /**
     * Constructor.
     *
     * @param classpathManager
     *            the classpath manager that holds the bundle's classpath, or null if the bundle has none.
     */
    public DefaultClassLoader(final @Nullable ClasspathManager classpathManager) {
        super(/* parent = */ null);
        this.classpathManager = classpathManager;
    }

    /**
     * The classpath manager that holds the bundle's classpath.
     *
     * @return the classpath manager, or null if the bundle has none.
     */
    public @Nullable ClasspathManager getClasspathManager() {
        return classpathManager;
    }
}
