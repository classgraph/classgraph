package org.eclipse.osgi.storage.bundlefile;

/**
 * Stand-in for the Equinox {@code BundleFileWrapper} that a framework extension's {@code ClassLoaderHook} can
 * install around a bundle file. It copies only the base file of the bundle file it wraps, not the classpath element
 * within it, so the bundle file it delegates to has to be read as well.
 */
public class BundleFileWrapper extends BundleFile {
    /** The bundle file that this wrapper delegates to. */
    private final BundleFile bundleFile;

    /**
     * Constructor.
     *
     * @param bundleFile
     *            the bundle file to delegate to.
     */
    public BundleFileWrapper(final BundleFile bundleFile) {
        super(bundleFile.basefile);
        this.bundleFile = bundleFile;
    }

    /**
     * The bundle file that this wrapper delegates to.
     *
     * @return the delegate.
     */
    public BundleFile getBundleFile() {
        return bundleFile;
    }
}
