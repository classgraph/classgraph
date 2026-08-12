package org.jboss.modules;

import org.jspecify.annotations.Nullable;

/**
 * A stand-in for JBoss' {@code org.jboss.modules.ModuleClassLoader}, the classloader that loads one module's
 * classes from the module's resource loaders.
 *
 * <p>
 * This class must be in this exact package, with this exact name, because {@code JBossClassLoaderHandler} matches
 * the classloader by its fully-qualified class name.
 */
public class ModuleClassLoader extends ClassLoader {
    /** The module whose classes this classloader loads, or null if it has none. */
    public final @Nullable Module module;

    /** The resource loaders that the module's classes and resources are read from. */
    private final Object[] resourceLoaders;

    /**
     * Constructor for a classloader with no module, used to test that the handler degrades gracefully when the
     * methods and fields it reads by reflection are not present.
     */
    public ModuleClassLoader() {
        super(ModuleClassLoader.class.getClassLoader());
        this.module = null;
        this.resourceLoaders = new Object[0];
    }

    /**
     * Constructor.
     *
     * @param module
     *            the module whose classes this classloader loads.
     * @param resourceLoaders
     *            the resource loaders that the module's classes and resources are read from.
     */
    public ModuleClassLoader(final Module module, final Object... resourceLoaders) {
        super(/* parent = */ null);
        this.module = module;
        this.resourceLoaders = resourceLoaders;
        module.loadedBy(this);
    }

    /**
     * The module whose classes this classloader loads.
     *
     * @return the module, or null if it has none.
     */
    public @Nullable Module getModule() {
        return module;
    }

    /**
     * The resource loaders that the module's classes and resources are read from.
     *
     * @return the resource loaders.
     */
    public Object[] getResourceLoaders() {
        return resourceLoaders;
    }

    /**
     * A local loader for this module, as a module's path map holds. It is an inner class so that it holds a
     * reference back to this classloader, which is how the handler gets from a path map entry to the module.
     *
     * @return the local loader.
     */
    public LocalLoader localLoader() {
        return new LocalLoader();
    }

    /** Stand-in for the anonymous {@code LocalLoader} that a {@code ModuleClassLoader} puts in its path map. */
    public class LocalLoader {
        /** Constructor. */
        LocalLoader() {
        }
    }
}
