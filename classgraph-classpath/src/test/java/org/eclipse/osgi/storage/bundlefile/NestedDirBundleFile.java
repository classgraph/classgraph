package org.eclipse.osgi.storage.bundlefile;

import java.io.File;

import org.jspecify.annotations.Nullable;

/**
 * Stand-in for Equinox' {@code NestedDirBundleFile}, a bundle whose contents are read from a directory nested
 * inside another bundle file.
 */
public class NestedDirBundleFile extends BundleFile {
    /** The bundle file that the nested directory lives inside. */
    public final @Nullable BundleFile baseBundleFile;

    /** The path of the nested directory within the base bundle file. */
    public final String nestedDirName;

    /**
     * Constructor.
     *
     * @param basefile
     *            the file or directory that the bundle's contents are read from.
     * @param baseBundleFile
     *            the bundle file that the nested directory lives inside, or null if there is none.
     * @param nestedDirName
     *            the path of the nested directory within the base bundle file.
     */
    public NestedDirBundleFile(final File basefile, final @Nullable BundleFile baseBundleFile,
            final String nestedDirName) {
        super(basefile);
        this.baseBundleFile = baseBundleFile;
        this.nestedDirName = nestedDirName;
    }
}
