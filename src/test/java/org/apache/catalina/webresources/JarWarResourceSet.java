package org.apache.catalina.webresources;

/**
 * Stand-in for Catalina's {@code JarWarResourceSet}, which serves files from a jarfile that is itself nested inside
 * a WAR file. Catalina serves each jarfile in a packed WAR file's {@code WEB-INF/lib} this way. {@code getBase()}
 * is the WAR file, and the {@code archivePath} field is the path of the jarfile within the WAR.
 */
public class JarWarResourceSet extends AbstractArchiveResourceSet {
    /** The path of the jarfile within the WAR file. Like Catalina's, it is private, and is read by reflection. */
    @SuppressWarnings("unused")
    private final String archivePath;

    /**
     * Constructor.
     *
     * @param webAppMount
     *            the path within the webapp's resources that the files are served at.
     * @param base
     *            the filesystem path of the WAR file that the jarfile is nested inside.
     * @param archivePath
     *            the path of the jarfile within the WAR file.
     * @param internalPath
     *            the path within the jarfile that the files are served from.
     */
    public JarWarResourceSet(final String webAppMount, final String base, final String archivePath,
            final String internalPath) {
        super(webAppMount, base, internalPath);
        this.archivePath = archivePath;
    }
}
