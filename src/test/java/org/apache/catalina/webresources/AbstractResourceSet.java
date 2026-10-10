package org.apache.catalina.webresources;

import java.net.URL;

/**
 * Stand-in for Catalina's {@code AbstractResourceSet}, the superclass of every {@code WebResourceSet} that serves
 * files. A resource set serves the files under a path within its directory or archive (its internal path) at a path
 * within the webapp's resources (its mount).
 */
public abstract class AbstractResourceSet {
    /** The path within the webapp's resources that the files are served at. */
    private final String webAppMount;

    /** The filesystem path of the directory or archive that the files are served from. */
    private final String base;

    /** The path within the directory or archive that the files are served from. */
    private final String internalPath;

    /** Whether this resource set serves static content only, and nothing to the classloader. */
    private boolean staticOnly;

    /** Whether this resource set serves the classloader only, and no static content. */
    private boolean classLoaderOnly;

    /**
     * Constructor.
     *
     * @param webAppMount
     *            the path within the webapp's resources that the files are served at.
     * @param base
     *            the filesystem path of the directory or archive that the files are served from.
     * @param internalPath
     *            the path within the directory or archive that the files are served from.
     */
    protected AbstractResourceSet(final String webAppMount, final String base, final String internalPath) {
        // Catalina normalizes a mount or an internal path of "/" to ""
        this.webAppMount = webAppMount.equals("/") ? "" : webAppMount;
        this.base = base;
        this.internalPath = internalPath.equals("/") ? "" : internalPath;
    }

    /**
     * The path within the webapp's resources that the files are served at.
     *
     * @return the mount.
     */
    protected final String getWebAppMount() {
        return webAppMount;
    }

    /**
     * The filesystem path of the directory or archive that the files are served from.
     *
     * @return the base.
     */
    protected final String getBase() {
        return base;
    }

    /**
     * The path within the directory or archive that the files are served from.
     *
     * @return the internal path.
     */
    protected final String getInternalPath() {
        return internalPath;
    }

    /**
     * Whether this resource set serves static content only, and nothing to the classloader, as each resource jar
     * does.
     *
     * @return true if this resource set serves static content only.
     */
    public boolean getStaticOnly() {
        return staticOnly;
    }

    /**
     * Mark this resource set as serving static content only, or not.
     *
     * @param staticOnly
     *            true if this resource set serves static content only.
     */
    public void setStaticOnly(final boolean staticOnly) {
        this.staticOnly = staticOnly;
    }

    /**
     * Whether this resource set serves the classloader only, and no static content, as each jarfile in
     * {@code WEB-INF/lib} does.
     *
     * @return true if this resource set serves the classloader only.
     */
    public boolean getClassLoaderOnly() {
        return classLoaderOnly;
    }

    /**
     * Mark this resource set as serving the classloader only, or not.
     *
     * @param classLoaderOnly
     *            true if this resource set serves the classloader only.
     */
    public void setClassLoaderOnly(final boolean classLoaderOnly) {
        this.classLoaderOnly = classLoaderOnly;
    }

    /**
     * The URL of the directory or archive that the files are served from.
     *
     * @return the URL.
     */
    public abstract URL getBaseUrl();
}
