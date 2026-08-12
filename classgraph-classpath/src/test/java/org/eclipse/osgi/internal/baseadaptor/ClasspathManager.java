package org.eclipse.osgi.internal.baseadaptor;

/** Stand-in for the OSGi {@code ClasspathManager}, which holds a bundle's classpath. */
public class ClasspathManager {
    /** The classpath entries of the bundle. */
    public final ClasspathEntry[] entries;

    /**
     * Constructor.
     *
     * @param entries
     *            the classpath entries of the bundle.
     */
    public ClasspathManager(final ClasspathEntry... entries) {
        this.entries = entries;
    }
}
