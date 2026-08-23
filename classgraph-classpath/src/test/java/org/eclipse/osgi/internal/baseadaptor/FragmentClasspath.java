package org.eclipse.osgi.internal.baseadaptor;

/** Stand-in for the OSGi {@code FragmentClasspath}, the classpath contributed by one of a bundle's fragments. */
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
