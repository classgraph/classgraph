package org.jboss.modules;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;

/** Stand-in for JBoss' {@code Module}, one module deployed in the module system. */
public class Module {
    /** The classloader that loads this module's classes, or null if the module has none. */
    private @Nullable ModuleClassLoader classLoader;

    /** The module loader that this module was loaded by, or null if the module has none. */
    private @Nullable ModuleLoader callerModuleLoader;

    /** The local loaders that each package in the module is loaded from. */
    private final Map<String, List<Object>> paths = new LinkedHashMap<>();

    /** Constructor. */
    public Module() {
    }

    /**
     * Set the classloader that loads this module's classes.
     *
     * @param classLoader
     *            the classloader.
     */
    void loadedBy(final ModuleClassLoader classLoader) {
        this.classLoader = classLoader;
    }

    /**
     * Set the module loader that this module was loaded by.
     *
     * @param callerModuleLoader
     *            the module loader.
     * @return this, for chaining.
     */
    public Module loadedFrom(final ModuleLoader callerModuleLoader) {
        this.callerModuleLoader = callerModuleLoader;
        return this;
    }

    /**
     * Record that a package in this module is loaded from a local loader.
     *
     * @param packagePath
     *            the package, as a path.
     * @param localLoader
     *            the local loader that the package is loaded from.
     * @return this, for chaining.
     */
    public Module loadingPackageFrom(final String packagePath, final Object localLoader) {
        paths.computeIfAbsent(packagePath, key -> new ArrayList<>()).add(localLoader);
        return this;
    }

    /**
     * The classloader that loads this module's classes.
     *
     * @return the classloader, or null if the module has none.
     */
    public @Nullable ModuleClassLoader getClassLoader() {
        return classLoader;
    }

    /**
     * The module loader that this module was loaded by.
     *
     * @return the module loader, or null if the module has none.
     */
    public @Nullable ModuleLoader getCallerModuleLoader() {
        return callerModuleLoader;
    }

    /**
     * The local loaders that each package in the module is loaded from.
     *
     * @return the path map.
     */
    public Map<String, List<Object>> getPaths() {
        return paths;
    }
}
