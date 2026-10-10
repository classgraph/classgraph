package org.apache.catalina.webresources;

/**
 * Stand-in for Catalina's {@code WarResourceSet}, which serves the files of a packed WAR file, mounted at
 * {@code "/"}.
 */
public class WarResourceSet extends AbstractSingleArchiveResourceSet {
    /**
     * Constructor.
     *
     * @param webAppMount
     *            the path within the webapp's resources that the files are served at.
     * @param base
     *            the filesystem path of the WAR file.
     */
    public WarResourceSet(final String webAppMount, final String base) {
        super(webAppMount, base, "/");
    }
}
