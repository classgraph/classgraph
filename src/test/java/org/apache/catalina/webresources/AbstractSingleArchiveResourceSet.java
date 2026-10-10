package org.apache.catalina.webresources;

/**
 * Stand-in for Catalina's {@code AbstractSingleArchiveResourceSet}, which serves files from a jarfile or a WAR file
 * that is not nested inside another archive.
 */
public abstract class AbstractSingleArchiveResourceSet extends AbstractArchiveResourceSet {
    /**
     * Constructor.
     *
     * @param webAppMount
     *            the path within the webapp's resources that the files are served at.
     * @param base
     *            the filesystem path of the archive that the files are served from.
     * @param internalPath
     *            the path within the archive that the files are served from.
     */
    protected AbstractSingleArchiveResourceSet(final String webAppMount, final String base,
            final String internalPath) {
        super(webAppMount, base, internalPath);
    }
}
