package org.eclipse.osgi.internal.loader;

import org.eclipse.osgi.internal.framework.EquinoxContainer;

/** Stand-in for Equinox' {@code BundleLoader}, a bundle's route to the framework it is installed in. */
public class BundleLoader {
    /** The framework that the bundle is installed in. */
    public final EquinoxContainer container;

    /**
     * Constructor.
     *
     * @param container
     *            the framework that the bundle is installed in.
     */
    public BundleLoader(final EquinoxContainer container) {
        this.container = container;
    }
}
