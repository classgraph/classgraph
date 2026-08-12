package org.eclipse.osgi.container;

import org.eclipse.osgi.internal.framework.EquinoxBundle;
import org.jspecify.annotations.Nullable;

/** Stand-in for Equinox' {@code Module}, one installed bundle as the framework knows it. */
public class Module {
    /** Where the bundle was installed from, or null if it has no location. */
    public final @Nullable String location;

    /** The bundle that this module is the framework's view of. */
    private @Nullable EquinoxBundle bundle;

    /**
     * Constructor.
     *
     * @param location
     *            where the bundle was installed from, or null if it has no location.
     */
    public Module(final @Nullable String location) {
        this.location = location;
    }

    /**
     * Set the bundle that this module is the framework's view of.
     *
     * @param bundle
     *            the bundle.
     * @return this, for chaining.
     */
    public Module withBundle(final EquinoxBundle bundle) {
        this.bundle = bundle;
        return this;
    }

    /**
     * The bundle that this module is the framework's view of.
     *
     * @return the bundle, or null if there is none.
     */
    public @Nullable EquinoxBundle getBundle() {
        return bundle;
    }
}
