package org.apache.catalina.webresources;

/**
 * Stand-in for Catalina's {@code JarResourceSet}, which serves resources from within a jarfile -- typically the
 * {@code META-INF/resources} directory of a resource jar in {@code WEB-INF/lib}. The jarfile is read from
 * {@code getBaseUrlString()}.
 */
public class JarResourceSet {
    /** The URL of the jarfile that resources are served from. */
    private final String baseUrlString;

    /** The path within the jarfile that resources are served from. */
    private final String internalPath;

    /**
     * Constructor.
     *
     * @param baseUrlString
     *            the URL of the jarfile that resources are served from.
     * @param internalPath
     *            the path within the jarfile that resources are served from.
     */
    public JarResourceSet(final String baseUrlString, final String internalPath) {
        this.baseUrlString = baseUrlString;
        this.internalPath = internalPath;
    }

    /**
     * The URL of the jarfile that resources are served from.
     *
     * @return the jarfile URL.
     */
    public String getBaseUrlString() {
        return baseUrlString;
    }

    /**
     * The path within the jarfile that resources are served from.
     *
     * @return the internal path.
     */
    public String getInternalPath() {
        return internalPath;
    }
}
