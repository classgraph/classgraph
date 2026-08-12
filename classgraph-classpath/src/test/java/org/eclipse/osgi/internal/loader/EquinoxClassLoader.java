package org.eclipse.osgi.internal.loader;

import org.eclipse.osgi.internal.loader.classpath.ClasspathManager;
import org.jspecify.annotations.Nullable;

/** Stand-in for the classloader that Equinox loads a bundle's classes with. */
public class EquinoxClassLoader extends ClassLoader {
    /** The bundle's classpath. */
    public final @Nullable ClasspathManager manager;

    /** The bundle's route to the framework it is installed in, or null if the framework cannot be reached. */
    public @Nullable BundleLoader delegate;

    /**
     * Constructor.
     *
     * @param manager
     *            the bundle's classpath, or null if the bundle has none.
     */
    public EquinoxClassLoader(final @Nullable ClasspathManager manager) {
        super(/* parent = */ null);
        this.manager = manager;
    }

    /**
     * Set the bundle's route to the framework it is installed in.
     *
     * @param delegate
     *            the bundle's route to the framework.
     * @return this, for chaining.
     */
    public EquinoxClassLoader withFramework(final BundleLoader delegate) {
        this.delegate = delegate;
        return this;
    }
}
