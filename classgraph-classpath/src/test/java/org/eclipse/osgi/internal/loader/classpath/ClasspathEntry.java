package org.eclipse.osgi.internal.loader.classpath;

import org.eclipse.osgi.storage.bundlefile.BundleFile;
import org.jspecify.annotations.Nullable;

/** Stand-in for Equinox' {@code ClasspathEntry}, one entry of a bundle's classpath. */
public class ClasspathEntry {
    /** The bundle file that this classpath entry's contents are read from. */
    public final @Nullable BundleFile bundlefile;

    /**
     * Constructor.
     *
     * @param bundlefile
     *            the bundle file that this classpath entry's contents are read from, or null if there is none.
     */
    public ClasspathEntry(final @Nullable BundleFile bundlefile) {
        this.bundlefile = bundlefile;
    }
}
