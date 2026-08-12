package org.eclipse.osgi.internal.framework;

import org.eclipse.osgi.container.Module;
import org.jspecify.annotations.Nullable;

/** Stand-in for Equinox' {@code EquinoxBundle}, one bundle installed in the framework. */
public class EquinoxBundle {
    /** The framework's view of this bundle, which knows where the bundle was installed from. */
    public final Module module;

    /** The bundle's context, through which every installed bundle can be reached. */
    private @Nullable BundleContextImpl bundleContext;

    /**
     * Constructor.
     *
     * @param module
     *            the framework's view of this bundle.
     */
    public EquinoxBundle(final Module module) {
        this.module = module;
        module.withBundle(this);
    }

    /**
     * Set the bundle's context.
     *
     * @param bundleContext
     *            the bundle's context.
     * @return this, for chaining.
     */
    public EquinoxBundle inContext(final BundleContextImpl bundleContext) {
        this.bundleContext = bundleContext;
        return this;
    }

    /**
     * The bundle's context.
     *
     * @return the bundle's context, or null if the bundle has none.
     */
    public @Nullable BundleContextImpl getBundleContext() {
        return bundleContext;
    }
}
