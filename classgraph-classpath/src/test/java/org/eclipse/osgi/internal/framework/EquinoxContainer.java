package org.eclipse.osgi.internal.framework;

import org.eclipse.osgi.storage.Storage;

/** Stand-in for Equinox' {@code EquinoxContainer}, the framework instance itself. */
public class EquinoxContainer {
    /** The framework's storage. */
    public final Storage storage;

    /**
     * Constructor.
     *
     * @param storage
     *            the framework's storage.
     */
    public EquinoxContainer(final Storage storage) {
        this.storage = storage;
    }
}
