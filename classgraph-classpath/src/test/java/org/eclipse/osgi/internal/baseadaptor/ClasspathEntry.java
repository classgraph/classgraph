package org.eclipse.osgi.internal.baseadaptor;

import org.jspecify.annotations.Nullable;

/** Stand-in for the OSGi {@code ClasspathEntry}, one entry of a bundle's classpath. */
public class ClasspathEntry {
    /** The bundle file that this classpath entry's contents are read from, or null if there is none. */
    private final @Nullable BundleFile bundleFile;

    /**
     * Constructor.
     *
     * @param bundleFile
     *            the bundle file that this classpath entry's contents are read from, or null if there is none.
     */
    public ClasspathEntry(final @Nullable BundleFile bundleFile) {
        this.bundleFile = bundleFile;
    }

    /**
     * The bundle file that this classpath entry's contents are read from.
     *
     * @return the bundle file, or null if there is none.
     */
    public @Nullable BundleFile getBundleFile() {
        return bundleFile;
    }
}
