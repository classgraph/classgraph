package org.apache.felix.framework;

import java.net.URL;
import java.net.URLClassLoader;

/**
 * A stand-in for Felix' {@code org.apache.felix.framework.BundleWiringImpl}, which holds the classloader that serves
 * an OSGi bundle.
 *
 * <p>
 * This class must be in this exact package, with this exact name, because {@code FelixClassLoaderHandler} recognizes
 * the classloader by its fully-qualified class name.
 */
public class BundleWiringImpl {
    /** Constructor. */
    public BundleWiringImpl() {
    }

    /**
     * A stand-in for Felix' bundle classloader. It extends {@link URLClassLoader}, as Felix' own does, so that it is
     * a classloader that both {@code FelixClassLoaderHandler} and {@code URLClassLoaderHandler} can handle.
     */
    public static class BundleClassLoader extends URLClassLoader {
        /**
         * Constructor.
         *
         * @param urls
         *            the URLs that the bundle is served from.
         */
        public BundleClassLoader(final URL[] urls) {
            super(urls, /* parent = */ null);
        }
    }
}
