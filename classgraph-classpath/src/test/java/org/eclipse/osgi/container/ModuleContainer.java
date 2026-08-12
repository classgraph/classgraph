package org.eclipse.osgi.container;

/** Stand-in for Equinox' {@code ModuleContainer}, which holds the module database. */
public class ModuleContainer {
    /** The module database. */
    public final ModuleDatabase moduleDatabase;

    /**
     * Constructor.
     *
     * @param moduleDatabase
     *            the module database.
     */
    public ModuleContainer(final ModuleDatabase moduleDatabase) {
        this.moduleDatabase = moduleDatabase;
    }
}
