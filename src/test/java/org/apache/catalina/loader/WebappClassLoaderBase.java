package org.apache.catalina.loader;

import java.net.URL;
import java.net.URLClassLoader;

/**
 * Stand-in for Catalina's {@code WebappClassLoaderBase}, as it is shaped in Tomcat 10.1 and later, where the
 * {@code getResources()} method was removed and only the {@code resources} field it used to return is left. Like
 * the real class, it extends {@link URLClassLoader}, so it can also load from URLs that are not served by any of
 * the webapp's {@code WebResourceSet}s.
 */
public class WebappClassLoaderBase extends URLClassLoader {
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
        this(resources, new URL[0], parent);
    }

    /**
     * Constructor.
     *
     * @param resources
     *            the {@code WebResourceRoot} of the webapp.
     * @param urls
     *            the URLs that the classloader also loads from, as a {@link URLClassLoader}.
     * @param parent
     *            the parent classloader.
     */
    public WebappClassLoaderBase(final Object resources, final URL[] urls, final ClassLoader parent) {
        super(urls, parent);
        this.resources = resources;
    }
}
