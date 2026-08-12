package org.jboss.modules;

import java.util.LinkedHashMap;
import java.util.Map;

/** Stand-in for JBoss' {@code ModuleLoader}, which knows every module it has loaded, by name. */
public class ModuleLoader {
    /** Every module this loader has loaded, by name. */
    public final Map<String, FutureModule> moduleMap = new LinkedHashMap<>();

    /** Constructor. */
    public ModuleLoader() {
    }

    /**
     * Record a module as loaded by this loader.
     *
     * @param moduleName
     *            the module's name.
     * @param module
     *            the module.
     * @return this, for chaining.
     */
    public ModuleLoader loaded(final String moduleName, final Module module) {
        moduleMap.put(moduleName, new FutureModule(module));
        module.loadedFrom(this);
        return this;
    }

    /** Stand-in for JBoss' {@code ModuleLoader$FutureModule}, a module that may still be loading. */
    public static class FutureModule {
        /** The module. */
        private final Module module;

        /**
         * Constructor.
         *
         * @param module
         *            the module.
         */
        FutureModule(final Module module) {
            this.module = module;
        }

        /**
         * The module.
         *
         * @return the module.
         */
        public Module getModule() {
            return module;
        }
    }
}
