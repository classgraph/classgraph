package org.apache.catalina.webresources;

/**
 * Stand-in for Catalina's {@code JarResourceSet}, which serves files from a jarfile. Catalina serves each jarfile
 * in an unpacked webapp's {@code WEB-INF/lib} twice: once mounted at {@code "/WEB-INF/classes"} for the
 * classloader, and, for a resource jar, once more with the internal path {@code "/META-INF/resources"} mounted at
 * {@code "/"}, for static content only.
 */
public class JarResourceSet extends AbstractSingleArchiveResourceSet {
    /**
     * Constructor.
     *
     * @param webAppMount
     *            the path within the webapp's resources that the files are served at.
     * @param base
     *            the filesystem path of the jarfile that the files are served from.
     * @param internalPath
     *            the path within the jarfile that the files are served from.
     */
    public JarResourceSet(final String webAppMount, final String base, final String internalPath) {
        super(webAppMount, base, internalPath);
    }
}
