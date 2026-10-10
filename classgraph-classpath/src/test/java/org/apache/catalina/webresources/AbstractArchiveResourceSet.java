package org.apache.catalina.webresources;

import java.io.File;
import java.io.UncheckedIOException;
import java.net.MalformedURLException;
import java.net.URL;

/** Stand-in for Catalina's {@code AbstractArchiveResourceSet}, which serves files from a jarfile or a WAR file. */
public abstract class AbstractArchiveResourceSet extends AbstractResourceSet {
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
    protected AbstractArchiveResourceSet(final String webAppMount, final String base, final String internalPath) {
        super(webAppMount, base, internalPath);
    }

    @Override
    public final URL getBaseUrl() {
        try {
            return new File(getBase()).toURI().toURL();
        } catch (final MalformedURLException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * The URL of the archive that the files are served from, as a string.
     *
     * @return the URL string.
     */
    protected final String getBaseUrlString() {
        return getBaseUrl().toString();
    }
}
