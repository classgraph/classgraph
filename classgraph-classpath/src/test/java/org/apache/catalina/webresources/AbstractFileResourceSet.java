package org.apache.catalina.webresources;

import java.io.File;
import java.io.UncheckedIOException;
import java.net.MalformedURLException;
import java.net.URL;

/** Stand-in for Catalina's {@code AbstractFileResourceSet}, which serves files from a directory. */
public abstract class AbstractFileResourceSet extends AbstractResourceSet {
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
    protected AbstractFileResourceSet(final String webAppMount, final String base, final String internalPath) {
        super(webAppMount, base, internalPath);
    }

    /**
     * The directory that the files are served from, which Catalina computes from the base and the internal path.
     *
     * @return the directory.
     */
    protected final File getFileBase() {
        return new File(getBase(), getInternalPath());
    }

    @Override
    public URL getBaseUrl() {
        try {
            return getFileBase().toURI().toURL();
        } catch (final MalformedURLException e) {
            throw new UncheckedIOException(e);
        }
    }
}
