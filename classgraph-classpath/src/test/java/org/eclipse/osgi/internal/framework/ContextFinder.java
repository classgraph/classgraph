package org.eclipse.osgi.internal.framework;

import org.jspecify.annotations.Nullable;

/**
 * Stand-in for Equinox' {@code ContextFinder}, the context classloader of the threads that Equinox runs. It loads a
 * class from the classloaders of the bundles on the call stack, and otherwise from the classloader that was the
 * context classloader before it, or from the framework's boot classloader if there was none.
 */
public class ContextFinder extends ClassLoader {
    /** The classloader that a class is loaded from when no bundle on the call stack has it. */
    private final ClassLoader parentContextClassLoader;

    /**
     * Constructor.
     *
     * @param contextClassLoader
     *            the classloader that was the context classloader before this one, which becomes the parent, or
     *            null if there was none.
     * @param bootLoader
     *            the framework's boot classloader, which classes are loaded from if there was no context
     *            classloader.
     */
    public ContextFinder(final @Nullable ClassLoader contextClassLoader, final ClassLoader bootLoader) {
        super(contextClassLoader);
        parentContextClassLoader = contextClassLoader != null ? contextClassLoader : bootLoader;
    }

    // There are no bundles on the call stack here, so a class is only looked for in parentContextClassLoader
    @Override
    protected Class<?> loadClass(final String name, final boolean resolve) throws ClassNotFoundException {
        return parentContextClassLoader.loadClass(name);
    }
}
