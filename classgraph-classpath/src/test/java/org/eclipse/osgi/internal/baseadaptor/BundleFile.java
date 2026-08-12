package org.eclipse.osgi.internal.baseadaptor;

import java.io.File;

import org.jspecify.annotations.Nullable;

/** Stand-in for the OSGi {@code BundleFile}, the file or directory that a bundle's contents are read from. */
public class BundleFile {
    /** The file or directory that the bundle's contents are read from, or null if there is none. */
    private final @Nullable File baseFile;

    /**
     * Constructor.
     *
     * @param baseFile
     *            the file or directory that the bundle's contents are read from, or null if there is none.
     */
    public BundleFile(final @Nullable File baseFile) {
        this.baseFile = baseFile;
    }

    /**
     * The file or directory that the bundle's contents are read from.
     *
     * @return the file or directory, or null if there is none.
     */
    public @Nullable File getBaseFile() {
        return baseFile;
    }
}
