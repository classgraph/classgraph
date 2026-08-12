package org.eclipse.osgi.internal.framework;

/** Stand-in for Equinox' {@code BundleContextImpl}, through which every installed bundle can be reached. */
public class BundleContextImpl {
    /** Every bundle installed in the framework. */
    private final EquinoxBundle[] bundles;

    /**
     * Constructor.
     *
     * @param bundles
     *            every bundle installed in the framework.
     */
    public BundleContextImpl(final EquinoxBundle... bundles) {
        this.bundles = bundles;
    }

    /**
     * Every bundle installed in the framework.
     *
     * @return the bundles.
     */
    public EquinoxBundle[] getBundles() {
        return bundles;
    }
}
