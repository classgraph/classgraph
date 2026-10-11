package com.ibm.ws.classloading.internal;

import org.jspecify.annotations.Nullable;

/**
 * Stand-in for WebSphere Liberty's {@code ParentLastClassLoader}, the {@code AppClassLoader} of an application
 * configured with {@code delegation="parentLast"}. It searches its "beforeApp" libraries, its own classpath and its
 * "afterApp" libraries before its parent.
 */
public class ParentLastClassLoader extends AppClassLoader {
    /**
     * Constructor.
     *
     * @param smartClassPath
     *            the {@code SmartClassPath} of the application, or null if the application has none.
     * @param parent
     *            the parent classloader.
     */
    public ParentLastClassLoader(final @Nullable Object smartClassPath, final @Nullable ClassLoader parent) {
        super(smartClassPath, parent);
    }
}
