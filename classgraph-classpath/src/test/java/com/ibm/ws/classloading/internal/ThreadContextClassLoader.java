package com.ibm.ws.classloading.internal;

import java.util.ArrayList;
import java.util.List;

/**
 * Stand-in for WebSphere Liberty's {@code ThreadContextClassLoader}, which does not hold an application's classpath
 * itself, but wraps the {@code AppClassLoader} that does, in its {@code appLoader} field. It extends
 * {@code UnifiedClassLoader}, which searches the classloaders in its {@code followOnClassLoaders} field after its
 * parent.
 */
public class ThreadContextClassLoader extends ClassLoader {
    /** The application classloader that this classloader wraps. */
    public final Object appLoader;

    /** The classloaders that this classloader searches after its parent. */
    public final List<ClassLoader> followOnClassLoaders = new ArrayList<>();

    /**
     * Constructor.
     *
     * @param appLoader
     *            the application classloader that this classloader wraps.
     * @param parent
     *            the parent classloader.
     */
    public ThreadContextClassLoader(final Object appLoader, final ClassLoader parent) {
        super(parent);
        this.appLoader = appLoader;
    }

    /**
     * Add a classloader that this classloader searches after its parent.
     *
     * @param followOn
     *            the classloader.
     * @return this, for chaining.
     */
    public ThreadContextClassLoader followedBy(final ClassLoader followOn) {
        followOnClassLoaders.add(followOn);
        return this;
    }
}
