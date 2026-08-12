package org.eclipse.osgi.container;

import java.util.HashMap;
import java.util.Map;

/** Stand-in for Equinox' {@code ModuleDatabase}, which knows every installed module by its id. */
public class ModuleDatabase {
    /** Every installed module, by id. Module 0 is always the system module. */
    public final Map<Long, Module> modulesById = new HashMap<>();

    /**
     * Constructor.
     *
     * @param systemModule
     *            the system module, which is always module 0.
     */
    public ModuleDatabase(final Module systemModule) {
        modulesById.put(0L, systemModule);
    }
}
