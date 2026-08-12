package org.eclipse.osgi.internal.loader.classpath;

/** Stand-in for Equinox' {@code FragmentClasspath}, the classpath contributed by a fragment bundle. */
public class FragmentClasspath {
    /** The classpath entries of the fragment. */
    public final ClasspathEntry[] entries;

    /**
     * Constructor.
     *
     * @param entries
     *            the classpath entries of the fragment.
     */
    public FragmentClasspath(final ClasspathEntry... entries) {
        this.entries = entries;
    }
}
