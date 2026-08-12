package org.eclipse.osgi.internal.loader.classpath;

import org.jspecify.annotations.Nullable;

/** Stand-in for Equinox' {@code ClasspathManager}, which holds a bundle's classpath and its fragments'. */
public class ClasspathManager {
    /** The classpath entries of the bundle itself. */
    public final ClasspathEntry[] entries;

    /** The classpaths contributed by the bundle's fragments, or null if the bundle has no fragments. */
    public @Nullable FragmentClasspath[] fragments;

    /**
     * Constructor.
     *
     * @param entries
     *            the classpath entries of the bundle itself.
     */
    public ClasspathManager(final ClasspathEntry... entries) {
        this.entries = entries;
    }

    /**
     * Set the classpaths contributed by the bundle's fragments.
     *
     * @param fragments
     *            the fragment classpaths.
     * @return this, for chaining.
     */
    public ClasspathManager withFragments(final FragmentClasspath... fragments) {
        this.fragments = fragments;
        return this;
    }
}
