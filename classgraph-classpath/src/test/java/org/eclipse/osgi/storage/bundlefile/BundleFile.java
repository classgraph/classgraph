package org.eclipse.osgi.storage.bundlefile;

import java.io.File;

import org.jspecify.annotations.Nullable;

/**
 * Stand-in for Equinox' {@code BundleFile}, the file or directory that a bundle's contents are read from. Bundle
 * files are chained: a bundle file can wrap another bundle file, and can be followed by another bundle file in a
 * list.
 */
public class BundleFile {
    /** The file or directory that the bundle's contents are read from. */
    public final @Nullable File basefile;

    /** The classpath element within the base file that this bundle file serves, e.g. {@code "bin/"}, or null. */
    public @Nullable String cp;

    /** The bundle file that this bundle file wraps, or null if it wraps none. */
    public @Nullable BundleFile wrapped;

    /** The next bundle file in the chain, or null if this is the last. */
    public @Nullable BundleFile next;

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

    /**
     * Set the bundle file that this bundle file wraps.
     *
     * @param wrapped
     *            the wrapped bundle file.
     * @return this, for chaining.
     */
    public BundleFile wrapping(final @Nullable BundleFile wrapped) {
        this.wrapped = wrapped;
        return this;
    }

    /**
     * Set the next bundle file in the chain.
     *
     * @param next
     *            the next bundle file.
     * @return this, for chaining.
     */
    public BundleFile followedBy(final @Nullable BundleFile next) {
        this.next = next;
        return this;
    }

    @Override
    public String toString() {
        return String.valueOf(basefile);
    }
}
