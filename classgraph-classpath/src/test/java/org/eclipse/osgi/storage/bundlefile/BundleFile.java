package org.eclipse.osgi.storage.bundlefile;

import java.io.File;

import org.jspecify.annotations.Nullable;

/**
 * Stand-in for Equinox' {@code BundleFile}, the file or directory that a bundle's contents are read from. Only a
 * {@link BundleFileWrapper} refers to another bundle file.
 */
public class BundleFile {
    /** The file or directory that the bundle's contents are read from. */
    public final @Nullable File basefile;

    /** The classpath element within the base file that this bundle file serves, e.g. {@code "bin/"}, or null. */
    public @Nullable String cp;

    /**
     * Constructor.
     *
     * @param basefile
     *            the file or directory that the bundle's contents are read from, or null if unknown.
     */
    public BundleFile(final @Nullable File basefile) {
        this.basefile = basefile;
    }

    /**
     * Set the classpath element within the base file that this bundle file serves.
     *
     * @param cp
     *            the classpath element.
     * @return this, for chaining.
     */
    public BundleFile serving(final String cp) {
        this.cp = cp;
        return this;
    }

    @Override
    public String toString() {
        return String.valueOf(basefile);
    }
}
