package org.apache.felix.framework;

import java.util.ArrayList;
import java.util.List;

import org.jspecify.annotations.Nullable;

/**
 * Stand-in for Felix' {@code BundleWiringImpl}, which ties an installed bundle to the bundles it imports packages
 * from.
 */
public class BundleWiringImpl {
    /** The revision of the bundle that this wiring is for. */
    private final @Nullable BundleRevisionImpl revision;

    /** The wires to the bundles that this bundle imports packages from. */
    private final List<BundleWire> requiredWires = new ArrayList<>();

    /**
     * Constructor.
     *
     * @param revision
     *            the revision of the bundle that this wiring is for, or null if there is none.
     */
    public BundleWiringImpl(final @Nullable BundleRevisionImpl revision) {
        this.revision = revision;
    }

    /**
     * Wire this bundle to a bundle it imports packages from.
     *
     * @param provider
     *            the wiring of the bundle that provides the packages.
     * @return this, for chaining.
     */
    public BundleWiringImpl requiring(final BundleWiringImpl provider) {
        requiredWires.add(new BundleWire(provider));
        return this;
    }

    /**
     * The revision of the bundle that this wiring is for.
     *
     * @return the revision, or null if there is none.
     */
    public @Nullable BundleRevisionImpl getRevision() {
        return revision;
    }

    /**
     * The wires to the bundles that this bundle imports packages from.
     *
     * @param namespace
     *            the namespace to restrict the wires to, or null for all of them.
     * @return the wires.
     */
    public List<BundleWire> getRequiredWires(final @Nullable String namespace) {
        return requiredWires;
    }

    /** Stand-in for Felix' {@code BundleWire}, one bundle's dependency on another. */
    public static class BundleWire {
        /** The wiring of the bundle that provides the packages. */
        private final BundleWiringImpl providerWiring;

        /**
         * Constructor.
         *
         * @param providerWiring
         *            the wiring of the bundle that provides the packages.
         */
        public BundleWire(final BundleWiringImpl providerWiring) {
            this.providerWiring = providerWiring;
        }

        /**
         * The wiring of the bundle that provides the packages.
         *
         * @return the provider's wiring.
         */
        public BundleWiringImpl getProviderWiring() {
            return providerWiring;
        }
    }

    /** Stand-in for the classloader that Felix loads a bundle's classes with. */
    public static class BundleClassLoader extends ClassLoader {
        /** The wiring of the bundle whose classes this classloader loads. */
        public final @Nullable BundleWiringImpl m_wiring;

        /**
         * Constructor.
         *
         * @param wiring
         *            the wiring of the bundle whose classes this classloader loads, or null if it has none.
         */
        public BundleClassLoader(final @Nullable BundleWiringImpl wiring) {
            super(/* parent = */ null);
            this.m_wiring = wiring;
        }
    }
}
