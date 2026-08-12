package org.eclipse.osgi.storage;

import org.eclipse.osgi.container.ModuleContainer;

/** Stand-in for Equinox' {@code Storage}, which holds the module container. */
public class Storage {
    /** The module container. */
    public final ModuleContainer moduleContainer;

    /**
     * Constructor.
     *
     * @param moduleContainer
     *            the module container.
     */
    public Storage(final ModuleContainer moduleContainer) {
        this.moduleContainer = moduleContainer;
    }
}
