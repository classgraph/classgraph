package org.apache.catalina.loader;

/**
 * Stand-in for Catalina's {@code WebappClassLoaderBase}, as it is shaped in Tomcat 10.1 and later, where the
 * {@code getResources()} method was removed and only the {@code resources} field it used to return is left.
 */
public class WebappClassLoaderBase extends ClassLoader {
    /** The {@code WebResourceRoot} of the webapp. */
    public final Object resources;

    /** True if this classloader delegates to its parent before looking in the webapp. */
    public boolean delegate = true;

    /**
     * Constructor.
     *
     * @param resources
     *            the {@code WebResourceRoot} of the webapp.
     * @param parent
     *            the parent classloader.
     */
    public WebappClassLoaderBase(final Object resources, final ClassLoader parent) {
        super(parent);
        this.resources = resources;
    }
}
