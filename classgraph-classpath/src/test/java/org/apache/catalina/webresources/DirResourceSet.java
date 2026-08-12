package org.apache.catalina.webresources;

import java.io.File;

/**
 * Stand-in for Catalina's {@code DirResourceSet}, which serves resources from a directory on the filesystem. The
 * directory is read from {@code getFileBase()}.
 */
public class DirResourceSet {
    /** The directory that resources are served from. */
    private final File fileBase;

    /** The path within this resource set that resources are served from. */
    private final String internalPath;

    /**
     * Constructor.
     *
     * @param fileBase
     *            the directory that resources are served from.
     * @param internalPath
     *            the path within this resource set that resources are served from.
     */
    public DirResourceSet(final File fileBase, final String internalPath) {
        this.fileBase = fileBase;
        this.internalPath = internalPath;
    }

    /**
     * The directory that resources are served from.
     *
     * @return the directory.
     */
    public File getFileBase() {
        return fileBase;
    }

    /**
     * The path within this resource set that resources are served from.
     *
     * @return the internal path.
     */
    public String getInternalPath() {
        return internalPath;
    }
}
