package org.eclipse.osgi.storage.bundlefile;

import java.io.File;

/** Stand-in for Equinox' {@code DirBundleFile}, a bundle whose contents are read from a directory. */
public class DirBundleFile extends BundleFile {
    /**
     * Constructor.
     *
     * @param basefile
     *            the directory that the bundle's contents are read from.
     */
    public DirBundleFile(final File basefile) {
        super(basefile);
    }
}
