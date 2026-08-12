package org.apache.catalina.webresources;

/**
 * Stand-in for Catalina's {@code JarWarResourceSet}, which serves resources from a jarfile that is itself nested
 * inside a WAR file. {@code getBaseUrlString()} is the WAR, and the {@code archivePath} field is the path of the
 * jarfile within the WAR.
 */
public class JarWarResourceSet {
    /** The URL of the WAR file that the jarfile is nested inside. */
    private final String baseUrlString;

    /** The path of the jarfile within the WAR file. */
    public final String archivePath;

    /** The path within the jarfile that resources are served from. */
    private final String internalPath;

    /**
     * Constructor.
     *
     * @param baseUrlString
     *            the URL of the WAR file that the jarfile is nested inside.
     * @param archivePath
     *            the path of the jarfile within the WAR file.
     * @param internalPath
     *            the path within the jarfile that resources are served from.
     */
    public JarWarResourceSet(final String baseUrlString, final String archivePath, final String internalPath) {
        this.baseUrlString = baseUrlString;
        this.archivePath = archivePath;
        this.internalPath = internalPath;
    }

    /**
     * The URL of the WAR file that the jarfile is nested inside.
     *
     * @return the WAR file URL.
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
