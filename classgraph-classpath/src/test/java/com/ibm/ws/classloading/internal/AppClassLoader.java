package com.ibm.ws.classloading.internal;

import org.jspecify.annotations.Nullable;

/**
 * Stand-in for WebSphere Liberty's {@code AppClassLoader}, which serves an application's classes from the
 * {@code SmartClassPath} held in its {@code smartClassPath} field.
 */
public class AppClassLoader extends ClassLoader {
    /** The {@code SmartClassPath} of the application, or null if the application has none. */
    public final @Nullable Object smartClassPath;

    /**
     * Constructor.
     *
     * @param smartClassPath
     *            the {@code SmartClassPath} of the application, or null if the application has none.
     * @param parent
     *            the parent classloader.
     */
    public AppClassLoader(final @Nullable Object smartClassPath, final @Nullable ClassLoader parent) {
        super(parent);
        this.smartClassPath = smartClassPath;
    }
}
