package org.apache.catalina.webresources;

/**
 * Stand-in for Catalina's {@code DirResourceSet}, which serves files from a directory on the filesystem. It serves
 * an unpacked webapp's directory, mounted at {@code "/"}, and any extra directory that a {@code <Resources>}
 * element adds.
 */
public class DirResourceSet extends AbstractFileResourceSet {
    /**
     * Constructor.
     *
     * @param webAppMount
     *            the path within the webapp's resources that the files are served at.
     * @param base
     *            the filesystem path of the directory that the files are served from.
     * @param internalPath
     *            the path within the directory that the files are served from.
     */
    public DirResourceSet(final String webAppMount, final String base, final String internalPath) {
        super(webAppMount, base, internalPath);
    }
}
