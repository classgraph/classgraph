package org.jboss.modules;

/**
 * A stand-in for JBoss' {@code org.jboss.modules.ModuleClassLoader}, used to test that
 * {@code JBossClassLoaderHandler} degrades gracefully when the methods and fields it reads by reflection are not
 * present.
 *
 * <p>
 * This class must be in this exact package, with this exact name, because {@code JBossClassLoaderHandler} matches
 * the classloader by its fully-qualified class name.
 */
public class ModuleClassLoader extends ClassLoader {
    /** Constructor. */
    public ModuleClassLoader() {
        super(ModuleClassLoader.class.getClassLoader());
    }
}
