package com.ibm.ws.classloading.internal;

/**
 * Stand-in for WebSphere Liberty's {@code ThreadContextClassLoader}, which does not hold an application's classpath
 * itself, but wraps the {@code AppClassLoader} that does, in its {@code appLoader} field.
 */
public class ThreadContextClassLoader extends ClassLoader {
    /** The application classloader that this classloader wraps. */
    public final Object appLoader;

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
}
