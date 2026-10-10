package org.apache.catalina.loader;

/**
 * Stand-in for Catalina's {@code WebappClassLoader}, as it is shaped in Tomcat 8.5 through 10.0, where
 * {@code getResources()} still returned the webapp's {@code WebResourceRoot}.
 */
public class WebappClassLoader extends WebappClassLoaderBase {
    /**
     * Constructor.
     *
     * @param resources
     *            the {@code WebResourceRoot} of the webapp.
     * @param parent
     *            the parent classloader.
     */
    public WebappClassLoader(final Object resources, final ClassLoader parent) {
        super(resources, parent);
    }

    /**
     * The {@code WebResourceRoot} of the webapp.
     *
     * @return the {@code WebResourceRoot}.
     */
    public Object getResources() {
        return resources;
    }
}
