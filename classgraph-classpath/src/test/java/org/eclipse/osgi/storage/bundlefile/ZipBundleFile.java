package org.eclipse.osgi.storage.bundlefile;

import java.io.File;

/** Stand-in for Equinox' {@code ZipBundleFile}, a bundle whose contents are read from a zip archive. */
public class ZipBundleFile extends BundleFile {
    /**
     * Constructor.
     *
     * @param basefile
     *            the zip archive that the bundle's contents are read from.
     */
    public ZipBundleFile(final File basefile) {
        super(basefile);
    }
}
